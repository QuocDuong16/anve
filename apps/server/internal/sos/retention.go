package sos

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/QuocDuong16/anve/server/internal/audit"
)

// RunRetention expires active requests and removes old metadata in bounded passes.
// ID fences remain owner-scoped until account deletion.
func RunRetention(ctx context.Context, store *Store, logger *slog.Logger) {
	pass := func() {
		for i := 0; i < 100; i++ {
			processed, err := store.RetainOne(ctx)
			if err != nil {
				if ctx.Err() == nil {
					logger.Error("sos retention failed")
				}
				return
			}
			if !processed {
				return
			}
		}
	}
	pass()
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			pass()
		}
	}
}

// RetainOne orders expiry and deletion with creates and transitions by taking
// the same user and event-ID locks before the event row lock.
func (s *Store) RetainOne(ctx context.Context) (bool, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return false, fmt.Errorf("begin sos retention: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var id, owner uuid.UUID
	err = tx.QueryRow(ctx, `SELECT id,owner_user_id FROM sos_events WHERE received_at<clock_timestamp()-interval '30 days' OR (state='ACTIVE' AND received_at<clock_timestamp()-interval '7 days') ORDER BY received_at,id LIMIT 1`).Scan(&id, &owner)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("find expired sos: %w", err)
	}
	var locked uuid.UUID
	err = tx.QueryRow(ctx, `SELECT id FROM users WHERE id=$1 FOR UPDATE`, owner).Scan(&locked)
	if errors.Is(err, pgx.ErrNoRows) {
		return true, nil
	}
	if err != nil {
		return false, fmt.Errorf("lock sos retention owner: %w", err)
	}
	if err = lockEventID(ctx, tx, id); err != nil {
		return false, err
	}
	var state string
	var expired, retired bool
	err = tx.QueryRow(ctx, `SELECT state,received_at<clock_timestamp()-interval '7 days',received_at<clock_timestamp()-interval '30 days' FROM sos_events WHERE id=$1 FOR UPDATE`, id).Scan(&state, &expired, &retired)
	if errors.Is(err, pgx.ErrNoRows) {
		return true, nil
	}
	if err != nil {
		return false, fmt.Errorf("lock expired sos: %w", err)
	}
	if retired {
		_, err = tx.Exec(ctx, `INSERT INTO sos_retired_requests(event_id,owner_user_id) VALUES($1,$2) ON CONFLICT DO NOTHING`, id, owner)
		if err == nil {
			_, err = tx.Exec(ctx, `DELETE FROM sos_events WHERE id=$1`, id)
		}
		if err == nil {
			err = audit.InTx(ctx, tx, audit.Event{ActorUserID: owner, Action: "sos.retired", Detail: map[string]any{"event_id": id.String(), "via": "server_retention"}})
		}
	} else if expired && state == "ACTIVE" {
		_, err = tx.Exec(ctx, `UPDATE sos_events SET state='EXPIRED',transitioned_at=clock_timestamp() WHERE id=$1`, id)
		if err == nil {
			_, err = tx.Exec(ctx, `UPDATE sos_notification_outbox SET state='EXPIRED' WHERE event_id=$1 AND state='QUEUED'`, id)
		}
		if err == nil {
			err = audit.InTx(ctx, tx, audit.Event{ActorUserID: owner, Action: "sos.expired", Detail: map[string]any{"event_id": id.String(), "via": "server_retention"}})
		}
	}
	if err != nil {
		return false, fmt.Errorf("apply sos retention: %w", err)
	}
	if err = tx.Commit(ctx); err != nil {
		return false, fmt.Errorf("commit sos retention: %w", err)
	}
	return true, nil
}
