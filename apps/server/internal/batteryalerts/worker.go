package batteryalerts

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"

	"github.com/QuocDuong16/anve/server/internal/notifications"
)

// RunWorker executes bounded retention cleanup and optional opaque push dispatch.
func RunWorker(ctx context.Context, store *Store, gateway notifications.Gateway, logger *slog.Logger) {
	pass := func() {
		for i := 0; i < 100; i++ {
			purgeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
			purged, err := store.PurgeRetention(purgeCtx)
			cancel()
			if err != nil {
				if ctx.Err() == nil {
					logger.Error("battery alert retention cleanup failed")
				}
				break
			}
			if purged == 0 {
				break
			}
		}
		if gateway == nil {
			return
		}
		for i := 0; i < 100; i++ {
			dispatchCtx, cancel := context.WithTimeout(ctx, 12*time.Second)
			worked, err := store.DispatchOne(dispatchCtx, gateway)
			cancel()
			if err != nil {
				if ctx.Err() == nil {
					logger.Error("battery alert notification dispatch failed")
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

// PurgeRetention removes bounded batches of expired alerts while keeping UUID
// fences in low_battery_alert_fences until account deletion.
func (s *Store) PurgeRetention(ctx context.Context) (int64, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return 0, fmt.Errorf("begin battery alert retention: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	res, err := tx.Exec(ctx, `DELETE FROM low_battery_alerts WHERE id IN (
		SELECT a.id FROM low_battery_alerts a JOIN users u ON u.id=a.owner_user_id
		WHERE a.recorded_at <= now() - LEAST($1, u.location_retention_days) * interval '1 day'
		   OR a.recorded_at <= COALESCE((SELECT cutoff FROM location_retention_watermark WHERE singleton), '-infinity'::timestamptz)
		ORDER BY a.recorded_at, a.id LIMIT 100
		FOR UPDATE OF a SKIP LOCKED
	)`, s.retentionDays)
	if err != nil {
		return 0, fmt.Errorf("purge expired battery alerts: %w", err)
	}
	if err = tx.Commit(ctx); err != nil {
		return 0, fmt.Errorf("commit battery alert retention: %w", err)
	}
	return res.RowsAffected(), nil
}

// DispatchOne processes one queued low-battery push recipient while holding
// authorization locks through the bounded provider call.
func (s *Store) DispatchOne(ctx context.Context, gateway notifications.Gateway) (bool, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return false, fmt.Errorf("begin battery alert dispatch: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var alertID, recipient, owner uuid.UUID
	err = tx.QueryRow(ctx, `SELECT o.alert_id,o.recipient_user_id,a.owner_user_id
		FROM low_battery_notification_outbox o
		JOIN low_battery_alerts a ON a.id=o.alert_id
		WHERE o.state='QUEUED' AND o.next_attempt_at<=clock_timestamp()
		ORDER BY o.next_attempt_at,o.alert_id,o.recipient_user_id LIMIT 1`).Scan(&alertID, &recipient, &owner)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("select battery alert notification: %w", err)
	}

	var sharingEnabled, lowBatteryEnabled bool
	err = tx.QueryRow(ctx, `SELECT location_sharing_enabled,low_battery_alerts_enabled FROM users WHERE id=$1 FOR SHARE`, owner).
		Scan(&sharingEnabled, &lowBatteryEnabled)
	if errors.Is(err, pgx.ErrNoRows) {
		return true, nil
	}
	if err != nil {
		return false, fmt.Errorf("lock battery alert owner: %w", err)
	}

	var recordedAt time.Time
	var resolvedAt pgtype.Timestamptz
	var scope string
	var journeyID pgtype.UUID
	var sharingEpochID uuid.UUID
	var captureGeneration int64
	var now time.Time
	err = tx.QueryRow(ctx, `SELECT recorded_at,resolved_at,scope,journey_id,sharing_epoch_id,capture_generation,clock_timestamp()
		FROM low_battery_alerts WHERE id=$1 FOR UPDATE`, alertID).
		Scan(&recordedAt, &resolvedAt, &scope, &journeyID, &sharingEpochID, &captureGeneration, &now)
	if errors.Is(err, pgx.ErrNoRows) {
		return true, tx.Commit(ctx)
	}
	if err != nil {
		return false, fmt.Errorf("lock battery alert row: %w", err)
	}

	var queueState string
	err = tx.QueryRow(ctx, `SELECT state FROM low_battery_notification_outbox WHERE alert_id=$1 AND recipient_user_id=$2 FOR UPDATE`, alertID, recipient).Scan(&queueState)
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && queueState != "QUEUED") {
		return true, tx.Commit(ctx)
	}
	if err != nil {
		return false, fmt.Errorf("lock battery alert outbox: %w", err)
	}

	if recordedAt.Before(now.Add(-MaxFreshnessAge)) {
		if _, err = tx.Exec(ctx, `UPDATE low_battery_notification_outbox SET state='EXPIRED' WHERE alert_id=$1 AND recipient_user_id=$2`, alertID, recipient); err != nil {
			return false, fmt.Errorf("expire battery alert notification: %w", err)
		}
		if err = tx.Commit(ctx); err != nil {
			return false, fmt.Errorf("commit battery alert expiration: %w", err)
		}
		return true, nil
	}

	if resolvedAt.Valid || !sharingEnabled || !lowBatteryEnabled {
		if _, err = tx.Exec(ctx, `UPDATE low_battery_notification_outbox SET state='SUPPRESSED' WHERE alert_id=$1 AND recipient_user_id=$2`, alertID, recipient); err != nil {
			return false, fmt.Errorf("suppress battery alert notification: %w", err)
		}
		if err = tx.Commit(ctx); err != nil {
			return false, fmt.Errorf("commit battery alert suppression: %w", err)
		}
		return true, nil
	}

	var lockedGrant uuid.UUID
	err = tx.QueryRow(ctx, `SELECT g.id
		FROM low_battery_alert_recipients ar
		JOIN location_audience_grants g ON g.id=ar.grant_id
		 AND g.owner_user_id=$3
		 AND g.viewer_user_id=$2
		 AND g.sharing_epoch_id=$4
		 AND g.revoked_at IS NULL
		 AND g.visible_from <= $5
		 AND (g.visible_until IS NULL OR g.visible_until > clock_timestamp())
		JOIN location_sharing_epochs e ON e.id=g.sharing_epoch_id AND e.ended_at IS NULL
		JOIN circle_membership_periods op ON op.id=g.owner_membership_period_id AND op.ended_at IS NULL
		JOIN memberships om ON om.id=op.membership_id AND om.user_id=g.owner_user_id AND om.circle_id=g.circle_id
		JOIN circle_membership_periods vp ON vp.id=g.viewer_membership_period_id AND vp.ended_at IS NULL
		JOIN memberships vm ON vm.id=vp.membership_id AND vm.user_id=g.viewer_user_id AND vm.circle_id=g.circle_id
		WHERE ar.alert_id=$1 AND ar.recipient_user_id=$2 AND $6 >= g.capture_generation
		FOR SHARE OF g,e,op,om,vp,vm`,
		alertID, recipient, owner, sharingEpochID, recordedAt, captureGeneration,
	).Scan(&lockedGrant)
	if err == nil && scope == ScopeJourney {
		if !journeyID.Valid {
			err = pgx.ErrNoRows
		} else {
			var lockedJourney uuid.UUID
			err = tx.QueryRow(ctx, `SELECT j.id
				FROM journeys j
				JOIN journey_recipients jr ON jr.journey_id=j.id AND jr.user_id=$2 AND jr.grant_id=$3 AND jr.revoked_at IS NULL
				WHERE j.id=$1 AND j.state='ACTIVE' AND j.expires_at > clock_timestamp()
				FOR SHARE OF j,jr`, uuid.UUID(journeyID.Bytes), recipient, lockedGrant).Scan(&lockedJourney)
		}
	}
	if errors.Is(err, pgx.ErrNoRows) {
		if _, err = tx.Exec(ctx, `UPDATE low_battery_notification_outbox SET state='SUPPRESSED' WHERE alert_id=$1 AND recipient_user_id=$2`, alertID, recipient); err != nil {
			return false, fmt.Errorf("suppress unauthorized battery alert notification: %w", err)
		}
		if err = tx.Commit(ctx); err != nil {
			return false, fmt.Errorf("commit unauthorized battery alert suppression: %w", err)
		}
		return true, nil
	}
	if err != nil {
		return false, fmt.Errorf("lock battery alert dispatch authorization: %w", err)
	}

	if _, err = tx.Exec(ctx, `INSERT INTO low_battery_notification_targets(alert_id,recipient_user_id,device_id)
		SELECT $1,$2,d.device_id FROM device_push_tokens d
		JOIN sessions s ON s.id=d.session_id AND s.device_id=d.device_id AND s.user_id=d.owner_user_id
		 AND s.revoked_at IS NULL AND s.refresh_expires_at>clock_timestamp()
		WHERE d.owner_user_id=$2 ON CONFLICT DO NOTHING`, alertID, recipient); err != nil {
		return false, fmt.Errorf("prepare battery alert targets: %w", err)
	}

	var device uuid.UUID
	var token string
	err = tx.QueryRow(ctx, `SELECT t.device_id,d.token
		FROM low_battery_notification_targets t
		JOIN device_push_tokens d ON d.device_id=t.device_id AND d.owner_user_id=t.recipient_user_id
		JOIN sessions s ON s.id=d.session_id AND s.user_id=d.owner_user_id AND s.device_id=d.device_id
		 AND s.revoked_at IS NULL AND s.refresh_expires_at>clock_timestamp()
		WHERE t.alert_id=$1 AND t.recipient_user_id=$2 AND t.state='QUEUED' AND t.next_attempt_at<=clock_timestamp()
		ORDER BY t.next_attempt_at,t.device_id LIMIT 1
		FOR UPDATE OF t FOR SHARE OF d,s`, alertID, recipient).Scan(&device, &token)
	if errors.Is(err, pgx.ErrNoRows) {
		if _, err = tx.Exec(ctx, `UPDATE low_battery_notification_outbox SET next_attempt_at=clock_timestamp()+interval '1 minute' WHERE alert_id=$1 AND recipient_user_id=$2`, alertID, recipient); err != nil {
			return false, fmt.Errorf("defer battery alert notification: %w", err)
		}
		if err = tx.Commit(ctx); err != nil {
			return false, fmt.Errorf("commit battery alert deferral: %w", err)
		}
		return true, nil
	}
	if err != nil {
		return false, fmt.Errorf("lock battery alert target: %w", err)
	}

	sendCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	sendErr := gateway.Send(sendCtx, token, notifications.Signal{EventID: alertID, Kind: "LOW_BATTERY"})
	cancel()

	switch {
	case sendErr == nil:
		_, err = tx.Exec(ctx, `UPDATE low_battery_notification_targets SET state='ACCEPTED',accepted_at=clock_timestamp(),attempts=attempts+1 WHERE alert_id=$1 AND recipient_user_id=$2 AND device_id=$3`, alertID, recipient, device)
	case errors.Is(sendErr, notifications.ErrInvalidToken):
		_, err = tx.Exec(ctx, `DELETE FROM device_push_tokens WHERE device_id=$1 AND owner_user_id=$2`, device, recipient)
	default:
		_, err = tx.Exec(ctx, `UPDATE low_battery_notification_targets SET attempts=attempts+1,next_attempt_at=clock_timestamp()+make_interval(secs=>LEAST(300,(1 << LEAST(attempts,8)))) WHERE alert_id=$1 AND recipient_user_id=$2 AND device_id=$3`, alertID, recipient, device)
	}
	if err != nil {
		return false, fmt.Errorf("record battery alert push attempt: %w", err)
	}

	if _, err = tx.Exec(ctx, `UPDATE low_battery_notification_outbox SET attempts=attempts+1,next_attempt_at=clock_timestamp()+interval '5 seconds',
		accepted_at=CASE WHEN $3 THEN COALESCE(accepted_at,clock_timestamp()) ELSE accepted_at END
		WHERE alert_id=$1 AND recipient_user_id=$2`, alertID, recipient, sendErr == nil); err != nil {
		return false, fmt.Errorf("schedule battery alert outbox: %w", err)
	}
	if _, err = tx.Exec(ctx, `UPDATE low_battery_notification_outbox o
		SET state='ACCEPTED',accepted_at=COALESCE(accepted_at,clock_timestamp())
		WHERE o.alert_id=$1 AND o.recipient_user_id=$2
		  AND EXISTS (SELECT 1 FROM low_battery_notification_targets t WHERE t.alert_id=o.alert_id AND t.recipient_user_id=o.recipient_user_id AND t.state='ACCEPTED')
		  AND NOT EXISTS (SELECT 1 FROM low_battery_notification_targets t WHERE t.alert_id=o.alert_id AND t.recipient_user_id=o.recipient_user_id AND t.state='QUEUED')`,
		alertID, recipient); err != nil {
		return false, fmt.Errorf("record battery alert provider acceptance: %w", err)
	}
	if err = tx.Commit(ctx); err != nil {
		return false, fmt.Errorf("commit battery alert dispatch: %w", err)
	}
	return true, nil
}
