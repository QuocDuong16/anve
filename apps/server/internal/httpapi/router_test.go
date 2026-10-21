package httpapi_test

import (
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

	router := httpapi.NewRouter("test-version")

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

func TestReadyz(t *testing.T) {
	t.Parallel()

	router := httpapi.NewRouter("test-version")

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/readyz", nil))

	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestVersionEndpoint(t *testing.T) {
	t.Parallel()

	router := httpapi.NewRouter("1.2.3")

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/version", nil))

	require.Equal(t, http.StatusOK, rec.Code)

	var body httpapi.VersionResponse
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &body))
	assert.Equal(t, "1.2.3", body.Version)
}

func TestUnknownRouteReturns404(t *testing.T) {
	t.Parallel()

	router := httpapi.NewRouter("test-version")

	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/does-not-exist", nil))

	assert.Equal(t, http.StatusNotFound, rec.Code)
}
