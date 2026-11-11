package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/auth/authtest"
	"github.com/QuocDuong16/anve/server/internal/observability"
)

func scrape(t *testing.T, metrics *observability.Registry) string {
	t.Helper()
	request := httptest.NewRequest("GET", "http://127.0.0.1:9090/metrics", nil)
	request.RemoteAddr = "127.0.0.1:2000"
	response := httptest.NewRecorder()
	metrics.Handler().ServeHTTP(response, request)
	require.Equal(t, 200, response.Code)
	return response.Body.String()
}

func TestCompletionTelemetryDoesNotCopyRequestData(t *testing.T) {
	metrics := observability.New()
	var output bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&output, nil))
	router := chi.NewRouter()
	router.Use(middleware.RequestID)
	router.Use(observeHTTP(metrics, logger))
	router.Get("/journeys/{journeyId}", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(204) })
	metrics.RegisterRoute("GET", "/journeys/{journeyId}")
	request := httptest.NewRequest("GET", "/journeys/private-owner?lat=private-location", strings.NewReader("private-body"))
	request.Header.Set("Authorization", "Bearer private-credential")
	request.Header.Set("X-Request-ID", "private-request-id")
	request.RemoteAddr = "192.0.2.7:2100"
	router.ServeHTTP(httptest.NewRecorder(), request)
	combined := output.String() + scrape(t, metrics)
	for _, forbidden := range []string{"private-owner", "private-location", "private-body", "private-credential", "private-request-id", "192.0.2.7"} {
		assert.NotContains(t, combined, forbidden)
	}
	var entry map[string]any
	require.NoError(t, json.Unmarshal(output.Bytes(), &entry))
	assert.Len(t, entry, 9)
	assert.Equal(t, "/journeys/{journeyId}", entry["route"])
	assert.Equal(t, float64(204), entry["status"])
	assert.Contains(t, combined, `route="/journeys/{journeyId}"`)
}

func TestControlledPanicRecoveryAndIntentionalAborts(t *testing.T) {
	cases := []struct {
		name                   string
		handler                http.HandlerFunc
		abort                  bool
		panicCount, abortCount string
		status                 int
	}{
		{"panic before response", func(http.ResponseWriter, *http.Request) { panic(map[string]string{"detail": "private-panic"}) }, false, "1", "0", 500},
		{"panic after response", func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(202)
			_, _ = w.Write([]byte("prefix"))
			panic("private-panic")
		}, true, "1", "1", 202},
		{"intentional abort", func(http.ResponseWriter, *http.Request) { panic(http.ErrAbortHandler) }, true, "0", "1", 200},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			metrics := observability.New()
			var output bytes.Buffer
			router := chi.NewRouter()
			router.Use(observeHTTP(metrics, slog.New(slog.NewJSONHandler(&output, nil))))
			router.Get("/panic", tc.handler)
			metrics.RegisterRoute("GET", "/panic")
			response := httptest.NewRecorder()
			serve := func() { router.ServeHTTP(response, httptest.NewRequest("GET", "/panic?private-query", nil)) }
			if tc.abort {
				assert.PanicsWithValue(t, http.ErrAbortHandler, serve)
			} else {
				assert.NotPanics(t, serve)
			}
			assert.Equal(t, tc.status, response.Code)
			text := scrape(t, metrics)
			assert.Contains(t, text, "anve_http_panics_total "+tc.panicCount)
			assert.Contains(t, text, "anve_http_aborted_requests_total "+tc.abortCount)
			assert.Contains(t, text, "anve_http_requests_in_flight 0")
			assert.NotContains(t, output.String()+text+response.Body.String(), "private-panic")
			assert.NotContains(t, output.String()+text, "private-query")
			if !tc.abort {
				assert.JSONEq(t, `{"code":"INTERNAL","message":"Internal server error."}`, response.Body.String())
			}
		})
	}
}

func TestMetricsAreAbsentFromPublicRouter(t *testing.T) {
	metrics := observability.New()
	router := NewRouter(Deps{Metrics: metrics})
	response := httptest.NewRecorder()
	router.ServeHTTP(response, httptest.NewRequest("GET", "/metrics", nil))
	assert.Equal(t, 404, response.Code)
	response = httptest.NewRecorder()
	router.ServeHTTP(response, httptest.NewRequest("GET", "/healthz", nil))
	assert.Equal(t, 200, response.Code)
	text := scrape(t, metrics)
	assert.Contains(t, text, `route="/healthz",status_class="2xx"`)
	assert.Contains(t, text, `route="unmatched",status_class="4xx"`)
}

func TestEmptyAndInformationalResponsesHaveFinalStatus(t *testing.T) {
	for _, info := range []bool{false, true} {
		t.Run(map[bool]string{false: "implicit 200", true: "early hint"}[info], func(t *testing.T) {
			metrics := observability.New()
			router := chi.NewRouter()
			router.Use(observeHTTP(metrics, nil))
			router.Get("/empty", func(w http.ResponseWriter, _ *http.Request) {
				if info {
					w.WriteHeader(103)
					w.WriteHeader(200)
				}
			})
			metrics.RegisterRoute("GET", "/empty")
			router.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("GET", "/empty", nil))
			text := scrape(t, metrics)
			assert.Contains(t, text, `status_class="2xx"} 1`)
			assert.NotContains(t, text, `status_class="1xx"`)
		})
	}
}

func TestRouterWalkRegistersNestedPrivateTemplate(t *testing.T) {
	service := auth.NewService(authtest.NewStore(), nil, true)
	_, tokens, err := service.DevSignIn(context.Background(), "metrics-fixture@example.com", "Fixture", auth.DeviceRegistration{ClientDeviceID: "metrics-fixture"})
	require.NoError(t, err)
	metrics := observability.New()
	var logs bytes.Buffer
	router := NewRouter(Deps{Auth: service, Metrics: metrics, Logger: slog.New(slog.NewJSONHandler(&logs, nil))})
	deviceID := uuid.NewString()
	request := httptest.NewRequest("DELETE", "/api/v1/devices/"+deviceID, nil)
	request.Header.Set("Authorization", "Bearer "+tokens.AccessToken)
	response := httptest.NewRecorder()
	router.ServeHTTP(response, request)
	require.Equal(t, 404, response.Code, response.Body.String())
	text := scrape(t, metrics)
	assert.Contains(t, text, `route="/api/v1/devices/{deviceId}",status_class="4xx"`)
	assert.NotContains(t, logs.String()+text, deviceID)
	assert.NotContains(t, logs.String()+text, tokens.AccessToken)
}
