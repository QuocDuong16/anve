package places

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/locations"
	"github.com/QuocDuong16/anve/server/internal/notifications"
	"github.com/QuocDuong16/anve/server/internal/sos"
)

type recordingGateway struct{ signals []notifications.Signal }

func (g *recordingGateway) Send(_ context.Context, _ string, signal notifications.Signal) error {
	g.signals = append(g.signals, signal)
	return nil
}

type blockingGateway struct {
	started chan struct{}
	release chan struct{}
	count   chan notifications.Signal
}

func (g *blockingGateway) Send(_ context.Context, _ string, signal notifications.Signal) error {
	g.count <- signal
	close(g.started)
	<-g.release
	return nil
}

func TestPGPlacePushRequiresOriginalAuthorization(t *testing.T) {
	for _, scenario := range []string{"authorized", "place_access", "location_grant", "monitor", "expired", "concurrent"} {
		t.Run(scenario, func(t *testing.T) {
			f := newPlaceFixture(t)
			p := f.create(t)
			require.NoError(t, f.places.SetAccess(f.ctx, f.owner.ID, p.ID, f.viewer.ID, true))
			_, err := f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
			require.NoError(t, err)
			_, err = f.places.ConfigureMonitor(f.ctx, f.owner.ID, p.ID, []uuid.UUID{f.viewer.ID})
			require.NoError(t, err)
			base := time.Now().UTC().Add(-9 * time.Minute).Truncate(time.Second)
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
			for i, longitude := range []float64{106.004, 106.004, 106, 106} {
				batch = append(batch, locations.Event{EventID: uuid.New(), ClientDeviceID: "place-owner-device", CaptureGeneration: generation, RecordedAt: base.Add(time.Duration(i) * 2 * time.Minute), Latitude: 10, Longitude: longitude, HorizontalAccuracyMeters: 5, Provider: "fused", SchemaVersion: 1})
			}
			_, err = f.locations.Ingest(f.ctx, principal, batch)
			require.NoError(t, err)
			for range batch {
				processed, err := f.places.ProcessOne(f.ctx)
				require.NoError(t, err)
				require.True(t, processed)
			}
			store := sos.NewStore(f.pool)
			require.NoError(t, store.RegisterPushToken(f.ctx, sos.Principal{UserID: f.viewer.ID, SessionID: f.viewerSession.ID, DeviceID: f.viewerSession.DeviceID}, "fake-token"))
			switch scenario {
			case "place_access":
				require.NoError(t, f.places.SetAccess(f.ctx, f.owner.ID, p.ID, f.viewer.ID, false))
				require.NoError(t, f.places.SetAccess(f.ctx, f.owner.ID, p.ID, f.viewer.ID, true))
			case "location_grant":
				require.NoError(t, f.access.Revoke(f.ctx, f.owner.ID, f.viewer.ID))
				_, err = f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
				require.NoError(t, err)
			case "monitor":
				require.NoError(t, f.places.StopMonitor(f.ctx, f.owner.ID, p.ID))
			case "expired":
				_, err = f.pool.Exec(f.ctx, `UPDATE place_events SET processed_at=clock_timestamp()-interval '8 days'`)
				require.NoError(t, err)
			}
			if scenario == "concurrent" {
				gateway := &blockingGateway{started: make(chan struct{}), release: make(chan struct{}), count: make(chan notifications.Signal, 2)}
				results := make(chan error, 2)
				go func() { _, err := f.places.DispatchNotification(f.ctx, gateway); results <- err }()
				<-gateway.started
				go func() { _, err := f.places.DispatchNotification(f.ctx, gateway); results <- err }()
				close(gateway.release)
				require.NoError(t, <-results)
				require.NoError(t, <-results)
				require.Len(t, gateway.count, 1, "concurrent workers must not send the same event twice")
				return
			}
			gateway := &recordingGateway{}
			processed, err := f.places.DispatchNotification(f.ctx, gateway)
			require.NoError(t, err)
			require.True(t, processed)
			if scenario != "authorized" {
				require.Empty(t, gateway.signals)
			} else {
				require.Len(t, gateway.signals, 1)
				require.Equal(t, "PLACE", gateway.signals[0].Kind)
			}
		})
	}
}
