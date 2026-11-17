package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/circles"
	"github.com/QuocDuong16/anve/server/internal/locationaccess"
	"github.com/QuocDuong16/anve/server/internal/places"
	"github.com/QuocDuong16/anve/server/internal/testdb"
)

func TestPGPlaceHandlersCreateMonitorAndDelete(t *testing.T) {
	ctx := context.Background()
	pool := testdb.Scratch(t)
	a := auth.NewService(auth.NewPGStore(pool), nil, true)
	owner, _, err := a.DevSignIn(ctx, "places-http@example.com", "Owner", auth.DeviceRegistration{ClientDeviceID: "places-http-device"})
	require.NoError(t, err)
	_, _, err = circles.NewService(circles.NewPGStore(pool)).Create(ctx, owner.ID, "Family")
	require.NoError(t, err)
	_, err = a.SetLocationSharing(ctx, owner.ID, true)
	require.NoError(t, err)
	store := places.NewStore(pool, 30*24*time.Hour)
	request := func(method, path, body string, placeID uuid.UUID) *http.Request {
		req := httptest.NewRequest(method, path, strings.NewReader(body))
		req.Header.Set("X-Anve-Expected-Owner", owner.ID.String())
		route := chi.NewRouteContext()
		if placeID != uuid.Nil {
			route.URLParams.Add("placeId", placeID.String())
		}
		req = req.WithContext(context.WithValue(req.Context(), chi.RouteCtxKey, route))
		return req.WithContext(WithAuthContext(req.Context(), AuthContext{User: owner}))
	}
	w := httptest.NewRecorder()
	createPlaceHandler(store).ServeHTTP(w, request(http.MethodPost, "/api/v1/places", `{"name":"Nhà","latitude":10,"longitude":106,"radiusMeters":150,"sharedWithCircle":true}`, uuid.Nil))
	require.Equal(t, http.StatusCreated, w.Code, w.Body.String())
	var created places.Place
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &created))
	require.NotEqual(t, uuid.Nil, created.ID)
	require.True(t, created.SharedWithCircle)
	w = httptest.NewRecorder()
	setPlaceMonitorHandler(store).ServeHTTP(w, request(http.MethodPut, "/api/v1/places/monitor", `{"recipientIds":[]}`, created.ID))
	require.Equal(t, http.StatusOK, w.Code, w.Body.String())
	var monitor places.Monitor
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &monitor))
	require.True(t, monitor.Active)
	w = httptest.NewRecorder()
	getPlaceMonitorHandler(store).ServeHTTP(w, request(http.MethodGet, "/api/v1/places/monitor", "", created.ID))
	require.Equal(t, http.StatusOK, w.Code, w.Body.String())
	w = httptest.NewRecorder()
	deletePlaceHandler(store).ServeHTTP(w, request(http.MethodDelete, "/api/v1/places", "", created.ID))
	require.Equal(t, http.StatusNoContent, w.Code, w.Body.String())
	w = httptest.NewRecorder()
	getPlaceMonitorHandler(store).ServeHTTP(w, request(http.MethodGet, "/api/v1/places/monitor", "", created.ID))
	require.Equal(t, http.StatusNotFound, w.Code, w.Body.String())
}

func TestPGIncomingPlaceMonitorsHonorsAuthorizations(t *testing.T) {
	ctx := context.Background()
	pool := testdb.Scratch(t)
	a := auth.NewService(auth.NewPGStore(pool), nil, true)
	circleSvc := circles.NewService(circles.NewPGStore(pool))
	accessSvc := locationaccess.NewService(locationaccess.NewPGStore(pool), 30*24*time.Hour)
	owner, _, err := a.DevSignIn(ctx, "incoming-owner@example.com", "Lan", auth.DeviceRegistration{ClientDeviceID: "incoming-owner-dev"})
	require.NoError(t, err)
	viewer, _, err := a.DevSignIn(ctx, "incoming-viewer@example.com", "Minh", auth.DeviceRegistration{ClientDeviceID: "incoming-viewer-dev"})
	require.NoError(t, err)
	circle, _, err := circleSvc.Create(ctx, owner.ID, "Family")
	require.NoError(t, err)
	invite, err := circleSvc.CreateInvite(ctx, owner.ID, circle.ID, nil)
	require.NoError(t, err)
	_, _, err = circleSvc.Redeem(ctx, viewer.ID, invite.Code)
	require.NoError(t, err)
	_, err = a.SetLocationSharing(ctx, owner.ID, true)
	require.NoError(t, err)
	_, err = accessSvc.Grant(ctx, owner.ID, viewer.ID)
	require.NoError(t, err)

	store := places.NewStore(pool, 30*24*time.Hour)
	place, err := store.Create(ctx, owner.ID, places.Input{
		Name: "Nhà", Latitude: 10, Longitude: 106, RadiusM: 150, SharedWithCircle: true,
	})
	require.NoError(t, err)
	_, err = store.ConfigureMonitor(ctx, owner.ID, place.ID, []uuid.UUID{viewer.ID})
	require.NoError(t, err)

	reqFor := func(actor auth.User, placeID uuid.UUID) *http.Request {
		req := httptest.NewRequest(http.MethodGet, "/api/v1/places/incoming-monitors", nil)
		req.Header.Set("X-Anve-Expected-Owner", actor.ID.String())
		route := chi.NewRouteContext()
		route.URLParams.Add("placeId", placeID.String())
		req = req.WithContext(context.WithValue(req.Context(), chi.RouteCtxKey, route))
		return req.WithContext(WithAuthContext(req.Context(), AuthContext{User: actor}))
	}

	w := httptest.NewRecorder()
	incomingPlaceMonitorsHandler(store).ServeHTTP(w, reqFor(viewer, place.ID))
	require.Equal(t, http.StatusOK, w.Code, w.Body.String())
	var body struct {
		Monitors []places.IncomingMonitor `json:"monitors"`
	}
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &body))
	require.Len(t, body.Monitors, 1)
	require.Equal(t, owner.ID, body.Monitors[0].OwnerID)
	require.Equal(t, "Lan", body.Monitors[0].OwnerName)

	// Revoking the location grant immediately hides the incoming monitor.
	require.NoError(t, accessSvc.Revoke(ctx, owner.ID, viewer.ID))
	w = httptest.NewRecorder()
	incomingPlaceMonitorsHandler(store).ServeHTTP(w, reqFor(viewer, place.ID))
	require.Equal(t, http.StatusOK, w.Code, w.Body.String())
	body.Monitors = nil
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &body))
	require.Empty(t, body.Monitors)
}
