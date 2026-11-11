// Package journeys implements voluntary, explicitly addressed journeys.
package journeys

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"slices"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/QuocDuong16/anve/server/internal/audit"
	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/locations"
)

// Stable domain errors are mapped to structured HTTP responses.
var (
	ErrInvalid   = errors.New("invalid journey request")
	ErrNotFound  = errors.New("journey unavailable")
	ErrConflict  = errors.New("journey conflict")
	ErrCancelled = errors.New("journey cancelled")
)

// Principal comes exclusively from the authenticated session.
type Principal struct{ UserID, SessionID, DeviceID uuid.UUID }

// Destination is private journey content, never audit or push content.
type Destination struct {
	Label     string  `json:"label"`
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
}

// CreateRequest is immutable once network admission begins.
type CreateRequest struct {
	JourneyID       uuid.UUID   `json:"journeyId"`
	PreparedAt      time.Time   `json:"preparedAt"`
	DurationMinutes int         `json:"durationMinutes"`
	Destination     Destination `json:"destination"`
	RecipientIDs    []uuid.UUID `json:"recipientIds"`
}

// Recipient is returned only to the journey owner.
type Recipient struct {
	UserID      uuid.UUID `json:"userId"`
	DisplayName string    `json:"displayName"`
}

// Progress is an observed post-start sample, not an arrival estimate.
type Progress struct {
	RecordedAt     time.Time `json:"recordedAt"`
	DistanceMeters float64   `json:"distanceMeters"`
	AccuracyMeters float64   `json:"accuracyMeters"`
	IsMock         bool      `json:"isMock"`
}

// Journey is the currently authorized server state.
type Journey struct {
	ID          uuid.UUID   `json:"id"`
	OwnerID     uuid.UUID   `json:"ownerId"`
	OwnerName   string      `json:"ownerName"`
	State       string      `json:"state"`
	StartedAt   time.Time   `json:"startedAt"`
	ExpiresAt   time.Time   `json:"expiresAt"`
	EndedAt     *time.Time  `json:"endedAt,omitempty"`
	Destination Destination `json:"destination"`
	Recipients  []Recipient `json:"recipients,omitempty"`
	Progress    *Progress   `json:"progress,omitempty"`
}

// Store rechecks authorization inside PostgreSQL, without a location cache.
type Store struct {
	pool          *pgxpool.Pool
	retentionDays int
}

// NewStore applies the stricter server or seven-day journey retention cap.
func NewStore(pool *pgxpool.Pool, retentionDays int) *Store {
	return &Store{pool: pool, retentionDays: max(1, min(7, retentionDays))}
}

func validate(in CreateRequest, owner uuid.UUID) (CreateRequest, [32]byte, error) {
	if owner == uuid.Nil || in.JourneyID == uuid.Nil || in.PreparedAt.IsZero() || in.DurationMinutes < 15 || in.DurationMinutes > 480 ||
		len(in.RecipientIDs) < 1 || len(in.RecipientIDs) > 30 || strings.TrimSpace(in.Destination.Label) != in.Destination.Label ||
		utf8.RuneCountInString(in.Destination.Label) < 1 || utf8.RuneCountInString(in.Destination.Label) > 80 ||
		strings.IndexFunc(in.Destination.Label, unicode.IsControl) >= 0 || math.IsNaN(in.Destination.Latitude) || math.IsInf(in.Destination.Latitude, 0) ||
		math.IsNaN(in.Destination.Longitude) || math.IsInf(in.Destination.Longitude, 0) || math.Abs(in.Destination.Latitude) > 90 || math.Abs(in.Destination.Longitude) > 180 {
		return in, [32]byte{}, ErrInvalid
	}
	in.RecipientIDs = slices.Clone(in.RecipientIDs)
	slices.SortFunc(in.RecipientIDs, func(a, b uuid.UUID) int { return strings.Compare(a.String(), b.String()) })
	for i, id := range in.RecipientIDs {
		if id == uuid.Nil || id == owner || (i > 0 && id == in.RecipientIDs[i-1]) {
			return in, [32]byte{}, ErrInvalid
		}
	}
	in.PreparedAt = in.PreparedAt.UTC()
	data, err := json.Marshal(in)
	if err != nil {
		return in, [32]byte{}, ErrInvalid
	}
	return in, sha256.Sum256(data), nil
}

func lockPrincipal(ctx context.Context, tx pgx.Tx, p Principal) error {
	var id uuid.UUID
	err := tx.QueryRow(ctx, `SELECT id FROM users WHERE id=$1 FOR UPDATE`, p.UserID).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return auth.ErrUnauthenticated
	}
	if err != nil {
		return fmt.Errorf("lock journey owner: %w", err)
	}
	err = tx.QueryRow(ctx, `SELECT s.id FROM sessions s JOIN devices d ON d.id=s.device_id AND d.user_id=s.user_id
 WHERE s.id=$1 AND s.user_id=$2 AND s.device_id=$3 AND s.revoked_at IS NULL AND s.access_expires_at>clock_timestamp()
 FOR SHARE OF s,d`, p.SessionID, p.UserID, p.DeviceID).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return auth.ErrUnauthenticated
	}
	if err != nil {
		return fmt.Errorf("lock journey session: %w", err)
	}
	return nil
}

func lockIdentity(ctx context.Context, tx pgx.Tx, id uuid.UUID) error {
	_, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended('journey:' || $1::text,0))`, id.String())
	if err != nil {
		return fmt.Errorf("lock journey identity: %w", err)
	}
	return nil
}

// Create reserves the ID, selected grants, lifecycle, audit and push work atomically.
func (s *Store) Create(ctx context.Context, p Principal, input CreateRequest) (Journey, bool, error) {
	in, hash, err := validate(input, p.UserID)
	if err != nil {
		return Journey{}, false, err
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Journey{}, false, fmt.Errorf("begin journey create: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err = lockPrincipal(ctx, tx, p); err != nil {
		return Journey{}, false, err
	}
	if err = lockIdentity(ctx, tx, in.JourneyID); err != nil {
		return Journey{}, false, err
	}
	var reservedOwner uuid.UUID
	var cancelled bool
	err = tx.QueryRow(ctx, `SELECT owner_user_id,cancelled FROM journey_requests WHERE id=$1`, in.JourneyID).Scan(&reservedOwner, &cancelled)
	if err == nil {
		if reservedOwner != p.UserID {
			return Journey{}, false, ErrNotFound
		}
		if cancelled {
			return Journey{}, false, ErrCancelled
		}
		var original []byte
		err = tx.QueryRow(ctx, `SELECT request_hash FROM journeys WHERE id=$1`, in.JourneyID).Scan(&original)
		if errors.Is(err, pgx.ErrNoRows) {
			return Journey{}, false, ErrConflict
		}
		if err != nil {
			return Journey{}, false, fmt.Errorf("read journey identity: %w", err)
		}
		if !slices.Equal(original, hash[:]) {
			return Journey{}, false, ErrConflict
		}
		j, err := s.read(ctx, tx, p.UserID, in.JourneyID)
		return j, false, err
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return Journey{}, false, fmt.Errorf("read journey reservation: %w", err)
	}
	var now time.Time
	var consent bool
	if err = tx.QueryRow(ctx, `SELECT clock_timestamp(),location_sharing_enabled FROM users WHERE id=$1`, p.UserID).Scan(&now, &consent); err != nil {
		return Journey{}, false, fmt.Errorf("read journey consent: %w", err)
	}
	if !consent {
		return Journey{}, false, locations.ErrConsentRequired
	}
	if in.PreparedAt.Before(now.Add(-2*time.Minute)) || in.PreparedAt.After(now.Add(2*time.Minute)) {
		return Journey{}, false, ErrInvalid
	}
	if err = s.normalizeOwner(ctx, tx, p.UserID); err != nil {
		return Journey{}, false, err
	}
	var active bool
	if err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM journeys WHERE owner_user_id=$1 AND state='ACTIVE')`, p.UserID).Scan(&active); err != nil {
		return Journey{}, false, fmt.Errorf("check active journey: %w", err)
	}
	if active {
		return Journey{}, false, ErrConflict
	}
	var epoch uuid.UUID
	grants := make([]uuid.UUID, 0, len(in.RecipientIDs))
	for _, recipient := range in.RecipientIDs {
		var grant, grantEpoch uuid.UUID
		err = tx.QueryRow(ctx, `SELECT g.id,g.sharing_epoch_id FROM active_location_audience a
 JOIN location_audience_grants g ON g.id=a.id JOIN location_sharing_epochs e ON e.id=g.sharing_epoch_id
 JOIN circle_membership_periods op ON op.id=g.owner_membership_period_id
 JOIN circle_membership_periods vp ON vp.id=g.viewer_membership_period_id
 JOIN memberships om ON om.id=op.membership_id JOIN memberships vm ON vm.id=vp.membership_id
 WHERE g.owner_user_id=$1 AND g.viewer_user_id=$2 FOR SHARE OF g,e,op,vp,om,vm`, p.UserID, recipient).Scan(&grant, &grantEpoch)
		if errors.Is(err, pgx.ErrNoRows) {
			return Journey{}, false, ErrInvalid
		}
		if err != nil {
			return Journey{}, false, fmt.Errorf("authorize journey selection: %w", err)
		}
		if epoch != uuid.Nil && epoch != grantEpoch {
			return Journey{}, false, ErrInvalid
		}
		epoch = grantEpoch
		grants = append(grants, grant)
	}
	_, err = tx.Exec(ctx, `INSERT INTO journey_requests(id,owner_user_id)VALUES($1,$2)`, in.JourneyID, p.UserID)
	if err != nil {
		return Journey{}, false, fmt.Errorf("reserve journey identity: %w", err)
	}
	_, err = tx.Exec(ctx, `INSERT INTO journeys(id,owner_user_id,sharing_epoch_id,destination_label,destination,started_at,expires_at,request_hash)
 VALUES($1,$2,$3,$4,ST_SetSRID(ST_MakePoint($5,$6),4326)::geography,$7,$8,$9)`, in.JourneyID, p.UserID, epoch, in.Destination.Label, in.Destination.Longitude, in.Destination.Latitude, now, now.Add(time.Duration(in.DurationMinutes)*time.Minute), hash[:])
	if err != nil {
		return Journey{}, false, fmt.Errorf("insert journey: %w", err)
	}
	for i, recipient := range in.RecipientIDs {
		if _, err = tx.Exec(ctx, `INSERT INTO journey_recipients(journey_id,user_id,grant_id)VALUES($1,$2,$3)`, in.JourneyID, recipient, grants[i]); err != nil {
			return Journey{}, false, fmt.Errorf("insert journey selection: %w", err)
		}
	}
	if err = recordTransition(ctx, tx, p.UserID, in.JourneyID, "ACTIVE"); err != nil {
		return Journey{}, false, err
	}
	j, err := s.read(ctx, tx, p.UserID, in.JourneyID)
	if err != nil {
		return Journey{}, false, err
	}
	if err = tx.Commit(ctx); err != nil {
		return Journey{}, false, fmt.Errorf("commit journey create: %w", err)
	}
	return j, true, nil
}

type querier interface {
	QueryRow(context.Context, string, ...any) pgx.Row
	Query(context.Context, string, ...any) (pgx.Rows, error)
}

// read deliberately checks current authorization even for completed journeys.
func (s *Store) read(ctx context.Context, q querier, actor, id uuid.UUID) (Journey, error) {
	var j Journey
	err := q.QueryRow(ctx, `SELECT j.id,j.owner_user_id,u.display_name,j.effective_state,j.started_at,j.expires_at,
 CASE WHEN j.effective_state='EXPIRED' THEN j.expires_at ELSE j.ended_at END,
 j.destination_label,ST_Y(j.destination::geometry),ST_X(j.destination::geometry)
 FROM current_journeys j JOIN users u ON u.id=j.owner_user_id
 WHERE j.id=$1 AND j.started_at>now()-LEAST($3,u.location_retention_days)*interval '1 day'
 AND j.started_at>COALESCE((SELECT cutoff FROM location_retention_watermark WHERE singleton),'-infinity'::timestamptz)
 AND (j.owner_user_id=$2 OR EXISTS(SELECT 1 FROM authorized_journey_recipients r WHERE r.journey_id=j.id AND r.user_id=$2))`, id, actor, s.retentionDays).Scan(&j.ID, &j.OwnerID, &j.OwnerName, &j.State, &j.StartedAt, &j.ExpiresAt, &j.EndedAt, &j.Destination.Label, &j.Destination.Latitude, &j.Destination.Longitude)
	if errors.Is(err, pgx.ErrNoRows) {
		return Journey{}, ErrNotFound
	}
	if err != nil {
		return Journey{}, fmt.Errorf("read authorized journey: %w", err)
	}
	if actor == j.OwnerID {
		rows, err := q.Query(ctx, `SELECT r.user_id,u.display_name FROM authorized_journey_recipients r JOIN users u ON u.id=r.user_id WHERE r.journey_id=$1 ORDER BY u.display_name,r.user_id`, id)
		if err != nil {
			return Journey{}, fmt.Errorf("read journey recipients: %w", err)
		}
		defer rows.Close()
		for rows.Next() {
			var r Recipient
			if err = rows.Scan(&r.UserID, &r.DisplayName); err != nil {
				return Journey{}, fmt.Errorf("scan journey recipient: %w", err)
			}
			j.Recipients = append(j.Recipients, r)
		}
		if err = rows.Err(); err != nil {
			return Journey{}, fmt.Errorf("iterate journey recipients: %w", err)
		}
	}
	if j.State == "ACTIVE" {
		var progress Progress
		err = q.QueryRow(ctx, `SELECT lp.recorded_at,ST_Distance(lp.geom,j.destination),lp.horizontal_accuracy_m,lp.is_mock FROM current_journeys j
 JOIN users u ON u.id=j.owner_user_id AND u.location_sharing_enabled
 JOIN location_points lp ON lp.user_id=j.owner_user_id AND lp.sharing_epoch_id=j.sharing_epoch_id
 WHERE j.id=$1 AND j.effective_state='ACTIVE' AND lp.recorded_at>=j.started_at AND lp.capture_generation=u.location_capture_generation
 AND lp.recorded_at>now()-LEAST($3,u.location_retention_days)*interval '1 day'
 AND lp.recorded_at>COALESCE((SELECT cutoff FROM location_retention_watermark WHERE singleton),'-infinity'::timestamptz)
 AND (j.owner_user_id=$2 OR EXISTS(SELECT 1 FROM authorized_journey_recipients r WHERE r.journey_id=j.id AND r.user_id=$2 AND lp.recorded_at>=r.visible_from AND lp.capture_generation>=r.capture_generation))
 ORDER BY lp.recorded_at DESC,lp.received_at DESC,lp.event_id DESC LIMIT 1`, id, actor, s.retentionDays).Scan(&progress.RecordedAt, &progress.DistanceMeters, &progress.AccuracyMeters, &progress.IsMock)
		if err == nil {
			j.Progress = &progress
		} else if !errors.Is(err, pgx.ErrNoRows) {
			return Journey{}, fmt.Errorf("read journey progress: %w", err)
		}
	}
	return j, nil
}

// Get provides an owner-bound authoritative read for unknown outcomes.
func (s *Store) Get(ctx context.Context, actor, id uuid.UUID) (Journey, error) {
	return s.read(ctx, s.pool, actor, id)
}

// List bounds work and returns only journeys still authorized at read time.
func (s *Store) List(ctx context.Context, actor uuid.UUID) ([]Journey, error) {
	rows, err := s.pool.Query(ctx, `SELECT j.id FROM journeys j JOIN users u ON u.id=j.owner_user_id
 WHERE j.started_at>now()-LEAST($2,u.location_retention_days)*interval '1 day'
 AND (j.owner_user_id=$1 OR EXISTS(SELECT 1 FROM authorized_journey_recipients r WHERE r.journey_id=j.id AND r.user_id=$1))
 ORDER BY j.started_at DESC,j.id DESC LIMIT 50`, actor, s.retentionDays)
	if err != nil {
		return nil, fmt.Errorf("list journey identities: %w", err)
	}
	ids := make([]uuid.UUID, 0, 50)
	for rows.Next() {
		var id uuid.UUID
		if err = rows.Scan(&id); err != nil {
			rows.Close()
			return nil, fmt.Errorf("scan journey identity: %w", err)
		}
		ids = append(ids, id)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, fmt.Errorf("iterate journey identities: %w", err)
	}
	result := make([]Journey, 0, len(ids))
	for _, id := range ids {
		j, err := s.Get(ctx, actor, id)
		if errors.Is(err, ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		result = append(result, j)
	}
	return result, nil
}

func recordTransition(ctx context.Context, tx pgx.Tx, owner, id uuid.UUID, state string) error {
	_, err := tx.Exec(ctx, `UPDATE journey_notification_outbox SET state='SUPPRESSED' WHERE journey_id=$1 AND state='QUEUED' AND kind<>$2`, id, state)
	if err != nil {
		return fmt.Errorf("suppress superseded journey work: %w", err)
	}
	_, err = tx.Exec(ctx, `INSERT INTO journey_notification_outbox(journey_id,recipient_user_id,kind)
 SELECT journey_id,user_id,$2 FROM authorized_journey_recipients WHERE journey_id=$1 ON CONFLICT DO NOTHING`, id, state)
	if err != nil {
		return fmt.Errorf("enqueue journey transition: %w", err)
	}
	return audit.InTx(ctx, tx, audit.Event{ActorUserID: owner, Action: "journey." + strings.ToLower(state), Detail: map[string]any{"journey_id": id.String()}})
}

func (s *Store) normalizeOwner(ctx context.Context, tx pgx.Tx, owner uuid.UUID) error {
	rows, err := tx.Query(ctx, `UPDATE journeys j SET state=c.effective_state,ended_at=CASE WHEN c.effective_state='EXPIRED' THEN j.expires_at ELSE clock_timestamp() END
 FROM current_journeys c WHERE j.id=c.id AND j.owner_user_id=$1 AND j.state='ACTIVE' AND c.effective_state<>'ACTIVE' RETURNING j.id,j.state`, owner)
	if err != nil {
		return fmt.Errorf("normalize journey lifecycle: %w", err)
	}
	type transition struct {
		id    uuid.UUID
		state string
	}
	changed := []transition{}
	for rows.Next() {
		var c transition
		if err = rows.Scan(&c.id, &c.state); err != nil {
			rows.Close()
			return fmt.Errorf("scan journey transition: %w", err)
		}
		changed = append(changed, c)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return fmt.Errorf("iterate journey transitions: %w", err)
	}
	for _, c := range changed {
		if err = recordTransition(ctx, tx, owner, c.id, c.state); err != nil {
			return err
		}
	}
	return nil
}

// Transition is idempotent for the same terminal state. Cancel fences missing IDs.
func (s *Store) Transition(ctx context.Context, p Principal, id uuid.UUID, action string) error {
	state := map[string]string{"arrive": "ARRIVED", "end": "ENDED", "cancel": "CANCELLED"}[action]
	if state == "" || id == uuid.Nil {
		return ErrInvalid
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin journey transition: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err = lockPrincipal(ctx, tx, p); err != nil {
		return err
	}
	if err = lockIdentity(ctx, tx, id); err != nil {
		return err
	}
	var owner uuid.UUID
	var cancelled bool
	err = tx.QueryRow(ctx, `SELECT owner_user_id,cancelled FROM journey_requests WHERE id=$1`, id).Scan(&owner, &cancelled)
	if errors.Is(err, pgx.ErrNoRows) && action == "cancel" {
		_, err = tx.Exec(ctx, `INSERT INTO journey_requests(id,owner_user_id,cancelled)VALUES($1,$2,TRUE)`, id, p.UserID)
		if err != nil {
			return fmt.Errorf("fence journey cancellation: %w", err)
		}
		if err = audit.InTx(ctx, tx, audit.Event{ActorUserID: p.UserID, Action: "journey.cancelled", Detail: map[string]any{"journey_id": id.String()}}); err != nil {
			return err
		}
		return tx.Commit(ctx)
	}
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && owner != p.UserID) {
		return ErrNotFound
	}
	if err != nil {
		return fmt.Errorf("read transition reservation: %w", err)
	}
	if cancelled && action == "cancel" {
		return nil
	}
	if err = s.normalizeOwner(ctx, tx, p.UserID); err != nil {
		return err
	}
	var current string
	err = tx.QueryRow(ctx, `SELECT effective_state FROM current_journeys WHERE id=$1`, id).Scan(&current)
	if errors.Is(err, pgx.ErrNoRows) {
		if action == "cancel" {
			return nil
		}
		return ErrNotFound
	}
	if err != nil {
		return fmt.Errorf("read journey transition: %w", err)
	}
	if current == state {
		return tx.Commit(ctx)
	}
	if current != "ACTIVE" {
		return ErrConflict
	}
	_, err = tx.Exec(ctx, `UPDATE journeys SET state=$2,ended_at=clock_timestamp() WHERE id=$1`, id, state)
	if err != nil {
		return fmt.Errorf("transition journey: %w", err)
	}
	if action == "cancel" {
		if _, err = tx.Exec(ctx, `UPDATE journey_requests SET cancelled=TRUE WHERE id=$1`, id); err != nil {
			return fmt.Errorf("record journey cancellation: %w", err)
		}
	}
	if err = recordTransition(ctx, tx, p.UserID, id, state); err != nil {
		return err
	}
	if err = tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit journey transition: %w", err)
	}
	return nil
}

// RevokeRecipient cannot be undone by a later location re-grant.
func (s *Store) RevokeRecipient(ctx context.Context, p Principal, id, recipient uuid.UUID) error {
	if id == uuid.Nil || recipient == uuid.Nil {
		return ErrInvalid
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin journey recipient revoke: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err = lockPrincipal(ctx, tx, p); err != nil {
		return err
	}
	var owned uuid.UUID
	err = tx.QueryRow(ctx, `SELECT id FROM journeys WHERE id=$1 AND owner_user_id=$2 FOR UPDATE`, id, p.UserID).Scan(&owned)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return fmt.Errorf("lock recipient journey: %w", err)
	}
	res, err := tx.Exec(ctx, `UPDATE journey_recipients SET revoked_at=clock_timestamp() WHERE journey_id=$1 AND user_id=$2 AND revoked_at IS NULL`, id, recipient)
	if err != nil {
		return fmt.Errorf("revoke journey recipient: %w", err)
	}
	if res.RowsAffected() > 0 {
		if _, err = tx.Exec(ctx, `UPDATE journey_notification_outbox SET state='SUPPRESSED' WHERE journey_id=$1 AND recipient_user_id=$2 AND state='QUEUED'`, id, recipient); err != nil {
			return fmt.Errorf("suppress revoked journey work: %w", err)
		}
		if err = audit.InTx(ctx, tx, audit.Event{ActorUserID: p.UserID, TargetUserID: &recipient, Action: "journey.recipient_revoked", Detail: map[string]any{"journey_id": id.String()}}); err != nil {
			return err
		}
	}
	if err = s.normalizeOwner(ctx, tx, p.UserID); err != nil {
		return err
	}
	if err = tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit journey revoke: %w", err)
	}
	return nil
}
