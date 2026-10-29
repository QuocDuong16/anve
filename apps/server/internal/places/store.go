package places

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/QuocDuong16/anve/server/internal/audit"
)

// Store owns place, monitor and event transactions.
type Store struct {
	pool      *pgxpool.Pool
	retention time.Duration
}

// NewStore creates the PostgreSQL implementation.
func NewStore(pool *pgxpool.Pool, retention time.Duration) *Store {
	if retention <= 0 {
		retention = 30 * 24 * time.Hour
	}
	return &Store{pool: pool, retention: retention}
}

// available joins the active membership periods for both the place owner and
// the caller; explicit place access cannot survive leaving and rejoining.
const availableJoins = ` FROM places p
 JOIN circle_membership_periods op ON op.id=p.owner_membership_period_id AND op.ended_at IS NULL
 JOIN memberships om ON om.id=op.membership_id AND om.user_id=p.owner_user_id AND om.circle_id=p.circle_id
 JOIN memberships am ON am.user_id=$1 AND am.circle_id=p.circle_id
 JOIN circle_membership_periods ap ON ap.membership_id=am.id AND ap.ended_at IS NULL `
const availableFilter = ` WHERE (p.owner_user_id=$1 OR EXISTS (SELECT 1 FROM place_access a
   WHERE a.place_id=p.id AND a.member_user_id=$1 AND a.member_period_id=ap.id AND a.revoked_at IS NULL)) `
const available = availableJoins + availableFilter

const placeFields = `p.id,p.owner_user_id,p.circle_id,p.name,p.latitude,p.longitude,p.radius_m,p.created_at,p.updated_at`

func scanPlace(row pgx.Row) (Place, error) {
	var p Place
	err := row.Scan(&p.ID, &p.OwnerID, &p.CircleID, &p.Name, &p.Latitude, &p.Longitude, &p.RadiusM, &p.CreatedAt, &p.UpdatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return Place{}, ErrNotFound
	}
	if err != nil {
		return Place{}, fmt.Errorf("scan place: %w", err)
	}
	p.CreatedAt, p.UpdatedAt = p.CreatedAt.UTC(), p.UpdatedAt.UTC()
	return p, nil
}

func lockOwner(ctx context.Context, tx pgx.Tx, id uuid.UUID) error {
	var found uuid.UUID
	if err := tx.QueryRow(ctx, `SELECT id FROM users WHERE id=$1 FOR UPDATE`, id).Scan(&found); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		return fmt.Errorf("lock place actor: %w", err)
	}
	return nil
}

func placeAudit(ctx context.Context, tx pgx.Tx, actor uuid.UUID, action string, id uuid.UUID, extra map[string]any) error {
	if extra == nil {
		extra = make(map[string]any)
	}
	extra["place_id"] = id.String()
	return audit.InTx(ctx, tx, audit.Event{ActorUserID: actor, Action: action, Detail: extra})
}

// List returns only owned or explicitly shared places in current periods.
func (s *Store) List(ctx context.Context, actor uuid.UUID) ([]Place, error) {
	rows, err := s.pool.Query(ctx, `SELECT `+placeFields+available+` ORDER BY p.created_at,p.id LIMIT 100`, actor)
	if err != nil {
		return nil, fmt.Errorf("list places: %w", err)
	}
	defer rows.Close()
	result := []Place{}
	for rows.Next() {
		p, err := scanPlace(rows)
		if err != nil {
			return nil, err
		}
		result = append(result, p)
	}
	return result, rows.Err()
}

// Create stores an owner-only place without granting monitoring access.
func (s *Store) Create(ctx context.Context, actor uuid.UUID, input Input) (Place, error) {
	if !input.validate() {
		return Place{}, ErrInvalid
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Place{}, fmt.Errorf("begin place creation: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := lockOwner(ctx, tx, actor); err != nil {
		return Place{}, err
	}
	var circle, period uuid.UUID
	err = tx.QueryRow(ctx, `SELECT m.circle_id,cp.id FROM memberships m JOIN circle_membership_periods cp ON cp.membership_id=m.id AND cp.ended_at IS NULL WHERE m.user_id=$1 FOR SHARE OF m`, actor).Scan(&circle, &period)
	if errors.Is(err, pgx.ErrNoRows) {
		return Place{}, ErrNoCircle
	}
	if err != nil {
		return Place{}, fmt.Errorf("find place circle: %w", err)
	}
	var count int
	if err := tx.QueryRow(ctx, `SELECT count(*) FROM places WHERE owner_user_id=$1`, actor).Scan(&count); err != nil {
		return Place{}, fmt.Errorf("count places: %w", err)
	}
	if count >= 50 {
		return Place{}, ErrInvalid
	}
	p, err := scanPlace(tx.QueryRow(ctx, `INSERT INTO places AS p(owner_user_id,circle_id,owner_membership_period_id,name,latitude,longitude,radius_m)
 VALUES ($1,$2,$3,$4,$5,$6,$7) RETURNING `+placeFields, actor, circle, period, input.Name, input.Latitude, input.Longitude, input.RadiusM))
	if err != nil {
		return Place{}, err
	}
	if err := placeAudit(ctx, tx, actor, audit.ActionPlaceCreated, p.ID, nil); err != nil {
		return Place{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return Place{}, fmt.Errorf("commit place creation: %w", err)
	}
	return p, nil
}

// Update changes owned geometry and ends all previous monitoring decisions.
func (s *Store) Update(ctx context.Context, actor, id uuid.UUID, input Input) (Place, error) {
	if !input.validate() {
		return Place{}, ErrInvalid
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Place{}, fmt.Errorf("begin place update: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := lockOwner(ctx, tx, actor); err != nil {
		return Place{}, err
	}
	p, err := scanPlace(tx.QueryRow(ctx, `UPDATE places AS p SET name=$3,latitude=$4,longitude=$5,radius_m=$6,revision=revision+1,updated_at=clock_timestamp()
 WHERE p.id=$2 AND p.owner_user_id=$1 AND EXISTS (SELECT 1 FROM circle_membership_periods op JOIN memberships m ON m.id=op.membership_id WHERE op.id=p.owner_membership_period_id AND op.ended_at IS NULL AND m.user_id=$1 AND m.circle_id=p.circle_id) RETURNING `+placeFields,
		actor, id, input.Name, input.Latitude, input.Longitude, input.RadiusM))
	if err != nil {
		return Place{}, err
	}
	if _, err := tx.Exec(ctx, `UPDATE place_monitors SET revoked_at=clock_timestamp() WHERE place_id=$1 AND revoked_at IS NULL`, id); err != nil {
		return Place{}, fmt.Errorf("invalidate place monitors: %w", err)
	}
	if err := placeAudit(ctx, tx, actor, audit.ActionPlaceUpdated, id, nil); err != nil {
		return Place{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return Place{}, fmt.Errorf("commit place update: %w", err)
	}
	return p, nil
}

// Delete removes a place, its monitors and associated events atomically.
func (s *Store) Delete(ctx context.Context, actor, id uuid.UUID) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin place deletion: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := lockOwner(ctx, tx, actor); err != nil {
		return err
	}
	result, err := tx.Exec(ctx, `DELETE FROM places p WHERE p.id=$2 AND p.owner_user_id=$1 AND EXISTS (SELECT 1 FROM circle_membership_periods op JOIN memberships m ON m.id=op.membership_id WHERE op.id=p.owner_membership_period_id AND op.ended_at IS NULL AND m.user_id=$1 AND m.circle_id=p.circle_id)`, actor, id)
	if err != nil {
		return fmt.Errorf("delete place: %w", err)
	}
	if result.RowsAffected() == 0 {
		return ErrNotFound
	}
	if err := placeAudit(ctx, tx, actor, audit.ActionPlaceDeleted, id, nil); err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit place deletion: %w", err)
	}
	return nil
}

// Access returns current explicit place recipients only to the place owner.
func (s *Store) Access(ctx context.Context, actor, id uuid.UUID) ([]uuid.UUID, error) {
	var members []uuid.UUID
	err := s.pool.QueryRow(ctx, `SELECT ARRAY(SELECT a.member_user_id FROM place_access a JOIN circle_membership_periods mp ON mp.id=a.member_period_id AND mp.ended_at IS NULL JOIN memberships m ON m.id=mp.membership_id AND m.user_id=a.member_user_id AND m.circle_id=p.circle_id WHERE a.place_id=p.id AND a.revoked_at IS NULL ORDER BY a.member_user_id)
 FROM places p JOIN circle_membership_periods op ON op.id=p.owner_membership_period_id AND op.ended_at IS NULL JOIN memberships om ON om.id=op.membership_id AND om.user_id=p.owner_user_id AND om.circle_id=p.circle_id
 WHERE p.id=$2 AND p.owner_user_id=$1`, actor, id).Scan(&members)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("read place access: %w", err)
	}
	if members == nil {
		members = []uuid.UUID{}
	}
	return members, nil
}

// SetAccess shares only the place definition; it never enables monitoring.
func (s *Store) SetAccess(ctx context.Context, actor, placeID, member uuid.UUID, allow bool) error {
	if actor == member || member == uuid.Nil {
		return ErrInvalid
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin place access change: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	locked, err := tx.Query(ctx, `SELECT id FROM users WHERE id IN ($1,$2) ORDER BY id FOR UPDATE`, actor, member)
	if err != nil {
		return fmt.Errorf("lock place participants: %w", err)
	}
	count := 0
	for locked.Next() {
		count++
	}
	err = locked.Err()
	locked.Close()
	if err != nil {
		return fmt.Errorf("read place participants: %w", err)
	}
	if count != 2 {
		return ErrNotFound
	}
	var period uuid.UUID
	err = tx.QueryRow(ctx, `SELECT mp.id FROM places p JOIN circle_membership_periods op ON op.id=p.owner_membership_period_id AND op.ended_at IS NULL
 JOIN memberships om ON om.id=op.membership_id AND om.user_id=p.owner_user_id AND om.circle_id=p.circle_id
 JOIN memberships m ON m.circle_id=p.circle_id AND m.user_id=$3 JOIN circle_membership_periods mp ON mp.membership_id=m.id AND mp.ended_at IS NULL
 WHERE p.id=$2 AND p.owner_user_id=$1 FOR SHARE OF p,m`, actor, placeID, member).Scan(&period)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return fmt.Errorf("check place participant: %w", err)
	}
	if allow {
		var exists bool
		var count int
		if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM place_access WHERE place_id=$1 AND member_user_id=$2 AND revoked_at IS NULL),
 (SELECT count(*) FROM place_access WHERE member_user_id=$2 AND revoked_at IS NULL)`, placeID, member).Scan(&exists, &count); err != nil {
			return fmt.Errorf("count place access: %w", err)
		}
		if exists {
			return tx.Commit(ctx)
		}
		if count >= 50 {
			return ErrInvalid
		}
		_, err = tx.Exec(ctx, `INSERT INTO place_access(place_id,member_user_id,member_period_id) VALUES($1,$2,$3) ON CONFLICT (place_id,member_user_id) WHERE revoked_at IS NULL DO NOTHING`, placeID, member, period)
	} else {
		result, execErr := tx.Exec(ctx, `UPDATE place_access SET revoked_at=clock_timestamp() WHERE place_id=$1 AND member_user_id=$2 AND revoked_at IS NULL`, placeID, member)
		err = execErr
		if err == nil && result.RowsAffected() == 0 {
			return tx.Commit(ctx)
		}
		if err == nil {
			_, err = tx.Exec(ctx, `UPDATE place_monitors SET revoked_at=clock_timestamp() WHERE place_id=$1 AND owner_user_id=$2 AND revoked_at IS NULL`, placeID, member)
		}
	}
	if err != nil {
		return fmt.Errorf("change place access: %w", err)
	}
	if err := placeAudit(ctx, tx, actor, audit.ActionPlaceAccessChanged, placeID, map[string]any{"member_id": member.String(), "enabled": allow}); err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit place access: %w", err)
	}
	return nil
}

// EligibleRecipients requires both the caller's grant and place access.
func (s *Store) EligibleRecipients(ctx context.Context, actor, placeID uuid.UUID) ([]uuid.UUID, error) {
	var recipients []uuid.UUID
	err := s.pool.QueryRow(ctx, `SELECT ARRAY(SELECT g.viewer_user_id FROM active_location_audience g
 WHERE g.owner_user_id=$1 AND g.circle_id=p.circle_id AND (g.viewer_user_id=p.owner_user_id OR EXISTS (
   SELECT 1 FROM place_access a JOIN circle_membership_periods rp ON rp.id=a.member_period_id AND rp.ended_at IS NULL
   JOIN memberships rm ON rm.id=rp.membership_id AND rm.user_id=g.viewer_user_id AND rm.circle_id=p.circle_id
   WHERE a.place_id=p.id AND a.member_user_id=g.viewer_user_id AND a.revoked_at IS NULL))
 ORDER BY g.viewer_user_id LIMIT 100) `+available+` AND p.id=$2`, actor, placeID).Scan(&recipients)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("read eligible place recipients: %w", err)
	}
	if recipients == nil {
		recipients = []uuid.UUID{}
	}
	return recipients, nil
}

// Monitor returns only the caller's own active monitoring configuration.
func (s *Store) Monitor(ctx context.Context, actor, placeID uuid.UUID) (Monitor, error) {
	var id uuid.UUID
	err := s.pool.QueryRow(ctx, `SELECT pm.id `+availableJoins+` JOIN place_monitors pm ON pm.place_id=p.id AND pm.owner_user_id=$1 AND pm.revoked_at IS NULL AND pm.place_revision=p.revision AND pm.owner_period_id=ap.id
 JOIN users u ON u.id=$1 AND u.location_sharing_enabled
 JOIN location_sharing_epochs e ON e.id=pm.sharing_epoch_id AND e.ended_at IS NULL `+availableFilter+` AND p.id=$2`, actor, placeID).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return Monitor{}, ErrNotFound
	}
	if err != nil {
		return Monitor{}, fmt.Errorf("read place monitor: %w", err)
	}
	rows, err := s.pool.Query(ctx, `SELECT r.recipient_user_id FROM place_monitor_recipients r
 JOIN place_monitors pm ON pm.id=r.monitor_id AND pm.revoked_at IS NULL
 JOIN places p ON p.id=pm.place_id AND p.revision=pm.place_revision
 JOIN active_location_audience g ON g.id=r.audience_grant_id AND g.owner_user_id=pm.owner_user_id AND g.viewer_user_id=r.recipient_user_id AND g.sharing_epoch_id=pm.sharing_epoch_id
 LEFT JOIN place_access a ON a.id=r.place_access_id AND a.place_id=p.id AND a.member_user_id=r.recipient_user_id AND a.revoked_at IS NULL
 LEFT JOIN circle_membership_periods ap ON ap.id=a.member_period_id AND ap.ended_at IS NULL
 LEFT JOIN memberships am ON am.id=ap.membership_id AND am.user_id=r.recipient_user_id AND am.circle_id=p.circle_id
 WHERE r.monitor_id=$1 AND (r.recipient_user_id=p.owner_user_id OR am.id IS NOT NULL) ORDER BY r.recipient_user_id`, id)
	if err != nil {
		return Monitor{}, fmt.Errorf("read place recipients: %w", err)
	}
	defer rows.Close()
	result := Monitor{PlaceID: placeID, OwnerID: actor, Active: true, RecipientIDs: []uuid.UUID{}}
	for rows.Next() {
		var recipient uuid.UUID
		if err := rows.Scan(&recipient); err != nil {
			return Monitor{}, fmt.Errorf("scan place recipient: %w", err)
		}
		result.RecipientIDs = append(result.RecipientIDs, recipient)
	}
	return result, rows.Err()
}

// ConfigureMonitor records the subject's consent and explicit recipients.
func (s *Store) ConfigureMonitor(ctx context.Context, actor, placeID uuid.UUID, recipients []uuid.UUID) (Monitor, error) {
	if len(recipients) > 30 {
		return Monitor{}, ErrInvalid
	}
	seen := make(map[uuid.UUID]bool, len(recipients))
	for _, recipient := range recipients {
		if recipient == uuid.Nil || recipient == actor || seen[recipient] {
			return Monitor{}, ErrInvalid
		}
		seen[recipient] = true
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Monitor{}, fmt.Errorf("begin monitor change: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := lockOwner(ctx, tx, actor); err != nil {
		return Monitor{}, err
	}
	var revision int64
	var period, epoch uuid.UUID
	err = tx.QueryRow(ctx, `SELECT p.revision,ap.id,e.id `+availableJoins+` JOIN users u ON u.id=$1 AND u.location_sharing_enabled
 JOIN location_sharing_epochs e ON e.owner_user_id=$1 AND e.circle_id=p.circle_id AND e.owner_membership_period_id=ap.id AND e.ended_at IS NULL
 `+availableFilter+` AND p.id=$2 FOR SHARE OF p,am`, actor, placeID).Scan(&revision, &period, &epoch)
	if errors.Is(err, pgx.ErrNoRows) {
		return Monitor{}, ErrNotFound
	}
	if err != nil {
		return Monitor{}, fmt.Errorf("authorize place monitor: %w", err)
	}
	type recipientGrant struct {
		grant       uuid.UUID
		placeAccess *uuid.UUID
	}
	grants := make([]recipientGrant, 0, len(recipients))
	for _, recipient := range recipients {
		var grant recipientGrant
		err := tx.QueryRow(ctx, `SELECT g.id,pa.id FROM active_location_audience g JOIN places p ON p.id=$4
 LEFT JOIN place_access pa ON pa.place_id=p.id AND pa.member_user_id=$2 AND pa.revoked_at IS NULL
 LEFT JOIN circle_membership_periods rp ON rp.id=pa.member_period_id AND rp.ended_at IS NULL
 LEFT JOIN memberships rm ON rm.id=rp.membership_id AND rm.user_id=$2 AND rm.circle_id=p.circle_id
 WHERE g.owner_user_id=$1 AND g.viewer_user_id=$2 AND g.sharing_epoch_id=$3
 AND ($2=p.owner_user_id OR rm.id IS NOT NULL) LIMIT 1`, actor, recipient, epoch, placeID).Scan(&grant.grant, &grant.placeAccess)
		if errors.Is(err, pgx.ErrNoRows) {
			return Monitor{}, ErrInvalid
		}
		if err != nil {
			return Monitor{}, fmt.Errorf("authorize place recipient: %w", err)
		}
		grants = append(grants, grant)
	}
	var count int
	if err := tx.QueryRow(ctx, `SELECT count(*) FROM place_monitors WHERE owner_user_id=$1 AND revoked_at IS NULL AND place_id<>$2`, actor, placeID).Scan(&count); err != nil {
		return Monitor{}, fmt.Errorf("count monitors: %w", err)
	}
	if count >= 20 {
		return Monitor{}, ErrInvalid
	}
	if _, err := tx.Exec(ctx, `UPDATE place_monitors SET revoked_at=clock_timestamp() WHERE place_id=$1 AND owner_user_id=$2 AND revoked_at IS NULL`, placeID, actor); err != nil {
		return Monitor{}, fmt.Errorf("end previous monitor: %w", err)
	}
	var captureGeneration int64
	if err := tx.QueryRow(ctx, `UPDATE users SET location_capture_generation=location_capture_generation+1 WHERE id=$1 RETURNING location_capture_generation`, actor).Scan(&captureGeneration); err != nil {
		return Monitor{}, fmt.Errorf("advance monitor capture checkpoint: %w", err)
	}
	var monitorID uuid.UUID
	if err := tx.QueryRow(ctx, `INSERT INTO place_monitors(place_id,owner_user_id,owner_period_id,sharing_epoch_id,place_revision,capture_generation) VALUES($1,$2,$3,$4,$5,$6) RETURNING id`, placeID, actor, period, epoch, revision, captureGeneration).Scan(&monitorID); err != nil {
		return Monitor{}, fmt.Errorf("create monitor: %w", err)
	}
	for i, recipient := range recipients {
		if _, err := tx.Exec(ctx, `INSERT INTO place_monitor_recipients(monitor_id,recipient_user_id,audience_grant_id,place_access_id) VALUES($1,$2,$3,$4)`, monitorID, recipient, grants[i].grant, grants[i].placeAccess); err != nil {
			return Monitor{}, fmt.Errorf("insert monitor recipient: %w", err)
		}
	}
	if err := placeAudit(ctx, tx, actor, audit.ActionPlaceMonitorChanged, placeID, map[string]any{"enabled": true, "recipient_count": len(recipients)}); err != nil {
		return Monitor{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return Monitor{}, fmt.Errorf("commit monitor: %w", err)
	}
	if recipients == nil {
		recipients = []uuid.UUID{}
	}
	return Monitor{PlaceID: placeID, OwnerID: actor, RecipientIDs: recipients, Active: true}, nil
}

// StopMonitor revokes only the authenticated subject's authorization.
func (s *Store) StopMonitor(ctx context.Context, actor, placeID uuid.UUID) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin monitor stop: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := lockOwner(ctx, tx, actor); err != nil {
		return err
	}
	var found uuid.UUID
	err = tx.QueryRow(ctx, `SELECT p.id `+available+` AND p.id=$2 FOR SHARE OF p`, actor, placeID).Scan(&found)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return fmt.Errorf("check monitor place: %w", err)
	}
	if _, err := tx.Exec(ctx, `UPDATE place_monitors SET revoked_at=clock_timestamp() WHERE place_id=$1 AND owner_user_id=$2 AND revoked_at IS NULL`, placeID, actor); err != nil {
		return fmt.Errorf("stop monitor: %w", err)
	}
	if err := placeAudit(ctx, tx, actor, audit.ActionPlaceMonitorChanged, placeID, map[string]any{"enabled": false}); err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit monitor stop: %w", err)
	}
	return nil
}

// Events rechecks consent, monitoring, membership, epoch and each recipient's grant.
func (s *Store) Events(ctx context.Context, actor uuid.UUID, limit int) ([]Event, error) {
	if limit < 1 || limit > 100 {
		return nil, ErrInvalid
	}
	rows, err := s.pool.Query(ctx, `SELECT e.id,p.id,p.name,pm.owner_user_id,u.display_name,e.transition,e.recorded_at,e.processed_at
 FROM place_events e JOIN place_monitors pm ON pm.id=e.monitor_id AND pm.revoked_at IS NULL
 JOIN places p ON p.id=pm.place_id AND p.revision=pm.place_revision
 JOIN users u ON u.id=pm.owner_user_id AND u.location_sharing_enabled
 JOIN location_points lp ON lp.id=e.point_id AND lp.sharing_epoch_id=pm.sharing_epoch_id
 JOIN location_points bp ON bp.id=e.baseline_point_id
 JOIN location_points ep ON ep.id=e.evidence_point_id
 JOIN location_sharing_epochs se ON se.id=pm.sharing_epoch_id AND se.ended_at IS NULL
 JOIN circle_membership_periods mp ON mp.id=pm.owner_period_id AND mp.ended_at IS NULL
 JOIN memberships m ON m.id=mp.membership_id AND m.user_id=pm.owner_user_id AND m.circle_id=p.circle_id
 JOIN circle_membership_periods pp ON pp.id=p.owner_membership_period_id AND pp.ended_at IS NULL
 JOIN memberships po ON po.id=pp.membership_id AND po.user_id=p.owner_user_id AND po.circle_id=p.circle_id
 WHERE e.recorded_at >= pm.started_at AND lp.received_at >= pm.started_at AND lp.capture_generation >= pm.capture_generation
 AND LEAST(e.recorded_at,bp.recorded_at,ep.recorded_at) >= GREATEST(now()-u.location_retention_days*interval '1 day', $3::timestamptz,
   COALESCE((SELECT cutoff FROM location_retention_watermark WHERE singleton),'-infinity'::timestamptz))
 AND (pm.owner_user_id=p.owner_user_id OR EXISTS (SELECT 1 FROM place_access pa WHERE pa.place_id=p.id AND pa.member_user_id=pm.owner_user_id AND pa.member_period_id=pm.owner_period_id AND pa.revoked_at IS NULL))
 AND (pm.owner_user_id=$1 OR EXISTS (SELECT 1 FROM place_monitor_recipients r JOIN active_location_audience g ON g.id=r.audience_grant_id
 LEFT JOIN place_access ra ON ra.id=r.place_access_id AND ra.place_id=p.id AND ra.member_user_id=$1 AND ra.revoked_at IS NULL
 LEFT JOIN circle_membership_periods rp ON rp.id=ra.member_period_id AND rp.ended_at IS NULL
 LEFT JOIN memberships rm ON rm.id=rp.membership_id AND rm.user_id=$1 AND rm.circle_id=p.circle_id
 WHERE r.monitor_id=pm.id AND r.recipient_user_id=$1 AND g.owner_user_id=pm.owner_user_id AND g.viewer_user_id=$1 AND g.sharing_epoch_id=pm.sharing_epoch_id
 AND ($1=p.owner_user_id OR rm.id IS NOT NULL)
 AND e.recorded_at >= g.visible_from AND lp.received_at >= g.visible_from AND lp.capture_generation >= g.capture_generation))
 ORDER BY e.recorded_at DESC,e.id DESC LIMIT $2`, actor, limit, time.Now().UTC().Add(-s.retention))
	if err != nil {
		return nil, fmt.Errorf("query place events: %w", err)
	}
	defer rows.Close()
	result := []Event{}
	for rows.Next() {
		var event Event
		if err := rows.Scan(&event.ID, &event.PlaceID, &event.PlaceName, &event.OwnerID, &event.OwnerName, &event.Type, &event.RecordedAt, &event.ProcessedAt); err != nil {
			return nil, fmt.Errorf("scan place event: %w", err)
		}
		event.RecordedAt, event.ProcessedAt = event.RecordedAt.UTC(), event.ProcessedAt.UTC()
		event.Delayed = event.ProcessedAt.Sub(event.RecordedAt) > 5*time.Minute
		event.Confidence = "OBSERVED"
		result = append(result, event)
	}
	return result, rows.Err()
}
