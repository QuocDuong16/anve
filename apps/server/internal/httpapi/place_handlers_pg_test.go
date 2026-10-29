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
	createPlaceHandler(store).ServeHTTP(w, request(http.MethodPost, "/api/v1/places", `{"name":"Nhà","latitude":10,"longitude":106,"radiusMeters":150}`, uuid.Nil))
	require.Equal(t, http.StatusCreated, w.Code, w.Body.String())
	var created places.Place
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &created))
	require.NotEqual(t, uuid.Nil, created.ID)
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
