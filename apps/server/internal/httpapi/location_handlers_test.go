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

func (*fakeLocationStore) CaptureGeneration(context.Context, uuid.UUID) (int64, error) { return 1, nil }

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

func (f *fakeLocationStore) DeleteHistory(context.Context, uuid.UUID, *time.Time, time.Time) error {
	return nil
}
func (f *fakeLocationStore) RetentionDays(context.Context, uuid.UUID) (int, error)  { return 30, nil }
func (f *fakeLocationStore) SetRetentionDays(context.Context, uuid.UUID, int) error { return nil }

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
		owner := uuid.New()
		req.Header.Set("X-Anve-Expected-Owner", owner.String())
		req = req.WithContext(WithAuthContext(req.Context(), AuthContext{
			Session: auth.Session{ID: uuid.New(), DeviceID: uuid.New()},
			User:    auth.User{ID: owner},
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

func TestLocationBatchRejectsChangedAccount(t *testing.T) {
	store := &fakeLocationStore{}
	req := locationRequest(t, locationRequestBody(uuid.New(), handlerNow), true)
	req.Header.Set("X-Anve-Expected-Owner", uuid.NewString())
	w := httptest.NewRecorder()
	locationHandlerFor(store).ServeHTTP(w, req)
	require.Equal(t, http.StatusUnauthorized, w.Code)
	require.Zero(t, store.calls)
}

func TestCaptureGenerationRequiresMatchingAccount(t *testing.T) {
	store := &fakeLocationStore{}
	req := locationRequest(t, "", true)
	req.Method = http.MethodGet
	w := httptest.NewRecorder()
	captureGenerationHandler(locations.NewService(store)).ServeHTTP(w, req)
	require.Equal(t, http.StatusOK, w.Code)
	require.JSONEq(t, `{"generation":1}`, w.Body.String())
	req.Header.Set("X-Anve-Expected-Owner", uuid.NewString())
	w = httptest.NewRecorder()
	captureGenerationHandler(locations.NewService(store)).ServeHTTP(w, req)
	require.Equal(t, http.StatusUnauthorized, w.Code)
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

type historyDeleteStore struct {
	fakeLocationStore
	owner uuid.UUID
	calls int
	days  int
}

func (f *historyDeleteStore) DeleteHistory(_ context.Context, owner uuid.UUID, _ *time.Time, _ time.Time) error {
	f.owner = owner
	f.calls++
	return nil
}

func TestHistoryDeletionRequiresMatchingAccountAndValidRange(t *testing.T) {
	owner := uuid.New()
	store := &historyDeleteStore{}
	handler := deleteLocationHistoryHandler(locations.NewServiceWithClock(store, func() time.Time { return handlerNow }, locations.MaxEventAge))
	for _, tc := range []struct {
		name, query, header string
		status              int
		calls               int
	}{
		{"wrong account", "to=2026-10-26T11:00:00Z", uuid.NewString(), http.StatusUnauthorized, 0},
		{"invalid interval", "from=2026-10-26T11:00:00Z&to=2026-10-26T10:00:00Z", owner.String(), http.StatusBadRequest, 0},
		{"future end", "to=2026-10-27T00:00:00Z", owner.String(), http.StatusBadRequest, 0},
		{"valid", "to=2026-10-26T11:00:00Z", owner.String(), http.StatusNoContent, 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			store.calls = 0
			req := httptest.NewRequest(http.MethodDelete, "/api/v1/me/location-history?"+tc.query, nil)
			req.Header.Set("X-Anve-Expected-Owner", tc.header)
			req = req.WithContext(WithAuthContext(req.Context(), AuthContext{User: auth.User{ID: owner}}))
			rec := httptest.NewRecorder()
			handler(rec, req)
			assert.Equal(t, tc.status, rec.Code)
			assert.Equal(t, tc.calls, store.calls)
			if tc.calls > 0 {
				assert.Equal(t, owner, store.owner)
			}
		})
	}
}

func (f *historyDeleteStore) RetentionDays(_ context.Context, _ uuid.UUID) (int, error) {
	return f.days, nil
}
func (f *historyDeleteStore) SetRetentionDays(_ context.Context, _ uuid.UUID, days int) error {
	f.days = days
	return nil
}

func TestHistoryRetentionChecksOwnerAndBounds(t *testing.T) {
	owner := uuid.New()
	store := &historyDeleteStore{days: 30}
	svc := locations.NewServiceWithClock(store, func() time.Time { return handlerNow }, locations.MaxEventAge)
	handler := historyRetentionHandler(svc)
	for _, tc := range []struct {
		name, body, header string
		status, want       int
	}{
		{"wrong account", `{"days":1}`, uuid.NewString(), http.StatusUnauthorized, 30},
		{"out of bounds", `{"days":31}`, owner.String(), http.StatusBadRequest, 30},
		{"valid", `{"days":7}`, owner.String(), http.StatusOK, 7},
	} {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodPut, "/api/v1/me/location-history/retention", strings.NewReader(tc.body))
			req.Header.Set("X-Anve-Expected-Owner", tc.header)
			req = req.WithContext(WithAuthContext(req.Context(), AuthContext{User: auth.User{ID: owner}}))
			rec := httptest.NewRecorder()
			handler(rec, req)
			assert.Equal(t, tc.status, rec.Code)
			assert.Equal(t, tc.want, store.days)
		})
	}
}
