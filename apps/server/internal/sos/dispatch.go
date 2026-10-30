package sos

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/QuocDuong16/anve/server/internal/notifications"
)

// RunDispatchWorker retries durable work on a bounded schedule until shutdown.
func RunDispatchWorker(ctx context.Context, store *Store, gateway notifications.Gateway, logger *slog.Logger) {
	pass := func() {
		for i := 0; i < 100; i++ {
			requestCtx, cancel := context.WithTimeout(ctx, 12*time.Second)
			processed, err := store.DispatchOne(requestCtx, gateway)
			cancel()
			if err != nil {
				if ctx.Err() == nil {
					logger.Error("sos notification evaluation failed")
				}
				return
			}
			if !processed {
				return
			}
		}
	}
	pass()
	ticker := time.NewTicker(5 * time.Second)
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

// DispatchOne processes one SOS recipient with bounded provider time. Locking
// the event through the attempt orders cancellation with dispatch; an already
// accepted push cannot be recalled after cancellation.
func (s *Store) DispatchOne(ctx context.Context, gateway notifications.Gateway) (bool, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return false, fmt.Errorf("begin sos dispatch: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var eventID, recipient uuid.UUID
	err = tx.QueryRow(ctx, `SELECT event_id,recipient_user_id FROM sos_notification_outbox WHERE state='QUEUED' AND next_attempt_at<=clock_timestamp() ORDER BY next_attempt_at,event_id,recipient_user_id LIMIT 1`).Scan(&eventID, &recipient)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("select sos notification: %w", err)
	}
	var state string
	var received time.Time
	err = tx.QueryRow(ctx, `SELECT state,received_at FROM sos_events WHERE id=$1 FOR UPDATE`, eventID).Scan(&state, &received)
	if errors.Is(err, pgx.ErrNoRows) {
		return true, nil
	}
	if err != nil {
		return false, fmt.Errorf("lock sos notification event: %w", err)
	}
	var queued string
	err = tx.QueryRow(ctx, `SELECT state FROM sos_notification_outbox WHERE event_id=$1 AND recipient_user_id=$2 FOR UPDATE`, eventID, recipient).Scan(&queued)
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && queued != "QUEUED") {
		return true, nil
	}
	if err != nil {
		return false, fmt.Errorf("lock sos outbox: %w", err)
	}
	if state != "ACTIVE" || received.Before(time.Now().Add(-7*24*time.Hour)) {
		_, err = tx.Exec(ctx, `UPDATE sos_notification_outbox SET state=CASE WHEN $3 THEN 'EXPIRED' ELSE 'SUPPRESSED' END WHERE event_id=$1 AND recipient_user_id=$2`, eventID, recipient, state == "ACTIVE")
		if err != nil {
			return false, fmt.Errorf("suppress sos notification: %w", err)
		}
		if err = tx.Commit(ctx); err != nil {
			return false, fmt.Errorf("commit sos suppression: %w", err)
		}
		return true, nil
	}
	var membership uuid.UUID
	err = tx.QueryRow(ctx, `SELECT rm.id FROM sos_events e
 JOIN circle_membership_periods op ON op.id=e.owner_period_id AND op.ended_at IS NULL
 JOIN memberships om ON om.id=op.membership_id AND om.user_id=e.owner_user_id AND om.circle_id=e.circle_id
 JOIN sos_recipients r ON r.event_id=e.id AND r.recipient_user_id=$2 AND r.revoked_at IS NULL
 JOIN circle_membership_periods rp ON rp.id=r.membership_period_id AND rp.ended_at IS NULL
 JOIN memberships rm ON rm.id=rp.membership_id AND rm.user_id=$2 AND rm.circle_id=e.circle_id
 WHERE e.id=$1 FOR SHARE OF op,om,rp,rm`, eventID, recipient).Scan(&membership)
	if errors.Is(err, pgx.ErrNoRows) {
		_, err = tx.Exec(ctx, `UPDATE sos_notification_outbox SET state='SUPPRESSED' WHERE event_id=$1 AND recipient_user_id=$2`, eventID, recipient)
		if err != nil {
			return false, fmt.Errorf("suppress unauthorized sos: %w", err)
		}
		if err = tx.Commit(ctx); err != nil {
			return false, fmt.Errorf("commit sos suppression: %w", err)
		}
		return true, nil
	}
	if err != nil {
		return false, fmt.Errorf("authorize sos dispatch: %w", err)
	}
	_, err = tx.Exec(ctx, `INSERT INTO sos_notification_targets(event_id,recipient_user_id,device_id)
 SELECT $1,$2,d.device_id FROM device_push_tokens d JOIN sessions s ON s.id=d.session_id AND s.device_id=d.device_id AND s.user_id=d.owner_user_id AND s.revoked_at IS NULL AND s.refresh_expires_at>clock_timestamp()
 WHERE d.owner_user_id=$2 ON CONFLICT DO NOTHING`, eventID, recipient)
	if err != nil {
		return false, fmt.Errorf("prepare sos targets: %w", err)
	}
	var device uuid.UUID
	var token string
	err = tx.QueryRow(ctx, `SELECT t.device_id,d.token FROM sos_notification_targets t JOIN device_push_tokens d ON d.device_id=t.device_id AND d.owner_user_id=t.recipient_user_id
 JOIN sessions s ON s.id=d.session_id AND s.user_id=d.owner_user_id AND s.device_id=d.device_id AND s.revoked_at IS NULL AND s.refresh_expires_at>clock_timestamp()
 WHERE t.event_id=$1 AND t.recipient_user_id=$2 AND t.state='QUEUED' AND t.next_attempt_at<=clock_timestamp()
 ORDER BY t.next_attempt_at,t.device_id LIMIT 1 FOR UPDATE OF t FOR SHARE OF d,s`, eventID, recipient).Scan(&device, &token)
	if errors.Is(err, pgx.ErrNoRows) {
		_, err = tx.Exec(ctx, `UPDATE sos_notification_outbox SET next_attempt_at=clock_timestamp()+interval '1 minute' WHERE event_id=$1 AND recipient_user_id=$2`, eventID, recipient)
		if err != nil {
			return false, fmt.Errorf("defer sos notification: %w", err)
		}
		if err = tx.Commit(ctx); err != nil {
			return false, fmt.Errorf("commit sos deferral: %w", err)
		}
		return true, nil
	}
	if err != nil {
		return false, fmt.Errorf("lock sos target: %w", err)
	}
	sendCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	sendErr := gateway.Send(sendCtx, token, notifications.Signal{EventID: eventID, Kind: "SOS"})
	cancel()
	switch {
	case sendErr == nil:
		_, err = tx.Exec(ctx, `UPDATE sos_notification_targets SET state='ACCEPTED',accepted_at=clock_timestamp(),attempts=attempts+1 WHERE event_id=$1 AND recipient_user_id=$2 AND device_id=$3`, eventID, recipient, device)
	case errors.Is(sendErr, notifications.ErrInvalidToken):
		_, err = tx.Exec(ctx, `DELETE FROM device_push_tokens WHERE device_id=$1 AND owner_user_id=$2`, device, recipient)
	default:
		_, err = tx.Exec(ctx, `UPDATE sos_notification_targets SET attempts=attempts+1,next_attempt_at=clock_timestamp()+make_interval(secs=>LEAST(300,(1 << LEAST(attempts,8)))) WHERE event_id=$1 AND recipient_user_id=$2 AND device_id=$3`, eventID, recipient, device)
	}
	if err != nil {
		return false, fmt.Errorf("record sos push attempt: %w", err)
	}
	_, err = tx.Exec(ctx, `UPDATE sos_notification_outbox SET attempts=attempts+1,next_attempt_at=clock_timestamp()+interval '5 seconds',
 accepted_at=CASE WHEN $3 THEN COALESCE(accepted_at,clock_timestamp()) ELSE accepted_at END WHERE event_id=$1 AND recipient_user_id=$2`, eventID, recipient, sendErr == nil)
	if err != nil {
		return false, fmt.Errorf("schedule sos notification: %w", err)
	}
	_, err = tx.Exec(ctx, `UPDATE sos_notification_outbox o SET state='ACCEPTED',accepted_at=COALESCE(accepted_at,clock_timestamp())
 WHERE o.event_id=$1 AND o.recipient_user_id=$2
 AND EXISTS (SELECT 1 FROM sos_notification_targets t WHERE t.event_id=o.event_id AND t.recipient_user_id=o.recipient_user_id AND t.state='ACCEPTED')
 AND NOT EXISTS (SELECT 1 FROM sos_notification_targets t WHERE t.event_id=o.event_id AND t.recipient_user_id=o.recipient_user_id AND t.state='QUEUED')`, eventID, recipient)
	if err != nil {
		return false, fmt.Errorf("record sos provider acceptance: %w", err)
	}
	if err = tx.Commit(ctx); err != nil {
		return false, fmt.Errorf("commit sos dispatch: %w", err)
	}
	return true, nil
}
