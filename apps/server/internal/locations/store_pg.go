package locations

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// PGStore is the PostgreSQL/PostGIS implementation of Store.
type PGStore struct {
	pool *pgxpool.Pool
	// testAfterConsentLock runs after the account row is share-locked and
	// the consent flag read. It is installed only by export_test.go helpers
	// and is nil in production; it orders the deterministic concurrency
	// tests instead of relying on sleeps.
	testAfterConsentLock func()
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
	err = tx.QueryRow(ctx,
		`SELECT location_sharing_enabled FROM users WHERE id = $1 FOR SHARE`,
		in.Principal.UserID).Scan(&sharingEnabled)
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

	result := BatchResult{ReceivedAt: in.ReceivedAt}
	for i := range in.Events {
		e := in.Events[i]
		var eventID uuid.UUID
		err := tx.QueryRow(ctx,
			`INSERT INTO location_points (
			     event_id, user_id, device_id, recorded_at, received_at,
			     latitude, longitude, geom,
			     horizontal_accuracy_m, altitude_m, speed_mps, bearing_deg,
			     provider, is_mock, schema_version)
			 VALUES ($1, $2, $3, $4, $5, $6, $7,
			         ST_SetSRID(ST_MakePoint($7, $6), 4326)::geography,
			         $8, $9, $10, $11, $12, $13, $14)
			 ON CONFLICT (user_id, event_id) DO NOTHING
			 RETURNING event_id`,
			e.EventID, in.Principal.UserID, deviceID, e.RecordedAt.UTC(), in.ReceivedAt,
			e.Latitude, e.Longitude,
			e.HorizontalAccuracyMeters, e.AltitudeMeters, e.SpeedMetersPerSecond, e.BearingDegrees,
			e.Provider, e.IsMock, e.SchemaVersion,
		).Scan(&eventID)
		switch {
		case err == nil:
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
	ct, err := s.pool.Exec(ctx,
		`DELETE FROM location_points WHERE recorded_at < $1`, cutoff.UTC())
	if err != nil {
		return 0, fmt.Errorf("purge expired locations: %w", err)
	}
	return ct.RowsAffected(), nil
}
