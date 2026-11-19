package batteryalerts_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/audit"
	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/batteryalerts"
	"github.com/QuocDuong16/anve/server/internal/circles"
	"github.com/QuocDuong16/anve/server/internal/journeys"
	"github.com/QuocDuong16/anve/server/internal/locationaccess"
	"github.com/QuocDuong16/anve/server/internal/locations"
	"github.com/QuocDuong16/anve/server/internal/notifications"
	"github.com/QuocDuong16/anve/server/internal/sos"
	"github.com/QuocDuong16/anve/server/internal/testdb"
)

type fakePush struct {
	tokens  []string
	signals []notifications.Signal
	err     error
}

func (f *fakePush) Send(_ context.Context, token string, sig notifications.Signal) error {
	f.tokens = append(f.tokens, token)
	f.signals = append(f.signals, sig)
	return f.err
}

type fixture struct {
	ctx                                context.Context
	pool                               *pgxpool.Pool
	auth                               *auth.Service
	circles                            *circles.Service
	access                             *locationaccess.Service
	journeys                           *journeys.Store
	sos                                *sos.Store
	locations                          *locations.Service
	store                              *batteryalerts.Store
	owner, viewerA, viewerB, nonViewer auth.User
	ownerSess                          auth.Session
	ownerP                             batteryalerts.Principal
	circleID                           uuid.UUID
}

func setupFixture(t *testing.T) *fixture {
	t.Helper()
	ctx := context.Background()
	pool := testdb.Scratch(t)
	a := auth.NewService(auth.NewPGStore(pool), nil, true)
	c := circles.NewService(circles.NewPGStore(pool))
	access := locationaccess.NewService(locationaccess.NewPGStore(pool), 30*24*time.Hour)
	jStore := journeys.NewStore(pool, 30)
	sStore := sos.NewStore(pool)
	lSvc := locations.NewService(locations.NewPGStore(pool))
	bStore := batteryalerts.NewStore(pool, 30)

	owner, ot, err := a.DevSignIn(ctx, "bat-owner@example.com", "Bé Minh", auth.DeviceRegistration{ClientDeviceID: "bat-owner-dev"})
	require.NoError(t, err)
	require.False(t, owner.LowBatteryAlertsEnabled, "low-battery alerts must default OFF")
	ownerSess, _, err := a.Authenticate(ctx, ot.AccessToken)
	require.NoError(t, err)

	viewerA, vat, err := a.DevSignIn(ctx, "bat-viewa@example.com", "Mẹ Lan", auth.DeviceRegistration{ClientDeviceID: "bat-viewa-dev"})
	require.NoError(t, err)
	vaSess, _, err := a.Authenticate(ctx, vat.AccessToken)
	require.NoError(t, err)

	viewerB, vbt, err := a.DevSignIn(ctx, "bat-viewb@example.com", "Bố Hải", auth.DeviceRegistration{ClientDeviceID: "bat-viewb-dev"})
	require.NoError(t, err)
	vbSess, _, err := a.Authenticate(ctx, vbt.AccessToken)
	require.NoError(t, err)

	nonViewer, _, err := a.DevSignIn(ctx, "bat-non@example.com", "Em Út", auth.DeviceRegistration{ClientDeviceID: "bat-non-dev"})
	require.NoError(t, err)

	circle, _, err := c.Create(ctx, owner.ID, "Gia đình")
	require.NoError(t, err)
	for _, u := range []auth.User{viewerA, viewerB, nonViewer} {
		inv, err := c.CreateInvite(ctx, owner.ID, circle.ID, nil)
		require.NoError(t, err)
		_, _, err = c.Redeem(ctx, u.ID, inv.Code)
		require.NoError(t, err)
	}
	require.NoError(t, sStore.RegisterPushToken(ctx, sos.Principal{UserID: viewerA.ID, SessionID: vaSess.ID, DeviceID: vaSess.DeviceID}, "token-viewer-a"))
	require.NoError(t, sStore.RegisterPushToken(ctx, sos.Principal{UserID: viewerB.ID, SessionID: vbSess.ID, DeviceID: vbSess.DeviceID}, "token-viewer-b"))

	return &fixture{
		ctx: ctx, pool: pool, auth: a, circles: c, access: access, journeys: jStore,
		sos: sStore, locations: lSvc, store: bStore,
		owner: owner, viewerA: viewerA, viewerB: viewerB, nonViewer: nonViewer,
		ownerSess: ownerSess,
		ownerP:    batteryalerts.Principal{UserID: owner.ID, SessionID: ownerSess.ID, DeviceID: ownerSess.DeviceID},
		circleID:  circle.ID,
	}
}

func (f *fixture) enableSharingAndAlerts(t *testing.T) {
	t.Helper()
	_, err := f.auth.SetLocationSharing(f.ctx, f.owner.ID, true)
	require.NoError(t, err)
	u, err := f.auth.SetLowBatteryAlertsAtVersion(f.ctx, f.owner.ID, true, 0)
	require.NoError(t, err)
	require.True(t, u.LowBatteryAlertsEnabled)
	require.Equal(t, int64(1), u.LowBatteryAlertsVersion)
	_, err = f.access.Grant(f.ctx, f.owner.ID, f.viewerA.ID)
	require.NoError(t, err)
	_, err = f.access.Grant(f.ctx, f.owner.ID, f.viewerB.ID)
	require.NoError(t, err)
}

func TestPGBatteryAlertOptInConsentIdempotencyAndCooldown(t *testing.T) {
	f := setupFixture(t)
	now := time.Now().UTC().Truncate(time.Microsecond)
	req := batteryalerts.CreateRequest{
		EventID:        uuid.New(),
		Threshold:      15,
		BatteryPercent: 15,
		RecordedAt:     now,
		Location: &batteryalerts.Location{
			Latitude:                 10.7769,
			Longitude:                106.7009,
			HorizontalAccuracyMeters: 16,
			RecordedAt:               now,
		},
	}

	// Consent OFF -> refused.
	_, _, err := f.store.Create(f.ctx, f.ownerP, req)
	require.ErrorIs(t, err, batteryalerts.ErrConsentRequired)

	// Consent ON, low-battery preference still default OFF -> refused.
	_, err = f.auth.SetLocationSharing(f.ctx, f.owner.ID, true)
	require.NoError(t, err)
	_, _, err = f.store.Create(f.ctx, f.ownerP, req)
	require.ErrorIs(t, err, batteryalerts.ErrPreferenceDisabled)

	// Enable opt-in preference -> CAS conflict tested too.
	_, err = f.auth.SetLowBatteryAlertsAtVersion(f.ctx, f.owner.ID, true, 99)
	require.ErrorIs(t, err, auth.ErrLowBatteryPreferenceVersionConflict)
	u, err := f.auth.SetLowBatteryAlertsAtVersion(f.ctx, f.owner.ID, true, 0)
	require.NoError(t, err)
	require.True(t, u.LowBatteryAlertsEnabled)

	_, err = f.access.Grant(f.ctx, f.owner.ID, f.viewerA.ID)
	require.NoError(t, err)
	_, err = f.access.Grant(f.ctx, f.owner.ID, f.viewerB.ID)
	require.NoError(t, err)

	// Capture timestamp after Grant so alerts fall inside visible_from >= Grant time.
	grantedAt := time.Now().UTC().Truncate(time.Microsecond)
	req.RecordedAt = grantedAt
	req.Location.RecordedAt = grantedAt

	// Create 15% alert.
	createdAlert, created, err := f.store.Create(f.ctx, f.ownerP, req)
	require.NoError(t, err)
	require.True(t, created)
	require.Equal(t, batteryalerts.ScopeSharing, createdAlert.Scope)
	require.Equal(t, 2, createdAlert.RecipientCount)
	require.NotNil(t, createdAlert.Location)

	// Exact retry -> idempotent (created=false), no duplicate audit.
	retryAlert, created, err := f.store.Create(f.ctx, f.ownerP, req)
	require.NoError(t, err)
	require.False(t, created)
	require.Equal(t, createdAlert.ID, retryAlert.ID)

	var auditCount int
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM audit_log WHERE action=$1`, audit.ActionLowBatteryAlertCreated).Scan(&auditCount))
	require.Equal(t, 1, auditCount)

	// Conflicting payload on same ID -> ErrConflict.
	conflictReq := req
	conflictReq.BatteryPercent = 14
	_, _, err = f.store.Create(f.ctx, f.ownerP, conflictReq)
	require.ErrorIs(t, err, batteryalerts.ErrConflict)

	// Distinct 15% alert within 30m cooldown -> ErrCooldown.
	dup15 := req
	dup15.EventID = uuid.New()
	_, _, err = f.store.Create(f.ctx, f.ownerP, dup15)
	require.ErrorIs(t, err, batteryalerts.ErrCooldown)

	// Different threshold (5%) IS allowed immediately!
	req5 := req
	req5.EventID = uuid.New()
	req5.Threshold = 5
	req5.BatteryPercent = 5
	alert5, created, err := f.store.Create(f.ctx, f.ownerP, req5)
	require.NoError(t, err)
	require.True(t, created)
	require.Equal(t, 5, alert5.Threshold)

	// Non-granted Circle member must not see either alert.
	_, err = f.store.Get(f.ctx, f.nonViewer.ID, req.EventID)
	require.ErrorIs(t, err, batteryalerts.ErrNotFound)

	// Granted viewers can read both alerts, and see recipientCount=1 (no peer leakage).
	readA, err := f.store.Get(f.ctx, f.viewerA.ID, req5.EventID)
	require.NoError(t, err)
	require.Equal(t, 1, readA.RecipientCount)
	require.Equal(t, "Bé Minh", readA.OwnerName)

	// Live Map Latest surfaces the latest unresolved alert (5%).
	latestA, err := f.access.Latest(f.ctx, f.viewerA.ID)
	require.NoError(t, err)
	require.Len(t, latestA, 1)
	require.NotNil(t, latestA[0].LowBatteryAlert)
	require.Equal(t, req5.EventID, latestA[0].LowBatteryAlert.ID)
	require.Equal(t, 5, latestA[0].LowBatteryAlert.Threshold)

	// Non-viewer sees no Live Map entry.
	latestNon, err := f.access.Latest(f.ctx, f.nonViewer.ID)
	require.NoError(t, err)
	require.Empty(t, latestNon)
}

func TestPGBatteryAlertJourneyNarrowingAndRevocation(t *testing.T) {
	f := setupFixture(t)
	f.enableSharingAndAlerts(t)

	// Active Safe Journey selects ONLY viewerA (not viewerB, even though viewerB has a location grant).
	jID := uuid.New()
	_, jCreated, err := f.journeys.Create(f.ctx, journeys.Principal{
		UserID: f.owner.ID, SessionID: f.ownerSess.ID, DeviceID: f.ownerSess.DeviceID,
	}, journeys.CreateRequest{
		JourneyID:       jID,
		PreparedAt:      time.Now().UTC(),
		DurationMinutes: 60,
		Destination:     journeys.Destination{Label: "Nhà", Latitude: 10.77, Longitude: 106.70},
		RecipientIDs:    []uuid.UUID{f.viewerA.ID},
	})
	require.NoError(t, err)
	require.True(t, jCreated)

	now := time.Now().UTC().Truncate(time.Microsecond)
	req := batteryalerts.CreateRequest{
		EventID:        uuid.New(),
		Threshold:      5,
		BatteryPercent: 5,
		RecordedAt:     now,
		Location: &batteryalerts.Location{
			Latitude:                 10.78,
			Longitude:                106.71,
			HorizontalAccuracyMeters: 12,
			RecordedAt:               now,
		},
	}
	alert, created, err := f.store.Create(f.ctx, f.ownerP, req)
	require.NoError(t, err)
	require.True(t, created)
	require.Equal(t, batteryalerts.ScopeJourney, alert.Scope)
	require.NotNil(t, alert.JourneyID)
	require.Equal(t, jID, *alert.JourneyID)
	require.Equal(t, 1, alert.RecipientCount)

	// viewerA sees alert on Get, Live Map, and Journey.
	_, err = f.store.Get(f.ctx, f.viewerA.ID, req.EventID)
	require.NoError(t, err)
	latestA, err := f.access.Latest(f.ctx, f.viewerA.ID)
	require.NoError(t, err)
	require.Len(t, latestA, 1)
	require.NotNil(t, latestA[0].LowBatteryAlert)
	require.Equal(t, jID, *latestA[0].LowBatteryAlert.JourneyID)
	jA, err := f.journeys.Get(f.ctx, f.viewerA.ID, jID)
	require.NoError(t, err)
	require.NotNil(t, jA.LowBatteryAlert)
	require.Equal(t, req.EventID, jA.LowBatteryAlert.ID)

	// viewerB (has location grant, NOT in Journey) sees NOTHING!
	_, err = f.store.Get(f.ctx, f.viewerB.ID, req.EventID)
	require.ErrorIs(t, err, batteryalerts.ErrNotFound)
	latestB, err := f.access.Latest(f.ctx, f.viewerB.ID)
	require.NoError(t, err)
	require.Len(t, latestB, 1)
	require.Nil(t, latestB[0].LowBatteryAlert, "non-journey viewer must not see journey-scoped battery alert on Live Map")

	// Dispatch sends strictly opaque FCM only to viewerA.
	push := &fakePush{}
	for {
		worked, dispatchErr := f.store.DispatchOne(f.ctx, push)
		require.NoError(t, dispatchErr)
		if !worked {
			break
		}
	}
	require.Equal(t, []string{"token-viewer-a"}, push.tokens)
	require.Len(t, push.signals, 1)
	require.Equal(t, notifications.Signal{EventID: req.EventID, Kind: "LOW_BATTERY", Episode: 0}, push.signals[0])

	// Charging recovery >= 20% silently resolves alert and hides it from Live Map and Journey.
	resolvedCount, err := f.store.Resolve(f.ctx, f.ownerP)
	require.NoError(t, err)
	require.Equal(t, int64(1), resolvedCount)

	latestAAfter, err := f.access.Latest(f.ctx, f.viewerA.ID)
	require.NoError(t, err)
	require.Len(t, latestAAfter, 1)
	require.Nil(t, latestAAfter[0].LowBatteryAlert)
	jAAfter, err := f.journeys.Get(f.ctx, f.viewerA.ID, jID)
	require.NoError(t, err)
	require.Nil(t, jAAfter.LowBatteryAlert)
}

func TestPGBatteryAlertFreshnessExpirySuppressionAndHistoryDeletion(t *testing.T) {
	f := setupFixture(t)
	f.enableSharingAndAlerts(t)

	// Offline alert older than 15 minutes is rejected at Create.
	tooOld := batteryalerts.CreateRequest{
		EventID:        uuid.New(),
		Threshold:      5,
		BatteryPercent: 4,
		RecordedAt:     time.Now().UTC().Add(-16 * time.Minute),
	}
	_, _, err := f.store.Create(f.ctx, f.ownerP, tooOld)
	require.ErrorIs(t, err, batteryalerts.ErrTooOld)

	// Fresh alert whose outbox ages past 15 minutes expires without sending FCM.
	freshID := uuid.New()
	_, created, err := f.store.Create(f.ctx, f.ownerP, batteryalerts.CreateRequest{
		EventID:        freshID,
		Threshold:      15,
		BatteryPercent: 15,
		RecordedAt:     time.Now().UTC(),
	})
	require.NoError(t, err)
	require.True(t, created)
	_, err = f.pool.Exec(f.ctx, `UPDATE low_battery_alerts SET recorded_at=now()-interval '16 minutes' WHERE id=$1`, freshID)
	require.NoError(t, err)

	push := &fakePush{}
	for {
		worked, dispatchErr := f.store.DispatchOne(f.ctx, push)
		require.NoError(t, dispatchErr)
		if !worked {
			break
		}
	}
	require.Empty(t, push.signals, "stale queued alert >15m must expire before calling FCM")
	var state string
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT state FROM low_battery_notification_outbox WHERE alert_id=$1 LIMIT 1`, freshID).Scan(&state))
	require.Equal(t, "EXPIRED", state)

	// Grant revocation suppresses queued dispatch for another alert.
	alert2ID := uuid.New()
	_, created, err = f.store.Create(f.ctx, f.ownerP, batteryalerts.CreateRequest{
		EventID:        alert2ID,
		Threshold:      5,
		BatteryPercent: 5,
		RecordedAt:     time.Now().UTC(),
	})
	require.NoError(t, err)
	require.True(t, created)
	require.NoError(t, f.access.Revoke(f.ctx, f.owner.ID, f.viewerB.ID))

	push2 := &fakePush{}
	for {
		worked, dispatchErr := f.store.DispatchOne(f.ctx, push2)
		require.NoError(t, dispatchErr)
		if !worked {
			break
		}
	}
	require.Equal(t, []string{"token-viewer-a"}, push2.tokens, "revoked viewerB must be suppressed before FCM send")

	// History deletion purges low_battery_alerts while keeping low_battery_alert_fences.
	now := time.Now().UTC()
	require.NoError(t, f.locations.DeleteHistory(f.ctx, f.owner.ID, nil, now))
	var alertCount, fenceCount int
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM low_battery_alerts WHERE owner_user_id=$1`, f.owner.ID).Scan(&alertCount))
	require.Zero(t, alertCount)
	require.NoError(t, f.pool.QueryRow(f.ctx, `SELECT count(*) FROM low_battery_alert_fences WHERE owner_user_id=$1`, f.owner.ID).Scan(&fenceCount))
	require.Equal(t, 2, fenceCount)

	// Reusing a purged fence UUID must fail with ErrConflict.
	_, _, err = f.store.Create(f.ctx, f.ownerP, batteryalerts.CreateRequest{
		EventID:        alert2ID,
		Threshold:      5,
		BatteryPercent: 5,
		RecordedAt:     time.Now().UTC(),
	})
	require.ErrorIs(t, err, batteryalerts.ErrConflict)

	// Creating a new alert whose location timestamp falls in a deleted range strips location.
	afterNow := time.Now().UTC()
	require.NoError(t, f.locations.DeleteHistory(f.ctx, f.owner.ID, nil, afterNow.Add(-2*time.Second)))
	strippedAlert, created, err := f.store.Create(f.ctx, f.ownerP, batteryalerts.CreateRequest{
		EventID:        uuid.New(),
		Threshold:      5,
		BatteryPercent: 5,
		RecordedAt:     afterNow,
		Location: &batteryalerts.Location{
			Latitude:                 10.77,
			Longitude:                106.70,
			HorizontalAccuracyMeters: 10,
			RecordedAt:               afterNow.Add(-5 * time.Second),
		},
	})
	require.NoError(t, err)
	require.True(t, created)
	require.Nil(t, strippedAlert.Location, "location inside deleted history window must be stripped")
}
