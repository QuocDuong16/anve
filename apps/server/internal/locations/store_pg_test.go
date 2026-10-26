package locations_test

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/circles"
	"github.com/QuocDuong16/anve/server/internal/locations"
	"github.com/QuocDuong16/anve/server/internal/testdb"
)

type locationFixture struct {
	t         *testing.T
	ctx       context.Context
	pool      *pgxpool.Pool
	authSvc   *auth.Service
	circleSvc *circles.Service
	store     *locations.PGStore
	service   *locations.Service
	user      auth.User
	session   auth.Session
	clientID  string
}

// newAuthorizedFixture creates an account that may ingest: consent on,
// one Circle, one active device session.
func newAuthorizedFixture(t *testing.T) *locationFixture {
	t.Helper()
	ctx := context.Background()
	pool := testdb.Scratch(t)
	authSvc := auth.NewService(auth.NewPGStore(pool), nil, true)
	circleSvc := circles.NewService(circles.NewPGStore(pool))

	f := &locationFixture{t: t, ctx: ctx, pool: pool, authSvc: authSvc, circleSvc: circleSvc,
		clientID: "device-owner-0001"}
	user, tokens, err := authSvc.DevSignIn(ctx, "owner@example.com", "Owner",
		auth.DeviceRegistration{ClientDeviceID: f.clientID})
	require.NoError(t, err)
	f.user = user
	session, _, err := authSvc.Authenticate(ctx, tokens.AccessToken)
	require.NoError(t, err)
	f.session = session

	_, _, err = circleSvc.Create(ctx, user.ID, "Gia đình")
	require.NoError(t, err)
	_, err = authSvc.SetLocationSharing(ctx, user.ID, true)
	require.NoError(t, err)

	f.store = locations.NewPGStore(pool)
	f.service = locations.NewService(f.store)
	return f
}

// newAccountWithoutCircle returns a consenting account with no Circle.
func newAccountWithoutCircle(t *testing.T) (*locationFixture, auth.Session, string) {
	t.Helper()
	ctx := context.Background()
	pool := testdb.Scratch(t)
	authSvc := auth.NewService(auth.NewPGStore(pool), nil, true)
	f := &locationFixture{t: t, ctx: ctx, pool: pool, authSvc: authSvc}
	user, tokens, err := authSvc.DevSignIn(ctx, "lonely@example.com", "Lonely",
		auth.DeviceRegistration{ClientDeviceID: "device-lonely-0001"})
	require.NoError(t, err)
	_, err = authSvc.SetLocationSharing(ctx, user.ID, true)
	require.NoError(t, err)
	session, _, err := authSvc.Authenticate(ctx, tokens.AccessToken)
	require.NoError(t, err)
	f.store = locations.NewPGStore(pool)
	f.service = locations.NewService(f.store)
	return f, session, "device-lonely-0001"
}

func (f *locationFixture) principal() locations.Principal {
	return locations.Principal{UserID: f.user.ID, SessionID: f.session.ID, DeviceID: f.session.DeviceID}
}

func (f *locationFixture) event(recordedAt time.Time) locations.Event {
	return eventWithDevice(uuid.Must(uuid.NewV7()), f.clientID, recordedAt)
}

func eventWithDevice(id uuid.UUID, clientID string, recordedAt time.Time) locations.Event {
	accuracy := 8.0
	speed := 2.5
	bearing := 90.0
	return locations.Event{
		EventID:                  id,
		ClientDeviceID:           clientID,
		RecordedAt:               recordedAt,
		Latitude:                 10.7769,
		Longitude:                106.7009,
		HorizontalAccuracyMeters: accuracy,
		SpeedMetersPerSecond:     &speed,
		BearingDegrees:           &bearing,
		Provider:                 "fused",
		SchemaVersion:            1,
	}
}

func countPoints(t *testing.T, pool *pgxpool.Pool) int {
	t.Helper()
	var count int
	require.NoError(t, pool.QueryRow(context.Background(),
		`SELECT count(*) FROM location_points`).Scan(&count))
	return count
}

func TestPGIngestPersistsOriginalTimestampAndGeography(t *testing.T) {
	f := newAuthorizedFixture(t)
	recorded := time.Now().UTC().Add(-10 * time.Minute).Truncate(time.Millisecond)
	event := f.event(recorded)
	event.IsMock = true

	result, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{event})
	require.NoError(t, err)
	require.Len(t, result.Accepted, 1)
	assert.Empty(t, result.Duplicates)

	var (
		lat, lon          float64
		storedRecorded    time.Time
		storedReceived    time.Time
		provider          string
		isMock            bool
		storedAccuracy    float64
		storedUser        uuid.UUID
		storedDevice      uuid.UUID
		storedSchema      int
		storedRecordedAt2 time.Time
	)
	err = f.pool.QueryRow(f.ctx,
		`SELECT ST_Y(geom::geometry), ST_X(geom::geometry),
		        recorded_at, received_at, provider, is_mock,
		        horizontal_accuracy_m, user_id, device_id, schema_version, recorded_at
		   FROM location_points WHERE event_id = $1`, event.EventID,
	).Scan(&lat, &lon, &storedRecorded, &storedReceived, &provider, &isMock,
		&storedAccuracy, &storedUser, &storedDevice, &storedSchema, &storedRecordedAt2)
	require.NoError(t, err)

	assert.InDelta(t, event.Latitude, lat, 1e-9)
	assert.InDelta(t, event.Longitude, lon, 1e-9)
	assert.True(t, recorded.Equal(storedRecorded), "the device capture time must be preserved unchanged")
	assert.True(t, storedRecordedAt2.Equal(storedRecorded))
	assert.WithinDuration(t, time.Now().UTC(), storedReceived, time.Minute)
	assert.Equal(t, "fused", provider)
	assert.True(t, isMock)
	assert.InDelta(t, event.HorizontalAccuracyMeters, storedAccuracy, 1e-9)
	assert.Equal(t, f.user.ID, storedUser)
	assert.Equal(t, f.session.DeviceID, storedDevice)
	assert.Equal(t, 1, storedSchema)
}

func TestPGIngestIsIdempotentPerOwnerEvent(t *testing.T) {
	f := newAuthorizedFixture(t)
	event := f.event(time.Now().UTC().Add(-time.Minute))

	first, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{event})
	require.NoError(t, err)
	require.Len(t, first.Accepted, 1)

	second, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{event})
	require.NoError(t, err)
	assert.Empty(t, second.Accepted)
	assert.Equal(t, []uuid.UUID{event.EventID}, second.Duplicates)
	assert.Equal(t, 1, countPoints(t, f.pool), "a retry must never create a second row")

	// The same owner event uploaded from another installation of the same
	// account is still a duplicate: identity is (owner, eventId).
	_, tokens, err := f.authSvc.DevSignIn(f.ctx, "owner@example.com", "Owner",
		auth.DeviceRegistration{ClientDeviceID: "device-owner-0002"})
	require.NoError(t, err)
	otherSession, _, err := f.authSvc.Authenticate(f.ctx, tokens.AccessToken)
	require.NoError(t, err)
	otherPrincipal := locations.Principal{
		UserID: f.user.ID, SessionID: otherSession.ID, DeviceID: otherSession.DeviceID,
	}
	other := eventWithDevice(event.EventID, "device-owner-0002", event.RecordedAt)
	third, err := f.service.Ingest(f.ctx, otherPrincipal, []locations.Event{other})
	require.NoError(t, err)
	assert.Empty(t, third.Accepted)
	assert.Equal(t, []uuid.UUID{event.EventID}, third.Duplicates)
	assert.Equal(t, 1, countPoints(t, f.pool))
}

func TestPGIngestRefusesConsentOffAndWritesNothing(t *testing.T) {
	f := newAuthorizedFixture(t)
	_, err := f.authSvc.SetLocationSharing(f.ctx, f.user.ID, false)
	require.NoError(t, err)

	_, err = f.service.Ingest(f.ctx, f.principal(), []locations.Event{f.event(time.Now().UTC())})
	require.ErrorIs(t, err, locations.ErrConsentRequired)
	assert.Zero(t, countPoints(t, f.pool))
}

func TestPGIngestRefusesWithoutCircle(t *testing.T) {
	f, session, clientID := newAccountWithoutCircle(t)
	principal := locations.Principal{
		UserID: session.UserID, SessionID: session.ID, DeviceID: session.DeviceID,
	}
	_, err := f.service.Ingest(f.ctx, principal,
		[]locations.Event{eventWithDevice(uuid.Must(uuid.NewV7()), clientID, time.Now().UTC())})
	require.ErrorIs(t, err, locations.ErrCircleRequired)
	assert.Zero(t, countPoints(t, f.pool))
}

func TestPGIngestRefusesForeignDeviceClaim(t *testing.T) {
	f := newAuthorizedFixture(t)
	event := eventWithDevice(uuid.Must(uuid.NewV7()), "device-other-0001", time.Now().UTC())
	_, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{event})
	require.ErrorIs(t, err, locations.ErrDeviceNotAuthorized)
	assert.Zero(t, countPoints(t, f.pool), "an owner cannot upload on behalf of another device")
}

func TestPGIngestRefusesRevokedSession(t *testing.T) {
	f := newAuthorizedFixture(t)
	require.NoError(t, f.authSvc.SignOut(f.ctx, f.session))

	_, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{f.event(time.Now().UTC())})
	require.ErrorIs(t, err, locations.ErrDeviceNotAuthorized)
	assert.Zero(t, countPoints(t, f.pool))
}

func TestPGAccountDeletionCascadesLocationRows(t *testing.T) {
	f := newAuthorizedFixture(t)
	_, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{f.event(time.Now().UTC())})
	require.NoError(t, err)
	require.Equal(t, 1, countPoints(t, f.pool))

	require.NoError(t, f.authSvc.DeleteAccount(f.ctx, f.user.ID))
	assert.Zero(t, countPoints(t, f.pool), "deleting the account must leave no orphan location rows")
}

func TestPGBackfillPreservesEventTimeOrder(t *testing.T) {
	f := newAuthorizedFixture(t)
	now := time.Now().UTC()
	newer := f.event(now.Add(-5 * time.Minute))
	older := f.event(now.Add(-20 * time.Minute))

	// Upload order is newer first; event-time order is older first.
	_, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{newer})
	require.NoError(t, err)
	_, err = f.service.Ingest(f.ctx, f.principal(), []locations.Event{older})
	require.NoError(t, err)

	rows, err := f.pool.Query(f.ctx,
		`SELECT event_id FROM location_points ORDER BY recorded_at, received_at`)
	require.NoError(t, err)
	defer rows.Close()
	var ids []uuid.UUID
	for rows.Next() {
		var id uuid.UUID
		require.NoError(t, rows.Scan(&id))
		ids = append(ids, id)
	}
	require.NoError(t, rows.Err())
	assert.Equal(t, []uuid.UUID{older.EventID, newer.EventID}, ids)
}

func TestPGRetentionPurgesOnlyExpiredPoints(t *testing.T) {
	f := newAuthorizedFixture(t)
	now := time.Now().UTC()
	recent := f.event(now.Add(-time.Hour))
	expired := f.event(now.Add(-31 * 24 * time.Hour))

	// Bypass the service for the expired point: the service would (correctly)
	// refuse it as too old, but retention must also clean rows that age out
	// after they were accepted.
	_, err := f.store.Ingest(f.ctx, locations.IngestInput{
		Principal: f.principal(), Events: []locations.Event{expired}, ReceivedAt: now,
	})
	require.NoError(t, err)
	_, err = f.service.Ingest(f.ctx, f.principal(), []locations.Event{recent})
	require.NoError(t, err)
	require.Equal(t, 2, countPoints(t, f.pool))

	deleted, err := f.store.PurgeOlderThan(f.ctx, now.Add(-30*24*time.Hour))
	require.NoError(t, err)
	assert.Equal(t, int64(1), deleted)
	assert.Equal(t, 1, countPoints(t, f.pool))

	var remaining uuid.UUID
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT event_id FROM location_points`).Scan(&remaining))
	assert.Equal(t, recent.EventID, remaining)
}

func TestPGConcurrentSameEventProducesOneRow(t *testing.T) {
	f := newAuthorizedFixture(t)
	event := f.event(time.Now().UTC().Add(-time.Minute))

	type outcome struct {
		result locations.BatchResult
		err    error
	}
	results := make(chan outcome, 2)
	var wg sync.WaitGroup
	for range 2 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			result, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{event})
			results <- outcome{result: result, err: err}
		}()
	}
	wg.Wait()
	close(results)

	var accepted, duplicates int
	for result := range results {
		require.NoError(t, result.err)
		accepted += len(result.result.Accepted)
		duplicates += len(result.result.Duplicates)
	}
	assert.Equal(t, 1, accepted, "exactly one concurrent insert wins")
	assert.Equal(t, 1, duplicates, "the loser observes the existing row as a duplicate")
	assert.Equal(t, 1, countPoints(t, f.pool))
}

func TestPGConsentRevocationCommittedBeforeBatchIsVisible(t *testing.T) {
	f := newAuthorizedFixture(t)
	_, err := f.authSvc.SetLocationSharing(f.ctx, f.user.ID, false)
	require.NoError(t, err)

	_, err = f.service.Ingest(f.ctx, f.principal(), []locations.Event{f.event(time.Now().UTC())})
	require.ErrorIs(t, err, locations.ErrConsentRequired)
	assert.Zero(t, countPoints(t, f.pool))
}

// TestPGIngestSerializesWithConsentRevocation proves the ordering
// invariant: the ingestion transaction share-locks the account row, so a
// revocation that starts while a batch is in flight cannot commit before
// it; the batch is accepted under the consent it observed, and the very
// next batch sees the committed revocation. The barrier is an observed
// pg_stat_activity lock wait, not a sleep.
func TestPGIngestSerializesWithConsentRevocation(t *testing.T) {
	f := newAuthorizedFixture(t)

	locked := make(chan struct{})
	release := make(chan struct{})
	f.store.SetTestAfterConsentLock(func() {
		close(locked)
		<-release
	})

	ingestErr := make(chan error, 1)
	go func() {
		_, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{f.event(time.Now().UTC())})
		ingestErr <- err
	}()

	select {
	case <-locked:
	case <-time.After(5 * time.Second):
		t.Fatal("ingestion never reached the consent lock")
	}

	revokeDone := make(chan error, 1)
	go func() {
		_, err := f.authSvc.SetLocationSharing(f.ctx, f.user.ID, false)
		revokeDone <- err
	}()
	waitForLockWait(t, f.pool)

	close(release)
	require.NoError(t, <-ingestErr)
	require.NoError(t, <-revokeDone)
	f.store.SetTestAfterConsentLock(nil)

	assert.Equal(t, 1, countPoints(t, f.pool))
	_, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{f.event(time.Now().UTC())})
	require.ErrorIs(t, err, locations.ErrConsentRequired)
	assert.Equal(t, 1, countPoints(t, f.pool))
}

// waitForLockWait blocks until at least one connection on this scratch
// database is waiting for a lock. It is a bounded observation loop, not an
// ordering primitive: the order is enforced by the row locks themselves.
func waitForLockWait(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		var waiting int
		err := pool.QueryRow(context.Background(),
			`SELECT count(*) FROM pg_stat_activity
			  WHERE datname = current_database()
			    AND wait_event_type = 'Lock'`).Scan(&waiting)
		require.NoError(t, err)
		if waiting > 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("no connection ever waited on a lock")
}
