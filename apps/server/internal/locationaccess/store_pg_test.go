package locationaccess

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/circles"
	"github.com/QuocDuong16/anve/server/internal/locations"
	"github.com/QuocDuong16/anve/server/internal/testdb"
)

type accessFixture struct {
	ctx       context.Context
	pool      *pgxpool.Pool
	auth      *auth.Service
	circles   *circles.Service
	owner     auth.User
	ownerSess auth.Session
	viewer    auth.User
	viewSess  auth.Session
	circleID  uuid.UUID
	access    *Service
	ingest    *locations.Service
}

func newAccessFixture(t *testing.T) *accessFixture {
	t.Helper()
	ctx := context.Background()
	pool := testdb.Scratch(t)
	authService := auth.NewService(auth.NewPGStore(pool), nil, true)
	circleService := circles.NewService(circles.NewPGStore(pool))
	owner, ownerTokens, err := authService.DevSignIn(ctx, "owner@example.com", "Owner", auth.DeviceRegistration{
		ClientDeviceID: "device-owner-0001",
	})
	require.NoError(t, err)
	ownerSession, _, err := authService.Authenticate(ctx, ownerTokens.AccessToken)
	require.NoError(t, err)
	circle, _, err := circleService.Create(ctx, owner.ID, "Gia đình")
	require.NoError(t, err)
	viewer, viewerTokens, err := authService.DevSignIn(ctx, "viewer@example.com", "Viewer", auth.DeviceRegistration{
		ClientDeviceID: "device-viewer-0001",
	})
	require.NoError(t, err)
	viewerSession, _, err := authService.Authenticate(ctx, viewerTokens.AccessToken)
	require.NoError(t, err)
	invite, err := circleService.CreateInvite(ctx, owner.ID, circle.ID, nil)
	require.NoError(t, err)
	_, _, err = circleService.Redeem(ctx, viewer.ID, invite.Code)
	require.NoError(t, err)
	_, err = authService.SetLocationSharing(ctx, owner.ID, true)
	require.NoError(t, err)
	return &accessFixture{
		ctx: ctx, pool: pool, auth: authService, circles: circleService,
		owner: owner, ownerSess: ownerSession, viewer: viewer, viewSess: viewerSession,
		circleID: circle.ID,
		access:   NewService(NewPGStore(pool), 30*24*time.Hour),
		ingest:   locations.NewService(locations.NewPGStore(pool)),
	}
}

func (f *accessFixture) record(t *testing.T, at time.Time) {
	t.Helper()
	_, err := f.ingest.Ingest(f.ctx, locations.Principal{
		UserID: f.owner.ID, SessionID: f.ownerSess.ID, DeviceID: f.ownerSess.DeviceID,
	}, []locations.Event{{
		EventID: uuid.Must(uuid.NewV7()), ClientDeviceID: "device-owner-0001", RecordedAt: at.UTC(),
		Latitude: 10.7769, Longitude: 106.7009, HorizontalAccuracyMeters: 18,
		Provider: "fused", SchemaVersion: 1,
	}})
	require.NoError(t, err)
}

func TestPGLatestRequiresExplicitGrantAndGrantTime(t *testing.T) {
	f := newAccessFixture(t)
	epochStart := time.Now().UTC()
	f.record(t, epochStart.Add(5*time.Millisecond))

	locations, err := f.access.Latest(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Empty(t, locations)

	grant, err := f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	require.Equal(t, f.viewer.ID, grant.ViewerID)
	f.access.now = func() time.Time { return grant.VisibleFrom.Add(5 * time.Second) }
	locations, err = f.access.Latest(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Len(t, locations, 1)
	require.Equal(t, "UNKNOWN", locations[0].Confidence)
	require.Nil(t, locations[0].Latitude, "the pre-grant point must remain hidden")
	_, err = f.pool.Exec(f.ctx,
		`INSERT INTO location_points (
		 event_id, user_id, device_id, recorded_at, received_at, latitude, longitude, geom,
		 horizontal_accuracy_m, provider, schema_version, sharing_epoch_id
		) VALUES ($1,$2,$3,$4,$5,10.7769,106.7009,
		 ST_SetSRID(ST_MakePoint(106.7009,10.7769),4326)::geography,18,'fused',1,
		 (SELECT id FROM location_sharing_epochs WHERE owner_user_id = $2 AND ended_at IS NULL))`,
		uuid.Must(uuid.NewV7()), f.owner.ID, f.ownerSess.DeviceID,
		grant.VisibleFrom.Add(time.Second), grant.VisibleFrom.Add(-time.Second),
	)
	require.NoError(t, err)
	locations, err = f.access.Latest(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Len(t, locations, 1)
	require.Nil(t, locations[0].Latitude,
		"a future client timestamp cannot make a point received before grant visible")

	f.record(t, grant.VisibleFrom.Add(time.Second))
	locations, err = f.access.Latest(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Len(t, locations, 1)
	require.Equal(t, "FRESH", locations[0].Confidence)
	require.NotNil(t, locations[0].Latitude)
	require.InDelta(t, 10.7769, *locations[0].Latitude, 0.00001)

	require.NoError(t, f.access.Revoke(f.ctx, f.owner.ID, f.viewer.ID))
	locations, err = f.access.Latest(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Empty(t, locations)
}

func TestPGRejoinAndConsentEpochDoNotRestoreOldGrantOrPoints(t *testing.T) {
	f := newAccessFixture(t)
	firstGrant, err := f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	f.record(t, firstGrant.VisibleFrom.Add(-time.Second))

	require.NoError(t, f.circles.Leave(f.ctx, f.viewer.ID, f.circleID, false))
	locations, err := f.access.Latest(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Empty(t, locations)

	invite, err := f.circles.CreateInvite(f.ctx, f.owner.ID, f.circleID, nil)
	require.NoError(t, err)
	_, _, err = f.circles.Redeem(f.ctx, f.viewer.ID, invite.Code)
	require.NoError(t, err)
	_, err = f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	f.access.now = func() time.Time { return time.Now().UTC().Add(time.Second) }
	locations, err = f.access.Latest(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Len(t, locations, 1)
	require.Equal(t, "UNKNOWN", locations[0].Confidence)
	require.Nil(t, locations[0].Latitude, "rejoining and regranting cannot reveal old-epoch points")

	_, err = f.auth.SetLocationSharing(f.ctx, f.owner.ID, false)
	require.NoError(t, err)
	locations, err = f.access.Latest(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Empty(t, locations)

	_, err = f.auth.SetLocationSharing(f.ctx, f.owner.ID, true)
	require.NoError(t, err)
	locations, err = f.access.Latest(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Empty(t, locations, "consent re-enable must not restore old grants")
}

func TestPGM3PointsWithoutEpochStayInvisible(t *testing.T) {
	f := newAccessFixture(t)
	require.NoError(t, f.pool.QueryRow(f.ctx,
		`INSERT INTO location_points (
		 event_id, user_id, device_id, recorded_at, received_at, latitude, longitude, geom,
		 horizontal_accuracy_m, provider, schema_version, sharing_epoch_id
		) VALUES ($1,$2,$3,now(),now(),10,106,ST_SetSRID(ST_MakePoint(106,10),4326)::geography,10,'fused',1,NULL)
		 RETURNING event_id`, uuid.Must(uuid.NewV7()), f.owner.ID, f.ownerSess.DeviceID,
	).Scan(new(uuid.UUID)))
	_, err := f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	locations, err := f.access.Latest(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Len(t, locations, 1)
	require.Equal(t, "UNKNOWN", locations[0].Confidence)
	require.Nil(t, locations[0].Latitude)
}

func TestPGConsentOffRacesWithGrantFailClosed(t *testing.T) {
	f := newAccessFixture(t)
	var wg sync.WaitGroup
	start := make(chan struct{})
	grantErr := make(chan error, 1)
	offErr := make(chan error, 1)
	wg.Add(2)
	go func() {
		defer wg.Done()
		<-start
		_, err := f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
		grantErr <- err
	}()
	go func() {
		defer wg.Done()
		<-start
		_, err := f.auth.SetLocationSharing(f.ctx, f.owner.ID, false)
		offErr <- err
	}()
	close(start)
	wg.Wait()
	require.NoError(t, <-offErr)
	grantResult := <-grantErr
	if grantResult != nil {
		require.ErrorIs(t, grantResult, ErrConsentOff)
	}
	locations, err := f.access.Latest(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Empty(t, locations)
}

func TestPGDissolveClosesAudienceBeforeCircleCascade(t *testing.T) {
	f := newAccessFixture(t)
	_, err := f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)

	require.NoError(t, f.circles.Leave(f.ctx, f.owner.ID, f.circleID, true))
	visible, err := f.access.Latest(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Empty(t, visible)

	var closedGrantAudit, closedEpochAudit int
	require.NoError(t, f.pool.QueryRow(f.ctx,
		`SELECT count(*) FROM audit_log WHERE action = 'location.audience_grant.closed_membership'
		   AND detail->>'reason' = 'circle_dissolved' AND target_user_id = $1`, f.viewer.ID,
	).Scan(&closedGrantAudit))
	require.NoError(t, f.pool.QueryRow(f.ctx,
		`SELECT count(*) FROM audit_log WHERE action = 'location.sharing_epoch.closed'
		   AND detail->>'reason' = 'circle_dissolved' AND target_user_id = $1`, f.owner.ID,
	).Scan(&closedEpochAudit))
	require.Equal(t, 1, closedGrantAudit)
	require.Equal(t, 1, closedEpochAudit)
}

func TestPGTemporalConstraintsRejectPreWindowGrantAndPoint(t *testing.T) {
	f := newAccessFixture(t)
	grant, err := f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	var epochStart time.Time
	require.NoError(t, f.pool.QueryRow(f.ctx,
		`SELECT started_at FROM location_sharing_epochs
		  WHERE owner_user_id = $1 AND circle_id = $2 AND ended_at IS NULL`, f.owner.ID, f.circleID,
	).Scan(&epochStart))

	_, err = f.pool.Exec(f.ctx,
		`UPDATE location_audience_grants SET visible_from = $2 WHERE id = $1`,
		grant.ID, epochStart.Add(-time.Second),
	)
	var grantWindowError *pgconn.PgError
	require.ErrorAs(t, err, &grantWindowError)
	require.Equal(t, "location_audience_grant_starts_within_window", grantWindowError.ConstraintName)

	_, err = f.pool.Exec(f.ctx,
		`INSERT INTO location_points (
		 event_id, user_id, device_id, recorded_at, received_at, latitude, longitude, geom,
		 horizontal_accuracy_m, provider, schema_version, sharing_epoch_id
		) VALUES ($1,$2,$3,$4,now(),10,106,ST_SetSRID(ST_MakePoint(106,10),4326)::geography,10,'fused',1,
		 (SELECT id FROM location_sharing_epochs WHERE owner_user_id = $2 AND ended_at IS NULL))`,
		uuid.Must(uuid.NewV7()), f.owner.ID, f.ownerSess.DeviceID, epochStart.Add(-time.Second),
	)
	var pointEpochError *pgconn.PgError
	require.ErrorAs(t, err, &pointEpochError)
	require.Equal(t, "location_point_sharing_epoch_matches_owner_and_time", pointEpochError.ConstraintName)
}
