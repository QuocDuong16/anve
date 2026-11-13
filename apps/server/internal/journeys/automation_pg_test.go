package journeys

import (
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/locations"
	"github.com/QuocDuong16/anve/server/internal/sos"
)

func automationJourney(t *testing.T, f *fixture, automatic bool, staleMinutes int) Journey {
	t.Helper()
	in := f.request()
	in.AutomaticArrival, in.StaleAfterMinutes = automatic, staleMinutes
	j, _, err := f.store.Create(f.ctx, f.p, in)
	require.NoError(t, err)
	// Advance the synthetic journey's clock without wall-clock sleeps.
	_, err = f.pool.Exec(f.ctx, `UPDATE location_sharing_epochs SET started_at=now()-interval '15 minutes' WHERE owner_user_id=$1 AND ended_at IS NULL`, f.owner.ID)
	require.NoError(t, err)
	_, err = f.pool.Exec(f.ctx, `UPDATE journeys SET started_at=now()-interval '10 minutes',automation_anchor_at=now()-interval '10 minutes' WHERE id=$1`, j.ID)
	require.NoError(t, err)
	return j
}

func automationPoint(t *testing.T, f *fixture, j Journey, recorded time.Time, mock bool, lag time.Duration) {
	t.Helper()
	store := locations.NewPGStore(f.pool)
	generation, err := store.CaptureGeneration(f.ctx, f.owner.ID)
	require.NoError(t, err)
	id := uuid.New()
	_, err = locations.NewService(store).Ingest(f.ctx, locations.Principal{UserID: f.p.UserID, SessionID: f.p.SessionID, DeviceID: f.p.DeviceID}, []locations.Event{{
		EventID: id, CaptureGeneration: generation, ClientDeviceID: "journey-owner", RecordedAt: recorded,
		Latitude: j.Destination.Latitude, Longitude: j.Destination.Longitude, HorizontalAccuracyMeters: 20, Provider: "fused", IsMock: mock, SchemaVersion: 1,
	}})
	require.NoError(t, err)
	_, err = f.pool.Exec(f.ctx, `UPDATE location_points SET received_at=recorded_at+$2::interval WHERE event_id=$1`, id, lag.String())
	require.NoError(t, err)
}

func TestPGAutomaticArrivalIsOptInAndRejectsBackfillAndMocks(t *testing.T) {
	for _, tc := range []struct {
		name            string
		automatic, mock bool
		lag             time.Duration
		state, source   string
	}{
		{"disabled", false, false, time.Second, "ACTIVE", ""},
		{"live", true, false, time.Second, "ARRIVED", "AUTOMATIC"},
		{"mock", true, true, time.Second, "ACTIVE", ""},
		{"backfill", true, false, 3 * time.Minute, "ACTIVE", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := setup(t)
			j := automationJourney(t, f, tc.automatic, 0)
			now := time.Now().UTC().Add(-time.Second)
			for i := range 3 {
				automationPoint(t, f, j, now.Add(-time.Duration(i)*time.Minute), tc.mock, tc.lag)
			}
			require.NoError(t, f.store.Maintain(f.ctx))
			require.NoError(t, f.store.Maintain(f.ctx))
			read, err := f.store.Get(f.ctx, f.viewer.ID, j.ID)
			require.NoError(t, err)
			require.Equal(t, tc.state, read.State)
			require.Equal(t, tc.source, read.ArrivalSource)
			if tc.state == "ARRIVED" {
				var count int
				require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM audit_log WHERE action='journey.arrived'`).Scan(&count))
				require.Equal(t, 1, count)
			}
		})
	}
}

func TestPGStaleEpisodesNotifyOwnerAndSelectedViewerAndRecoverBeforeDispatch(t *testing.T) {
	f := setup(t)
	j := automationJourney(t, f, false, 5)
	tokens := sos.NewStore(f.pool)
	for _, p := range []Principal{f.p, f.viewerP} {
		require.NoError(t, tokens.RegisterPushToken(f.ctx, sos.Principal{UserID: p.UserID, SessionID: p.SessionID, DeviceID: p.DeviceID}, "fake-"+p.UserID.String()))
	}
	require.NoError(t, f.store.Maintain(f.ctx))
	require.NoError(t, f.store.Maintain(f.ctx))
	read, err := f.store.Get(f.ctx, f.owner.ID, j.ID)
	require.NoError(t, err)
	require.NotNil(t, read.StaleSince)
	require.Equal(t, 1, read.StaleEpisode)
	var count int
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM journey_notification_outbox WHERE journey_id=$1 AND kind='STALE'`, j.ID).Scan(&count))
	require.Equal(t, 2, count, "only owner and selected viewer; ordinary grant to other is insufficient")
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM audit_log WHERE action='journey.stale'`).Scan(&count))
	require.Equal(t, 1, count, "repeated maintenance cannot create duplicate episode audit")
	_, err = f.pool.Exec(f.ctx, `UPDATE journey_notification_outbox SET state='SUPPRESSED' WHERE journey_id=$1 AND kind='ACTIVE'`, j.ID)
	require.NoError(t, err)
	gateway := &fakeGateway{}
	for range 2 {
		worked, dispatchErr := f.store.DispatchOne(f.ctx, gateway)
		require.NoError(t, dispatchErr)
		require.True(t, worked)
	}
	require.Len(t, gateway.signals, 2)
	for _, signal := range gateway.signals {
		require.Equal(t, "JOURNEY_STALE", signal.Kind)
		require.Equal(t, 1, signal.Episode)
	}
	// A fresh sample recovers the episode; a subsequent genuine gap can notify again.
	automationPoint(t, f, j, time.Now().UTC().Add(-time.Second), false, 0)
	require.NoError(t, f.store.Maintain(f.ctx))
	read, err = f.store.Get(f.ctx, f.owner.ID, j.ID)
	require.NoError(t, err)
	require.Nil(t, read.StaleSince)
	_, err = f.pool.Exec(f.ctx, `UPDATE journeys SET last_live_update_at=now()-interval '6 minutes' WHERE id=$1`, j.ID)
	require.NoError(t, err)
	_, err = f.pool.Exec(f.ctx, `DELETE FROM location_points WHERE user_id=$1`, f.owner.ID)
	require.NoError(t, err)
	require.NoError(t, f.store.Maintain(f.ctx))
	read, err = f.store.Get(f.ctx, f.owner.ID, j.ID)
	require.NoError(t, err)
	require.Equal(t, 2, read.StaleEpisode)
	automationPoint(t, f, j, time.Now().UTC().Add(-time.Second), false, 0)
	// Dispatch must commit recovery even when its selected queued job is suppressed by evaluation.
	worked, err := f.store.DispatchOne(f.ctx, gateway)
	require.NoError(t, err)
	require.True(t, worked)
	require.Len(t, gateway.signals, 2)
	read, err = f.store.Get(f.ctx, f.owner.ID, j.ID)
	require.NoError(t, err)
	require.Nil(t, read.StaleSince)
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM journey_notification_outbox WHERE journey_id=$1 AND kind='STALE' AND episode=2 AND state='SUPPRESSED'`, j.ID).Scan(&count))
	require.Equal(t, 2, count)
}

func TestPGStaleDispatchRechecksPrivacyAndDoesNotRestoreOldSelections(t *testing.T) {
	for _, change := range []string{"recipient", "consent", "membership", "history", "generation"} {
		t.Run(change, func(t *testing.T) {
			f := setup(t)
			j := automationJourney(t, f, false, 5)
			tokens := sos.NewStore(f.pool)
			for _, p := range []Principal{f.p, f.viewerP} {
				require.NoError(t, tokens.RegisterPushToken(f.ctx, sos.Principal{UserID: p.UserID, SessionID: p.SessionID, DeviceID: p.DeviceID}, "fake-"+p.UserID.String()))
			}
			require.NoError(t, f.store.Maintain(f.ctx))
			_, err := f.pool.Exec(f.ctx, `UPDATE journey_notification_outbox SET state='SUPPRESSED' WHERE journey_id=$1 AND kind='ACTIVE'`, j.ID)
			require.NoError(t, err)
			switch change {
			case "recipient":
				require.NoError(t, f.store.RevokeRecipient(f.ctx, f.p, j.ID, f.viewer.ID))
			case "consent":
				_, err = f.auth.SetLocationSharing(f.ctx, f.owner.ID, false)
				require.NoError(t, err)
			case "membership":
				require.NoError(t, f.circles.Leave(f.ctx, f.viewer.ID, f.circle, false))
			case "history":
				require.NoError(t, locations.NewPGStore(f.pool).DeleteHistory(f.ctx, f.owner.ID, nil, time.Now()))
			case "generation":
				_, err = f.pool.Exec(f.ctx, `UPDATE users SET location_capture_generation=location_capture_generation+1 WHERE id=$1`, f.owner.ID)
				require.NoError(t, err)
			}
			gateway := &fakeGateway{}
			for range 5 {
				worked, dispatchErr := f.store.DispatchOne(f.ctx, gateway)
				require.NoError(t, dispatchErr)
				if !worked {
					break
				}
			}
			require.Empty(t, gateway.signals, "stale reminders must not escape completed privacy mutations")
			if change == "recipient" {
				_, err = f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
				require.NoError(t, err)
				_, err = f.store.Get(f.ctx, f.viewer.ID, j.ID)
				require.ErrorIs(t, err, ErrNotFound)
			}
		})
	}
}

func TestPGAutomationCheckpointAndExpiryDoNotRestoreOldEvidence(t *testing.T) {
	f := setup(t)
	j := automationJourney(t, f, true, 5)
	require.NoError(t, f.store.Maintain(f.ctx))
	_, err := f.pool.Exec(f.ctx, `UPDATE users SET location_capture_generation=location_capture_generation+1 WHERE id=$1`, f.owner.ID)
	require.NoError(t, err)
	read, err := f.store.Get(f.ctx, f.owner.ID, j.ID)
	require.NoError(t, err)
	require.Nil(t, read.StaleSince, "old generation metadata is hidden before maintenance")
	require.NoError(t, f.store.Maintain(f.ctx))
	read, err = f.store.Get(f.ctx, f.owner.ID, j.ID)
	require.NoError(t, err)
	require.Nil(t, read.StaleSince)
	_, err = f.pool.Exec(f.ctx, `UPDATE journeys SET expires_at=now()-interval '1 second' WHERE id=$1`, j.ID)
	require.NoError(t, err)
	now := time.Now().UTC().Add(-time.Second)
	for i := range 3 {
		automationPoint(t, f, j, now.Add(-time.Duration(i)*time.Minute), false, 0)
	}
	require.NoError(t, f.store.Maintain(f.ctx))
	read, err = f.store.Get(f.ctx, f.owner.ID, j.ID)
	require.NoError(t, err)
	require.Equal(t, "EXPIRED", read.State)
	require.Empty(t, read.ArrivalSource)
}
