package journeys

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/circles"
	"github.com/QuocDuong16/anve/server/internal/locationaccess"
	"github.com/QuocDuong16/anve/server/internal/locations"
	"github.com/QuocDuong16/anve/server/internal/notifications"
	"github.com/QuocDuong16/anve/server/internal/sos"
	"github.com/QuocDuong16/anve/server/internal/testdb"
)

type fixture struct {
	ctx                  context.Context
	pool                 *pgxpool.Pool
	store                *Store
	auth                 *auth.Service
	circles              *circles.Service
	access               *locationaccess.Service
	owner, viewer, other auth.User
	session              auth.Session
	p, viewerP           Principal
	circle               uuid.UUID
}

func setup(t *testing.T) *fixture {
	t.Helper()
	ctx := context.Background()
	pool := testdb.Scratch(t)
	a := auth.NewService(auth.NewPGStore(pool), nil, true)
	c := circles.NewService(circles.NewPGStore(pool))
	owner, tokens, err := a.DevSignIn(ctx, "journey-owner@example.com", "Owner", auth.DeviceRegistration{ClientDeviceID: "journey-owner"})
	require.NoError(t, err)
	session, _, err := a.Authenticate(ctx, tokens.AccessToken)
	require.NoError(t, err)
	viewer, vt, err := a.DevSignIn(ctx, "journey-viewer@example.com", "Viewer", auth.DeviceRegistration{ClientDeviceID: "journey-viewer"})
	require.NoError(t, err)
	vs, _, err := a.Authenticate(ctx, vt.AccessToken)
	require.NoError(t, err)
	other, _, err := a.DevSignIn(ctx, "journey-other@example.com", "Other", auth.DeviceRegistration{ClientDeviceID: "journey-other"})
	require.NoError(t, err)
	circle, _, err := c.Create(ctx, owner.ID, "Family")
	require.NoError(t, err)
	for _, u := range []auth.User{viewer, other} {
		invite, err := c.CreateInvite(ctx, owner.ID, circle.ID, nil)
		require.NoError(t, err)
		_, _, err = c.Redeem(ctx, u.ID, invite.Code)
		require.NoError(t, err)
	}
	_, err = a.SetLocationSharing(ctx, owner.ID, true)
	require.NoError(t, err)
	access := locationaccess.NewService(locationaccess.NewPGStore(pool), 30*24*time.Hour)
	for _, id := range []uuid.UUID{viewer.ID, other.ID} {
		_, err = access.Grant(ctx, owner.ID, id)
		require.NoError(t, err)
	}
	return &fixture{ctx: ctx, pool: pool, store: NewStore(pool, 30), auth: a, circles: c, access: access, owner: owner, viewer: viewer, other: other, session: session,
		p: Principal{owner.ID, session.ID, session.DeviceID}, viewerP: Principal{viewer.ID, vs.ID, vs.DeviceID}, circle: circle.ID}
}

func (f *fixture) request() CreateRequest {
	return CreateRequest{JourneyID: uuid.New(), PreparedAt: time.Now().UTC().Truncate(time.Microsecond), DurationMinutes: 120,
		Destination: Destination{Label: "Nhà", Latitude: 21.04, Longitude: 105.78}, RecipientIDs: []uuid.UUID{f.viewer.ID}}
}

func TestPGJourneyAudienceLifecycleAndIdempotency(t *testing.T) {
	f := setup(t)
	in := f.request()
	j, created, err := f.store.Create(f.ctx, f.p, in)
	require.NoError(t, err)
	require.True(t, created)
	require.Equal(t, "ACTIVE", j.State)
	require.Len(t, j.Recipients, 1)
	require.Nil(t, j.Progress)
	retry, created, err := f.store.Create(f.ctx, f.p, in)
	require.NoError(t, err)
	require.False(t, created)
	require.Equal(t, j.ExpiresAt, retry.ExpiresAt)
	changed := in
	changed.Destination.Label = "Other"
	_, _, err = f.store.Create(f.ctx, f.p, changed)
	require.ErrorIs(t, err, ErrConflict)
	_, err = f.store.Get(f.ctx, f.other.ID, j.ID)
	require.ErrorIs(t, err, ErrNotFound, "ordinary location grant alone never selects a journey viewer")
	visible, err := f.store.Get(f.ctx, f.viewer.ID, j.ID)
	require.NoError(t, err)
	require.Empty(t, visible.Recipients, "do not disclose other recipients to a viewer")
	require.ErrorIs(t, f.store.Transition(f.ctx, f.viewerP, j.ID, "arrive"), ErrNotFound)
	require.NoError(t, f.store.Transition(f.ctx, f.p, j.ID, "arrive"))
	require.NoError(t, f.store.Transition(f.ctx, f.p, j.ID, "arrive"))
	require.ErrorIs(t, f.store.Transition(f.ctx, f.p, j.ID, "end"), ErrConflict)
	j, err = f.store.Get(f.ctx, f.viewer.ID, j.ID)
	require.NoError(t, err)
	require.Equal(t, "ARRIVED", j.State)
	require.NotNil(t, j.EndedAt)
	var count int
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM audit_log WHERE action='journey.arrived'`).Scan(&count))
	require.Equal(t, 1, count)
	require.NoError(t, f.store.RevokeRecipient(f.ctx, f.p, j.ID, f.viewer.ID))
	require.NoError(t, f.store.RevokeRecipient(f.ctx, f.p, j.ID, f.viewer.ID))
	_, err = f.store.Get(f.ctx, f.viewer.ID, j.ID)
	require.ErrorIs(t, err, ErrNotFound)
}

func TestPGJourneyConcurrentStartsAndCancellationFence(t *testing.T) {
	f := setup(t)
	inputs := []CreateRequest{f.request(), f.request()}
	start := make(chan struct{})
	errs := make([]error, 2)
	var wg sync.WaitGroup
	for i := range inputs {
		wg.Go(func() { <-start; _, _, errs[i] = f.store.Create(f.ctx, f.p, inputs[i]) })
	}
	close(start)
	wg.Wait()
	successes := 0
	for _, err := range errs {
		if err == nil {
			successes++
		} else {
			require.ErrorIs(t, err, ErrConflict)
		}
	}
	require.Equal(t, 1, successes)
	for i, err := range errs {
		if err == nil {
			require.NoError(t, f.store.Transition(f.ctx, f.p, inputs[i].JourneyID, "end"))
		}
	}
	in := f.request()
	start = make(chan struct{})
	var createErr, cancelErr error
	wg.Go(func() { <-start; _, _, createErr = f.store.Create(f.ctx, f.p, in) })
	wg.Go(func() { <-start; cancelErr = f.store.Transition(f.ctx, f.p, in.JourneyID, "cancel") })
	close(start)
	wg.Wait()
	require.NoError(t, cancelErr)
	require.True(t, createErr == nil || errors.Is(createErr, ErrCancelled))
	_, _, err := f.store.Create(f.ctx, f.p, in)
	require.ErrorIs(t, err, ErrCancelled)
	var active int
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM journeys WHERE owner_user_id=$1 AND state='ACTIVE'`, f.owner.ID).Scan(&active))
	require.Zero(t, active)
	before := f.request()
	require.NoError(t, f.store.Transition(f.ctx, f.p, before.JourneyID, "cancel"))
	_, _, err = f.store.Create(f.ctx, f.p, before)
	require.ErrorIs(t, err, ErrCancelled)
	require.ErrorIs(t, f.store.Transition(f.ctx, f.viewerP, before.JourneyID, "cancel"), ErrNotFound)
}

func TestPGJourneyRevocationConsentAndRejoinNeverRestore(t *testing.T) {
	f := setup(t)
	in := f.request()
	_, _, err := f.store.Create(f.ctx, f.p, in)
	require.NoError(t, err)
	require.NoError(t, f.access.Revoke(f.ctx, f.owner.ID, f.viewer.ID))
	_, err = f.store.Get(f.ctx, f.viewer.ID, in.JourneyID)
	require.ErrorIs(t, err, ErrNotFound)
	_, err = f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	_, err = f.store.Get(f.ctx, f.viewer.ID, in.JourneyID)
	require.ErrorIs(t, err, ErrNotFound, "a new grant ID cannot revive a selection")
	j, err := f.store.Get(f.ctx, f.owner.ID, in.JourneyID)
	require.NoError(t, err)
	require.Equal(t, "CANCELLED", j.State)
	in = f.request()
	_, _, err = f.store.Create(f.ctx, f.p, in)
	require.NoError(t, err)
	_, err = f.auth.SetLocationSharing(f.ctx, f.owner.ID, false)
	require.NoError(t, err)
	_, err = f.store.Get(f.ctx, f.viewer.ID, in.JourneyID)
	require.ErrorIs(t, err, ErrNotFound)
	j, err = f.store.Get(f.ctx, f.owner.ID, in.JourneyID)
	require.NoError(t, err)
	require.Equal(t, "CANCELLED", j.State)
	_, _, err = f.store.Create(f.ctx, f.p, f.request())
	require.ErrorIs(t, err, locations.ErrConsentRequired)
	_, err = f.auth.SetLocationSharing(f.ctx, f.owner.ID, true)
	require.NoError(t, err)
	_, err = f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	in = f.request()
	_, _, err = f.store.Create(f.ctx, f.p, in)
	require.NoError(t, err)
	require.NoError(t, f.circles.Leave(f.ctx, f.viewer.ID, f.circle, false))
	_, err = f.store.Get(f.ctx, f.viewer.ID, in.JourneyID)
	require.ErrorIs(t, err, ErrNotFound)
	invite, err := f.circles.CreateInvite(f.ctx, f.owner.ID, f.circle, nil)
	require.NoError(t, err)
	_, _, err = f.circles.Redeem(f.ctx, f.viewer.ID, invite.Code)
	require.NoError(t, err)
	_, err = f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	_, err = f.store.Get(f.ctx, f.viewer.ID, in.JourneyID)
	require.ErrorIs(t, err, ErrNotFound)
}

func TestPGJourneyExpiryDeletionRetentionAndSession(t *testing.T) {
	f := setup(t)
	in := f.request()
	_, _, err := f.store.Create(f.ctx, f.p, in)
	require.NoError(t, err)
	_, err = f.pool.Exec(f.ctx, `UPDATE journeys SET started_at=now()-interval '2 hours',expires_at=now()-interval '1 hour' WHERE id=$1`, in.JourneyID)
	require.NoError(t, err)
	j, err := f.store.Get(f.ctx, f.owner.ID, in.JourneyID)
	require.NoError(t, err)
	require.Equal(t, "EXPIRED", j.State)
	retry, created, err := f.store.Create(f.ctx, f.p, in)
	require.NoError(t, err)
	require.False(t, created)
	require.Equal(t, j.ExpiresAt, retry.ExpiresAt)
	require.NoError(t, f.store.Maintain(f.ctx))
	in = f.request()
	_, _, err = f.store.Create(f.ctx, f.p, in)
	require.NoError(t, err)
	locationStore := locations.NewPGStore(f.pool)
	require.NoError(t, locationStore.DeleteHistory(f.ctx, f.owner.ID, nil, time.Now()))
	_, err = f.store.Get(f.ctx, f.owner.ID, in.JourneyID)
	require.ErrorIs(t, err, ErrNotFound)
	_, _, err = f.store.Create(f.ctx, f.p, in)
	require.ErrorIs(t, err, ErrConflict, "deleted content cannot be recreated")
	in = f.request()
	_, _, err = f.store.Create(f.ctx, f.p, in)
	require.NoError(t, err)
	require.NoError(t, locationStore.SetRetentionDays(f.ctx, f.owner.ID, 1))
	_, err = f.store.Get(f.ctx, f.owner.ID, in.JourneyID)
	require.ErrorIs(t, err, ErrNotFound)
	in = f.request()
	_, _, err = f.store.Create(f.ctx, f.p, in)
	require.NoError(t, err)
	_, err = f.pool.Exec(f.ctx, `UPDATE journeys SET state='ENDED',started_at=now()-interval '2 days',expires_at=now()-interval '2 days'+interval '2 hours' WHERE id=$1`, in.JourneyID)
	require.NoError(t, err)
	_, err = f.store.Get(f.ctx, f.owner.ID, in.JourneyID)
	require.ErrorIs(t, err, ErrNotFound, "retention is enforced before cleanup")
	require.NoError(t, f.store.Maintain(f.ctx))
	var remaining int
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM journeys WHERE id=$1`, in.JourneyID).Scan(&remaining))
	require.Zero(t, remaining)
	require.NoError(t, f.auth.SignOut(f.ctx, f.session))
	_, _, err = f.store.Create(f.ctx, f.p, f.request())
	require.ErrorIs(t, err, auth.ErrUnauthenticated)
}

type fakeGateway struct {
	signals []notifications.Signal
	err     error
}

func (f *fakeGateway) Send(_ context.Context, _ string, s notifications.Signal) error {
	f.signals = append(f.signals, s)
	return f.err
}

func TestPGJourneyAtomicOutboxDispatchAndSuppression(t *testing.T) {
	f := setup(t)
	in := f.request()
	_, err := f.pool.Exec(f.ctx, `CREATE FUNCTION reject_journey_work() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$;
 CREATE TRIGGER reject_journey_work BEFORE INSERT ON journey_notification_outbox FOR EACH ROW EXECUTE FUNCTION reject_journey_work()`)
	require.NoError(t, err)
	_, _, err = f.store.Create(f.ctx, f.p, in)
	require.Error(t, err)
	var count int
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM journey_requests WHERE id=$1`, in.JourneyID).Scan(&count))
	require.Zero(t, count)
	_, err = f.pool.Exec(f.ctx, `DROP TRIGGER reject_journey_work ON journey_notification_outbox; DROP FUNCTION reject_journey_work()`)
	require.NoError(t, err)
	sosStore := sos.NewStore(f.pool)
	require.NoError(t, sosStore.RegisterPushToken(f.ctx, sos.Principal{UserID: f.viewerP.UserID, SessionID: f.viewerP.SessionID, DeviceID: f.viewerP.DeviceID}, "fake-journey-token"))
	_, _, err = f.store.Create(f.ctx, f.p, in)
	require.NoError(t, err)
	gateway := &fakeGateway{err: errors.New("unknown provider outcome")}
	worked, err := f.store.DispatchOne(f.ctx, gateway)
	require.NoError(t, err)
	require.True(t, worked)
	var state string
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT state FROM journey_notification_outbox WHERE journey_id=$1`, in.JourneyID).Scan(&state))
	require.Equal(t, "QUEUED", state)
	_, err = f.pool.Exec(f.ctx, `UPDATE journey_notification_outbox SET next_attempt_at=now() WHERE journey_id=$1`, in.JourneyID)
	require.NoError(t, err)
	gateway.err = nil
	worked, err = f.store.DispatchOne(f.ctx, gateway)
	require.NoError(t, err)
	require.True(t, worked)
	require.Len(t, gateway.signals, 2)
	require.Equal(t, "JOURNEY_ACTIVE", gateway.signals[0].Kind)
	require.Equal(t, in.JourneyID, gateway.signals[0].EventID)
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT state FROM journey_notification_outbox WHERE journey_id=$1`, in.JourneyID).Scan(&state))
	require.Equal(t, "ACCEPTED", state)
	require.NoError(t, f.store.Transition(f.ctx, f.p, in.JourneyID, "arrive"))
	require.NoError(t, f.store.RevokeRecipient(f.ctx, f.p, in.JourneyID, f.viewer.ID))
	_, err = f.store.DispatchOne(f.ctx, gateway)
	require.NoError(t, err)
	require.Len(t, gateway.signals, 2, "revoked recipients must not receive terminal work")
}
