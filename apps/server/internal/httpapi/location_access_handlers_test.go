package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/locationaccess"
)

type accessHandlerStore struct {
	locations  []locationaccess.VisibleLocation
	calls      int
	grantCalls int
}

func (s *accessHandlerStore) Audience(context.Context, uuid.UUID) ([]locationaccess.Grant, error) {
	return nil, nil
}

func (s *accessHandlerStore) Grant(context.Context, uuid.UUID, uuid.UUID) (locationaccess.Grant, error) {
	s.grantCalls++
	return locationaccess.Grant{}, nil
}

func (s *accessHandlerStore) Revoke(context.Context, uuid.UUID, uuid.UUID) error { return nil }

func (s *accessHandlerStore) Latest(_ context.Context, _ uuid.UUID, _, _ time.Time) ([]locationaccess.VisibleLocation, error) {
	s.calls++
	return s.locations, nil
}

func (s *accessHandlerStore) BeginHistory(context.Context, uuid.UUID, uuid.UUID) (int64, error) {
	return 0, nil
}
func (s *accessHandlerStore) History(context.Context, locationaccess.HistoryQuery) ([]locationaccess.HistoryPoint, error) {
	return nil, nil
}

func TestLatestVisibleLocationsUsesPrivateConditionalSnapshot(t *testing.T) {
	recordedAt := time.Now().UTC().Add(-time.Minute)
	latitude, longitude, accuracy := 10.7, 106.6, 12.0
	store := &accessHandlerStore{locations: []locationaccess.VisibleLocation{{
		OwnerID: uuid.New(), DisplayName: "Family member", Latitude: &latitude,
		Longitude: &longitude, AccuracyM: &accuracy, RecordedAt: &recordedAt,
	}}}
	handler := latestVisibleLocationsHandler(locationaccess.NewService(store, 30*24*time.Hour))
	viewerID := uuid.New()

	first := httptest.NewRequest(http.MethodGet, "/api/v1/locations/visible/latest", nil)
	first = first.WithContext(WithAuthContext(first.Context(), AuthContext{User: auth.User{ID: viewerID}}))
	firstResponse := httptest.NewRecorder()
	handler.ServeHTTP(firstResponse, first)
	require.Equal(t, http.StatusOK, firstResponse.Code)
	require.Equal(t, "no-store, private", firstResponse.Header().Get("Cache-Control"))
	require.Equal(t, "Authorization", firstResponse.Header().Get("Vary"))
	etag := firstResponse.Header().Get("ETag")
	require.NotEmpty(t, etag)
	var firstBody latestLocationsResponse
	require.NoError(t, json.Unmarshal(firstResponse.Body.Bytes(), &firstBody))
	require.Len(t, firstBody.Locations, 1)
	require.Equal(t, "FRESH", firstBody.Locations[0].Confidence)
	require.NotNil(t, firstBody.Locations[0].AgeMillis)

	second := httptest.NewRequest(http.MethodGet, "/api/v1/locations/visible/latest", nil)
	second.Header.Set("If-None-Match", etag)
	second = second.WithContext(WithAuthContext(second.Context(), AuthContext{User: auth.User{ID: viewerID}}))
	secondResponse := httptest.NewRecorder()
	handler.ServeHTTP(secondResponse, second)
	require.Equal(t, http.StatusNotModified, secondResponse.Code)
	require.Empty(t, secondResponse.Body.Bytes())
	require.Equal(t, "no-store, private", secondResponse.Header().Get("Cache-Control"))
	require.Equal(t, 2, store.calls)
}

func TestLatestVisibleLocationsRequiresAuthentication(t *testing.T) {
	store := &accessHandlerStore{}
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/api/v1/locations/visible/latest", nil)

	latestVisibleLocationsHandler(locationaccess.NewService(store, time.Hour))(recorder, request)

	require.Equal(t, http.StatusUnauthorized, recorder.Code)
	require.Equal(t, "UNAUTHENTICATED", decodeErrorCode(t, recorder.Body.Bytes()))
	require.Zero(t, store.calls)
}

func TestAudienceMutationRejectsStaleAccountOwner(t *testing.T) {
	store := &accessHandlerStore{}
	service := locationaccess.NewService(store, time.Hour)
	router := chi.NewRouter()
	router.Put("/api/v1/location-sharing/audience/{viewerId}", grantAudienceHandler(service))
	viewerID := uuid.New()
	request := httptest.NewRequest(http.MethodPut, "/api/v1/location-sharing/audience/"+viewerID.String(), nil)
	request.Header.Set("X-Anve-Expected-Owner", uuid.NewString())
	request = request.WithContext(WithAuthContext(request.Context(), AuthContext{User: auth.User{ID: uuid.New()}}))
	response := httptest.NewRecorder()

	router.ServeHTTP(response, request)

	require.Equal(t, http.StatusUnauthorized, response.Code)
	require.Equal(t, "UNAUTHENTICATED", decodeErrorCode(t, response.Body.Bytes()))
	require.Zero(t, store.grantCalls)
}

type historyHandlerStore struct {
	accessHandlerStore
	query locationaccess.HistoryQuery
}

func (s *historyHandlerStore) BeginHistory(_ context.Context, _, _ uuid.UUID) (int64, error) {
	return 0, nil
}
func (s *historyHandlerStore) History(_ context.Context, query locationaccess.HistoryQuery) ([]locationaccess.HistoryPoint, error) {
	s.query = query
	return nil, nil
}

func TestHistoryHandlerEnforcesAccountAndBounds(t *testing.T) {
	viewer, owner := uuid.New(), uuid.New()
	store := &historyHandlerStore{}
	handler := historyHandler(locationaccess.NewService(store, 30*24*time.Hour))
	now := time.Now().UTC()
	build := func(from time.Time, expected string) *http.Request {
		req := httptest.NewRequest(http.MethodGet, "/api/v1/locations/history", nil)
		values := req.URL.Query()
		values.Set("ownerId", owner.String())
		values.Set("from", from.Format(time.RFC3339Nano))
		values.Set("to", now.Format(time.RFC3339Nano))
		req.URL.RawQuery = values.Encode()
		req.Header.Set("X-Anve-Expected-Owner", expected)
		return req.WithContext(WithAuthContext(req.Context(), AuthContext{User: auth.User{ID: viewer}}))
	}
	rec := httptest.NewRecorder()
	handler(rec, build(now.Add(-time.Hour), uuid.NewString()))
	require.Equal(t, http.StatusUnauthorized, rec.Code)
	rec = httptest.NewRecorder()
	handler(rec, build(now.Add(-72*time.Hour), viewer.String()))
	require.Equal(t, http.StatusBadRequest, rec.Code)
	rec = httptest.NewRecorder()
	handler(rec, build(now.Add(-time.Hour), viewer.String()))
	require.Equal(t, http.StatusOK, rec.Code)
	require.Equal(t, viewer, store.query.Viewer)
	require.Equal(t, owner, store.query.Owner)
	require.Equal(t, "no-store, private", rec.Header().Get("Cache-Control"))
}
