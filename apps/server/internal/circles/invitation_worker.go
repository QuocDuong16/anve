package circles

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

// MaintainInvitations retires content in bounded batches; UUID fences remain
// until account deletion so expired work cannot be silently replayed.
func (s *InvitationStore) MaintainInvitations(ctx context.Context) error {
	_, err := s.pool.Exec(ctx, `DELETE FROM invitation_notification_budgets WHERE (email_hash,budget_day) IN
 (SELECT email_hash,budget_day FROM invitation_notification_budgets WHERE budget_day<(clock_timestamp() AT TIME ZONE 'UTC')::date-1 LIMIT 100)`)
	if err != nil {
		return fmt.Errorf("purge invitation notification budgets: %w", err)
	}
	_, err = s.pool.Exec(ctx, `DELETE FROM invites WHERE id IN(SELECT id FROM invites WHERE expires_at<clock_timestamp()-interval '7 days'
 ORDER BY expires_at,id LIMIT 100)`)
	if err != nil {
		return fmt.Errorf("purge invitation content: %w", err)
	}
	return nil
}

// RunInvitationWorker maintains content even without a configured provider.
func RunInvitationWorker(ctx context.Context, s *InvitationStore, gateway notifications.Gateway, logger *slog.Logger) {
	pass := func() {
		work, cancel := context.WithTimeout(ctx, 15*time.Second)
		err := s.MaintainInvitations(work)
		cancel()
		if err != nil && ctx.Err() == nil {
			logger.Error("invitation maintenance failed")
		}
		if gateway == nil {
			return
		}
		for i := 0; i < 50; i++ {
			work, cancel := context.WithTimeout(ctx, 12*time.Second)
			done, err := s.DispatchInvitation(work, gateway)
			cancel()
			if err != nil {
				if ctx.Err() == nil {
					logger.Error("invitation dispatch failed")
				}
				return
			}
			if !done {
				return
			}
		}
	}
	pass()
	timer := time.NewTicker(5 * time.Second)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
			pass()
		}
	}
}

// DispatchInvitation locks current authorization through provider acceptance.
// Hiding, revoking, account deletion and session revocation completed before
// dispatch suppress work; a signal already accepted by FCM cannot be recalled.
func (s *InvitationStore) DispatchInvitation(ctx context.Context, gateway notifications.Gateway) (bool, error) {
	if gateway == nil {
		return false, nil
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return false, fmt.Errorf("begin invitation dispatch: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var id, owner, recipient uuid.UUID
	err = tx.QueryRow(ctx, `SELECT o.invite_id,i.created_by,o.recipient_user_id FROM invitation_notification_outbox o
 JOIN invites i ON i.id=o.invite_id WHERE o.state='QUEUED' AND o.next_attempt_at<=clock_timestamp()
 ORDER BY o.next_attempt_at,o.invite_id LIMIT 1`).Scan(&id, &owner, &recipient)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("select invitation dispatch: %w", err)
	}
	// Every multi-account worker acquires account rows in UUID order.
	rows, err := tx.Query(ctx, `SELECT id FROM users WHERE id IN ($1,$2) ORDER BY id FOR UPDATE`, owner, recipient)
	if err != nil {
		return false, fmt.Errorf("lock invitation accounts: %w", err)
	}
	count := 0
	for rows.Next() {
		count++
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return false, fmt.Errorf("iterate invitation account locks: %w", err)
	}
	if count != 2 {
		return true, nil
	}
	var state string
	err = tx.QueryRow(ctx, `SELECT o.state FROM invitation_notification_outbox o JOIN invites i ON i.id=o.invite_id
 WHERE o.invite_id=$1 FOR UPDATE OF i,o`, id).Scan(&state)
	if errors.Is(err, pgx.ErrNoRows) {
		return true, nil
	}
	if err != nil {
		return false, fmt.Errorf("lock invitation dispatch: %w", err)
	}
	if state != "QUEUED" {
		return true, tx.Commit(ctx)
	}
	var valid uuid.UUID
	err = tx.QueryRow(ctx, `SELECT m.id FROM invites i JOIN circles c ON c.id=i.circle_id
 JOIN memberships m ON m.circle_id=c.id AND m.user_id=i.created_by AND m.role='owner'
 JOIN users u ON u.id=$2
 WHERE i.id=$1 AND c.created_by=i.created_by AND i.used_at IS NULL AND i.revoked_at IS NULL AND i.expires_at>clock_timestamp()
 AND i.recipient_email_hash=sha256(convert_to(lower(trim(u.email)),'UTF8'))
 AND NOT EXISTS(SELECT 1 FROM invitation_private_hides h WHERE h.invite_id=i.id AND h.recipient_user_id=$2)
 FOR SHARE OF m,c`, id, recipient).Scan(&valid)
	if errors.Is(err, pgx.ErrNoRows) {
		_, err = tx.Exec(ctx, `UPDATE invitation_notification_outbox SET state='SUPPRESSED' WHERE invite_id=$1`, id)
		if err != nil {
			return false, fmt.Errorf("suppress invitation dispatch: %w", err)
		}
		return true, tx.Commit(ctx)
	}
	if err != nil {
		return false, fmt.Errorf("authorize invitation dispatch: %w", err)
	}
	_, err = tx.Exec(ctx, `INSERT INTO invitation_notification_targets(invite_id,device_id)
 SELECT $1,d.device_id FROM device_push_tokens d JOIN sessions s ON s.id=d.session_id AND s.user_id=d.owner_user_id
 AND s.device_id=d.device_id AND s.revoked_at IS NULL AND s.refresh_expires_at>clock_timestamp()
 WHERE d.owner_user_id=$2 ON CONFLICT DO NOTHING`, id, recipient)
	if err != nil {
		return false, fmt.Errorf("prepare invitation targets: %w", err)
	}
	var device uuid.UUID
	var token string
	err = tx.QueryRow(ctx, `SELECT t.device_id,d.token FROM invitation_notification_targets t
 JOIN device_push_tokens d ON d.device_id=t.device_id AND d.owner_user_id=$2
 JOIN sessions s ON s.id=d.session_id AND s.user_id=d.owner_user_id AND s.device_id=d.device_id
 AND s.revoked_at IS NULL AND s.refresh_expires_at>clock_timestamp()
 WHERE t.invite_id=$1 AND t.accepted_at IS NULL ORDER BY t.device_id LIMIT 1 FOR UPDATE OF t FOR SHARE OF d,s`, id, recipient).Scan(&device, &token)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return false, fmt.Errorf("lock invitation target: %w", err)
	}
	if err == nil {
		sendCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		outcome := gateway.Send(sendCtx, token, notifications.Signal{EventID: id, Kind: "CIRCLE_INVITE"})
		cancel()
		switch {
		case outcome == nil:
			_, err = tx.Exec(ctx, `UPDATE invitation_notification_targets SET accepted_at=clock_timestamp() WHERE invite_id=$1 AND device_id=$2`, id, device)
		case errors.Is(outcome, notifications.ErrInvalidToken):
			_, err = tx.Exec(ctx, `DELETE FROM device_push_tokens WHERE device_id=$1 AND owner_user_id=$2`, device, recipient)
		default:
			err = nil
		}
		if err != nil {
			return false, fmt.Errorf("record invitation target outcome: %w", err)
		}
	}
	_, err = tx.Exec(ctx, `UPDATE invitation_notification_outbox o SET attempts=attempts+1,
 next_attempt_at=clock_timestamp()+make_interval(secs=>LEAST(300,5*(1 << LEAST(attempts,6)))),
 state=CASE WHEN EXISTS(SELECT 1 FROM invitation_notification_targets t WHERE t.invite_id=o.invite_id)
 AND NOT EXISTS(SELECT 1 FROM invitation_notification_targets t WHERE t.invite_id=o.invite_id AND t.accepted_at IS NULL)
 THEN 'ACCEPTED' ELSE 'QUEUED' END WHERE invite_id=$1`, id)
	if err != nil {
		return false, fmt.Errorf("schedule invitation dispatch: %w", err)
	}
	if err = tx.Commit(ctx); err != nil {
		return false, fmt.Errorf("commit invitation dispatch: %w", err)
	}
	return true, nil
}
