package httpapi_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

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
