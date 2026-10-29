package locations

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

// PGStore is the PostgreSQL/PostGIS implementation of Store.
type PGStore struct {
	pool *pgxpool.Pool
	// testAfterConsentLock runs after the account row is share-locked and
	// the consent flag read. It is installed only by export_test.go helpers
	// and is nil in production; it orders the deterministic concurrency
	// tests instead of relying on sleeps.
	testAfterConsentLock func()
	// testAfterDeviceLock runs after the session/device share locks are
	// held, so a concurrency test can order a device revocation precisely.
	testAfterDeviceLock func()
}

// NewPGStore returns a Store backed by the given pool.
func NewPGStore(pool *pgxpool.Pool) *PGStore { return &PGStore{pool: pool} }

// Ingest performs the authorization checks and the inserts in one
// transaction.
//
// The lock order is deliberate and matches account deletion/consent:
//
//  1. users FOR SHARE — serializes with the consent UPDATE and with account
//     deletion's FOR UPDATE. A revocation that committed first is visible;
//     a batch that acquired the lock first commits while consent was on.
//  2. session/device FOR SHARE — a sign-out or device revocation either
//     commits first (and the batch sees no live session) or waits for the
//     batch, so a location row can never outlive the session that was used
//     to authorize it without the revocation having been ordered after.
//  3. membership FOR SHARE — a Circle leave either commits first (no
//     membership, refused) or waits; no orphan location row for a circle
//     the subject is no longer in.
//
// Every row references users/devices with ON DELETE CASCADE, so account
// deletion cannot leave orphan data.
func (s *PGStore) Ingest(ctx context.Context, in IngestInput) (BatchResult, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return BatchResult{}, fmt.Errorf("begin location ingestion: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var sharingEnabled bool
	var retentionDays int
	var historyGeneration int64
	var captureGeneration int64
	var policyNow time.Time
	err = tx.QueryRow(ctx,
		`SELECT location_sharing_enabled, location_retention_days, location_history_generation, location_capture_generation FROM users WHERE id = $1 FOR SHARE`,
		in.Principal.UserID).Scan(&sharingEnabled, &retentionDays, &historyGeneration, &captureGeneration)
	if errors.Is(err, pgx.ErrNoRows) {
		// Deleted between authentication and ingestion: the session no
		// longer authorizes anything.
		return BatchResult{}, ErrDeviceNotAuthorized
	}
	if err != nil {
		return BatchResult{}, fmt.Errorf("lock account for ingestion: %w", err)
	}
	if !sharingEnabled {
		return BatchResult{}, ErrConsentRequired
	}
	if s.testAfterConsentLock != nil {
		s.testAfterConsentLock()
	}

	var globalCutoff *time.Time
	if err := tx.QueryRow(ctx, `SELECT cutoff FROM location_retention_watermark WHERE singleton FOR SHARE`).Scan(&globalCutoff); err != nil {
		return BatchResult{}, fmt.Errorf("lock retention watermark: %w", err)
	}

	var deviceID uuid.UUID
	var clientDeviceID string
	err = tx.QueryRow(ctx,
		`SELECT d.id, d.client_device_id
		   FROM sessions s
		   JOIN devices d ON d.id = s.device_id
		  WHERE s.id = $1
		    AND s.user_id = $2
		    AND d.user_id = $2
		    AND s.revoked_at IS NULL
		    AND s.access_expires_at > now()
		  FOR SHARE OF s, d`,
		in.Principal.SessionID, in.Principal.UserID,
	).Scan(&deviceID, &clientDeviceID)
	if errors.Is(err, pgx.ErrNoRows) {
		return BatchResult{}, ErrDeviceNotAuthorized
	}
	if err != nil {
		return BatchResult{}, fmt.Errorf("lock session and device: %w", err)
	}
	if s.testAfterDeviceLock != nil {
		s.testAfterDeviceLock()
	}
	// The session already fixes the device; the body's clientDeviceId is
	// accepted only when it names that same installation. This stops an
	// owner from uploading as a member and stops a stale installation id
	// from writing under the current session.
	for i := range in.Events {
		if in.Events[i].ClientDeviceID != clientDeviceID {
			return BatchResult{}, fmt.Errorf("%w: the batch names a device outside this session",
				ErrDeviceNotAuthorized)
		}
	}

	var circleID uuid.UUID
	err = tx.QueryRow(ctx,
		`SELECT circle_id FROM memberships WHERE user_id = $1 FOR SHARE`,
		in.Principal.UserID).Scan(&circleID)
	if errors.Is(err, pgx.ErrNoRows) {
		return BatchResult{}, ErrCircleRequired
	}
	if err != nil {
		return BatchResult{}, fmt.Errorf("lock circle membership: %w", err)
	}
	var sharingEpochID uuid.UUID
	var sharingEpochStartedAt time.Time
	epochErr := tx.QueryRow(ctx,
		`SELECT id, started_at FROM location_sharing_epochs
		  WHERE owner_user_id = $1 AND circle_id = $2 AND ended_at IS NULL
		  FOR SHARE`,
		in.Principal.UserID, circleID,
	).Scan(&sharingEpochID, &sharingEpochStartedAt)
	if epochErr != nil && !errors.Is(epochErr, pgx.ErrNoRows) {
		return BatchResult{}, fmt.Errorf("read active sharing epoch: %w", epochErr)
	}

	if err := tx.QueryRow(ctx, `SELECT clock_timestamp()`).Scan(&policyNow); err != nil {
		return BatchResult{}, fmt.Errorf("read ingestion policy time: %w", err)
	}

	result := BatchResult{ReceivedAt: in.ReceivedAt}
	for i := range in.Events {
		e := in.Events[i]
		if e.RecordedAt.Before(policyNow.Add(-time.Duration(retentionDays)*24*time.Hour)) || (globalCutoff != nil && e.RecordedAt.Before(*globalCutoff)) {
			return BatchResult{}, ErrEventTooOld
		}
		var deleted bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM location_history_deletions
            WHERE owner_user_id = $1 AND deleted_range @> $2::timestamptz)
                OR EXISTS (SELECT 1 FROM location_deleted_events WHERE owner_user_id = $1 AND event_id = $3)`, in.Principal.UserID, e.RecordedAt.UTC(), e.EventID).Scan(&deleted); err != nil {
			return BatchResult{}, fmt.Errorf("check deleted history: %w", err)
		}
		if deleted {
			return BatchResult{}, ErrHistoryDeleted
		}
		if e.CaptureGeneration != captureGeneration {
			return BatchResult{}, ErrCaptureGeneration
		}
		var eventID uuid.UUID
		var pointID int64
		var pointEpochID *uuid.UUID
		if epochErr == nil && !e.RecordedAt.UTC().Before(sharingEpochStartedAt) {
			pointEpochID = &sharingEpochID
		}
		err := tx.QueryRow(ctx,
			`INSERT INTO location_points (
			     event_id, user_id, device_id, recorded_at, received_at,
			     latitude, longitude, geom,
			     horizontal_accuracy_m, altitude_m, speed_mps, bearing_deg,
			     provider, is_mock, schema_version, sharing_epoch_id, history_generation, capture_generation)
			 VALUES ($1, $2, $3, $4, $5, $6, $7,
			         ST_SetSRID(ST_MakePoint($7, $6), 4326)::geography,
			         $8, $9, $10, $11, $12, $13, $14, $15, $16, $17)
			 ON CONFLICT (user_id, event_id) DO NOTHING
			 RETURNING event_id, id`,
			e.EventID, in.Principal.UserID, deviceID, e.RecordedAt.UTC(), in.ReceivedAt,
			e.Latitude, e.Longitude,
			e.HorizontalAccuracyMeters, e.AltitudeMeters, e.SpeedMetersPerSecond, e.BearingDegrees,
			e.Provider, e.IsMock, e.SchemaVersion, pointEpochID, historyGeneration, captureGeneration,
		).Scan(&eventID, &pointID)
		switch {
		case err == nil:
			if _, err := tx.Exec(ctx, `INSERT INTO place_event_queue(point_id,owner_user_id,recorded_at)
				SELECT $1,$2,$6 WHERE EXISTS (SELECT 1 FROM place_monitors pm
                  WHERE pm.owner_user_id=$2 AND pm.revoked_at IS NULL AND pm.sharing_epoch_id=$3
					AND pm.capture_generation <= $4 AND pm.started_at <= $5)`, pointID, in.Principal.UserID, pointEpochID, captureGeneration, in.ReceivedAt, e.RecordedAt.UTC()); err != nil {
				return BatchResult{}, fmt.Errorf("queue place evaluation: %w", err)
			}
			result.Accepted = append(result.Accepted, eventID)
		case errors.Is(err, pgx.ErrNoRows):
			// Conflict: this owner already stored the event. Idempotent
			// retry — report it, do not create a second row.
			result.Duplicates = append(result.Duplicates, e.EventID)
		default:
			return BatchResult{}, fmt.Errorf("insert location point: %w", err)
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return BatchResult{}, fmt.Errorf("commit location ingestion: %w", err)
	}
	return result, nil
}

// PurgeOlderThan deletes points captured before cutoff. Retention is based
// on the capture time, not the upload time: a point that is old at arrival
// is refused by validation, and one that ages out is removed even if it was
// uploaded recently.
func (s *PGStore) PurgeOlderThan(ctx context.Context, cutoff time.Time) (int64, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return 0, fmt.Errorf("begin retention purge: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	// Lock candidate owners before the watermark and point rows, matching
	// ingestion and deletion. The event-ID fence has a user foreign key;
	// taking point locks first can deadlock with an account/history deletion.
	rows, err := tx.Query(ctx, `SELECT id FROM users WHERE id IN (
        SELECT user_id FROM (
          SELECT lp.user_id FROM location_points lp JOIN users u ON u.id=lp.user_id
          WHERE lp.recorded_at < GREATEST($1, COALESCE((SELECT cutoff FROM location_retention_watermark WHERE singleton), $1))
             OR lp.recorded_at < now() - u.location_retention_days * interval '1 day'
          ORDER BY lp.recorded_at, lp.id LIMIT $2
        ) candidates
    ) ORDER BY id FOR SHARE`, cutoff.UTC(), RetentionBatchSize)
	if err != nil {
		return 0, fmt.Errorf("lock retention owners: %w", err)
	}
	var owners []uuid.UUID
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return 0, fmt.Errorf("scan retention owner: %w", err)
		}
		owners = append(owners, id)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return 0, fmt.Errorf("iterate retention owners: %w", err)
	}
	rows.Close()
	var effective time.Time
	if err := tx.QueryRow(ctx, `UPDATE location_retention_watermark SET cutoff = GREATEST(cutoff, $1) WHERE singleton RETURNING cutoff`, cutoff.UTC()).Scan(&effective); err != nil {
		return 0, fmt.Errorf("advance retention watermark: %w", err)
	}
	ct, err := tx.Exec(ctx, `WITH expired AS MATERIALIZED (
        SELECT lp.id, lp.user_id, lp.event_id FROM location_points lp JOIN users u ON u.id=lp.user_id
        WHERE lp.user_id = ANY($3::uuid[]) AND (lp.recorded_at < $1 OR lp.recorded_at < now() - u.location_retention_days * interval '1 day')
        ORDER BY lp.recorded_at, lp.id LIMIT $2 FOR UPDATE OF lp SKIP LOCKED
    ), fenced AS (
        INSERT INTO location_deleted_events (owner_user_id, event_id)
        SELECT user_id, event_id FROM expired ON CONFLICT DO NOTHING RETURNING event_id
    )
    DELETE FROM location_points WHERE id IN (SELECT id FROM expired)`, effective, RetentionBatchSize, owners)
	if err != nil {
		return 0, fmt.Errorf("purge expired locations: %w", err)
	}
	if err := tx.Commit(ctx); err != nil {
		return 0, fmt.Errorf("commit retention purge: %w", err)
	}
	return ct.RowsAffected(), nil
}

// DeleteHistory serializes with ingestion on the owner row and atomically
// excludes delayed uploads before deleting stored points.
func (s *PGStore) DeleteHistory(ctx context.Context, owner uuid.UUID, from *time.Time, to time.Time) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin history deletion: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var locked uuid.UUID
	if err := tx.QueryRow(ctx, `SELECT id FROM users WHERE id = $1 FOR UPDATE`, owner).Scan(&locked); err != nil {
		return fmt.Errorf("lock history owner: %w", err)
	}
	if _, err := tx.Exec(ctx, `UPDATE users SET location_capture_generation = location_capture_generation + 1 WHERE id = $1`, owner); err != nil {
		return fmt.Errorf("advance capture generation: %w", err)
	}
	_, err = tx.Exec(ctx, `INSERT INTO location_history_deletions (owner_user_id, deleted_range) VALUES ($1, tstzrange($2, $3, '[)'))`, owner, from, to.UTC())
	if err != nil {
		return fmt.Errorf("record history deletion: %w", err)
	}
	if err := compactHistoryDeletions(ctx, tx, owner); err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `INSERT INTO location_deleted_events (owner_user_id, event_id)
        SELECT user_id, event_id FROM location_points
        WHERE user_id = $1 AND ($2::timestamptz IS NULL OR recorded_at >= $2) AND recorded_at < $3
        ON CONFLICT DO NOTHING`, owner, from, to.UTC())
	if err != nil {
		return fmt.Errorf("record deleted event identities: %w", err)
	}
	result, err := tx.Exec(ctx, `DELETE FROM location_points WHERE user_id = $1 AND ($2::timestamptz IS NULL OR recorded_at >= $2) AND recorded_at < $3`, owner, from, to.UTC())
	if err != nil {
		return fmt.Errorf("delete history: %w", err)
	}
	if err := audit.InTx(ctx, tx, audit.Event{ActorUserID: owner, TargetUserID: &owner, Action: audit.ActionLocationHistoryDeleted, Detail: map[string]any{"from": from, "to": to.UTC(), "deleted_count": result.RowsAffected()}}); err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit history deletion: %w", err)
	}
	return nil
}

// RetentionDays returns the owner setting, independently of sharing consent.
func (s *PGStore) RetentionDays(ctx context.Context, owner uuid.UUID) (int, error) {
	var days int
	if err := s.pool.QueryRow(ctx, `SELECT location_retention_days FROM users WHERE id = $1`, owner).Scan(&days); err != nil {
		return 0, fmt.Errorf("read history retention: %w", err)
	}
	return days, nil
}

// CaptureGeneration returns the account checkpoint without exposing location data.
func (s *PGStore) CaptureGeneration(ctx context.Context, owner uuid.UUID) (int64, error) {
	var generation int64
	if err := s.pool.QueryRow(ctx, `SELECT location_capture_generation FROM users WHERE id = $1`, owner).Scan(&generation); err != nil {
		return 0, fmt.Errorf("read capture generation: %w", err)
	}
	return generation, nil
}

// SetRetentionDays records a permanent cutoff when changing retention. The
// owner lock orders this with uploads and avoids restoring expired history.
func (s *PGStore) SetRetentionDays(ctx context.Context, owner uuid.UUID, days int) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin retention update: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var old int
	var now time.Time
	if err := tx.QueryRow(ctx, `SELECT location_retention_days FROM users WHERE id=$1 FOR UPDATE`, owner).Scan(&old); err != nil {
		return fmt.Errorf("lock retention owner: %w", err)
	}
	if old == days {
		return tx.Commit(ctx)
	}
	if err := tx.QueryRow(ctx, `SELECT clock_timestamp()`).Scan(&now); err != nil {
		return fmt.Errorf("read retention policy time: %w", err)
	}
	cutoffDays := min(old, days)
	cutoff := now.Add(-time.Duration(cutoffDays) * 24 * time.Hour)
	if _, err := tx.Exec(ctx, `INSERT INTO location_history_deletions (owner_user_id, deleted_range) VALUES ($1, tstzrange(NULL, $2, '[)'))`, owner, cutoff); err != nil {
		return fmt.Errorf("record retention cutoff: %w", err)
	}
	if err := compactHistoryDeletions(ctx, tx, owner); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO location_deleted_events (owner_user_id,event_id) SELECT user_id,event_id FROM location_points WHERE user_id=$1 AND recorded_at < $2 ON CONFLICT DO NOTHING`, owner, cutoff); err != nil {
		return fmt.Errorf("record expired identities: %w", err)
	}
	deleted, err := tx.Exec(ctx, `DELETE FROM location_points WHERE user_id=$1 AND recorded_at < $2`, owner, cutoff)
	if err != nil {
		return fmt.Errorf("purge shortened history: %w", err)
	}
	if _, err := tx.Exec(ctx, `UPDATE users SET location_retention_days=$2, location_capture_generation=location_capture_generation+1 WHERE id=$1`, owner, days); err != nil {
		return fmt.Errorf("update retention: %w", err)
	}
	if err := audit.InTx(ctx, tx, audit.Event{ActorUserID: owner, TargetUserID: &owner, Action: audit.ActionLocationRetentionChanged, Detail: map[string]any{"old_days": old, "days": days, "cutoff": cutoff, "deleted_count": deleted.RowsAffected()}}); err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit retention update: %w", err)
	}
	return nil
}

// compactHistoryDeletions merges overlapping and adjacent fences while the
// caller holds the owner lock, so repeated requests do not accumulate ranges.
func compactHistoryDeletions(ctx context.Context, tx pgx.Tx, owner uuid.UUID) error {
	_, err := tx.Exec(ctx, `WITH merged AS MATERIALIZED (
      SELECT range_agg(deleted_range) AS ranges FROM location_history_deletions WHERE owner_user_id=$1
    ), removed AS (
      DELETE FROM location_history_deletions WHERE owner_user_id=$1 RETURNING id
    )
    INSERT INTO location_history_deletions (owner_user_id, deleted_range)
      SELECT $1, r FROM merged CROSS JOIN LATERAL unnest(merged.ranges) AS r`, owner)
	if err != nil {
		return fmt.Errorf("compact history deletion fences: %w", err)
	}
	return nil
}
