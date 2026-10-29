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
	generation, err := f.ingest.CaptureGeneration(f.ctx, f.owner.ID)
	require.NoError(t, err)
	_, err = f.ingest.Ingest(f.ctx, locations.Principal{
		UserID: f.owner.ID, SessionID: f.ownerSess.ID, DeviceID: f.ownerSess.DeviceID,
	}, []locations.Event{{
		EventID: uuid.Must(uuid.NewV7()), ClientDeviceID: "device-owner-0001", RecordedAt: at.UTC(),
		CaptureGeneration: generation,
		Latitude:          10.7769, Longitude: 106.7009, HorizontalAccuracyMeters: 18,
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

func TestPGConcurrentGrantAndRevokeHaveOneSerializedOutcome(t *testing.T) {
	f := newAccessFixture(t)
	lockOwner, err := f.pool.Begin(f.ctx)
	require.NoError(t, err)
	require.NoError(t, lockOwner.QueryRow(f.ctx, `SELECT id FROM users WHERE id = $1 FOR UPDATE`, f.owner.ID).Scan(new(uuid.UUID)))

	grantDone := make(chan error, 1)
	revokeDone := make(chan error, 1)
	go func() {
		_, grantErr := f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
		grantDone <- grantErr
	}()
	go func() {
		revokeDone <- f.access.Revoke(f.ctx, f.owner.ID, f.viewer.ID)
	}()
	waitForAudienceLockWaits(t, f.pool, 2)
	require.NoError(t, lockOwner.Commit(f.ctx))
	require.NoError(t, <-grantDone)
	require.NoError(t, <-revokeDone)

	var active, createdAudit, revokedAudit int
	require.NoError(t, f.pool.QueryRow(f.ctx,
		`SELECT count(*) FROM location_audience_grants WHERE owner_user_id = $1 AND viewer_user_id = $2 AND revoked_at IS NULL`,
		f.owner.ID, f.viewer.ID).Scan(&active))
	require.NoError(t, f.pool.QueryRow(f.ctx,
		`SELECT count(*) FROM audit_log WHERE actor_user_id = $1 AND target_user_id = $2 AND action = 'location.audience_grant.created'`,
		f.owner.ID, f.viewer.ID).Scan(&createdAudit))
	require.NoError(t, f.pool.QueryRow(f.ctx,
		`SELECT count(*) FROM audit_log WHERE actor_user_id = $1 AND target_user_id = $2 AND action = 'location.audience_grant.revoked'`,
		f.owner.ID, f.viewer.ID).Scan(&revokedAudit))
	require.Equal(t, 1, createdAudit)
	if active == 1 {
		require.Zero(t, revokedAudit, "grant won the row-lock order")
	} else {
		require.Zero(t, active)
		require.Equal(t, 1, revokedAudit, "revoke won after the grant committed")
	}
}

func TestPGConcurrentGrantRequestsCreateOneGrantAndAudit(t *testing.T) {
	f := newAccessFixture(t)
	lockOwner, err := f.pool.Begin(f.ctx)
	require.NoError(t, err)
	require.NoError(t, lockOwner.QueryRow(f.ctx, `SELECT id FROM users WHERE id = $1 FOR UPDATE`, f.owner.ID).Scan(new(uuid.UUID)))

	results := make(chan error, 2)
	for range 2 {
		go func() {
			_, grantErr := f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
			results <- grantErr
		}()
	}
	waitForAudienceLockWaits(t, f.pool, 2)
	require.NoError(t, lockOwner.Commit(f.ctx))
	require.NoError(t, <-results)
	require.NoError(t, <-results)

	var active, createdAudit int
	require.NoError(t, f.pool.QueryRow(f.ctx,
		`SELECT count(*) FROM location_audience_grants WHERE owner_user_id = $1 AND viewer_user_id = $2 AND revoked_at IS NULL`,
		f.owner.ID, f.viewer.ID).Scan(&active))
	require.NoError(t, f.pool.QueryRow(f.ctx,
		`SELECT count(*) FROM audit_log WHERE actor_user_id = $1 AND target_user_id = $2 AND action = 'location.audience_grant.created'`,
		f.owner.ID, f.viewer.ID).Scan(&createdAudit))
	require.Equal(t, 1, active)
	require.Equal(t, 1, createdAudit)
}

func TestPGGrantRacingViewerLeaveNeverLeavesAccessAfterLeave(t *testing.T) {
	f := newAccessFixture(t)
	lockViewer, err := f.pool.Begin(f.ctx)
	require.NoError(t, err)
	require.NoError(t, lockViewer.QueryRow(f.ctx, `SELECT id FROM users WHERE id = $1 FOR UPDATE`, f.viewer.ID).Scan(new(uuid.UUID)))

	grantDone := make(chan error, 1)
	leaveDone := make(chan error, 1)
	go func() {
		_, grantErr := f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
		grantDone <- grantErr
	}()
	go func() {
		leaveDone <- f.circles.Leave(f.ctx, f.viewer.ID, f.circleID, false)
	}()
	waitForAudienceLockWaits(t, f.pool, 2)
	require.NoError(t, lockViewer.Commit(f.ctx))
	grantErr := <-grantDone
	if grantErr != nil {
		require.ErrorIs(t, grantErr, ErrNoMembership)
	}
	require.NoError(t, <-leaveDone)

	grants, err := f.access.Audience(f.ctx, f.owner.ID)
	require.NoError(t, err)
	require.Empty(t, grants)
	visible, err := f.access.Latest(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Empty(t, visible)
}

func TestPGRevokeCommitBlocksSubsequentLatestRead(t *testing.T) {
	f := newAccessFixture(t)
	grant, err := f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	f.access.now = func() time.Time { return grant.VisibleFrom.Add(10 * time.Second) }
	f.record(t, grant.VisibleFrom.Add(time.Second))
	visible, err := f.access.Latest(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Len(t, visible, 1)
	require.NotNil(t, visible[0].Latitude)

	require.NoError(t, f.access.Revoke(f.ctx, f.owner.ID, f.viewer.ID))
	visible, err = f.access.Latest(f.ctx, f.viewer.ID)
	require.NoError(t, err)
	require.Empty(t, visible, "reads started after revoke commit must see current authorization")
}

func waitForAudienceLockWaits(t *testing.T, pool *pgxpool.Pool, minimum int) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		var waiting int
		err := pool.QueryRow(context.Background(),
			`SELECT count(*) FROM pg_stat_activity
			  WHERE datname = current_database() AND wait_event_type = 'Lock'`).Scan(&waiting)
		require.NoError(t, err)
		if waiting >= minimum {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %d PostgreSQL lock waiters", minimum)
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

func TestPGHistoryRequiresCurrentGrantAndPaginatesTies(t *testing.T) {
	f := newAccessFixture(t)
	f.record(t, time.Now().UTC())
	from, to := time.Now().UTC().Add(-time.Hour), time.Now().UTC().Add(time.Hour)
	page, err := f.access.History(f.ctx, f.viewer.ID, f.owner.ID, from, to, 1, "")
	require.ErrorIs(t, err, ErrNotFound)
	grant, err := f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	for i := 0; i < 3; i++ {
		_, err = f.pool.Exec(f.ctx, `INSERT INTO location_points (event_id,user_id,device_id,recorded_at,received_at,latitude,longitude,geom,horizontal_accuracy_m,provider,schema_version,sharing_epoch_id,capture_generation)
   VALUES ($1,$2,$3,$4,$4,10,106,ST_SetSRID(ST_MakePoint(106,10),4326)::geography,10,'fused',1,
   (SELECT id FROM location_sharing_epochs WHERE owner_user_id=$2 AND ended_at IS NULL), (SELECT location_capture_generation FROM users WHERE id=$2))`, uuid.New(), f.owner.ID, f.ownerSess.DeviceID, grant.VisibleFrom)
		require.NoError(t, err)
	}
	seen := map[uuid.UUID]bool{}
	cursor := ""
	for {
		page, err = f.access.History(f.ctx, f.viewer.ID, f.owner.ID, from, to, 1, cursor)
		require.NoError(t, err)
		for _, point := range page.Points {
			require.False(t, seen[point.EventID])
			seen[point.EventID] = true
		}
		if page.NextCursor == nil {
			break
		}
		cursor = *page.NextCursor
	}
	require.Len(t, seen, 3)
	require.NoError(t, f.access.Revoke(f.ctx, f.owner.ID, f.viewer.ID))
	page, err = f.access.History(f.ctx, f.viewer.ID, f.owner.ID, from, to, 10, "")
	require.ErrorIs(t, err, ErrNotFound)
	_, err = f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	page, err = f.access.History(f.ctx, f.viewer.ID, f.owner.ID, from, to, 10, "")
	require.NoError(t, err)
	require.Empty(t, page.Points, "a fresh grant must not restore pre-grant history")
	_, err = f.auth.SetLocationSharing(f.ctx, f.owner.ID, false)
	require.NoError(t, err)
	_, err = f.access.History(f.ctx, f.viewer.ID, f.owner.ID, from, to, 10, "")
	require.ErrorIs(t, err, ErrNotFound)
}

func TestPGGrantRejectsPreviouslyCapturedOfflineGeneration(t *testing.T) {
	f := newAccessFixture(t)
	oldGeneration, err := f.ingest.CaptureGeneration(f.ctx, f.owner.ID)
	require.NoError(t, err)
	grant, err := f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	principal := locations.Principal{UserID: f.owner.ID, SessionID: f.ownerSess.ID, DeviceID: f.ownerSess.DeviceID}
	event := locations.Event{EventID: uuid.New(), ClientDeviceID: "device-owner-0001", RecordedAt: grant.VisibleFrom,
		CaptureGeneration: oldGeneration, Latitude: 10, Longitude: 106, HorizontalAccuracyMeters: 10, Provider: "fused", SchemaVersion: 1}
	_, err = f.ingest.Ingest(f.ctx, principal, []locations.Event{event})
	require.ErrorIs(t, err, locations.ErrCaptureGeneration)
	event.EventID = uuid.New()
	event.CaptureGeneration, err = f.ingest.CaptureGeneration(f.ctx, f.owner.ID)
	require.NoError(t, err)
	_, err = f.ingest.Ingest(f.ctx, principal, []locations.Event{event})
	require.NoError(t, err)
	page, err := f.access.History(f.ctx, f.viewer.ID, f.owner.ID, grant.VisibleFrom.Add(-time.Hour), grant.VisibleFrom.Add(time.Hour), 10, "")
	require.NoError(t, err)
	require.Len(t, page.Points, 1)
}

func TestPGGrantWaitsForCaptureAndNeverExposesPriorGeneration(t *testing.T) {
	f := newAccessFixture(t)
	tx, err := f.pool.Begin(f.ctx)
	require.NoError(t, err)
	defer func() { _ = tx.Rollback(f.ctx) }()
	var generation int64
	require.NoError(t, tx.QueryRow(f.ctx, `SELECT location_capture_generation FROM users WHERE id=$1 FOR SHARE`, f.owner.ID).Scan(&generation))
	grantDone := make(chan error, 1)
	go func() { _, err := f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID); grantDone <- err }()
	waitForAudienceLockWaits(t, f.pool, 1)
	now := time.Now().UTC()
	pointID := uuid.New()
	_, err = tx.Exec(f.ctx, `INSERT INTO location_points (event_id,user_id,device_id,recorded_at,received_at,latitude,longitude,geom,horizontal_accuracy_m,provider,schema_version,sharing_epoch_id,capture_generation)
	  VALUES ($1,$2,$3,$4,$4,10,106,ST_SetSRID(ST_MakePoint(106,10),4326)::geography,10,'fused',1,
	  (SELECT id FROM location_sharing_epochs WHERE owner_user_id=$2 AND ended_at IS NULL),$5)`, pointID, f.owner.ID, f.ownerSess.DeviceID, now.Add(time.Second), generation)
	require.NoError(t, err)
	require.NoError(t, tx.Commit(f.ctx))
	require.NoError(t, <-grantDone)
	_, err = f.pool.Exec(f.ctx, `UPDATE location_points SET recorded_at=(SELECT visible_from FROM location_audience_grants WHERE owner_user_id=$2 AND viewer_user_id=$3 AND revoked_at IS NULL), received_at=(SELECT visible_from FROM location_audience_grants WHERE owner_user_id=$2 AND viewer_user_id=$3 AND revoked_at IS NULL) WHERE event_id=$1`, pointID, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	page, err := f.access.History(f.ctx, f.viewer.ID, f.owner.ID, now.Add(-time.Hour), now.Add(time.Hour), 10, "")
	require.NoError(t, err)
	require.Empty(t, page.Points)
}

func TestPGHistorySeparatesRouteAcrossDeletedInterval(t *testing.T) {
	f := newAccessFixture(t)
	now := time.Now().UTC()
	f.record(t, now.Add(-2*time.Minute))
	f.record(t, now.Add(-time.Minute))
	from, to := now.Add(-90*time.Second), now.Add(-80*time.Second)
	require.NoError(t, f.ingest.DeleteHistory(f.ctx, f.owner.ID, &from, to))
	page, err := f.access.History(f.ctx, f.owner.ID, f.owner.ID, now.Add(-time.Hour), now.Add(time.Minute), 10, "")
	require.NoError(t, err)
	require.Len(t, page.Points, 2)
	require.NotEqual(t, page.Points[0].SegmentID, page.Points[1].SegmentID)
}

func TestPGHistoryRejoinAndOldUnassociatedPointStayHidden(t *testing.T) {
	f := newAccessFixture(t)
	now := time.Now().UTC()
	_, err := f.pool.Exec(f.ctx, `INSERT INTO location_points
 (event_id,user_id,device_id,recorded_at,received_at,latitude,longitude,geom,horizontal_accuracy_m,provider,schema_version,sharing_epoch_id)
 VALUES ($1,$2,$3,$4,$4,10,106,ST_SetSRID(ST_MakePoint(106,10),4326)::geography,10,'fused',1,NULL)`, uuid.New(), f.owner.ID, f.ownerSess.DeviceID, now)
	require.NoError(t, err)
	_, err = f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	require.NoError(t, f.circles.Leave(f.ctx, f.viewer.ID, f.circleID, false))
	invite, err := f.circles.CreateInvite(f.ctx, f.owner.ID, f.circleID, nil)
	require.NoError(t, err)
	_, _, err = f.circles.Redeem(f.ctx, f.viewer.ID, invite.Code)
	require.NoError(t, err)
	_, err = f.access.History(f.ctx, f.viewer.ID, f.owner.ID, now.Add(-time.Hour), now.Add(time.Hour), 20, "")
	require.ErrorIs(t, err, ErrNotFound)
	_, err = f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	page, err := f.access.History(f.ctx, f.viewer.ID, f.owner.ID, now.Add(-time.Hour), now.Add(time.Hour), 20, "")
	require.NoError(t, err)
	require.Empty(t, page.Points)
	_, err = f.access.History(f.ctx, uuid.New(), f.owner.ID, now.Add(-time.Hour), now.Add(time.Hour), 20, "")
	require.ErrorIs(t, err, ErrNotFound)
}

func TestPGOwnerHistoryRetentionReductionDoesNotRestoreOnIncrease(t *testing.T) {
	f := newAccessFixture(t)
	now := time.Now().UTC()
	f.record(t, now.Add(-48*time.Hour))
	f.record(t, now.Add(-time.Hour))
	from, to := now.Add(-48*time.Hour-time.Minute), now.Add(-24*time.Hour)
	page, err := f.access.History(f.ctx, f.owner.ID, f.owner.ID, from, to, 10, "")
	require.NoError(t, err)
	require.Len(t, page.Points, 1)
	require.NoError(t, f.ingest.SetRetentionDays(f.ctx, f.owner.ID, 1))
	page, err = f.access.History(f.ctx, f.owner.ID, f.owner.ID, from, to, 10, "")
	require.NoError(t, err)
	require.Empty(t, page.Points)
	require.NoError(t, f.ingest.SetRetentionDays(f.ctx, f.owner.ID, 30))
	page, err = f.access.History(f.ctx, f.owner.ID, f.owner.ID, from, to, 10, "")
	require.NoError(t, err)
	require.Empty(t, page.Points)
}

func TestPGHistoryReadAfterDeletionCannotSeeRemovedPoint(t *testing.T) {
	f := newAccessFixture(t)
	grant, err := f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	f.record(t, grant.VisibleFrom)
	from, to := grant.VisibleFrom.Add(-time.Hour), grant.VisibleFrom.Add(time.Hour)
	page, err := f.access.History(f.ctx, f.viewer.ID, f.owner.ID, from, to, 10, "")
	require.NoError(t, err)
	require.Len(t, page.Points, 1)
	require.NoError(t, f.ingest.DeleteHistory(f.ctx, f.owner.ID, nil, time.Now().UTC()))
	page, err = f.access.History(f.ctx, f.viewer.ID, f.owner.ID, from, to, 10, "")
	require.NoError(t, err)
	require.Empty(t, page.Points)
}

func TestPGHistorySnapshotExcludesBackfillUntilRefresh(t *testing.T) {
	f := newAccessFixture(t)
	now := time.Now().UTC()
	f.record(t, now.Add(-2*time.Minute))
	f.record(t, now.Add(-time.Minute))
	from, to := now.Add(-time.Hour), now.Add(time.Minute)
	first, err := f.access.History(f.ctx, f.owner.ID, f.owner.ID, from, to, 1, "")
	require.NoError(t, err)
	require.Len(t, first.Points, 1)
	require.NotNil(t, first.NextCursor)
	f.record(t, now.Add(-90*time.Second))
	second, err := f.access.History(f.ctx, f.owner.ID, f.owner.ID, from, to, 1, *first.NextCursor)
	require.NoError(t, err)
	require.Len(t, second.Points, 1)
	require.Nil(t, second.NextCursor)
	require.Equal(t, now.Add(-time.Minute).Truncate(time.Microsecond), second.Points[0].RecordedAt)
	refreshed, err := f.access.History(f.ctx, f.owner.ID, f.owner.ID, from, to, 10, "")
	require.NoError(t, err)
	require.Len(t, refreshed.Points, 3)
}

func TestPGHistorySnapshotWaitsForInFlightOwnerWrite(t *testing.T) {
	f := newAccessFixture(t)
	tx, err := f.pool.Begin(f.ctx)
	require.NoError(t, err)
	defer func() { _ = tx.Rollback(f.ctx) }()
	var generation int64
	require.NoError(t, tx.QueryRow(f.ctx, `SELECT location_history_generation FROM users WHERE id=$1 FOR SHARE`, f.owner.ID).Scan(&generation))
	now := time.Now().UTC()
	_, err = tx.Exec(f.ctx, `INSERT INTO location_points (event_id,user_id,device_id,recorded_at,received_at,latitude,longitude,geom,horizontal_accuracy_m,provider,schema_version,history_generation)
 VALUES ($1,$2,$3,$4,$4,10,106,ST_SetSRID(ST_MakePoint(106,10),4326)::geography,10,'fused',1,$5)`, uuid.New(), f.owner.ID, f.ownerSess.DeviceID, now, generation)
	require.NoError(t, err)
	result := make(chan struct {
		page HistoryPage
		err  error
	}, 1)
	go func() {
		page, err := f.access.History(f.ctx, f.owner.ID, f.owner.ID, now.Add(-time.Hour), now.Add(time.Hour), 10, "")
		result <- struct {
			page HistoryPage
			err  error
		}{page, err}
	}()
	waitForAudienceLockWaits(t, f.pool, 1)
	require.NoError(t, tx.Commit(f.ctx))
	value := <-result
	require.NoError(t, value.err)
	require.Len(t, value.page.Points, 1)
}

func TestPGHistoryDeletionWhileConsentOffDoesNotRestoreViewer(t *testing.T) {
	f := newAccessFixture(t)
	_, err := f.access.Grant(f.ctx, f.owner.ID, f.viewer.ID)
	require.NoError(t, err)
	now := time.Now().UTC()
	f.record(t, now)
	_, err = f.auth.SetLocationSharing(f.ctx, f.owner.ID, false)
	require.NoError(t, err)
	require.NoError(t, f.ingest.DeleteHistory(f.ctx, f.owner.ID, nil, time.Now().UTC()))
	_, err = f.access.History(f.ctx, f.viewer.ID, f.owner.ID, now.Add(-time.Hour), now.Add(time.Hour), 10, "")
	require.ErrorIs(t, err, ErrNotFound)
	_, err = f.auth.SetLocationSharing(f.ctx, f.owner.ID, true)
	require.NoError(t, err)
	_, err = f.access.History(f.ctx, f.viewer.ID, f.owner.ID, now.Add(-time.Hour), now.Add(time.Hour), 10, "")
	require.ErrorIs(t, err, ErrNotFound)
}

func TestPGHistoryMarksDelayedBackfillAndPreservesCaptureOrder(t *testing.T) {
	f := newAccessFixture(t)
	now := time.Now().UTC()
	f.record(t, now.Add(-6*time.Minute))
	f.record(t, now.Add(-2*time.Minute))
	page, err := f.access.History(f.ctx, f.owner.ID, f.owner.ID, now.Add(-time.Hour), now.Add(time.Minute), 10, "")
	require.NoError(t, err)
	require.Len(t, page.Points, 2)
	require.True(t, page.Points[0].Backfill)
	require.False(t, page.Points[1].Backfill)
	require.True(t, page.Points[0].RecordedAt.Before(page.Points[1].RecordedAt))
}

func TestPGHistoryHonorsGlobalWatermarkBeforePhysicalCleanup(t *testing.T) {
	f := newAccessFixture(t)
	now := time.Now().UTC()
	f.record(t, now.Add(-48*time.Hour))
	_, err := f.pool.Exec(f.ctx, `UPDATE location_retention_watermark SET cutoff=$1 WHERE singleton`, now.Add(-24*time.Hour))
	require.NoError(t, err)
	page, err := f.access.History(f.ctx, f.owner.ID, f.owner.ID, now.Add(-49*time.Hour), now.Add(-47*time.Hour), 10, "")
	require.NoError(t, err)
	require.Empty(t, page.Points)
	var stored int
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM location_points WHERE user_id=$1`, f.owner.ID).Scan(&stored))
	require.Equal(t, 1, stored, "the read must enforce the watermark before physical cleanup")
}

func TestPGHistoryIndexPlan(t *testing.T) {
	f := newAccessFixture(t)
	_, err := f.pool.Exec(f.ctx, `INSERT INTO location_points (event_id,user_id,device_id,recorded_at,received_at,latitude,longitude,geom,horizontal_accuracy_m,provider,schema_version)
 SELECT uuid_generate_v4(), $1, $2, now() - n * interval '1 minute', now(), 10, 106,
 ST_SetSRID(ST_MakePoint(106,10),4326)::geography, 10, 'fused', 1 FROM generate_series(1, 5000) n`, f.owner.ID, f.ownerSess.DeviceID)
	require.NoError(t, err)
	_, err = f.pool.Exec(f.ctx, `INSERT INTO location_points (event_id,user_id,device_id,recorded_at,received_at,latitude,longitude,geom,horizontal_accuracy_m,provider,schema_version)
 SELECT uuid_generate_v4(), $1, $2, now() - (n % 5000) * interval '1 minute', now(), 10, 106,
 ST_SetSRID(ST_MakePoint(106,10),4326)::geography, 10, 'fused', 1 FROM generate_series(1, 10000) n`, f.viewer.ID, f.viewSess.DeviceID)
	require.NoError(t, err)
	_, err = f.pool.Exec(f.ctx, `ANALYZE location_points`)
	require.NoError(t, err)
	var plan []byte
	err = f.pool.QueryRow(f.ctx, `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT event_id, recorded_at, received_at
 FROM location_points WHERE user_id=$1 AND recorded_at >= now() - interval '1 hour'
 ORDER BY recorded_at, received_at, event_id LIMIT 100`, f.owner.ID).Scan(&plan)
	require.NoError(t, err)
	require.Contains(t, string(plan), "Execution Time")
	t.Logf("synthetic history plan: %s", plan)
}
