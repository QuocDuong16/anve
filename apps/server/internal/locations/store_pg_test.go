package locations_test

import (
	"context"
	"os"
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
	event := eventWithDevice(uuid.Must(uuid.NewV7()), f.clientID, recordedAt)
	var generation int64
	require.NoError(f.t, f.pool.QueryRow(f.ctx, `SELECT location_capture_generation FROM users WHERE id=$1`, f.user.ID).Scan(&generation))
	event.CaptureGeneration = generation
	return event
}

func eventWithDevice(id uuid.UUID, clientID string, recordedAt time.Time) locations.Event {
	accuracy := 8.0
	speed := 2.5
	bearing := 90.0
	return locations.Event{
		EventID:                  id,
		CaptureGeneration:        1,
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

	// Insert a previously accepted point that has since aged out.
	_, err := f.pool.Exec(f.ctx, `INSERT INTO location_points
        (event_id,user_id,device_id,recorded_at,received_at,latitude,longitude,geom,horizontal_accuracy_m,provider,schema_version)
        VALUES ($1,$2,$3,$4,$4,10,106,ST_SetSRID(ST_MakePoint(106,10),4326)::geography,10,'fused',1)`,
		expired.EventID, f.user.ID, f.session.DeviceID, expired.RecordedAt)
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

// TestPGBatchRollsBackOnDatabaseError proves the atomic contract: if any
// insert in a batch fails, none of the earlier inserts survive. A test-only
// trigger rejects one specific provider so validation passes and the failure
// happens inside the transaction.
func TestPGBatchRollsBackOnDatabaseError(t *testing.T) {
	f := newAuthorizedFixture(t)
	_, err := f.pool.Exec(f.ctx, `
		CREATE FUNCTION anve_test_reject_poison() RETURNS trigger AS $body$
		BEGIN
			IF NEW.provider = 'poison' THEN
				RAISE EXCEPTION 'poison provider rejected for test';
			END IF;
			RETURN NEW;
		END;
		$body$ LANGUAGE plpgsql;
		CREATE TRIGGER anve_test_reject_poison
			BEFORE INSERT ON location_points
			FOR EACH ROW EXECUTE FUNCTION anve_test_reject_poison();`)
	require.NoError(t, err)

	now := time.Now().UTC()
	first := f.event(now)
	second := f.event(now)
	second.Provider = "poison"

	_, err = f.service.Ingest(f.ctx, f.principal(), []locations.Event{first, second})
	require.ErrorIs(t, err, locations.ErrTemporarilyUnavailable)
	assert.Zero(t, countPoints(t, f.pool), "the first insert must roll back with the failed one")
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

// TestPGIngestSerializesWithDeviceRevocation proves the session/device lock
// boundary: a revocation that starts while a batch is in flight waits for
// the batch, then cascades its rows. The batch therefore never commits a
// location for a device that was already revoked, and no orphan row
// survives the delete.
func TestPGIngestSerializesWithDeviceRevocation(t *testing.T) {
	f := newAuthorizedFixture(t)

	locked := make(chan struct{})
	release := make(chan struct{})
	f.store.SetTestAfterDeviceLock(func() {
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
		t.Fatal("ingestion never reached the device lock")
	}

	revoked := make(chan error, 1)
	go func() {
		revoked <- f.authSvc.RevokeDevice(f.ctx, f.user.ID, f.session.DeviceID)
	}()
	waitForLockWait(t, f.pool)

	close(release)
	require.NoError(t, <-ingestErr)
	require.NoError(t, <-revoked)
	f.store.SetTestAfterDeviceLock(nil)

	assert.Zero(t, countPoints(t, f.pool), "revoking the device must cascade its location rows")
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

func TestPGDeletedHistoryCannotReturnViaOfflineUpload(t *testing.T) {
	f := newAuthorizedFixture(t)
	now := time.Now().UTC()
	old := f.event(now.Add(-time.Minute))
	result, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{old})
	require.NoError(t, err)
	require.Len(t, result.Accepted, 1)
	from := now.Add(-2 * time.Minute)
	require.NoError(t, f.service.DeleteHistory(f.ctx, f.user.ID, &from, now))
	assert.Equal(t, 0, countPoints(t, f.pool))
	_, err = f.service.Ingest(f.ctx, f.principal(), []locations.Event{old})
	require.ErrorIs(t, err, locations.ErrHistoryDeleted)
	assert.Equal(t, 0, countPoints(t, f.pool))
	delayed := f.event(now.Add(-30 * time.Second))
	_, err = f.service.Ingest(f.ctx, f.principal(), []locations.Event{delayed})
	require.ErrorIs(t, err, locations.ErrHistoryDeleted)
	old.RecordedAt = now.Add(time.Second)
	_, err = f.service.Ingest(f.ctx, f.principal(), []locations.Event{old})
	require.ErrorIs(t, err, locations.ErrHistoryDeleted)
	assert.Zero(t, countPoints(t, f.pool))
	fresh := f.event(now.Add(time.Second))
	_, err = f.service.Ingest(f.ctx, f.principal(), []locations.Event{fresh})
	require.NoError(t, err)
	assert.Equal(t, 1, countPoints(t, f.pool))
	var auditCount int
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM audit_log WHERE action = 'location.history.deleted' AND actor_user_id = $1`, f.user.ID).Scan(&auditCount))
	assert.Equal(t, 1, auditCount)
}

func TestPGDeletionRejectsUnseenStaleGenerationOutsideDeletedRange(t *testing.T) {
	f := newAuthorizedFixture(t)
	now := time.Now().UTC()
	stale := f.event(now)
	end := now.Add(-time.Second)
	require.NoError(t, f.service.DeleteHistory(f.ctx, f.user.ID, nil, end))
	_, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{stale})
	require.ErrorIs(t, err, locations.ErrCaptureGeneration)
	fresh := f.event(now)
	_, err = f.service.Ingest(f.ctx, f.principal(), []locations.Event{fresh})
	require.NoError(t, err)
	require.Equal(t, 1, countPoints(t, f.pool))
}

func TestPGHistoryDeletionWaitsForInFlightIngest(t *testing.T) {
	f := newAuthorizedFixture(t)
	now := time.Now().UTC()
	event := f.event(now.Add(-time.Second))
	locked := make(chan struct{})
	release := make(chan struct{})
	f.store.SetTestAfterConsentLock(func() { close(locked); <-release })
	ingestDone := make(chan error, 1)
	go func() { _, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{event}); ingestDone <- err }()
	select {
	case <-locked:
	case <-time.After(5 * time.Second):
		t.Fatal("ingest did not lock owner")
	}
	deleteDone := make(chan error, 1)
	go func() { deleteDone <- f.service.DeleteHistory(f.ctx, f.user.ID, nil, now) }()
	waitForLockWait(t, f.pool)
	close(release)
	require.NoError(t, <-ingestDone)
	require.NoError(t, <-deleteDone)
	f.store.SetTestAfterConsentLock(nil)
	assert.Zero(t, countPoints(t, f.pool))
	_, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{event})
	require.ErrorIs(t, err, locations.ErrHistoryDeleted)
}

func TestPGRetentionReductionAndIncreaseCannotRestoreOldBackfill(t *testing.T) {
	f := newAuthorizedFixture(t)
	old := f.event(time.Now().UTC().Add(-48 * time.Hour))
	_, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{old})
	require.NoError(t, err)
	require.NoError(t, f.service.SetRetentionDays(f.ctx, f.user.ID, 1))
	assert.Zero(t, countPoints(t, f.pool))
	_, err = f.service.Ingest(f.ctx, f.principal(), []locations.Event{old})
	require.ErrorIs(t, err, locations.ErrEventTooOld)
	require.NoError(t, f.service.SetRetentionDays(f.ctx, f.user.ID, 30))
	_, err = f.service.Ingest(f.ctx, f.principal(), []locations.Event{old})
	require.ErrorIs(t, err, locations.ErrHistoryDeleted)
	assert.Zero(t, countPoints(t, f.pool))
	days, err := f.service.RetentionDays(f.ctx, f.user.ID)
	require.NoError(t, err)
	assert.Equal(t, 30, days)
}

func TestPGAccountDeletionCascadesHistoryFences(t *testing.T) {
	f := newAuthorizedFixture(t)
	now := time.Now().UTC()
	_, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{f.event(now.Add(-time.Second))})
	require.NoError(t, err)
	require.NoError(t, f.service.DeleteHistory(f.ctx, f.user.ID, nil, now))
	require.NoError(t, f.authSvc.DeleteAccount(f.ctx, f.user.ID))
	var intervals, events int
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT (SELECT count(*) FROM location_history_deletions), (SELECT count(*) FROM location_deleted_events)`).Scan(&intervals, &events))
	assert.Zero(t, intervals)
	assert.Zero(t, events)
}

func TestPGHistoryDeletionMergesOverlappingFences(t *testing.T) {
	f := newAuthorizedFixture(t)
	now := time.Now().UTC()
	first := now.Add(-2 * time.Hour)
	second := now.Add(-time.Hour)
	require.NoError(t, f.service.DeleteHistory(f.ctx, f.user.ID, &first, now.Add(-30*time.Minute)))
	require.NoError(t, f.service.DeleteHistory(f.ctx, f.user.ID, &second, now))
	var count int
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM location_history_deletions WHERE owner_user_id=$1`, f.user.ID).Scan(&count))
	assert.Equal(t, 1, count)
}

func TestPGHistoryMigrationRefusesUnsafeRollback(t *testing.T) {
	f := newAuthorizedFixture(t)
	require.NoError(t, f.service.DeleteHistory(f.ctx, f.user.ID, nil, time.Now().UTC()))
	migration, err := os.ReadFile("../../../../infra/migrations/0007_history_deletions.down.sql")
	require.NoError(t, err)
	_, err = f.pool.Exec(f.ctx, string(migration))
	require.Error(t, err, "rollback must retain deletion fences")
	var count int
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM location_history_deletions WHERE owner_user_id=$1`, f.user.ID).Scan(&count))
	assert.Equal(t, 1, count)
}

func TestPGDeletedHistoryRejectsDelayedUploadsFromAnotherDevice(t *testing.T) {
	f := newAuthorizedFixture(t)
	_, tokens, err := f.authSvc.DevSignIn(f.ctx, "owner@example.com", "Owner", auth.DeviceRegistration{ClientDeviceID: "device-owner-0002"})
	require.NoError(t, err)
	session, _, err := f.authSvc.Authenticate(f.ctx, tokens.AccessToken)
	require.NoError(t, err)
	now := time.Now().UTC()
	from := now.Add(-time.Hour)
	require.NoError(t, f.service.DeleteHistory(f.ctx, f.user.ID, &from, now))
	event := eventWithDevice(uuid.New(), "device-owner-0002", now.Add(-time.Minute))
	_, err = f.service.Ingest(f.ctx, locations.Principal{UserID: f.user.ID, SessionID: session.ID, DeviceID: session.DeviceID}, []locations.Event{event})
	require.ErrorIs(t, err, locations.ErrHistoryDeleted)
	stale := eventWithDevice(uuid.New(), "device-owner-0002", now.Add(time.Second))
	_, err = f.service.Ingest(f.ctx, locations.Principal{UserID: f.user.ID, SessionID: session.ID, DeviceID: session.DeviceID}, []locations.Event{stale})
	require.ErrorIs(t, err, locations.ErrCaptureGeneration)
	assert.Zero(t, countPoints(t, f.pool))
}

func TestPGHistoryDeletionRollsBackWhenAuditFails(t *testing.T) {
	f := newAuthorizedFixture(t)
	now := time.Now().UTC()
	_, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{f.event(now.Add(-time.Second))})
	require.NoError(t, err)
	_, err = f.pool.Exec(f.ctx, `CREATE FUNCTION fail_history_audit() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN IF NEW.action = 'location.history.deleted' THEN RAISE EXCEPTION 'audit failure'; END IF; RETURN NEW; END $$;
 CREATE TRIGGER fail_history_audit BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION fail_history_audit()`)
	require.NoError(t, err)
	require.Error(t, f.service.DeleteHistory(f.ctx, f.user.ID, nil, now))
	assert.Equal(t, 1, countPoints(t, f.pool))
	var fences int
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM location_history_deletions WHERE owner_user_id=$1`, f.user.ID).Scan(&fences))
	assert.Zero(t, fences)
}

func TestPGGlobalRetentionWatermarkPreventsResurrectionAfterIncrease(t *testing.T) {
	f := newAuthorizedFixture(t)
	now := time.Now().UTC()
	old := f.event(now.Add(-48 * time.Hour))
	_, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{old})
	require.NoError(t, err)
	_, err = f.store.PurgeOlderThan(f.ctx, now.Add(-24*time.Hour))
	require.NoError(t, err)
	assert.Zero(t, countPoints(t, f.pool))
	_, err = f.store.PurgeOlderThan(f.ctx, now.Add(-30*24*time.Hour))
	require.NoError(t, err)
	_, err = f.service.Ingest(f.ctx, f.principal(), []locations.Event{old})
	require.ErrorIs(t, err, locations.ErrEventTooOld)
	old.RecordedAt = time.Now().UTC()
	_, err = f.service.Ingest(f.ctx, f.principal(), []locations.Event{old})
	require.ErrorIs(t, err, locations.ErrHistoryDeleted)
	assert.Zero(t, countPoints(t, f.pool))
}

func TestPGRetentionReductionSerializesWithInFlightIngestion(t *testing.T) {
	f := newAuthorizedFixture(t)
	event := f.event(time.Now().UTC().Add(-48 * time.Hour))
	locked := make(chan struct{})
	release := make(chan struct{})
	f.store.SetTestAfterConsentLock(func() { close(locked); <-release })
	ingestDone := make(chan error, 1)
	go func() { _, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{event}); ingestDone <- err }()
	select {
	case <-locked:
	case <-time.After(5 * time.Second):
		t.Fatal("ingestion did not lock owner")
	}
	changed := make(chan error, 1)
	go func() { changed <- f.service.SetRetentionDays(f.ctx, f.user.ID, 1) }()
	waitForLockWait(t, f.pool)
	close(release)
	require.NoError(t, <-ingestDone)
	require.NoError(t, <-changed)
	f.store.SetTestAfterConsentLock(nil)
	assert.Zero(t, countPoints(t, f.pool))
	_, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{event})
	require.ErrorIs(t, err, locations.ErrEventTooOld)
}

func TestPGIngestSeesConcurrentGlobalRetentionWatermark(t *testing.T) {
	f := newAuthorizedFixture(t)
	now := time.Now().UTC()
	event := f.event(now.Add(-48 * time.Hour))
	tx, err := f.pool.Begin(f.ctx)
	require.NoError(t, err)
	defer func() { _ = tx.Rollback(f.ctx) }()
	_, err = tx.Exec(f.ctx, `UPDATE location_retention_watermark SET cutoff=$1 WHERE singleton`, now.Add(-24*time.Hour))
	require.NoError(t, err)
	done := make(chan error, 1)
	go func() { _, err := f.service.Ingest(f.ctx, f.principal(), []locations.Event{event}); done <- err }()
	waitForLockWait(t, f.pool)
	require.NoError(t, tx.Commit(f.ctx))
	require.ErrorIs(t, <-done, locations.ErrEventTooOld)
	assert.Zero(t, countPoints(t, f.pool))
}
