package httpapi_test

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/contacts"
	"github.com/QuocDuong16/anve/server/internal/httpapi"
)

func TestHealthz(t *testing.T) {
	t.Parallel()

	router := httpapi.NewRouter(httpapi.Deps{Version: "test-version"})

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))

	require.Equal(t, http.StatusOK, rec.Code)
	assert.Equal(t, "application/json; charset=utf-8", rec.Header().Get("Content-Type"))

	var body httpapi.HealthResponse
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &body))
	assert.Equal(t, "ok", body.Status)
	assert.Equal(t, "test-version", body.Version)
	assert.NotEmpty(t, body.Time)
}

func TestReadyzWithoutDatabaseIsNotReady(t *testing.T) {
	t.Parallel()

	router := httpapi.NewRouter(httpapi.Deps{Version: "test-version"})

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/readyz", nil))

	// Since M1 the business endpoints are database-backed, so readiness
	// without a database must fail.
	assert.Equal(t, http.StatusServiceUnavailable, rec.Code)
}

type fakePinger struct{ err error }

func (p fakePinger) Ping(context.Context) error { return p.err }

func TestReadyzWithReachableDatabase(t *testing.T) {
	t.Parallel()

	router := httpapi.NewRouter(httpapi.Deps{Version: "test-version", DB: fakePinger{}})

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/readyz", nil))

	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestReadyzWithFailingDatabase(t *testing.T) {
	t.Parallel()

	router := httpapi.NewRouter(httpapi.Deps{
		Version: "test-version",
		DB:      fakePinger{err: context.DeadlineExceeded},
	})

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/readyz", nil))

	assert.Equal(t, http.StatusServiceUnavailable, rec.Code)
}

func TestVersionEndpoint(t *testing.T) {
	t.Parallel()

	router := httpapi.NewRouter(httpapi.Deps{Version: "1.2.3"})

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/version", nil))

	require.Equal(t, http.StatusOK, rec.Code)

	var body httpapi.VersionResponse
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &body))
	assert.Equal(t, "1.2.3", body.Version)
}

func TestUnknownRouteReturns404(t *testing.T) {
	t.Parallel()

	router := httpapi.NewRouter(httpapi.Deps{Version: "test-version"})

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/does-not-exist", nil))

	assert.Equal(t, http.StatusNotFound, rec.Code)
}

func TestBusinessRoutesWithoutDatabaseReturn503(t *testing.T) {
	t.Parallel()

	router := httpapi.NewRouter(httpapi.Deps{Version: "test-version"})

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/me", nil))

	assert.Equal(t, http.StatusServiceUnavailable, rec.Code)

	var body struct {
		Code string `json:"code"`
	}
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &body))
	assert.Equal(t, "SERVICE_UNAVAILABLE", body.Code)
}

type fakeContactStore struct {
	created []contacts.Contact
}

func (f *fakeContactStore) Create(_ context.Context, input contacts.CreateInput) (contacts.Contact, error) {
	c := contacts.Contact{
		ID:        "11111111-1111-1111-1111-111111111111",
		Name:      input.Name,
		Email:     input.Email,
		Phone:     input.Phone,
		Message:   input.Message,
		Status:    contacts.StatusNew,
		CreatedAt: time.Date(2026, 11, 16, 12, 0, 0, 0, time.UTC),
	}
	f.created = append(f.created, c)
	return c, nil
}

func (f *fakeContactStore) UpdateStatus(_ context.Context, _ string, _ contacts.Status) error {
	return nil
}

func TestLandingPageServesStitchHTML(t *testing.T) {
	t.Parallel()

	router := httpapi.NewRouter(httpapi.Deps{Version: "test-version"})
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/", nil))

	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Header().Get("Content-Type"), "text/html")
	assert.Contains(t, rec.Body.String(), "Internal Testing")
	assert.Contains(t, rec.Body.String(), "AnVe hiện đang trong giai đoạn phát triển")
	assert.Contains(t, rec.Body.String(), "/assets/hero-family-welcome.webp")

	assetRec := httptest.NewRecorder()
	router.ServeHTTP(assetRec, httptest.NewRequest(http.MethodGet, "/assets/hero-family-welcome.webp", nil))
	assert.Equal(t, http.StatusOK, assetRec.Code)
	assert.Equal(t, "image/webp", assetRec.Header().Get("Content-Type"))
	assert.NotEmpty(t, assetRec.Body.Bytes())
}

func TestContactsEndpointRoutes(t *testing.T) {
	t.Parallel()

	t.Run("without database returns 503", func(t *testing.T) {
		router := httpapi.NewRouter(httpapi.Deps{Version: "test-version"})
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, "/api/v1/contacts", strings.NewReader(`{"name":"A","email":"a@b.co","message":"hi"}`))
		req.Header.Set("Content-Type", "application/json")
		router.ServeHTTP(rec, req)

		assert.Equal(t, http.StatusServiceUnavailable, rec.Code)
	})

	t.Run("invalid fields return 400 validation", func(t *testing.T) {
		svc := contacts.NewService(&fakeContactStore{}, nil, slog.New(slog.NewTextHandler(io.Discard, nil)))
		router := httpapi.NewRouter(httpapi.Deps{Version: "test-version", Contacts: svc})

		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, "/api/v1/contacts", strings.NewReader(`{"name":"A","email":"bad","message":"hi"}`))
		req.Header.Set("Content-Type", "application/json")
		router.ServeHTTP(rec, req)

		assert.Equal(t, http.StatusBadRequest, rec.Code)
		var body struct {
			Code string `json:"code"`
		}
		require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &body))
		assert.Equal(t, "VALIDATION", body.Code)
	})

	t.Run("valid submission persists and returns 201", func(t *testing.T) {
		store := &fakeContactStore{}
		notifier := contacts.NewMultiNotifier(contacts.SMTPConfig{}, contacts.TelegramConfig{})
		svc := contacts.NewService(store, notifier, slog.New(slog.NewTextHandler(io.Discard, nil)))
		router := httpapi.NewRouter(httpapi.Deps{Version: "test-version", Contacts: svc})

		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, "/api/v1/contacts", strings.NewReader(`{"name":"Minh Anh","email":"minh@example.com","phone":"+84 912 345 678","message":"Trai nghiem som"}`))
		req.Header.Set("Content-Type", "application/json")
		router.ServeHTTP(rec, req)

		assert.Equal(t, http.StatusCreated, rec.Code)
		var got contacts.Contact
		require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &got))
		assert.Equal(t, "Minh Anh", got.Name)
		assert.Equal(t, contacts.StatusNew, got.Status)
		require.Len(t, store.created, 1)
	})
}
