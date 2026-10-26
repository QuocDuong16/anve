package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/locations"
)

type fakeLocationStore struct {
	result locations.BatchResult
	err    error
	calls  int
}

func (f *fakeLocationStore) Ingest(_ context.Context, in locations.IngestInput) (locations.BatchResult, error) {
	f.calls++
	result := f.result
	if result.Accepted == nil && result.Duplicates == nil && f.err == nil {
		for _, event := range in.Events {
			result.Accepted = append(result.Accepted, event.EventID)
		}
		result.ReceivedAt = in.ReceivedAt
	}
	return result, f.err
}

func (f *fakeLocationStore) PurgeOlderThan(context.Context, time.Time) (int64, error) {
	return 0, nil
}

var handlerNow = time.Date(2026, 10, 26, 12, 0, 0, 0, time.UTC)

func locationHandlerFor(store locations.Store) http.HandlerFunc {
	return ingestLocationBatchHandler(
		locations.NewServiceWithClock(store, func() time.Time { return handlerNow }, locations.MaxEventAge),
	)
}

func locationRequestBody(eventID uuid.UUID, recordedAt time.Time) string {
	return fmt.Sprintf(`{
		"events": [{
			"eventId": %q,
			"clientDeviceId": "device-owner-0001",
			"recordedAt": %q,
			"latitude": 10.7769,
			"longitude": 106.7009,
			"horizontalAccuracyMeters": 8.5,
			"provider": "fused",
			"schemaVersion": 1
		}]
	}`, eventID, recordedAt.Format(time.RFC3339Nano))
}

func locationRequest(t *testing.T, body string, authenticated bool) *http.Request {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/locations/batch", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.RemoteAddr = "127.0.0.1:54321"
	if authenticated {
		req = req.WithContext(WithAuthContext(req.Context(), AuthContext{
			Session: auth.Session{ID: uuid.New(), DeviceID: uuid.New()},
			User:    auth.User{ID: uuid.New()},
		}))
	}
	return req
}

func decodeErrorCode(t *testing.T, body []byte) string {
	t.Helper()
	var envelope errorBody
	require.NoError(t, json.Unmarshal(body, &envelope))
	return envelope.Code
}

func TestIngestLocationBatchStoresAndConfirmsEvents(t *testing.T) {
	store := &fakeLocationStore{}
	eventID := uuid.Must(uuid.NewV7())
	req := locationRequest(t, locationRequestBody(eventID, handlerNow.Add(-time.Minute)), true)
	rec := httptest.NewRecorder()

	locationHandlerFor(store)(rec, req)

	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
	var response locationBatchResponse
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &response))
	assert.Equal(t, []uuid.UUID{eventID}, response.Accepted)
	assert.Empty(t, response.Duplicates)
	assert.False(t, response.ReceivedAt.IsZero())
	assert.Equal(t, 1, store.calls)
}

func TestIngestLocationBatchReportsDuplicatesAsSuccess(t *testing.T) {
	eventID := uuid.Must(uuid.NewV7())
	store := &fakeLocationStore{result: locations.BatchResult{
		Duplicates: []uuid.UUID{eventID},
		ReceivedAt: handlerNow,
	}}
	req := locationRequest(t, locationRequestBody(eventID, handlerNow.Add(-time.Minute)), true)
	rec := httptest.NewRecorder()

	locationHandlerFor(store)(rec, req)

	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
	var response locationBatchResponse
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &response))
	assert.Empty(t, response.Accepted)
	assert.Equal(t, []uuid.UUID{eventID}, response.Duplicates)
}

func TestIngestLocationBatchRequiresAuthentication(t *testing.T) {
	store := &fakeLocationStore{}
	req := locationRequest(t, locationRequestBody(uuid.Must(uuid.NewV7()), handlerNow), false)
	rec := httptest.NewRecorder()

	locationHandlerFor(store)(rec, req)

	assert.Equal(t, http.StatusUnauthorized, rec.Code)
	assert.Equal(t, "UNAUTHENTICATED", decodeErrorCode(t, rec.Body.Bytes()))
	assert.Zero(t, store.calls)
}

func TestIngestLocationBatchRejectsMalformedInput(t *testing.T) {
	tests := []struct {
		name       string
		body       string
		wantStatus int
		wantCode   string
	}{
		{
			name:       "not json",
			body:       `{`,
			wantStatus: http.StatusBadRequest,
			wantCode:   "LOCATION_BATCH_INVALID",
		},
		{
			name:       "trailing json",
			body:       locationRequestBody(uuid.Must(uuid.NewV7()), handlerNow) + `{}`,
			wantStatus: http.StatusBadRequest,
			wantCode:   "LOCATION_BATCH_INVALID",
		},
		{
			name:       "empty batch",
			body:       `{"events":[]}`,
			wantStatus: http.StatusBadRequest,
			wantCode:   "LOCATION_BATCH_INVALID",
		},
		{
			name: "missing latitude",
			body: fmt.Sprintf(`{"events":[{"eventId":%q,"clientDeviceId":"device-owner-0001",
				"recordedAt":%q,"longitude":106.7,"horizontalAccuracyMeters":5,"provider":"fused"}]}`,
				uuid.Must(uuid.NewV7()), handlerNow.Format(time.RFC3339)),
			wantStatus: http.StatusBadRequest,
			wantCode:   "LOCATION_BATCH_INVALID",
		},
		{
			name: "no event id",
			body: fmt.Sprintf(`{"events":[{"eventId":"nope","clientDeviceId":"device-owner-0001",
				"recordedAt":%q,"latitude":10.7,"longitude":106.7,
				"horizontalAccuracyMeters":5,"provider":"fused"}]}`, handlerNow.Format(time.RFC3339)),
			wantStatus: http.StatusBadRequest,
			wantCode:   "LOCATION_BATCH_INVALID",
		},
		{
			name: "too old",
			body: locationRequestBody(uuid.Must(uuid.NewV7()),
				handlerNow.Add(-locations.MaxEventAge-time.Hour)),
			wantStatus: http.StatusBadRequest,
			wantCode:   "LOCATION_EVENT_TOO_OLD",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			store := &fakeLocationStore{}
			req := locationRequest(t, tc.body, true)
			rec := httptest.NewRecorder()
			locationHandlerFor(store)(rec, req)
			assert.Equal(t, tc.wantStatus, rec.Code, rec.Body.String())
			assert.Equal(t, tc.wantCode, decodeErrorCode(t, rec.Body.Bytes()))
			assert.Zero(t, store.calls, "invalid input never reaches the store")
		})
	}
}

func TestIngestLocationBatchRejectsOversizedBatch(t *testing.T) {
	events := make([]string, locations.MaxBatchEvents+1)
	for i := range events {
		events[i] = fmt.Sprintf(`{"eventId":%q,"clientDeviceId":"device-owner-0001",
			"recordedAt":%q,"latitude":10.7,"longitude":106.7,
			"horizontalAccuracyMeters":5,"provider":"fused"}`,
			uuid.Must(uuid.NewV7()), handlerNow.Format(time.RFC3339))
	}
	store := &fakeLocationStore{}
	req := locationRequest(t, `{"events":[`+strings.Join(events, ",")+`]}`, true)
	rec := httptest.NewRecorder()

	locationHandlerFor(store)(rec, req)

	assert.Equal(t, http.StatusBadRequest, rec.Code)
	assert.Equal(t, "LOCATION_BATCH_TOO_LARGE", decodeErrorCode(t, rec.Body.Bytes()))
}

func TestIngestLocationBatchRejectsOversizedBody(t *testing.T) {
	padding := strings.Repeat("a", int(locations.MaxBodyBytes)+1024)
	body := fmt.Sprintf(`{"events":[],"padding":%q}`, padding)
	req := locationRequest(t, body, true)
	rec := httptest.NewRecorder()

	locationHandlerFor(&fakeLocationStore{})(rec, req)

	assert.Equal(t, http.StatusRequestEntityTooLarge, rec.Code)
	assert.Equal(t, "LOCATION_BATCH_TOO_LARGE", decodeErrorCode(t, rec.Body.Bytes()))
}

func TestIngestLocationBatchMapsPolicyAndStorageErrors(t *testing.T) {
	tests := []struct {
		name       string
		storeErr   error
		wantStatus int
		wantCode   string
	}{
		{
			name:       "consent off",
			storeErr:   locations.ErrConsentRequired,
			wantStatus: http.StatusConflict,
			wantCode:   "LOCATION_CONSENT_REQUIRED",
		},
		{
			name:       "no circle",
			storeErr:   locations.ErrCircleRequired,
			wantStatus: http.StatusConflict,
			wantCode:   "LOCATION_CIRCLE_REQUIRED",
		},
		{
			name:       "foreign device",
			storeErr:   locations.ErrDeviceNotAuthorized,
			wantStatus: http.StatusForbidden,
			wantCode:   "DEVICE_NOT_AUTHORIZED",
		},
		{
			name:       "storage unavailable",
			storeErr:   errors.New("connection reset"),
			wantStatus: http.StatusServiceUnavailable,
			wantCode:   "LOCATION_TEMPORARILY_UNAVAILABLE",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			store := &fakeLocationStore{err: tc.storeErr}
			req := locationRequest(t, locationRequestBody(uuid.Must(uuid.NewV7()),
				handlerNow.Add(-time.Minute)), true)
			rec := httptest.NewRecorder()
			locationHandlerFor(store)(rec, req)
			assert.Equal(t, tc.wantStatus, rec.Code, rec.Body.String())
			assert.Equal(t, tc.wantCode, decodeErrorCode(t, rec.Body.Bytes()))
		})
	}
}
