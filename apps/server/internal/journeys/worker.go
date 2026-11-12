package journeys

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

// Maintain rotates bounded owner batches, including opt-in active automation.
func (s *Store) Maintain(ctx context.Context) error {
	rows, err := s.pool.Query(ctx, `SELECT j.owner_user_id FROM current_journeys j JOIN users u ON u.id=j.owner_user_id
 WHERE j.state='ACTIVE' OR j.started_at<=now()-LEAST($1,u.location_retention_days)*interval '1 day'
 OR j.started_at<=COALESCE((SELECT cutoff FROM location_retention_watermark WHERE singleton),'-infinity'::timestamptz)
 GROUP BY j.owner_user_id ORDER BY MIN(j.evaluated_at),j.owner_user_id LIMIT 100`, s.retentionDays)
	if err != nil {
		return fmt.Errorf("select journey maintenance: %w", err)
	}
	owners := []uuid.UUID{}
	for rows.Next() {
		var owner uuid.UUID
		if err = rows.Scan(&owner); err != nil {
			rows.Close()
			return fmt.Errorf("scan journey maintenance: %w", err)
		}
		owners = append(owners, owner)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return fmt.Errorf("iterate journey maintenance: %w", err)
	}
	for _, owner := range owners {
		err = s.maintainOwner(ctx, owner)
		if err != nil {
			return err
		}
	}
	return nil
}

func (s *Store) maintainOwner(ctx context.Context, owner uuid.UUID) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin journey maintenance: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var locked uuid.UUID
	err = tx.QueryRow(ctx, `SELECT id FROM users WHERE id=$1 FOR UPDATE`, owner).Scan(&locked)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("lock journey maintenance: %w", err)
	}
	if err = s.normalizeOwner(ctx, tx, owner); err != nil {
		return err
	}
	if err = s.evaluateAutomation(ctx, tx, owner); err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `DELETE FROM journeys WHERE id IN (SELECT j.id FROM journeys j JOIN users u ON u.id=j.owner_user_id WHERE u.id=$1
 AND (j.started_at<=now()-LEAST($2,u.location_retention_days)*interval '1 day'
 OR j.started_at<=COALESCE((SELECT cutoff FROM location_retention_watermark WHERE singleton),'-infinity'::timestamptz)) ORDER BY j.started_at,j.id LIMIT 100)`, owner, s.retentionDays)
	if err != nil {
		return fmt.Errorf("purge journey content: %w", err)
	}
	if err = tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit journey maintenance: %w", err)
	}
	return nil
}

// RunWorker maintains expiry without Firebase; configured delivery is optional.
func RunWorker(ctx context.Context, s *Store, gateway notifications.Gateway, logger *slog.Logger) {
	pass := func() {
		maintenanceCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
		err := s.Maintain(maintenanceCtx)
		cancel()
		if err != nil && ctx.Err() == nil {
			logger.Error("journey maintenance failed")
		}
		if gateway == nil {
			return
		}
		for i := 0; i < 100; i++ {
			attemptCtx, cancel := context.WithTimeout(ctx, 12*time.Second)
			worked, err := s.DispatchOne(attemptCtx, gateway)
			cancel()
			if err != nil {
				if ctx.Err() == nil {
					logger.Error("journey dispatch failed")
				}
				return
			}
			if !worked {
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

// DispatchOne orders dispatch behind privacy mutations using owner, exact grant,
// membership and session locks. Already provider-accepted signals cannot be recalled.
func (s *Store) DispatchOne(ctx context.Context, gateway notifications.Gateway) (bool, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return false, fmt.Errorf("begin journey dispatch: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var id, owner, recipient uuid.UUID
	var kind string
	var episode int
	err = tx.QueryRow(ctx, `SELECT o.journey_id,j.owner_user_id,o.recipient_user_id,o.kind,o.episode FROM journey_notification_outbox o JOIN journeys j ON j.id=o.journey_id
 WHERE o.state='QUEUED' AND o.next_attempt_at<=clock_timestamp() ORDER BY o.next_attempt_at,o.journey_id,o.recipient_user_id,o.kind,o.episode LIMIT 1`).Scan(&id, &owner, &recipient, &kind, &episode)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("select journey dispatch: %w", err)
	}
	var locked uuid.UUID
	err = tx.QueryRow(ctx, `SELECT id FROM users WHERE id=$1 FOR UPDATE`, owner).Scan(&locked)
	if errors.Is(err, pgx.ErrNoRows) {
		return true, nil
	}
	if err != nil {
		return false, fmt.Errorf("lock journey dispatch owner: %w", err)
	}
	if err = s.normalizeOwner(ctx, tx, owner); err != nil {
		return false, err
	}
	if err = s.evaluateAutomation(ctx, tx, owner); err != nil {
		return false, err
	}
	var state string
	var currentEpisode int
	var staleActive bool
	err = tx.QueryRow(ctx, `SELECT j.effective_state,j.stale_episode,j.stale_since IS NOT NULL AND j.automation_generation=u.location_capture_generation FROM current_journeys j JOIN journeys base ON base.id=j.id JOIN users u ON u.id=j.owner_user_id WHERE j.id=$1 FOR UPDATE OF base`, id).Scan(&state, &currentEpisode, &staleActive)
	if errors.Is(err, pgx.ErrNoRows) {
		return true, tx.Commit(ctx)
	}
	if err != nil {
		return false, fmt.Errorf("lock journey dispatch state: %w", err)
	}
	var queued string
	err = tx.QueryRow(ctx, `SELECT state FROM journey_notification_outbox WHERE journey_id=$1 AND recipient_user_id=$2 AND kind=$3 AND episode=$4 FOR UPDATE`, id, recipient, kind, episode).Scan(&queued)
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && queued != "QUEUED") {
		return true, tx.Commit(ctx)
	}
	if err != nil {
		return false, fmt.Errorf("lock journey dispatch work: %w", err)
	}
	_, readErr := s.read(ctx, tx, recipient, id)
	if readErr != nil && !errors.Is(readErr, ErrNotFound) {
		return false, readErr
	}
	validState := state == kind
	if kind == "STALE" {
		validState = state == "ACTIVE" && staleActive && episode == currentEpisode
	}
	if !validState || errors.Is(readErr, ErrNotFound) || (recipient == owner && kind != "STALE") {
		_, err = tx.Exec(ctx, `UPDATE journey_notification_outbox SET state='SUPPRESSED' WHERE journey_id=$1 AND recipient_user_id=$2 AND kind=$3 AND episode=$4`, id, recipient, kind, episode)
		if err != nil {
			return false, fmt.Errorf("suppress journey dispatch: %w", err)
		}
		return true, tx.Commit(ctx)
	}
	// Lock the same authorization rows that consent, grant revocation and Circle
	// removal change; their completed transaction must precede subsequent sends.
	if recipient != owner {
		err = tx.QueryRow(ctx, `SELECT g.id FROM authorized_journey_recipients a
 JOIN location_audience_grants g ON g.id=a.grant_id JOIN location_sharing_epochs e ON e.id=g.sharing_epoch_id
 JOIN circle_membership_periods op ON op.id=g.owner_membership_period_id JOIN memberships om ON om.id=op.membership_id
 JOIN circle_membership_periods vp ON vp.id=g.viewer_membership_period_id JOIN memberships vm ON vm.id=vp.membership_id
 WHERE a.journey_id=$1 AND a.user_id=$2 FOR SHARE OF g,e,op,om,vp,vm`, id, recipient).Scan(&locked)
		if errors.Is(err, pgx.ErrNoRows) {
			_, err = tx.Exec(ctx, `UPDATE journey_notification_outbox SET state='SUPPRESSED' WHERE journey_id=$1 AND recipient_user_id=$2 AND kind=$3 AND episode=$4`, id, recipient, kind, episode)
			if err != nil {
				return false, fmt.Errorf("suppress unauthorized journey dispatch: %w", err)
			}
			return true, tx.Commit(ctx)
		}
		if err != nil {
			return false, fmt.Errorf("lock journey dispatch authorization: %w", err)
		}
	}
	_, err = tx.Exec(ctx, `INSERT INTO journey_notification_targets(journey_id,recipient_user_id,kind,episode,device_id)
 SELECT $1,$2,$3,$4,d.device_id FROM device_push_tokens d JOIN sessions s ON s.id=d.session_id AND s.user_id=d.owner_user_id AND s.device_id=d.device_id
 AND s.revoked_at IS NULL AND s.refresh_expires_at>clock_timestamp() WHERE d.owner_user_id=$2 ON CONFLICT DO NOTHING`, id, recipient, kind, episode)
	if err != nil {
		return false, fmt.Errorf("prepare journey targets: %w", err)
	}
	var device uuid.UUID
	var token string
	err = tx.QueryRow(ctx, `SELECT t.device_id,d.token FROM journey_notification_targets t JOIN device_push_tokens d ON d.device_id=t.device_id AND d.owner_user_id=t.recipient_user_id
 JOIN sessions s ON s.id=d.session_id AND s.user_id=d.owner_user_id AND s.device_id=d.device_id AND s.revoked_at IS NULL AND s.refresh_expires_at>clock_timestamp()
 WHERE t.journey_id=$1 AND t.recipient_user_id=$2 AND t.kind=$3 AND t.episode=$4 AND t.accepted_at IS NULL ORDER BY t.device_id LIMIT 1
 FOR UPDATE OF t FOR SHARE OF d,s`, id, recipient, kind, episode).Scan(&device, &token)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return false, fmt.Errorf("lock journey target: %w", err)
	}
	if err == nil {
		sendCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		sendErr := gateway.Send(sendCtx, token, notifications.Signal{EventID: id, Kind: "JOURNEY_" + kind, Episode: episode})
		cancel()
		switch {
		case sendErr == nil:
			_, err = tx.Exec(ctx, `UPDATE journey_notification_targets SET accepted_at=clock_timestamp() WHERE journey_id=$1 AND recipient_user_id=$2 AND kind=$3 AND episode=$4 AND device_id=$5`, id, recipient, kind, episode, device)
		case errors.Is(sendErr, notifications.ErrInvalidToken):
			_, err = tx.Exec(ctx, `DELETE FROM device_push_tokens WHERE device_id=$1 AND owner_user_id=$2`, device, recipient)
		default:
			err = nil
		}
		if err != nil {
			return false, fmt.Errorf("record journey target outcome: %w", err)
		}
	}
	_, err = tx.Exec(ctx, `UPDATE journey_notification_outbox o SET attempts=attempts+1,
 next_attempt_at=clock_timestamp()+make_interval(secs=>LEAST(300,5*(1 << LEAST(attempts,6)))),
 state=CASE WHEN EXISTS(SELECT 1 FROM journey_notification_targets t WHERE t.journey_id=o.journey_id AND t.recipient_user_id=o.recipient_user_id AND t.kind=o.kind AND t.episode=o.episode)
 AND NOT EXISTS(SELECT 1 FROM journey_notification_targets t WHERE t.journey_id=o.journey_id AND t.recipient_user_id=o.recipient_user_id AND t.kind=o.kind AND t.episode=o.episode AND t.accepted_at IS NULL)
 THEN 'ACCEPTED' ELSE 'QUEUED' END,
 accepted_at=(SELECT MIN(t.accepted_at) FROM journey_notification_targets t WHERE t.journey_id=o.journey_id AND t.recipient_user_id=o.recipient_user_id AND t.kind=o.kind AND t.episode=o.episode)
 WHERE journey_id=$1 AND recipient_user_id=$2 AND kind=$3 AND episode=$4`, id, recipient, kind, episode)
	if err != nil {
		return false, fmt.Errorf("schedule journey dispatch: %w", err)
	}
	if err = tx.Commit(ctx); err != nil {
		return false, fmt.Errorf("commit journey dispatch: %w", err)
	}
	return true, nil
}
