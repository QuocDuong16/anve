package httpapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/circles"
	"github.com/QuocDuong16/anve/server/internal/locationaccess"
	"github.com/QuocDuong16/anve/server/internal/testdb"
)

func TestPostgresLatestETagChangesAcrossAudienceAuthorization(t *testing.T) {
	ctx := context.Background()
	pool := testdb.Scratch(t)
	authService := auth.NewService(auth.NewPGStore(pool), nil, true)
	circleService := circles.NewService(circles.NewPGStore(pool))
	owner, _, err := authService.DevSignIn(ctx, "etag-owner@example.com", "Owner", auth.DeviceRegistration{
		ClientDeviceID: "device-etag-owner",
	})
	require.NoError(t, err)
	viewer, _, err := authService.DevSignIn(ctx, "etag-viewer@example.com", "Viewer", auth.DeviceRegistration{
		ClientDeviceID: "device-etag-viewer",
	})
	require.NoError(t, err)
	circle, _, err := circleService.Create(ctx, owner.ID, "ETag family")
	require.NoError(t, err)
	invite, err := circleService.CreateInvite(ctx, owner.ID, circle.ID, nil)
	require.NoError(t, err)
	_, _, err = circleService.Redeem(ctx, viewer.ID, invite.Code)
	require.NoError(t, err)
	_, err = authService.SetLocationSharing(ctx, owner.ID, true)
	require.NoError(t, err)

	access := locationaccess.NewService(locationaccess.NewPGStore(pool), 30*24*time.Hour)
	handler := latestVisibleLocationsHandler(access)
	readETag := func() string {
		t.Helper()
		request := httptest.NewRequest(http.MethodGet, "/api/v1/locations/visible/latest", nil)
		request = request.WithContext(WithAuthContext(request.Context(), AuthContext{User: auth.User{ID: viewer.ID}}))
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		require.Equal(t, http.StatusOK, response.Code)
		tag := response.Header().Get("ETag")
		require.NotEmpty(t, tag)
		return tag
	}

	withoutGrant := readETag()
	_, err = access.Grant(ctx, owner.ID, viewer.ID)
	require.NoError(t, err)
	withGrant := readETag()
	require.NotEqual(t, withoutGrant, withGrant, "grant must invalidate a previous empty snapshot")

	require.NoError(t, access.Revoke(ctx, owner.ID, viewer.ID))
	withoutGrantAgain := readETag()
	require.NotEqual(t, withGrant, withoutGrantAgain, "revoke must invalidate a snapshot with an audience member")

	_, err = access.Grant(ctx, owner.ID, viewer.ID)
	require.NoError(t, err)
	beforeConsentOff := readETag()
	_, err = authService.SetLocationSharing(ctx, owner.ID, false)
	require.NoError(t, err)
	afterConsentOff := readETag()
	require.NotEqual(t, beforeConsentOff, afterConsentOff, "consent OFF must invalidate previously visible authorization")

	_, err = authService.SetLocationSharing(ctx, owner.ID, true)
	require.NoError(t, err)
	_, err = access.Grant(ctx, owner.ID, viewer.ID)
	require.NoError(t, err)
	beforeLeave := readETag()
	require.NoError(t, circleService.Leave(ctx, viewer.ID, circle.ID, false))
	afterLeave := readETag()
	require.NotEqual(t, beforeLeave, afterLeave, "Circle leave must invalidate a prior authorized snapshot")
}
