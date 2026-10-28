package locationaccess

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/QuocDuong16/anve/server/internal/audit"
)

// PGStore implements location audience persistence with PostgreSQL transactions.
type PGStore struct{ pool *pgxpool.Pool }

// NewPGStore constructs a PostgreSQL-backed location-access store.
func NewPGStore(pool *pgxpool.Pool) *PGStore { return &PGStore{pool: pool} }

// Audience lists the owner's active grants whose participants remain eligible.
func (s *PGStore) Audience(ctx context.Context, ownerID uuid.UUID) ([]Grant, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT g.id, g.viewer_user_id, v.display_name, g.circle_id,
		        g.generation, g.visible_from, g.created_at
		   FROM location_audience_grants g
		   JOIN users o ON o.id = g.owner_user_id AND o.location_sharing_enabled
		   JOIN users v ON v.id = g.viewer_user_id
		   JOIN location_sharing_epochs e ON e.id = g.sharing_epoch_id AND e.ended_at IS NULL
		   JOIN circle_membership_periods op ON op.id = g.owner_membership_period_id AND op.ended_at IS NULL
		   JOIN memberships om ON om.id = op.membership_id AND om.user_id = g.owner_user_id AND om.circle_id = g.circle_id
		   JOIN circle_membership_periods vp ON vp.id = g.viewer_membership_period_id AND vp.ended_at IS NULL
		   JOIN memberships vm ON vm.id = vp.membership_id AND vm.user_id = g.viewer_user_id AND vm.circle_id = g.circle_id
		  WHERE g.owner_user_id = $1 AND g.revoked_at IS NULL
		  ORDER BY v.display_name, g.created_at`, ownerID)
	if err != nil {
		return nil, fmt.Errorf("list location audience: %w", err)
	}
	defer rows.Close()
	var grants []Grant
	for rows.Next() {
		var grant Grant
		if err := rows.Scan(&grant.ID, &grant.ViewerID, &grant.DisplayName, &grant.CircleID,
			&grant.Generation, &grant.VisibleFrom, &grant.CreatedAt); err != nil {
			return nil, fmt.Errorf("scan location audience grant: %w", err)
		}
		grants = append(grants, grant)
	}
	return grants, rows.Err()
}

// Grant creates an explicit grant after locking and validating both Circle members.
func (s *PGStore) Grant(ctx context.Context, ownerID, viewerID uuid.UUID) (Grant, error) {
	if ownerID == viewerID {
		return Grant{}, ErrNoMembership
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Grant{}, fmt.Errorf("begin audience grant: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	locked, err := tx.Query(ctx,
		`SELECT id FROM users WHERE id IN ($1, $2) ORDER BY id FOR UPDATE`, ownerID, viewerID)
	if err != nil {
		return Grant{}, fmt.Errorf("lock grant participants: %w", err)
	}
	var lockedCount int
	for locked.Next() {
		var id uuid.UUID
		if err := locked.Scan(&id); err != nil {
			locked.Close()
			return Grant{}, fmt.Errorf("scan locked grant participant: %w", err)
		}
		lockedCount++
	}
	if err := locked.Err(); err != nil {
		locked.Close()
		return Grant{}, fmt.Errorf("iterate locked grant participants: %w", err)
	}
	locked.Close()
	if lockedCount != 2 {
		return Grant{}, ErrNotFound
	}

	var sharingEnabled bool
	if err := tx.QueryRow(ctx, `SELECT location_sharing_enabled FROM users WHERE id = $1`, ownerID).Scan(&sharingEnabled); err != nil {
		return Grant{}, fmt.Errorf("read owner consent: %w", err)
	}
	if !sharingEnabled {
		return Grant{}, ErrConsentOff
	}

	var circleID, ownerPeriodID, viewerPeriodID uuid.UUID
	err = tx.QueryRow(ctx,
		`SELECT om.circle_id, op.id, vp.id
		   FROM memberships om
		   JOIN circle_membership_periods op ON op.membership_id = om.id AND op.ended_at IS NULL
		   JOIN memberships vm ON vm.circle_id = om.circle_id AND vm.user_id = $2
		   JOIN circle_membership_periods vp ON vp.membership_id = vm.id AND vp.ended_at IS NULL
		  WHERE om.user_id = $1`, ownerID, viewerID,
	).Scan(&circleID, &ownerPeriodID, &viewerPeriodID)
	if errors.Is(err, pgx.ErrNoRows) {
		return Grant{}, ErrNoMembership
	}
	if err != nil {
		return Grant{}, fmt.Errorf("validate active Circle membership: %w", err)
	}

	var epochID uuid.UUID
	if err := tx.QueryRow(ctx,
		`SELECT id FROM location_sharing_epochs
		  WHERE owner_user_id = $1 AND circle_id = $2
		    AND owner_membership_period_id = $3 AND ended_at IS NULL
		  FOR UPDATE`, ownerID, circleID, ownerPeriodID,
	).Scan(&epochID); errors.Is(err, pgx.ErrNoRows) {
		return Grant{}, ErrConsentOff
	} else if err != nil {
		return Grant{}, fmt.Errorf("lock sharing epoch: %w", err)
	}

	var grant Grant
	err = tx.QueryRow(ctx,
		`SELECT g.id, g.viewer_user_id, v.display_name, g.circle_id,
		        g.generation, g.visible_from, g.created_at
		   FROM location_audience_grants g JOIN users v ON v.id = g.viewer_user_id
		  WHERE g.owner_user_id = $1 AND g.viewer_user_id = $2 AND g.sharing_epoch_id = $3
		    AND g.viewer_membership_period_id = $4 AND g.revoked_at IS NULL`,
		ownerID, viewerID, epochID, viewerPeriodID,
	).Scan(&grant.ID, &grant.ViewerID, &grant.DisplayName, &grant.CircleID,
		&grant.Generation, &grant.VisibleFrom, &grant.CreatedAt)
	if err == nil {
		if err := tx.Commit(ctx); err != nil {
			return Grant{}, fmt.Errorf("commit idempotent audience grant: %w", err)
		}
		return grant, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return Grant{}, fmt.Errorf("check existing audience grant: %w", err)
	}

	var generation int64
	if err := tx.QueryRow(ctx,
		`SELECT COALESCE(MAX(generation), 0) + 1
		   FROM location_audience_grants
		  WHERE owner_user_id = $1 AND viewer_user_id = $2 AND circle_id = $3`,
		ownerID, viewerID, circleID,
	).Scan(&generation); err != nil {
		return Grant{}, fmt.Errorf("allocate audience generation: %w", err)
	}
	err = tx.QueryRow(ctx,
		`INSERT INTO location_audience_grants (
		     owner_user_id, viewer_user_id, circle_id, owner_membership_period_id,
		     viewer_membership_period_id, sharing_epoch_id, generation, visible_from
		 ) VALUES ($1, $2, $3, $4, $5, $6, $7, clock_timestamp())
		 RETURNING id, viewer_user_id, circle_id, generation, visible_from, created_at`,
		ownerID, viewerID, circleID, ownerPeriodID, viewerPeriodID, epochID, generation,
	).Scan(&grant.ID, &grant.ViewerID, &grant.CircleID, &grant.Generation, &grant.VisibleFrom, &grant.CreatedAt)
	if err != nil {
		return Grant{}, fmt.Errorf("insert audience grant: %w", err)
	}
	if err := tx.QueryRow(ctx, `SELECT display_name FROM users WHERE id = $1`, viewerID).Scan(&grant.DisplayName); err != nil {
		return Grant{}, fmt.Errorf("read viewer display name: %w", err)
	}
	if err := audit.InTx(ctx, tx, audit.Event{
		ActorUserID:  ownerID,
		TargetUserID: &viewerID,
		Action:       audit.ActionLocationAudienceGrantCreated,
		Detail:       map[string]any{"grant_id": grant.ID.String(), "circle_id": circleID.String(), "generation": generation},
	}); err != nil {
		return Grant{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return Grant{}, fmt.Errorf("commit audience grant: %w", err)
	}
	return grant, nil
}

// Revoke closes active grants from an owner to a viewer in one transaction.
func (s *PGStore) Revoke(ctx context.Context, ownerID, viewerID uuid.UUID) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin audience revocation: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var owner uuid.UUID
	if err := tx.QueryRow(ctx, `SELECT id FROM users WHERE id = $1 FOR UPDATE`, ownerID).Scan(&owner); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		return fmt.Errorf("lock audience owner: %w", err)
	}
	type revokedGrant struct{ id, circleID uuid.UUID }
	rows, err := tx.Query(ctx,
		`UPDATE location_audience_grants
		    SET revoked_at = clock_timestamp(),
		        visible_until = GREATEST(visible_from, LEAST(COALESCE(visible_until, clock_timestamp()), clock_timestamp()))
		  WHERE owner_user_id = $1 AND viewer_user_id = $2 AND revoked_at IS NULL
		  RETURNING id, circle_id`, ownerID, viewerID)
	if err != nil {
		return fmt.Errorf("revoke audience grant: %w", err)
	}
	var revoked []revokedGrant
	for rows.Next() {
		var grant revokedGrant
		if err := rows.Scan(&grant.id, &grant.circleID); err != nil {
			rows.Close()
			return fmt.Errorf("scan revoked grant: %w", err)
		}
		revoked = append(revoked, grant)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return fmt.Errorf("iterate revoked grants: %w", err)
	}
	rows.Close()
	for _, grant := range revoked {
		if err := audit.InTx(ctx, tx, audit.Event{
			ActorUserID:  ownerID,
			TargetUserID: &viewerID,
			Action:       audit.ActionLocationAudienceGrantRevoked,
			Detail:       map[string]any{"grant_id": grant.id.String(), "circle_id": grant.circleID.String()},
		}); err != nil {
			return err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit audience revocation: %w", err)
	}
	return nil
}

// Latest returns only retained points allowed by current grants and temporal boundaries.
func (s *PGStore) Latest(ctx context.Context, viewerID uuid.UUID, now, retentionCutoff time.Time) ([]VisibleLocation, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT DISTINCT ON (g.owner_user_id)
		        o.id, o.display_name, p.latitude, p.longitude,
		        p.horizontal_accuracy_m, p.recorded_at, p.received_at
		   FROM location_audience_grants g
		   JOIN users o ON o.id = g.owner_user_id AND o.location_sharing_enabled
		   JOIN location_sharing_epochs e ON e.id = g.sharing_epoch_id AND e.ended_at IS NULL
		   JOIN memberships om ON om.user_id = g.owner_user_id AND om.circle_id = g.circle_id
		   JOIN circle_membership_periods op ON op.id = g.owner_membership_period_id
		                                AND op.membership_id = om.id AND op.ended_at IS NULL
		   JOIN memberships vm ON vm.user_id = g.viewer_user_id AND vm.circle_id = g.circle_id
		   JOIN circle_membership_periods vp ON vp.id = g.viewer_membership_period_id
		                                AND vp.membership_id = vm.id AND vp.ended_at IS NULL
		   LEFT JOIN LATERAL (
		       SELECT lp.latitude, lp.longitude, lp.horizontal_accuracy_m, lp.recorded_at, lp.received_at
		         FROM location_points lp
		        WHERE lp.user_id = g.owner_user_id AND lp.sharing_epoch_id = e.id
		          AND lp.recorded_at >= g.visible_from
		          AND lp.received_at >= g.visible_from
		          AND (g.visible_until IS NULL OR lp.recorded_at <= g.visible_until)
		          AND lp.recorded_at >= $3 AND lp.recorded_at <= $2
		        ORDER BY lp.recorded_at DESC, lp.received_at DESC
		        LIMIT 1
		   ) p ON TRUE
		  WHERE g.viewer_user_id = $1 AND g.revoked_at IS NULL
		    AND g.visible_from <= $2
		    AND EXISTS (SELECT 1 FROM users v WHERE v.id = $1)
		  ORDER BY g.owner_user_id, p.recorded_at DESC NULLS LAST`, viewerID, now, retentionCutoff)
	if err != nil {
		return nil, fmt.Errorf("query authorized latest locations: %w", err)
	}
	defer rows.Close()
	var out []VisibleLocation
	for rows.Next() {
		var item VisibleLocation
		var latitude, longitude, accuracy pgtype.Float8
		var recordedAt, receivedAt pgtype.Timestamptz
		if err := rows.Scan(&item.OwnerID, &item.DisplayName, &latitude, &longitude, &accuracy, &recordedAt, &receivedAt); err != nil {
			return nil, fmt.Errorf("scan authorized latest location: %w", err)
		}
		if latitude.Valid {
			item.Latitude = &latitude.Float64
			item.Longitude = &longitude.Float64
			item.AccuracyM = &accuracy.Float64
			recorded := recordedAt.Time.UTC()
			received := receivedAt.Time.UTC()
			item.RecordedAt = &recorded
			item.ReceivedAt = &received
		}
		out = append(out, item)
	}
	return out, rows.Err()
}
