package places

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

// RunNotificationWorker processes a bounded number of place signals per pass.
func RunNotificationWorker(ctx context.Context, store *Store, gateway notifications.Gateway, logger *slog.Logger) {
	pass := func() {
		for i := 0; i < 100; i++ {
			attempt, cancel := context.WithTimeout(ctx, 12*time.Second)
			processed, err := store.DispatchNotification(attempt, gateway)
			cancel()
			if err != nil {
				if ctx.Err() == nil {
					logger.Error("place notification evaluation failed")
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

// DispatchNotification rechecks current consent and the original grants in
// the same transaction as provider admission. Provider acceptance is not a
// claim that a notification was received or displayed.
func (s *Store) DispatchNotification(ctx context.Context, gateway notifications.Gateway) (bool, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return false, fmt.Errorf("begin place notification: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var eventID, recipient, owner uuid.UUID
	err = tx.QueryRow(ctx, `SELECT o.event_id,o.recipient_user_id,pm.owner_user_id FROM place_notification_outbox o
 JOIN place_events e ON e.id=o.event_id JOIN place_monitors pm ON pm.id=e.monitor_id
 WHERE o.state='QUEUED' AND o.next_attempt_at<=clock_timestamp() ORDER BY o.next_attempt_at,o.event_id,o.recipient_user_id LIMIT 1`).Scan(&eventID, &recipient, &owner)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("select place notification: %w", err)
	}
	var lock uuid.UUID
	err = tx.QueryRow(ctx, `SELECT id FROM users WHERE id=$1 FOR SHARE`, owner).Scan(&lock)
	if errors.Is(err, pgx.ErrNoRows) {
		return true, nil
	}
	if err != nil {
		return false, fmt.Errorf("lock place owner: %w", err)
	}
	var processedAt time.Time
	err = tx.QueryRow(ctx, `SELECT id,processed_at FROM place_events WHERE id=$1 FOR UPDATE`, eventID).Scan(&lock, &processedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return true, nil
	}
	if err != nil {
		return false, fmt.Errorf("lock place event: %w", err)
	}
	var queueState string
	err = tx.QueryRow(ctx, `SELECT state FROM place_notification_outbox WHERE event_id=$1 AND recipient_user_id=$2 FOR UPDATE`, eventID, recipient).Scan(&queueState)
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && queueState != "QUEUED") {
		return true, nil
	}
	if err != nil {
		return false, fmt.Errorf("lock place notification: %w", err)
	}
	if processedAt.Before(time.Now().Add(-7 * 24 * time.Hour)) {
		_, err = tx.Exec(ctx, `UPDATE place_notification_outbox SET state='EXPIRED' WHERE event_id=$1 AND recipient_user_id=$2`, eventID, recipient)
		if err != nil {
			return false, fmt.Errorf("expire place notification: %w", err)
		}
		if err = tx.Commit(ctx); err != nil {
			return false, fmt.Errorf("commit place expiration: %w", err)
		}
		return true, nil
	}
	// Hold the same records whose removal or revocation invalidates the event
	// until the provider attempt completes. A read-only authorization check
	// alone would race monitor revocation or deletion.
	err = tx.QueryRow(ctx, `SELECT pm.id FROM place_events e
 JOIN place_monitors pm ON pm.id=e.monitor_id AND pm.revoked_at IS NULL
 JOIN places p ON p.id=pm.place_id AND p.revision=pm.place_revision
 JOIN location_sharing_epochs se ON se.id=pm.sharing_epoch_id AND se.ended_at IS NULL
 JOIN circle_membership_periods mp ON mp.id=pm.owner_period_id AND mp.ended_at IS NULL
 JOIN memberships m ON m.id=mp.membership_id
 JOIN circle_membership_periods pp ON pp.id=p.owner_membership_period_id AND pp.ended_at IS NULL
 JOIN memberships po ON po.id=pp.membership_id
 JOIN location_points lp ON lp.id=e.point_id
 JOIN location_points bp ON bp.id=e.baseline_point_id
 JOIN location_points ep ON ep.id=e.evidence_point_id
 WHERE e.id=$1 FOR SHARE OF pm,p,se,mp,m,pp,po,lp,bp,ep`, eventID).Scan(&lock)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return false, fmt.Errorf("lock place authorization: %w", err)
	}
	if err == nil {
		// When the monitored member is not the place owner, its explicit
		// access is also revocable and must be ordered with the send.
		_, err = tx.Exec(ctx, `SELECT pa.id FROM place_events e JOIN place_monitors pm ON pm.id=e.monitor_id
 JOIN places p ON p.id=pm.place_id JOIN place_access pa ON pa.place_id=p.id
 AND pa.member_user_id=pm.owner_user_id AND pa.member_period_id=pm.owner_period_id AND pa.revoked_at IS NULL
 WHERE e.id=$1 FOR SHARE OF pa`, eventID)
		if err != nil {
			return false, fmt.Errorf("lock monitored member place access: %w", err)
		}
	}
	// The exact grant and place-access identities are bound at event creation.
	err = tx.QueryRow(ctx, `SELECT g.id FROM place_notification_outbox o JOIN location_audience_grants g ON g.id=o.audience_grant_id AND g.revoked_at IS NULL
 JOIN circle_membership_periods vp ON vp.id=g.viewer_membership_period_id AND vp.ended_at IS NULL
 JOIN memberships vm ON vm.id=vp.membership_id AND vm.user_id=g.viewer_user_id AND vm.circle_id=g.circle_id
 WHERE o.event_id=$1 AND o.recipient_user_id=$2 FOR SHARE OF g,vp,vm`, eventID, recipient).Scan(&lock)
	if err == nil {
		// Lock any explicit place access separately; the creator has no access row.
		_, err = tx.Exec(ctx, `SELECT a.id FROM place_notification_outbox o JOIN place_access a ON a.id=o.place_access_id AND a.revoked_at IS NULL WHERE o.event_id=$1 AND o.recipient_user_id=$2 FOR SHARE OF a`, eventID, recipient)
	}
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return false, fmt.Errorf("lock place grants: %w", err)
	}
	var authorized bool
	if err == nil {
		err = tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM place_events e JOIN place_notification_outbox o ON o.event_id=e.id AND o.recipient_user_id=$2
 JOIN place_monitors pm ON pm.id=e.monitor_id AND pm.revoked_at IS NULL
 JOIN places p ON p.id=pm.place_id AND p.revision=pm.place_revision
 JOIN users u ON u.id=pm.owner_user_id AND u.location_sharing_enabled
 JOIN location_points lp ON lp.id=e.point_id AND lp.sharing_epoch_id=pm.sharing_epoch_id
 JOIN location_points bp ON bp.id=e.baseline_point_id JOIN location_points ep ON ep.id=e.evidence_point_id
 JOIN location_sharing_epochs se ON se.id=pm.sharing_epoch_id AND se.ended_at IS NULL
 JOIN circle_membership_periods mp ON mp.id=pm.owner_period_id AND mp.ended_at IS NULL
 JOIN memberships m ON m.id=mp.membership_id AND m.user_id=pm.owner_user_id AND m.circle_id=p.circle_id
 JOIN circle_membership_periods pp ON pp.id=p.owner_membership_period_id AND pp.ended_at IS NULL
 JOIN memberships po ON po.id=pp.membership_id AND po.user_id=p.owner_user_id AND po.circle_id=p.circle_id
 JOIN place_monitor_recipients r ON r.monitor_id=pm.id AND r.recipient_user_id=$2 AND r.audience_grant_id=o.audience_grant_id AND r.place_access_id IS NOT DISTINCT FROM o.place_access_id
 JOIN active_location_audience g ON g.id=r.audience_grant_id AND g.owner_user_id=pm.owner_user_id AND g.viewer_user_id=$2 AND g.sharing_epoch_id=pm.sharing_epoch_id
 LEFT JOIN place_access ra ON ra.id=r.place_access_id AND ra.place_id=p.id AND ra.member_user_id=$2 AND ra.revoked_at IS NULL
 LEFT JOIN circle_membership_periods rp ON rp.id=ra.member_period_id AND rp.ended_at IS NULL
 LEFT JOIN memberships rm ON rm.id=rp.membership_id AND rm.user_id=$2 AND rm.circle_id=p.circle_id
 WHERE e.id=$1 AND e.recorded_at>=pm.started_at AND lp.received_at>=pm.started_at AND lp.capture_generation>=pm.capture_generation
 AND LEAST(e.recorded_at,bp.recorded_at,ep.recorded_at)>=GREATEST(now()-u.location_retention_days*interval '1 day',$3::timestamptz,COALESCE((SELECT cutoff FROM location_retention_watermark WHERE singleton),'-infinity'::timestamptz))
 AND (pm.owner_user_id=p.owner_user_id OR EXISTS(SELECT 1 FROM place_access pa WHERE pa.place_id=p.id AND pa.member_user_id=pm.owner_user_id AND pa.member_period_id=pm.owner_period_id AND pa.revoked_at IS NULL))
 AND ($2=p.owner_user_id OR rm.id IS NOT NULL) AND e.recorded_at>=g.visible_from AND lp.received_at>=g.visible_from AND lp.capture_generation>=g.capture_generation)`, eventID, recipient, time.Now().UTC().Add(-s.retention)).Scan(&authorized)
		if err != nil {
			return false, fmt.Errorf("authorize place notification: %w", err)
		}
	}
	if !authorized {
		_, err = tx.Exec(ctx, `UPDATE place_notification_outbox SET state='SUPPRESSED' WHERE event_id=$1 AND recipient_user_id=$2`, eventID, recipient)
		if err != nil {
			return false, fmt.Errorf("suppress place notification: %w", err)
		}
		if err = tx.Commit(ctx); err != nil {
			return false, fmt.Errorf("commit place suppression: %w", err)
		}
		return true, nil
	}
	_, err = tx.Exec(ctx, `INSERT INTO place_notification_targets(event_id,recipient_user_id,device_id)
 SELECT $1,$2,d.device_id FROM device_push_tokens d JOIN sessions s ON s.id=d.session_id AND s.device_id=d.device_id AND s.user_id=d.owner_user_id AND s.revoked_at IS NULL AND s.refresh_expires_at>clock_timestamp()
 WHERE d.owner_user_id=$2 ON CONFLICT DO NOTHING`, eventID, recipient)
	if err != nil {
		return false, fmt.Errorf("prepare place targets: %w", err)
	}
	var device uuid.UUID
	var token string
	err = tx.QueryRow(ctx, `SELECT t.device_id,d.token FROM place_notification_targets t JOIN device_push_tokens d ON d.device_id=t.device_id AND d.owner_user_id=t.recipient_user_id
 JOIN sessions s ON s.id=d.session_id AND s.user_id=d.owner_user_id AND s.device_id=d.device_id AND s.revoked_at IS NULL AND s.refresh_expires_at>clock_timestamp()
 WHERE t.event_id=$1 AND t.recipient_user_id=$2 AND t.state='QUEUED' AND t.next_attempt_at<=clock_timestamp()
 ORDER BY t.next_attempt_at,t.device_id LIMIT 1 FOR UPDATE OF t FOR SHARE OF d,s`, eventID, recipient).Scan(&device, &token)
	if errors.Is(err, pgx.ErrNoRows) {
		_, err = tx.Exec(ctx, `UPDATE place_notification_outbox SET next_attempt_at=clock_timestamp()+interval '1 minute' WHERE event_id=$1 AND recipient_user_id=$2`, eventID, recipient)
		if err != nil {
			return false, fmt.Errorf("defer place notification: %w", err)
		}
		if err = tx.Commit(ctx); err != nil {
			return false, fmt.Errorf("commit place deferral: %w", err)
		}
		return true, nil
	}
	if err != nil {
		return false, fmt.Errorf("lock place target: %w", err)
	}
	sendCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	sendErr := gateway.Send(sendCtx, token, notifications.Signal{EventID: eventID, Kind: "PLACE"})
	cancel()
	switch {
	case sendErr == nil:
		_, err = tx.Exec(ctx, `UPDATE place_notification_targets SET state='ACCEPTED',accepted_at=clock_timestamp(),attempts=attempts+1 WHERE event_id=$1 AND recipient_user_id=$2 AND device_id=$3`, eventID, recipient, device)
	case errors.Is(sendErr, notifications.ErrInvalidToken):
		_, err = tx.Exec(ctx, `DELETE FROM device_push_tokens WHERE device_id=$1 AND owner_user_id=$2`, device, recipient)
	default:
		_, err = tx.Exec(ctx, `UPDATE place_notification_targets SET attempts=attempts+1,next_attempt_at=clock_timestamp()+make_interval(secs=>LEAST(300,(1 << LEAST(attempts,8)))) WHERE event_id=$1 AND recipient_user_id=$2 AND device_id=$3`, eventID, recipient, device)
	}
	if err != nil {
		return false, fmt.Errorf("record place push attempt: %w", err)
	}
	_, err = tx.Exec(ctx, `UPDATE place_notification_outbox SET attempts=attempts+1,next_attempt_at=clock_timestamp()+interval '5 seconds' WHERE event_id=$1 AND recipient_user_id=$2`, eventID, recipient)
	if err != nil {
		return false, fmt.Errorf("schedule place notification: %w", err)
	}
	_, err = tx.Exec(ctx, `UPDATE place_notification_outbox o SET state='ACCEPTED',accepted_at=clock_timestamp() WHERE o.event_id=$1 AND o.recipient_user_id=$2
 AND EXISTS(SELECT 1 FROM place_notification_targets t WHERE t.event_id=o.event_id AND t.recipient_user_id=o.recipient_user_id AND t.state='ACCEPTED')
 AND NOT EXISTS(SELECT 1 FROM place_notification_targets t WHERE t.event_id=o.event_id AND t.recipient_user_id=o.recipient_user_id AND t.state='QUEUED')`, eventID, recipient)
	if err != nil {
		return false, fmt.Errorf("record place provider acceptance: %w", err)
	}
	if err = tx.Commit(ctx); err != nil {
		return false, fmt.Errorf("commit place notification: %w", err)
	}
	return true, nil
}
