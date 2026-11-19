package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/batteryalerts"
	"github.com/QuocDuong16/anve/server/internal/circles"
	"github.com/QuocDuong16/anve/server/internal/locationaccess"
	"github.com/QuocDuong16/anve/server/internal/testdb"
)

func TestPGBatteryAlertHTTPHandlersAndLiveMapETag(t *testing.T) {
	ctx := context.Background()
	pool := testdb.Scratch(t)
	a := auth.NewService(auth.NewPGStore(pool), nil, true)
	c := circles.NewService(circles.NewPGStore(pool))
	access := locationaccess.NewService(locationaccess.NewPGStore(pool), 30*24*time.Hour)
	bStore := batteryalerts.NewStore(pool, 30)

	owner, ot, err := a.DevSignIn(ctx, "bat-http-owner@example.com", "Owner", auth.DeviceRegistration{ClientDeviceID: "bat-http-owner"})
	require.NoError(t, err)
	viewer, vt, err := a.DevSignIn(ctx, "bat-http-viewer@example.com", "Viewer", auth.DeviceRegistration{ClientDeviceID: "bat-http-viewer"})
	require.NoError(t, err)
	circle, _, err := c.Create(ctx, owner.ID, "Family")
	require.NoError(t, err)
	invite, err := c.CreateInvite(ctx, owner.ID, circle.ID, nil)
	require.NoError(t, err)
	_, _, err = c.Redeem(ctx, viewer.ID, invite.Code)
	require.NoError(t, err)
	_, err = a.SetLocationSharing(ctx, owner.ID, true)
	require.NoError(t, err)
	_, err = access.Grant(ctx, owner.ID, viewer.ID)
	require.NoError(t, err)

	router := NewRouter(Deps{
		Auth:           a,
		LocationAccess: access,
		BatteryAlerts:  bStore,
	})
	request := func(method, path, token, expectedOwner, payload string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, path, bytes.NewBufferString(payload))
		req.Header.Set("Authorization", "Bearer "+token)
		if expectedOwner != "" {
			req.Header.Set("X-Anve-Expected-Owner", expectedOwner)
		}
		w := httptest.NewRecorder()
		router.ServeHTTP(w, req)
		return w
	}

	// Enable preference via PUT /api/v1/me/low-battery-alerts (CAS conflict on wrong version).
	w := request(http.MethodPut, "/api/v1/me/low-battery-alerts", ot.AccessToken, owner.ID.String(), `{"enabled":true,"expectedVersion":2}`)
	require.Equal(t, http.StatusConflict, w.Code)
	require.Contains(t, w.Body.String(), "LOW_BATTERY_PREFERENCE_VERSION_CONFLICT")

	w = request(http.MethodPut, "/api/v1/me/low-battery-alerts", ot.AccessToken, owner.ID.String(), `{"enabled":true,"expectedVersion":0}`)
	require.Equal(t, http.StatusOK, w.Code, w.Body.String())
	var userResp map[string]any
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &userResp))
	require.Equal(t, true, userResp["lowBatteryAlertsEnabled"])
	require.Equal(t, float64(1), userResp["lowBatteryAlertsVersion"])

	// Baseline Live Map ETag.
	w = request(http.MethodGet, "/api/v1/locations/visible/latest", vt.AccessToken, viewer.ID.String(), "")
	require.Equal(t, http.StatusOK, w.Code)
	etagBefore := w.Header().Get("ETag")

	// Wrong expected-owner -> 401.
	id := uuid.New()
	now := time.Now().UTC().Truncate(time.Microsecond)
	body := fmt.Sprintf(`{"eventId":%q,"threshold":5,"batteryPercent":5,"recordedAt":%q,"location":{"latitude":10.7769,"longitude":106.7009,"horizontalAccuracyMeters":15,"recordedAt":%q}}`,
		id, now.Format(time.RFC3339Nano), now.Format(time.RFC3339Nano))
	w = request(http.MethodPost, "/api/v1/battery-alerts", ot.AccessToken, viewer.ID.String(), body)
	require.Equal(t, http.StatusUnauthorized, w.Code)

	// Missing required field or invalid payload -> 400.
	w = request(http.MethodPost, "/api/v1/battery-alerts", ot.AccessToken, owner.ID.String(), fmt.Sprintf(`{"eventId":%q,"threshold":5,"recordedAt":%q}`, id, now.Format(time.RFC3339Nano)))
	require.Equal(t, http.StatusBadRequest, w.Code)

	// Valid create -> 201, retry -> 200.
	w = request(http.MethodPost, "/api/v1/battery-alerts", ot.AccessToken, owner.ID.String(), body)
	require.Equal(t, http.StatusCreated, w.Code, w.Body.String())
	require.Equal(t, "no-store, private", w.Header().Get("Cache-Control"))
	w = request(http.MethodPost, "/api/v1/battery-alerts", ot.AccessToken, owner.ID.String(), body)
	require.Equal(t, http.StatusOK, w.Code, w.Body.String())

	// Recipient can read GET /api/v1/battery-alerts/{id}.
	w = request(http.MethodGet, "/api/v1/battery-alerts/"+id.String(), vt.AccessToken, viewer.ID.String(), "")
	require.Equal(t, http.StatusOK, w.Code, w.Body.String())

	// Live Map ETag updates to expose lowBatteryAlert.
	w = request(http.MethodGet, "/api/v1/locations/visible/latest", vt.AccessToken, viewer.ID.String(), "")
	require.Equal(t, http.StatusOK, w.Code)
	etagAlert := w.Header().Get("ETag")
	require.NotEqual(t, etagBefore, etagAlert)
	require.Contains(t, w.Body.String(), `"lowBatteryAlert"`)

	// Charging recovery -> 204 and clears Live Map ETag back.
	w = request(http.MethodPost, "/api/v1/battery-alerts/resolve", ot.AccessToken, owner.ID.String(), "")
	require.Equal(t, http.StatusNoContent, w.Code)
	w = request(http.MethodGet, "/api/v1/locations/visible/latest", vt.AccessToken, viewer.ID.String(), "")
	require.Equal(t, http.StatusOK, w.Code)
	require.NotEqual(t, etagAlert, w.Header().Get("ETag"))
	require.NotContains(t, w.Body.String(), `"lowBatteryAlert"`)
}
