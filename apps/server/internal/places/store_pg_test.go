package places

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/circles"
	"github.com/QuocDuong16/anve/server/internal/locationaccess"
	"github.com/QuocDuong16/anve/server/internal/locations"
	"github.com/QuocDuong16/anve/server/internal/testdb"
)

type placeFixture struct {
	ctx           context.Context
	pool          *pgxpool.Pool
	auth          *auth.Service
	circles       *circles.Service
	access        *locationaccess.Service
	locations     *locations.Service
	places        *Store
	owner         auth.User
	viewer        auth.User
	session       auth.Session
	viewerSession auth.Session
	circleID      uuid.UUID
}

func newPlaceFixture(t *testing.T) *placeFixture {
	t.Helper()
	ctx := context.Background()
	pool := testdb.Scratch(t)
	a := auth.NewService(auth.NewPGStore(pool), nil, true)
	c := circles.NewService(circles.NewPGStore(pool))
	owner, ownerTokens, err := a.DevSignIn(ctx, "place-owner@example.com", "Owner", auth.DeviceRegistration{ClientDeviceID: "place-owner-device"})
	require.NoError(t, err)
	session, _, err := a.Authenticate(ctx, ownerTokens.AccessToken)
	require.NoError(t, err)
	circle, _, err := c.Create(ctx, owner.ID, "Family")
	require.NoError(t, err)
	viewer, viewerTokens, err := a.DevSignIn(ctx, "place-viewer@example.com", "Viewer", auth.DeviceRegistration{ClientDeviceID: "place-viewer-device"})
	require.NoError(t, err)
	viewerSession, _, err := a.Authenticate(ctx, viewerTokens.AccessToken)
	require.NoError(t, err)
	invite, err := c.CreateInvite(ctx, owner.ID, circle.ID, nil)
	require.NoError(t, err)
	_, _, err = c.Redeem(ctx, viewer.ID, invite.Code)
	require.NoError(t, err)
	_, err = a.SetLocationSharing(ctx, owner.ID, true)
	require.NoError(t, err)
	return &placeFixture{ctx: ctx, pool: pool, auth: a, circles: c, access: locationaccess.NewService(locationaccess.NewPGStore(pool), 30*24*time.Hour),
		locations: locations.NewService(locations.NewPGStore(pool)), places: NewStore(pool, 30*24*time.Hour),
		owner: owner, viewer: viewer, session: session, viewerSession: viewerSession, circleID: circle.ID}
}

func (f *placeFixture) create(t *testing.T) Place {
	t.Helper()
	p, err := f.places.Create(f.ctx, f.owner.ID, Input{Name: "Home", Latitude: 10, Longitude: 106, RadiusM: 150})
	require.NoError(t, err)
	return p
}

func TestPGPlacesRequireExplicitSharingAndMonitorConsent(t *testing.T) {
	f := newPlaceFixture(t)
	p := f.create(t)
	list, err := f.places.List(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Empty(t, list)
	_, err = f.places.ConfigureMonitor(f.ctx, f.viewer.ID, p.ID, nil)
	require.ErrorIs(t, err, ErrNotFound)
	_, err = f.places.ConfigureMonitor(f.ctx, f.owner.ID, p.ID, []uuid.UUID{f.viewer.ID})
	require.ErrorIs(t, err, ErrInvalid, "a recipient requires both grants")
	require.NoError(t, f.places.SetAccess(f.ctx, f.owner.ID, p.ID, f.viewer.ID, true))
	_, err = f.places.ConfigureMonitor(f.ctx, f.owner.ID, p.ID, []uuid.UUID{f.viewer.ID})
	require.ErrorIs(t, err, ErrInvalid, "place sharing alone must not grant event access")
	require.NoError(t, f.places.SetAccess(f.ctx, f.owner.ID, p.ID, f.viewer.ID, false))
	_, err = f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	_, err = f.places.ConfigureMonitor(f.ctx, f.owner.ID, p.ID, []uuid.UUID{f.viewer.ID})
	require.ErrorIs(t, err, ErrInvalid, "a location grant alone must not disclose a place")
	require.NoError(t, f.places.SetAccess(f.ctx, f.owner.ID, p.ID, f.viewer.ID, true))
	shared, err := f.places.Access(f.ctx, f.owner.ID, p.ID)
	require.NoError(t, err)
	require.Equal(t, []uuid.UUID{f.viewer.ID}, shared)
	_, err = f.places.Access(f.ctx, f.viewer.ID, p.ID)
	require.ErrorIs(t, err, ErrNotFound)
	list, err = f.places.List(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Len(t, list, 1)
	_, err = f.places.Update(f.ctx, f.viewer.ID, p.ID, Input{Name: "Other", Latitude: 10, Longitude: 106, RadiusM: 100})
	require.ErrorIs(t, err, ErrNotFound)
	monitor, err := f.places.ConfigureMonitor(f.ctx, f.owner.ID, p.ID, []uuid.UUID{f.viewer.ID})
	require.NoError(t, err)
	require.Equal(t, []uuid.UUID{f.viewer.ID}, monitor.RecipientIDs)
	loaded, err := f.places.Monitor(f.ctx, f.owner.ID, p.ID)
	require.NoError(t, err)
	require.Equal(t, monitor.RecipientIDs, loaded.RecipientIDs)
	require.NoError(t, f.places.SetAccess(f.ctx, f.owner.ID, p.ID, f.viewer.ID, false))
	list, err = f.places.List(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Empty(t, list)
	_, err = f.places.Create(f.ctx, f.owner.ID, Input{Name: "bad", Latitude: 10, Longitude: 106, RadiusM: 1})
	require.ErrorIs(t, err, ErrInvalid)
}

func TestPGMonitorActivationRejectsPreviousCaptureGeneration(t *testing.T) {
	f := newPlaceFixture(t)
	p := f.create(t)
	old, err := f.locations.CaptureGeneration(f.ctx, f.owner.ID)
	require.NoError(t, err)
	_, err = f.places.ConfigureMonitor(f.ctx, f.owner.ID, p.ID, nil)
	require.NoError(t, err)
	current, err := f.locations.CaptureGeneration(f.ctx, f.owner.ID)
	require.NoError(t, err)
	require.Greater(t, current, old)
	principal := locations.Principal{UserID: f.owner.ID, SessionID: f.session.ID, DeviceID: f.session.DeviceID}
	event := locations.Event{EventID: uuid.New(), ClientDeviceID: "place-owner-device", CaptureGeneration: old,
		RecordedAt: time.Now().UTC(), Latitude: 10, Longitude: 106, HorizontalAccuracyMeters: 5, Provider: "fused", SchemaVersion: 1}
	_, err = f.locations.Ingest(f.ctx, principal, []locations.Event{event})
	require.ErrorIs(t, err, locations.ErrCaptureGeneration)
	event.EventID = uuid.New()
	event.CaptureGeneration = current
	_, err = f.locations.Ingest(f.ctx, principal, []locations.Event{event})
	require.NoError(t, err)
}

func TestPGPlaceTransitionsBackfillAuthorizationAndDeletion(t *testing.T) {
	f := newPlaceFixture(t)
	p := f.create(t)
	require.NoError(t, f.places.SetAccess(f.ctx, f.owner.ID, p.ID, f.viewer.ID, true))
	_, err := f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	_, err = f.places.ConfigureMonitor(f.ctx, f.owner.ID, p.ID, []uuid.UUID{f.viewer.ID})
	require.NoError(t, err)
	// Fixtures model delayed samples inside already-established consent and
	// membership windows; event timestamps, not ingestion order, drive state.
	base := time.Now().UTC().Add(-11 * time.Minute).Truncate(time.Second)
	_, err = f.pool.Exec(f.ctx, `UPDATE circle_membership_periods SET started_at=$1 WHERE user_id IN ($2,$3)`, base.Add(-time.Minute), f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	_, err = f.pool.Exec(f.ctx, `UPDATE location_sharing_epochs SET started_at=$1 WHERE owner_user_id=$2`, base.Add(-time.Minute), f.owner.ID)
	require.NoError(t, err)
	_, err = f.pool.Exec(f.ctx, `UPDATE location_audience_grants SET visible_from=$1 WHERE owner_user_id=$2 AND viewer_user_id=$3`, base.Add(-time.Minute), f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	_, err = f.pool.Exec(f.ctx, `UPDATE place_monitors SET started_at=$1 WHERE owner_user_id=$2 AND place_id=$3`, base.Add(-time.Minute), f.owner.ID, p.ID)
	require.NoError(t, err)
	generation, err := f.locations.CaptureGeneration(f.ctx, f.owner.ID)
	require.NoError(t, err)
	principal := locations.Principal{UserID: f.owner.ID, SessionID: f.session.ID, DeviceID: f.session.DeviceID}
	var batch []locations.Event
	for i, longitude := range []float64{106.004, 106.004, 106, 106, 106.004, 106.004} {
		batch = append(batch, locations.Event{EventID: uuid.New(), ClientDeviceID: "place-owner-device", CaptureGeneration: generation,
			RecordedAt: base.Add(time.Duration(i) * 2 * time.Minute), Latitude: 10, Longitude: longitude, HorizontalAccuracyMeters: 5, Provider: "fused", SchemaVersion: 1})
	}
	_, err = f.locations.Ingest(f.ctx, principal, batch)
	require.NoError(t, err)
	for i := range batch {
		if i == 3 {
			_, err := f.pool.Exec(f.ctx, `CREATE FUNCTION reject_place_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected failure'; END $$;
 CREATE TRIGGER reject_place_event BEFORE INSERT ON place_events FOR EACH ROW EXECUTE FUNCTION reject_place_event()`)
			require.NoError(t, err)
			_, err = f.places.ProcessOne(f.ctx)
			require.Error(t, err)
			var pending, events int
			require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT (SELECT count(*) FROM place_event_queue),(SELECT count(*) FROM place_events)`).Scan(&pending, &events))
			require.Equal(t, 3, pending)
			require.Zero(t, events, "rolled-back processing must not emit an event")
			var attempts int
			require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT max(attempts) FROM place_event_queue`).Scan(&attempts))
			require.Equal(t, 1, attempts)
			_, err = f.pool.Exec(f.ctx, `UPDATE place_event_queue SET next_attempt_at='infinity' WHERE attempts>0`)
			require.NoError(t, err)
			processed, err := f.places.ProcessOne(f.ctx)
			require.NoError(t, err)
			require.False(t, processed, "newer points for the same owner must wait for retry")
			_, err = f.pool.Exec(f.ctx, `DROP TRIGGER reject_place_event ON place_events; DROP FUNCTION reject_place_event()`)
			require.NoError(t, err)
			_, err = f.pool.Exec(f.ctx, `UPDATE place_event_queue SET next_attempt_at=clock_timestamp()`)
			require.NoError(t, err)
		}
		processed, err := f.places.ProcessOne(f.ctx)
		require.NoError(t, err)
		require.True(t, processed)
	}
	processed, err := f.places.ProcessOne(f.ctx)
	require.NoError(t, err)
	require.False(t, processed)
	events, err := f.places.Events(f.ctx, f.viewer.ID, 20)
	require.NoError(t, err)
	require.Len(t, events, 2)
	require.Equal(t, "EXIT", events[0].Type)
	require.Equal(t, "ENTER", events[1].Type)
	require.True(t, events[1].Delayed)
	// The feed must apply the retention watermark before physical cleanup.
	_, err = f.pool.Exec(f.ctx, `UPDATE location_retention_watermark SET cutoff=$1 WHERE singleton`, base.Add(3*time.Minute))
	require.NoError(t, err)
	events, err = f.places.Events(f.ctx, f.viewer.ID, 20)
	require.NoError(t, err)
	require.Len(t, events, 1)
	require.Equal(t, "EXIT", events[0].Type)
	_, err = f.pool.Exec(f.ctx, `UPDATE location_retention_watermark SET cutoff=NULL WHERE singleton`)
	require.NoError(t, err)
	late := locations.Event{EventID: uuid.New(), ClientDeviceID: "place-owner-device", CaptureGeneration: generation,
		RecordedAt: base.Add(3 * time.Minute), Latitude: 10, Longitude: 106, HorizontalAccuracyMeters: 5, Provider: "fused", SchemaVersion: 1}
	_, err = f.locations.Ingest(f.ctx, principal, []locations.Event{late})
	require.NoError(t, err)
	processed, err = f.places.ProcessOne(f.ctx)
	require.NoError(t, err)
	require.True(t, processed)
	events, err = f.places.Events(f.ctx, f.viewer.ID, 20)
	require.NoError(t, err)
	require.Len(t, events, 2, "late backfill must not rewind the stable state")
	_, err = f.locations.Ingest(f.ctx, principal, batch)
	require.NoError(t, err)
	processed, err = f.places.ProcessOne(f.ctx)
	require.NoError(t, err)
	require.False(t, processed, "duplicate batches must not enqueue work")
	require.NoError(t, f.places.SetAccess(f.ctx, f.owner.ID, p.ID, f.viewer.ID, false))
	events, err = f.places.Events(f.ctx, f.viewer.ID, 20)
	require.NoError(t, err)
	require.Empty(t, events, "revoking place access removes event visibility")
	require.NoError(t, f.places.SetAccess(f.ctx, f.owner.ID, p.ID, f.viewer.ID, true))
	events, err = f.places.Events(f.ctx, f.viewer.ID, 20)
	require.NoError(t, err)
	require.Empty(t, events, "resharing a place must not revive old recipient authorization")
	require.NoError(t, f.access.Revoke(f.ctx, f.owner.ID, f.viewer.ID))
	events, err = f.places.Events(f.ctx, f.viewer.ID, 20)
	require.NoError(t, err)
	require.Empty(t, events)
	_, err = f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	events, err = f.places.Events(f.ctx, f.viewer.ID, 20)
	require.NoError(t, err)
	require.Empty(t, events, "a new grant must not revive an old event subscription")
	from, to := base.Add(3*time.Minute), base.Add(5*time.Minute)
	require.NoError(t, f.locations.DeleteHistory(f.ctx, f.owner.ID, &from, to))
	events, err = f.places.Events(f.ctx, f.owner.ID, 20)
	require.NoError(t, err)
	require.Len(t, events, 1, "deleting evidence must remove its dependent transition")
}

func TestPGNewGrantDoesNotRestoreOldEventRecipient(t *testing.T) {
	f := newPlaceFixture(t)
	p := f.create(t)
	require.NoError(t, f.places.SetAccess(f.ctx, f.owner.ID, p.ID, f.viewer.ID, true))
	_, err := f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	_, err = f.places.ConfigureMonitor(f.ctx, f.owner.ID, p.ID, []uuid.UUID{f.viewer.ID})
	require.NoError(t, err)
	require.NoError(t, f.access.Revoke(f.ctx, f.owner.ID, f.viewer.ID))
	monitor, err := f.places.Monitor(f.ctx, f.owner.ID, p.ID)
	require.NoError(t, err)
	require.Empty(t, monitor.RecipientIDs)
	_, err = f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	monitor, err = f.places.Monitor(f.ctx, f.owner.ID, p.ID)
	require.NoError(t, err)
	require.Empty(t, monitor.RecipientIDs)
	eligible, err := f.places.EligibleRecipients(f.ctx, f.owner.ID, p.ID)
	require.NoError(t, err)
	require.Equal(t, []uuid.UUID{f.viewer.ID}, eligible, "a fresh decision may select the new grant")
}

func TestPGMonitorEndsOnConsentOffAndMembershipDeparture(t *testing.T) {
	f := newPlaceFixture(t)
	p := f.create(t)
	require.NoError(t, f.places.SetAccess(f.ctx, f.owner.ID, p.ID, f.viewer.ID, true))
	_, err := f.auth.SetLocationSharing(f.ctx, f.viewer.ID, true)
	require.NoError(t, err)
	_, err = f.places.ConfigureMonitor(f.ctx, f.viewer.ID, p.ID, nil)
	require.NoError(t, err)
	_, err = f.auth.SetLocationSharing(f.ctx, f.viewer.ID, false)
	require.NoError(t, err)
	_, err = f.places.Monitor(f.ctx, f.viewer.ID, p.ID)
	require.ErrorIs(t, err, ErrNotFound)
	_, err = f.auth.SetLocationSharing(f.ctx, f.viewer.ID, true)
	require.NoError(t, err)
	_, err = f.places.Monitor(f.ctx, f.viewer.ID, p.ID)
	require.ErrorIs(t, err, ErrNotFound, "consent re-enable must not restore monitoring")
	_, err = f.places.ConfigureMonitor(f.ctx, f.viewer.ID, p.ID, nil)
	require.NoError(t, err)
	require.NoError(t, f.circles.Leave(f.ctx, f.viewer.ID, f.circleID, false))
	invite, err := f.circles.CreateInvite(f.ctx, f.owner.ID, f.circleID, nil)
	require.NoError(t, err)
	_, _, err = f.circles.Redeem(f.ctx, f.viewer.ID, invite.Code)
	require.NoError(t, err)
	list, err := f.places.List(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Empty(t, list, "a rejoined member must not regain old place access")
	_, err = f.places.Monitor(f.ctx, f.viewer.ID, p.ID)
	require.ErrorIs(t, err, ErrNotFound)
}

func TestPGAccountDeletionRemovesMonitorAndPlaceAccess(t *testing.T) {
	f := newPlaceFixture(t)
	p := f.create(t)
	require.NoError(t, f.places.SetAccess(f.ctx, f.owner.ID, p.ID, f.viewer.ID, true))
	_, err := f.auth.SetLocationSharing(f.ctx, f.viewer.ID, true)
	require.NoError(t, err)
	_, err = f.places.ConfigureMonitor(f.ctx, f.viewer.ID, p.ID, nil)
	require.NoError(t, err)
	require.NoError(t, f.auth.DeleteAccount(f.ctx, f.viewer.ID))
	var access, monitors, places int
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT (SELECT count(*) FROM place_access WHERE member_user_id=$1),
 (SELECT count(*) FROM place_monitors WHERE owner_user_id=$1), (SELECT count(*) FROM places WHERE id=$2)`, f.viewer.ID, p.ID).Scan(&access, &monitors, &places))
	require.Zero(t, access)
	require.Zero(t, monitors)
	require.Equal(t, 1, places, "another member's account deletion must not delete the owner's place")
}

func TestPGPlaceEditInvalidatesMonitorsAndHidesEvents(t *testing.T) {
	f := newPlaceFixture(t)
	p := f.create(t)
	_, err := f.places.ConfigureMonitor(f.ctx, f.owner.ID, p.ID, nil)
	require.NoError(t, err)
	_, err = f.places.Update(f.ctx, f.owner.ID, p.ID, Input{Name: "New home", Latitude: 10, Longitude: 106, RadiusM: 250})
	require.NoError(t, err)
	_, err = f.places.Monitor(f.ctx, f.owner.ID, p.ID)
	require.ErrorIs(t, err, ErrNotFound)
	var revoked bool
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT revoked_at IS NOT NULL FROM place_monitors WHERE place_id=$1`, p.ID).Scan(&revoked))
	require.True(t, revoked)
}

func TestPGOverlappingPlacesEvaluateIndependently(t *testing.T) {
	f := newPlaceFixture(t)
	first := f.create(t)
	second, err := f.places.Create(f.ctx, f.owner.ID, Input{Name: "School", Latitude: 10, Longitude: 106, RadiusM: 150})
	require.NoError(t, err)
	_, err = f.places.ConfigureMonitor(f.ctx, f.owner.ID, first.ID, nil)
	require.NoError(t, err)
	_, err = f.places.ConfigureMonitor(f.ctx, f.owner.ID, second.ID, nil)
	require.NoError(t, err)
	base := time.Now().UTC().Add(-8 * time.Minute).Truncate(time.Second)
	_, err = f.pool.Exec(f.ctx, `UPDATE circle_membership_periods SET started_at=$1 WHERE user_id=$2`, base.Add(-time.Minute), f.owner.ID)
	require.NoError(t, err)
	_, err = f.pool.Exec(f.ctx, `UPDATE location_sharing_epochs SET started_at=$1 WHERE owner_user_id=$2`, base.Add(-time.Minute), f.owner.ID)
	require.NoError(t, err)
	_, err = f.pool.Exec(f.ctx, `UPDATE place_monitors SET started_at=$1 WHERE owner_user_id=$2`, base.Add(-time.Minute), f.owner.ID)
	require.NoError(t, err)
	generation, err := f.locations.CaptureGeneration(f.ctx, f.owner.ID)
	require.NoError(t, err)
	var batch []locations.Event
	for i, longitude := range []float64{106.004, 106.004, 106, 106} {
		batch = append(batch, locations.Event{EventID: uuid.New(), ClientDeviceID: "place-owner-device", CaptureGeneration: generation,
			RecordedAt: base.Add(time.Duration(i) * 2 * time.Minute), Latitude: 10, Longitude: longitude, HorizontalAccuracyMeters: 5, Provider: "fused", SchemaVersion: 1})
	}
	_, err = f.locations.Ingest(f.ctx, locations.Principal{UserID: f.owner.ID, SessionID: f.session.ID, DeviceID: f.session.DeviceID}, batch)
	require.NoError(t, err)
	for range batch {
		processed, err := f.places.ProcessOne(f.ctx)
		require.NoError(t, err)
		require.True(t, processed)
	}
	events, err := f.places.Events(f.ctx, f.owner.ID, 20)
	require.NoError(t, err)
	require.Len(t, events, 2)
	require.NotEqual(t, events[0].PlaceID, events[1].PlaceID)
	require.Equal(t, "ENTER", events[0].Type)
	require.Equal(t, "ENTER", events[1].Type)
}

func TestPGQueueFailureRollsBackIngestion(t *testing.T) {
	f := newPlaceFixture(t)
	p := f.create(t)
	_, err := f.places.ConfigureMonitor(f.ctx, f.owner.ID, p.ID, nil)
	require.NoError(t, err)
	_, err = f.pool.Exec(f.ctx, `CREATE FUNCTION reject_place_queue() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected failure'; END $$;
 CREATE TRIGGER reject_place_queue BEFORE INSERT ON place_event_queue FOR EACH ROW EXECUTE FUNCTION reject_place_queue()`)
	require.NoError(t, err)
	generation, err := f.locations.CaptureGeneration(f.ctx, f.owner.ID)
	require.NoError(t, err)
	event := locations.Event{EventID: uuid.New(), ClientDeviceID: "place-owner-device", CaptureGeneration: generation,
		RecordedAt: time.Now().UTC(), Latitude: 10, Longitude: 106, HorizontalAccuracyMeters: 5, Provider: "fused", SchemaVersion: 1}
	_, err = f.locations.Ingest(f.ctx, locations.Principal{UserID: f.owner.ID, SessionID: f.session.ID, DeviceID: f.session.DeviceID}, []locations.Event{event})
	require.Error(t, err)
	var count int
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM location_points WHERE user_id=$1`, f.owner.ID).Scan(&count))
	require.Zero(t, count)
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM place_event_queue`).Scan(&count))
	require.Zero(t, count)
}

func TestPGRetryBackoffDoesNotBlockAnotherOwner(t *testing.T) {
	f := newPlaceFixture(t)
	p := f.create(t)
	require.NoError(t, f.places.SetAccess(f.ctx, f.owner.ID, p.ID, f.viewer.ID, true))
	_, err := f.auth.SetLocationSharing(f.ctx, f.viewer.ID, true)
	require.NoError(t, err)
	_, err = f.places.ConfigureMonitor(f.ctx, f.owner.ID, p.ID, nil)
	require.NoError(t, err)
	_, err = f.places.ConfigureMonitor(f.ctx, f.viewer.ID, p.ID, nil)
	require.NoError(t, err)
	when := time.Now().UTC()
	for _, account := range []struct {
		user    auth.User
		session auth.Session
		device  string
	}{{f.owner, f.session, "place-owner-device"}, {f.viewer, f.viewerSession, "place-viewer-device"}} {
		generation, err := f.locations.CaptureGeneration(f.ctx, account.user.ID)
		require.NoError(t, err)
		event := locations.Event{EventID: uuid.New(), ClientDeviceID: account.device, CaptureGeneration: generation,
			RecordedAt: when, Latitude: 10, Longitude: 106, HorizontalAccuracyMeters: 5, Provider: "fused", SchemaVersion: 1}
		_, err = f.locations.Ingest(f.ctx, locations.Principal{UserID: account.user.ID, SessionID: account.session.ID, DeviceID: account.session.DeviceID}, []locations.Event{event})
		require.NoError(t, err)
	}
	_, err = f.pool.Exec(f.ctx, `UPDATE place_event_queue SET attempts=1,next_attempt_at='infinity' WHERE point_id IN (SELECT id FROM location_points WHERE user_id=$1)`, f.owner.ID)
	require.NoError(t, err)
	processed, err := f.places.ProcessOne(f.ctx)
	require.NoError(t, err)
	require.True(t, processed)
	var ownerPending, viewerPending int
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FILTER (WHERE lp.user_id=$1),count(*) FILTER (WHERE lp.user_id=$2) FROM place_event_queue q JOIN location_points lp ON lp.id=q.point_id`, f.owner.ID, f.viewer.ID).Scan(&ownerPending, &viewerPending))
	require.Equal(t, 1, ownerPending)
	require.Zero(t, viewerPending)
}

func TestPGConcurrentMonitorRevocationAndPlaceDeletion(t *testing.T) {
	for _, removePlace := range []bool{false, true} {
		t.Run(map[bool]string{false: "monitor revoked", true: "place deleted"}[removePlace], func(t *testing.T) {
			f := newPlaceFixture(t)
			p := f.create(t)
			_, err := f.places.ConfigureMonitor(f.ctx, f.owner.ID, p.ID, nil)
			require.NoError(t, err)
			generation, err := f.locations.CaptureGeneration(f.ctx, f.owner.ID)
			require.NoError(t, err)
			event := locations.Event{EventID: uuid.New(), ClientDeviceID: "place-owner-device", CaptureGeneration: generation,
				RecordedAt: time.Now().UTC(), Latitude: 10, Longitude: 106, HorizontalAccuracyMeters: 5, Provider: "fused", SchemaVersion: 1}
			_, err = f.locations.Ingest(f.ctx, locations.Principal{UserID: f.owner.ID, SessionID: f.session.ID, DeviceID: f.session.DeviceID}, []locations.Event{event})
			require.NoError(t, err)
			tx, err := f.pool.Begin(f.ctx)
			require.NoError(t, err)
			defer func() { _ = tx.Rollback(f.ctx) }()
			require.NoError(t, tx.QueryRow(f.ctx, `SELECT id FROM users WHERE id=$1 FOR UPDATE`, f.owner.ID).Scan(new(uuid.UUID)))
			done := make(chan error, 1)
			go func() { _, err := f.places.ProcessOne(f.ctx); done <- err }()
			waitForPlaceLock(t, f.pool)
			if removePlace {
				_, err = tx.Exec(f.ctx, `DELETE FROM places WHERE id=$1`, p.ID)
			} else {
				_, err = tx.Exec(f.ctx, `UPDATE place_monitors SET revoked_at=clock_timestamp() WHERE place_id=$1`, p.ID)
			}
			require.NoError(t, err)
			require.NoError(t, tx.Commit(f.ctx))
			require.NoError(t, <-done)
			var events, pending int
			require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT (SELECT count(*) FROM place_events),(SELECT count(*) FROM place_event_queue)`).Scan(&events, &pending))
			require.Zero(t, events)
			require.Zero(t, pending)
		})
	}
}

func TestPGWorkerRechecksOrderAfterWaitingForInFlightBackfill(t *testing.T) {
	f := newPlaceFixture(t)
	p := f.create(t)
	_, err := f.places.ConfigureMonitor(f.ctx, f.owner.ID, p.ID, nil)
	require.NoError(t, err)
	generation, err := f.locations.CaptureGeneration(f.ctx, f.owner.ID)
	require.NoError(t, err)
	later := time.Now().UTC().Truncate(time.Microsecond)
	event := locations.Event{EventID: uuid.New(), ClientDeviceID: "place-owner-device", CaptureGeneration: generation,
		RecordedAt: later, Latitude: 10, Longitude: 106, HorizontalAccuracyMeters: 5, Provider: "fused", SchemaVersion: 1}
	_, err = f.locations.Ingest(f.ctx, locations.Principal{UserID: f.owner.ID, SessionID: f.session.ID, DeviceID: f.session.DeviceID}, []locations.Event{event})
	require.NoError(t, err)
	tx, err := f.pool.Begin(f.ctx)
	require.NoError(t, err)
	defer func() { _ = tx.Rollback(f.ctx) }()
	require.NoError(t, tx.QueryRow(f.ctx, `SELECT id FROM users WHERE id=$1 FOR SHARE`, f.owner.ID).Scan(new(uuid.UUID)))
	done := make(chan error, 1)
	go func() { _, err := f.places.ProcessOne(f.ctx); done <- err }()
	waitForPlaceLock(t, f.pool)
	earlier := later.Add(-time.Microsecond)
	var earlierID int64
	err = tx.QueryRow(f.ctx, `INSERT INTO location_points(event_id,user_id,device_id,recorded_at,received_at,latitude,longitude,geom,horizontal_accuracy_m,provider,schema_version,sharing_epoch_id,capture_generation)
 VALUES($1,$2,$3,$4,clock_timestamp(),10,106,ST_SetSRID(ST_MakePoint(106,10),4326)::geography,5,'fused',1,
 (SELECT id FROM location_sharing_epochs WHERE owner_user_id=$2 AND ended_at IS NULL),$5) RETURNING id`, uuid.New(), f.owner.ID, f.session.DeviceID, earlier, generation).Scan(&earlierID)
	require.NoError(t, err)
	_, err = tx.Exec(f.ctx, `INSERT INTO place_event_queue(point_id,owner_user_id,recorded_at) VALUES($1,$2,$3)`, earlierID, f.owner.ID, earlier)
	require.NoError(t, err)
	require.NoError(t, tx.Commit(f.ctx))
	require.NoError(t, <-done)
	var pending int
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM place_event_queue`).Scan(&pending))
	require.Equal(t, 2, pending, "the worker must reselect after the earlier transaction commits")
	for range 2 {
		processed, err := f.places.ProcessOne(f.ctx)
		require.NoError(t, err)
		require.True(t, processed)
	}
	var observed time.Time
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT last_observed_at FROM place_monitors WHERE owner_user_id=$1 AND revoked_at IS NULL`, f.owner.ID).Scan(&observed))
	require.True(t, later.Equal(observed))
}

func waitForPlaceLock(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	deadline := time.NewTimer(5 * time.Second)
	defer deadline.Stop()
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for {
		var waiting int
		require.NoError(t, pool.QueryRow(context.Background(), `SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'`).Scan(&waiting))
		if waiting > 0 {
			return
		}
		select {
		case <-deadline.C:
			t.Fatal("worker did not wait for the owner lock")
		case <-ticker.C:
		}
	}
}
