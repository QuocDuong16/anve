// Package sos implements explicitly addressed emergency events without GPS.
package sos

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"sort"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/QuocDuong16/anve/server/internal/audit"
	"github.com/QuocDuong16/anve/server/internal/auth"
)

var (
	// ErrInvalid indicates invalid input or an unavailable recipient.
	ErrInvalid = errors.New("invalid sos request")
	// ErrNotFound avoids exposing events to unauthorized accounts.
	ErrNotFound = errors.New("sos not found")
	// ErrConflict indicates a conflicting retry or lifecycle transition.
	ErrConflict = errors.New("sos conflict")
	// ErrCancelled means this event ID was cancelled and cannot be recreated.
	ErrCancelled = errors.New("sos request cancelled")
)

// Event is the server-confirmed SOS state without location data.
type Event struct {
	ID             uuid.UUID  `json:"id"`
	OwnerID        uuid.UUID  `json:"ownerId"`
	RecordedAt     time.Time  `json:"recordedAt"`
	ReceivedAt     time.Time  `json:"receivedAt"`
	State          string     `json:"state"`
	AcknowledgedAt *time.Time `json:"acknowledgedAt,omitempty"`
}

// RecipientStatus distinguishes provider acceptance from human acknowledgement.
type RecipientStatus struct {
	UserID         uuid.UUID  `json:"userId"`
	PushState      string     `json:"pushState"`
	AcceptedAt     *time.Time `json:"acceptedAt,omitempty"`
	AcknowledgedAt *time.Time `json:"acknowledgedAt,omitempty"`
}

// Store persists SOS events and their explicit recipients.
type Store struct{ pool *pgxpool.Pool }

// Principal is derived from the authenticated session, never from request data.
type Principal struct{ UserID, SessionID, DeviceID uuid.UUID }

func lockPrincipal(ctx context.Context, tx pgx.Tx, p Principal) error {
	var id uuid.UUID
	err := tx.QueryRow(ctx, `SELECT id FROM users WHERE id=$1 FOR UPDATE`, p.UserID).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return auth.ErrUnauthenticated
	}
	if err != nil {
		return fmt.Errorf("lock sos account: %w", err)
	}
	err = tx.QueryRow(ctx, `SELECT s.id FROM sessions s JOIN devices d ON d.id=s.device_id AND d.user_id=s.user_id
 WHERE s.id=$1 AND s.user_id=$2 AND s.device_id=$3 AND s.revoked_at IS NULL AND s.access_expires_at>clock_timestamp()
 FOR SHARE OF s,d`, p.SessionID, p.UserID, p.DeviceID).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return auth.ErrUnauthenticated
	}
	if err != nil {
		return fmt.Errorf("lock sos session: %w", err)
	}
	return nil
}

func lockEventID(ctx context.Context, tx pgx.Tx, id uuid.UUID) error {
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1::text, 0))`, id.String()); err != nil {
		return fmt.Errorf("lock sos event identity: %w", err)
	}
	return nil
}

// NewStore builds an SOS store using the shared database pool.
func NewStore(pool *pgxpool.Pool) *Store { return &Store{pool: pool} }

// Create stores the client-generated event ID and exact recipient set atomically.
func (s *Store) Create(ctx context.Context, principal Principal, id uuid.UUID, recorded time.Time, recipients []uuid.UUID) (Event, bool, error) {
	owner := principal.UserID
	if id == uuid.Nil || owner == uuid.Nil || len(recipients) == 0 || len(recipients) > 30 || recorded.IsZero() || recorded.After(time.Now().Add(2*time.Minute)) || recorded.Before(time.Now().Add(-7*24*time.Hour)) {
		return Event{}, false, ErrInvalid
	}
	ids := append([]uuid.UUID(nil), recipients...)
	sort.Slice(ids, func(i, j int) bool { return ids[i].String() < ids[j].String() })
	material := id.String() + "|" + recorded.UTC().Format(time.RFC3339Nano)
	for i, recipient := range ids {
		if recipient == uuid.Nil || recipient == owner || (i > 0 && recipient == ids[i-1]) {
			return Event{}, false, ErrInvalid
		}
		material += "|" + recipient.String()
	}
	hash := sha256.Sum256([]byte(material))
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Event{}, false, fmt.Errorf("begin sos create: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err = lockPrincipal(ctx, tx, principal); err != nil {
		return Event{}, false, err
	}
	if err = lockEventID(ctx, tx, id); err != nil {
		return Event{}, false, err
	}
	var cancelledOwner uuid.UUID
	err = tx.QueryRow(ctx, `SELECT owner_user_id FROM sos_cancelled_requests WHERE event_id=$1`, id).Scan(&cancelledOwner)
	if err == nil {
		if cancelledOwner == owner {
			return Event{}, false, ErrCancelled
		}
		return Event{}, false, ErrNotFound
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return Event{}, false, fmt.Errorf("read sos cancellation: %w", err)
	}
	var retiredOwner uuid.UUID
	err = tx.QueryRow(ctx, `SELECT owner_user_id FROM sos_retired_requests WHERE event_id=$1`, id).Scan(&retiredOwner)
	if err == nil {
		if retiredOwner == owner {
			return Event{}, false, ErrConflict
		}
		return Event{}, false, ErrNotFound
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return Event{}, false, fmt.Errorf("read retired sos: %w", err)
	}
	var existingHash []byte
	var existingOwner uuid.UUID
	err = tx.QueryRow(ctx, `SELECT owner_user_id,request_hash FROM sos_events WHERE id=$1`, id).Scan(&existingOwner, &existingHash)
	if err == nil {
		if existingOwner != owner {
			return Event{}, false, ErrNotFound
		}
		if string(existingHash) != string(hash[:]) {
			return Event{}, false, ErrConflict
		}
		event, err := readEvent(ctx, tx, owner, id)
		if err != nil {
			return Event{}, false, err
		}
		return event, false, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return Event{}, false, fmt.Errorf("lookup sos: %w", err)
	}
	var circle, ownerPeriod uuid.UUID
	err = tx.QueryRow(ctx, `SELECT m.circle_id,p.id FROM memberships m JOIN circle_membership_periods p ON p.membership_id=m.id AND p.ended_at IS NULL WHERE m.user_id=$1 FOR SHARE OF m,p`, owner).Scan(&circle, &ownerPeriod)
	if errors.Is(err, pgx.ErrNoRows) {
		return Event{}, false, ErrInvalid
	}
	if err != nil {
		return Event{}, false, fmt.Errorf("authorize sos owner: %w", err)
	}
	periods := make([]uuid.UUID, 0, len(ids))
	for _, recipient := range ids {
		var period uuid.UUID
		err = tx.QueryRow(ctx, `SELECT p.id FROM memberships m JOIN circle_membership_periods p ON p.membership_id=m.id AND p.ended_at IS NULL WHERE m.circle_id=$1 AND m.user_id=$2 FOR SHARE OF m,p`, circle, recipient).Scan(&period)
		if errors.Is(err, pgx.ErrNoRows) {
			return Event{}, false, ErrInvalid
		}
		if err != nil {
			return Event{}, false, fmt.Errorf("authorize sos recipient: %w", err)
		}
		periods = append(periods, period)
	}
	_, err = tx.Exec(ctx, `INSERT INTO sos_events(id,owner_user_id,circle_id,owner_period_id,recorded_at,request_hash) VALUES($1,$2,$3,$4,$5,$6)`, id, owner, circle, ownerPeriod, recorded.UTC(), hash[:])
	if err != nil {
		return Event{}, false, fmt.Errorf("insert sos: %w", err)
	}
	for i, recipient := range ids {
		_, err = tx.Exec(ctx, `INSERT INTO sos_recipients(event_id,recipient_user_id,membership_period_id) VALUES($1,$2,$3)`, id, recipient, periods[i])
		if err != nil {
			return Event{}, false, fmt.Errorf("insert sos recipient: %w", err)
		}
		_, err = tx.Exec(ctx, `INSERT INTO sos_notification_outbox(event_id,recipient_user_id) VALUES($1,$2)`, id, recipient)
		if err != nil {
			return Event{}, false, fmt.Errorf("enqueue sos notification: %w", err)
		}
	}
	if err = audit.InTx(ctx, tx, audit.Event{ActorUserID: owner, Action: "sos.created", Detail: map[string]any{"event_id": id.String(), "recipient_count": len(ids)}}); err != nil {
		return Event{}, false, err
	}
	event, err := readEvent(ctx, tx, owner, id)
	if err != nil {
		return Event{}, false, err
	}
	if err = tx.Commit(ctx); err != nil {
		return Event{}, false, fmt.Errorf("commit sos: %w", err)
	}
	return event, true, nil
}

type queryRower interface {
	QueryRow(context.Context, string, ...any) pgx.Row
}

func readEvent(ctx context.Context, db queryRower, actor, id uuid.UUID) (Event, error) {
	var e Event
	err := db.QueryRow(ctx, `SELECT e.id,e.owner_user_id,e.recorded_at,e.received_at,CASE WHEN e.state='ACTIVE' AND e.received_at<=now()-interval '7 days' THEN 'EXPIRED' ELSE e.state END,r.acknowledged_at
 FROM sos_events e JOIN circle_membership_periods op ON op.id=e.owner_period_id AND op.ended_at IS NULL
 JOIN memberships om ON om.id=op.membership_id AND om.user_id=e.owner_user_id AND om.circle_id=e.circle_id
 LEFT JOIN sos_recipients r ON r.event_id=e.id AND r.recipient_user_id=$1 AND r.revoked_at IS NULL
 LEFT JOIN circle_membership_periods rp ON rp.id=r.membership_period_id AND rp.ended_at IS NULL
 LEFT JOIN memberships rm ON rm.id=rp.membership_id AND rm.user_id=$1 AND rm.circle_id=e.circle_id
 WHERE e.id=$2 AND e.received_at>now()-interval '30 days' AND (e.owner_user_id=$1 OR rm.id IS NOT NULL)`, actor, id).Scan(&e.ID, &e.OwnerID, &e.RecordedAt, &e.ReceivedAt, &e.State, &e.AcknowledgedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return Event{}, ErrNotFound
	}
	if err != nil {
		return Event{}, fmt.Errorf("read sos: %w", err)
	}
	return e, nil
}

// Get returns an event only while the caller remains authorized.
func (s *Store) Get(ctx context.Context, actor, id uuid.UUID) (Event, error) {
	return readEvent(ctx, s.pool, actor, id)
}

// Recipients returns sender-only status for currently authorized recipients.
func (s *Store) Recipients(ctx context.Context, owner, id uuid.UUID) ([]RecipientStatus, error) {
	event, err := s.Get(ctx, owner, id)
	if err != nil {
		return nil, err
	}
	if event.OwnerID != owner {
		return nil, ErrNotFound
	}
	rows, err := s.pool.Query(ctx, `SELECT r.recipient_user_id,o.state,o.accepted_at,r.acknowledged_at FROM sos_events e
 JOIN circle_membership_periods op ON op.id=e.owner_period_id AND op.ended_at IS NULL
 JOIN memberships om ON om.id=op.membership_id AND om.user_id=e.owner_user_id AND om.circle_id=e.circle_id
 JOIN sos_recipients r ON r.event_id=e.id AND r.revoked_at IS NULL
 JOIN circle_membership_periods rp ON rp.id=r.membership_period_id AND rp.ended_at IS NULL
 JOIN memberships rm ON rm.id=rp.membership_id AND rm.user_id=r.recipient_user_id AND rm.circle_id=e.circle_id
 JOIN sos_notification_outbox o ON o.event_id=r.event_id AND o.recipient_user_id=r.recipient_user_id
 WHERE e.id=$1 AND e.owner_user_id=$2 AND e.received_at>now()-interval '30 days' ORDER BY r.recipient_user_id LIMIT 30`, id, owner)
	if err != nil {
		return nil, fmt.Errorf("list sos recipient statuses: %w", err)
	}
	defer rows.Close()
	result := []RecipientStatus{}
	for rows.Next() {
		var item RecipientStatus
		if err = rows.Scan(&item.UserID, &item.PushState, &item.AcceptedAt, &item.AcknowledgedAt); err != nil {
			return nil, fmt.Errorf("scan sos recipient status: %w", err)
		}
		result = append(result, item)
	}
	if err = rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate sos recipient statuses: %w", err)
	}
	return result, nil
}

// RevokeRecipient permanently ends this event-specific authorization. A new
// explicit SOS is required to notify the same person again.
func (s *Store) RevokeRecipient(ctx context.Context, principal Principal, id, recipient uuid.UUID) error {
	if id == uuid.Nil || recipient == uuid.Nil || recipient == principal.UserID {
		return ErrInvalid
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin sos recipient revocation: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err = lockPrincipal(ctx, tx, principal); err != nil {
		return err
	}
	if err = lockEventID(ctx, tx, id); err != nil {
		return err
	}
	var locked uuid.UUID
	err = tx.QueryRow(ctx, `SELECT id FROM sos_events WHERE id=$1 FOR UPDATE`, id).Scan(&locked)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return fmt.Errorf("lock sos recipient event: %w", err)
	}
	event, err := readEvent(ctx, tx, principal.UserID, id)
	if err != nil {
		return err
	}
	if event.OwnerID != principal.UserID {
		return ErrNotFound
	}
	result, err := tx.Exec(ctx, `UPDATE sos_recipients SET revoked_at=clock_timestamp() WHERE event_id=$1 AND recipient_user_id=$2 AND revoked_at IS NULL`, id, recipient)
	if err != nil {
		return fmt.Errorf("revoke sos recipient: %w", err)
	}
	if result.RowsAffected() == 0 {
		var exists bool
		if err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM sos_recipients WHERE event_id=$1 AND recipient_user_id=$2)`, id, recipient).Scan(&exists); err != nil {
			return fmt.Errorf("check sos recipient: %w", err)
		}
		if !exists {
			return ErrNotFound
		}
	} else {
		if _, err = tx.Exec(ctx, `UPDATE sos_notification_outbox SET state='SUPPRESSED' WHERE event_id=$1 AND recipient_user_id=$2 AND state='QUEUED'`, id, recipient); err != nil {
			return fmt.Errorf("suppress revoked sos recipient: %w", err)
		}
		if err = audit.InTx(ctx, tx, audit.Event{ActorUserID: principal.UserID, Action: "sos.recipient.revoked", Detail: map[string]any{"event_id": id.String(), "recipient_id": recipient.String()}}); err != nil {
			return err
		}
	}
	if err = tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit sos recipient revocation: %w", err)
	}
	return nil
}

// RegisterPushToken associates a provider identifier with the authenticated device.
// Token material is never returned or included in audit records.
func (s *Store) RegisterPushToken(ctx context.Context, principal Principal, token string) error {
	if len(token) < 1 || len(token) > 4096 {
		return ErrInvalid
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin push registration: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err = lockPrincipal(ctx, tx, principal); err != nil {
		return err
	}
	// A token retained by an expired session must not permanently prevent the
	// same installation from registering after an account switch. Never take a
	// token away from a still-live session merely because it was presented.
	_, err = tx.Exec(ctx, `DELETE FROM device_push_tokens d USING sessions s
 WHERE d.session_id=s.id AND d.token=$1 AND (s.revoked_at IS NOT NULL OR
 (s.refresh_expires_at<=clock_timestamp() AND s.access_expires_at<=clock_timestamp()))`, token)
	if err != nil {
		return fmt.Errorf("remove expired push registration: %w", err)
	}
	result, err := tx.Exec(ctx, `INSERT INTO device_push_tokens(device_id,owner_user_id,session_id,token) VALUES($1,$2,$3,$4)
 ON CONFLICT(device_id) DO UPDATE SET session_id=excluded.session_id,token=excluded.token,updated_at=clock_timestamp()
 WHERE device_push_tokens.owner_user_id=excluded.owner_user_id AND (device_push_tokens.session_id,device_push_tokens.token) IS DISTINCT FROM (excluded.session_id,excluded.token)`, principal.DeviceID, principal.UserID, principal.SessionID, token)
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "23505" {
		return ErrConflict
	}
	if err != nil {
		return fmt.Errorf("register push token: %w", err)
	}
	if result.RowsAffected() > 0 {
		if err = audit.InTx(ctx, tx, audit.Event{ActorUserID: principal.UserID, Action: "notification.device.registered", Detail: map[string]any{"device_id": principal.DeviceID.String()}}); err != nil {
			return err
		}
	}
	if err = tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit push registration: %w", err)
	}
	return nil
}

// PushTokenRegistered reports current registration without returning its token.
func (s *Store) PushTokenRegistered(ctx context.Context, p Principal) (bool, error) {
	var registered bool
	err := s.pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM device_push_tokens d JOIN sessions s ON s.id=d.session_id
 WHERE d.device_id=$1 AND d.owner_user_id=$2 AND d.session_id=$3 AND s.device_id=d.device_id AND s.user_id=d.owner_user_id
 AND s.revoked_at IS NULL AND s.refresh_expires_at>clock_timestamp())`, p.DeviceID, p.UserID, p.SessionID).Scan(&registered)
	if err != nil {
		return false, fmt.Errorf("read push registration: %w", err)
	}
	return registered, nil
}

// UnregisterPushToken removes only the authenticated device's token.
func (s *Store) UnregisterPushToken(ctx context.Context, principal Principal) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin push unregistration: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err = lockPrincipal(ctx, tx, principal); err != nil {
		return err
	}
	result, err := tx.Exec(ctx, `DELETE FROM device_push_tokens WHERE device_id=$1 AND owner_user_id=$2`, principal.DeviceID, principal.UserID)
	if err != nil {
		return fmt.Errorf("unregister push token: %w", err)
	}
	if result.RowsAffected() > 0 {
		if err = audit.InTx(ctx, tx, audit.Event{ActorUserID: principal.UserID, Action: "notification.device.unregistered", Detail: map[string]any{"device_id": principal.DeviceID.String()}}); err != nil {
			return err
		}
	}
	if err = tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit push unregistration: %w", err)
	}
	return nil
}

// Transition serializes changes on the event; duplicate acknowledgements are idempotent.
func (s *Store) Transition(ctx context.Context, principal Principal, id uuid.UUID, action string) (Event, error) {
	actor := principal.UserID
	if id == uuid.Nil || (action != "acknowledge" && action != "cancel" && action != "resolve") {
		return Event{}, ErrInvalid
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Event{}, fmt.Errorf("begin sos transition: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err = lockPrincipal(ctx, tx, principal); err != nil {
		return Event{}, err
	}
	if err = lockEventID(ctx, tx, id); err != nil {
		return Event{}, err
	}
	var locked uuid.UUID
	if err = tx.QueryRow(ctx, `SELECT id FROM sos_events WHERE id=$1 FOR UPDATE`, id).Scan(&locked); errors.Is(err, pgx.ErrNoRows) {
		if action != "cancel" {
			return Event{}, ErrNotFound
		}
		var retiredOwner uuid.UUID
		retiredErr := tx.QueryRow(ctx, `SELECT owner_user_id FROM sos_retired_requests WHERE event_id=$1`, id).Scan(&retiredOwner)
		if retiredErr == nil {
			if retiredOwner == actor {
				return Event{}, ErrConflict
			}
			return Event{}, ErrNotFound
		}
		if !errors.Is(retiredErr, pgx.ErrNoRows) {
			return Event{}, fmt.Errorf("read retired sos: %w", retiredErr)
		}
		result, insertErr := tx.Exec(ctx, `INSERT INTO sos_cancelled_requests(event_id,owner_user_id) VALUES($1,$2) ON CONFLICT DO NOTHING`, id, actor)
		if insertErr != nil {
			return Event{}, fmt.Errorf("record sos cancellation fence: %w", insertErr)
		}
		var owner uuid.UUID
		if err = tx.QueryRow(ctx, `SELECT owner_user_id FROM sos_cancelled_requests WHERE event_id=$1`, id).Scan(&owner); err != nil {
			return Event{}, fmt.Errorf("read sos cancellation fence: %w", err)
		}
		if owner != actor {
			return Event{}, ErrNotFound
		}
		if result.RowsAffected() > 0 {
			if err = audit.InTx(ctx, tx, audit.Event{ActorUserID: actor, Action: "sos.cancel", Detail: map[string]any{"event_id": id.String()}}); err != nil {
				return Event{}, err
			}
		}
		if err = tx.Commit(ctx); err != nil {
			return Event{}, fmt.Errorf("commit sos cancellation fence: %w", err)
		}
		return Event{ID: id, OwnerID: actor, State: "CANCELLED"}, nil
	} else if err != nil {
		return Event{}, fmt.Errorf("lock sos event: %w", err)
	}
	// Membership termination must serialize with acknowledgement or closure.
	err = tx.QueryRow(ctx, `SELECT m.id FROM sos_events e JOIN circle_membership_periods p ON p.id=e.owner_period_id AND p.ended_at IS NULL JOIN memberships m ON m.id=p.membership_id AND m.user_id=e.owner_user_id AND m.circle_id=e.circle_id WHERE e.id=$1 FOR SHARE OF m,p`, id).Scan(&locked)
	if errors.Is(err, pgx.ErrNoRows) {
		return Event{}, ErrNotFound
	}
	if err != nil {
		return Event{}, fmt.Errorf("lock sos owner membership: %w", err)
	}
	if action == "acknowledge" {
		err = tx.QueryRow(ctx, `SELECT m.id FROM sos_events e JOIN sos_recipients r ON r.event_id=e.id AND r.recipient_user_id=$2 AND r.revoked_at IS NULL JOIN circle_membership_periods p ON p.id=r.membership_period_id AND p.ended_at IS NULL JOIN memberships m ON m.id=p.membership_id AND m.user_id=$2 AND m.circle_id=e.circle_id WHERE e.id=$1 FOR SHARE OF m,p`, id, actor).Scan(&locked)
		if errors.Is(err, pgx.ErrNoRows) {
			return Event{}, ErrNotFound
		}
		if err != nil {
			return Event{}, fmt.Errorf("lock sos recipient membership: %w", err)
		}
	}
	event, err := readEvent(ctx, tx, actor, id)
	if err != nil {
		return Event{}, err
	}
	changed := false
	if action == "acknowledge" {
		if actor == event.OwnerID {
			return Event{}, ErrNotFound
		}
		if event.State != "ACTIVE" {
			return Event{}, ErrConflict
		}
		if event.AcknowledgedAt == nil {
			_, err = tx.Exec(ctx, `UPDATE sos_recipients SET acknowledged_at=clock_timestamp() WHERE event_id=$1 AND recipient_user_id=$2`, id, actor)
			changed = true
		}
	} else {
		if actor != event.OwnerID {
			return Event{}, ErrNotFound
		}
		state := "CANCELLED"
		if action == "resolve" {
			state = "RESOLVED"
		}
		if event.State != state && event.State != "ACTIVE" {
			return Event{}, ErrConflict
		}
		if event.State == "ACTIVE" {
			_, err = tx.Exec(ctx, `UPDATE sos_events SET state=$2,transitioned_at=clock_timestamp() WHERE id=$1`, id, state)
			if err == nil {
				_, err = tx.Exec(ctx, `UPDATE sos_notification_outbox SET state='SUPPRESSED' WHERE event_id=$1 AND state='QUEUED'`, id)
			}
			changed = true
		}
		if action == "cancel" && err == nil {
			_, err = tx.Exec(ctx, `INSERT INTO sos_cancelled_requests(event_id,owner_user_id) VALUES($1,$2) ON CONFLICT DO NOTHING`, id, actor)
		}
	}
	if err != nil {
		return Event{}, fmt.Errorf("update sos transition: %w", err)
	}
	if changed {
		if err = audit.InTx(ctx, tx, audit.Event{ActorUserID: actor, Action: "sos." + action, Detail: map[string]any{"event_id": id.String()}}); err != nil {
			return Event{}, err
		}
	}
	event, err = readEvent(ctx, tx, actor, id)
	if err != nil {
		return Event{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return Event{}, fmt.Errorf("commit sos transition: %w", err)
	}
	return event, nil
}

// List is bounded and uses the same current membership predicates as Get.
func (s *Store) List(ctx context.Context, actor uuid.UUID) ([]Event, error) {
	rows, err := s.pool.Query(ctx, `SELECT e.id,e.owner_user_id,e.recorded_at,e.received_at,CASE WHEN e.state='ACTIVE' AND e.received_at<=now()-interval '7 days' THEN 'EXPIRED' ELSE e.state END,r.acknowledged_at
 FROM sos_events e JOIN circle_membership_periods op ON op.id=e.owner_period_id AND op.ended_at IS NULL
 JOIN memberships om ON om.id=op.membership_id AND om.user_id=e.owner_user_id AND om.circle_id=e.circle_id
 LEFT JOIN sos_recipients r ON r.event_id=e.id AND r.recipient_user_id=$1 AND r.revoked_at IS NULL
 LEFT JOIN circle_membership_periods rp ON rp.id=r.membership_period_id AND rp.ended_at IS NULL
 LEFT JOIN memberships rm ON rm.id=rp.membership_id AND rm.user_id=$1 AND rm.circle_id=e.circle_id
 WHERE e.received_at>now()-interval '30 days' AND (e.owner_user_id=$1 OR rm.id IS NOT NULL) ORDER BY e.received_at DESC,e.id DESC LIMIT 100`, actor)
	if err != nil {
		return nil, fmt.Errorf("list sos events: %w", err)
	}
	defer rows.Close()
	result := []Event{}
	for rows.Next() {
		var e Event
		if err = rows.Scan(&e.ID, &e.OwnerID, &e.RecordedAt, &e.ReceivedAt, &e.State, &e.AcknowledgedAt); err != nil {
			return nil, fmt.Errorf("scan sos event: %w", err)
		}
		result = append(result, e)
	}
	if err = rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate sos events: %w", err)
	}
	return result, nil
}
