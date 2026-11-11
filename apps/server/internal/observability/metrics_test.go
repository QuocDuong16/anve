package observability

import (
	"fmt"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	dto "github.com/prometheus/client_model/go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func gather(t *testing.T, registry *Registry) map[string]*dto.MetricFamily {
	t.Helper()
	rows, err := registry.collectors.Gather()
	require.NoError(t, err)
	result := make(map[string]*dto.MetricFamily)
	for _, row := range rows {
		result[row.GetName()] = row
	}
	return result
}

func TestMetricsExposeOnlyRegisteredTemplatesAndFixedLabels(t *testing.T) {
	registry := New()
	registry.RegisterRoute("GET", "/journeys/{journeyId}")
	registry.Begin()
	labels := registry.End("GET", "/journeys/{journeyId}", 200, 20*time.Millisecond, false, false)
	assert.Equal(t, Labels{"GET", "/journeys/{journeyId}"}, labels)
	metrics := gather(t, registry)
	require.Len(t, metrics, 5, "default Go/process collectors must not be registered")
	counter := metrics["anve_http_requests_total"].Metric[0]
	assert.Equal(t, float64(1), counter.Counter.GetValue())
	values := map[string]string{}
	for _, label := range counter.Label {
		values[label.GetName()] = label.GetValue()
	}
	assert.Equal(t, map[string]string{"method": "GET", "route": "/journeys/{journeyId}", "status_class": "2xx"}, values)
	histogram := metrics["anve_http_request_duration_seconds"].Metric[0].Histogram
	assert.Equal(t, uint64(1), histogram.GetSampleCount())
	assert.InDelta(t, 0.02, histogram.GetSampleSum(), 0.00001)
	assert.Zero(t, histogram.Bucket[1].GetCumulativeCount())
	assert.Equal(t, uint64(1), histogram.Bucket[2].GetCumulativeCount())
	assert.Zero(t, metrics["anve_http_requests_in_flight"].Metric[0].Gauge.GetValue())
}

func TestUntrustedPathsAndMethodsDoNotCreateSeries(t *testing.T) {
	registry := New()
	registry.RegisterRoute("GET", "/known/{id}")
	for i := 0; i < 2000; i++ {
		registry.Begin()
		labels := registry.End(fmt.Sprintf("METHOD%d", i), fmt.Sprintf("/private/person-%d?coordinates=private", i), 404, time.Millisecond, false, false)
		require.Equal(t, Labels{"OTHER", "unmatched"}, labels)
	}
	metrics := gather(t, registry)
	require.Len(t, metrics["anve_http_requests_total"].Metric, 1)
	require.Len(t, metrics["anve_http_request_duration_seconds"].Metric, 1)
	assert.Equal(t, float64(2000), metrics["anve_http_requests_total"].Metric[0].Counter.GetValue())
}

func TestRouteRegistrationAndStatusCardinalityAreBounded(t *testing.T) {
	registry := New()
	for i := 0; i < maxRoutes+100; i++ {
		registry.RegisterRoute("GET", fmt.Sprintf("/fixed/%d", i))
	}
	require.Len(t, registry.routes, maxRoutes)
	for i := 0; i < maxRoutes+100; i++ {
		for _, status := range []int{0, 101, 200, 204, 302, 401, 429, 500, 503, 999} {
			registry.Begin()
			registry.End("GET", fmt.Sprintf("/fixed/%d", i), status, time.Millisecond, false, false)
		}
	}
	metrics := gather(t, registry)
	assert.Len(t, metrics["anve_http_requests_total"].Metric, (maxRoutes+1)*6)
	assert.Len(t, metrics["anve_http_request_duration_seconds"].Metric, maxRoutes+1)
}

func TestConcurrentRequestsAndScrapes(t *testing.T) {
	registry := New()
	registry.RegisterRoute("POST", "/locations/batch")
	var group sync.WaitGroup
	for range 40 {
		group.Go(func() {
			for range 50 {
				registry.Begin()
				registry.End("POST", "/locations/batch", 202, time.Millisecond, false, false)
				_, err := registry.collectors.Gather()
				assert.NoError(t, err)
			}
		})
	}
	group.Wait()
	metrics := gather(t, registry)
	assert.Equal(t, float64(2000), metrics["anve_http_requests_total"].Metric[0].Counter.GetValue())
	assert.Zero(t, metrics["anve_http_requests_in_flight"].Metric[0].Gauge.GetValue())
}

func TestOperatorAccessUsesRealPeerAndLiteralHost(t *testing.T) {
	handler := New().Handler()
	cases := []struct {
		name, peer, host, origin, method, path string
		status                                 int
	}{
		{"ipv4", "127.0.0.1:2000", "127.0.0.1:9090", "", "GET", "/metrics", 200},
		{"ipv6", "[::1]:2000", "[::1]:9090", "", "GET", "/metrics", 200},
		{"remote", "192.0.2.5:2000", "127.0.0.1:9090", "", "GET", "/metrics", 403},
		{"dns rebinding", "127.0.0.1:2000", "attacker.invalid:9090", "", "GET", "/metrics", 403},
		{"localhost name", "127.0.0.1:2000", "localhost:9090", "", "GET", "/metrics", 403},
		{"browser", "127.0.0.1:2000", "127.0.0.1:9090", "https://attacker.invalid", "GET", "/metrics", 403},
		{"post", "127.0.0.1:2000", "127.0.0.1:9090", "", "POST", "/metrics", 405},
		{"other path", "127.0.0.1:2000", "127.0.0.1:9090", "", "GET", "/readyz", 404},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			request := httptest.NewRequest(tc.method, "http://127.0.0.1:9090"+tc.path, nil)
			request.RemoteAddr, request.Host = tc.peer, tc.host
			request.Header.Set("Origin", tc.origin)
			request.Header.Set("X-Forwarded-For", "127.0.0.1")
			request.Header.Set("X-Real-IP", "127.0.0.1")
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, request)
			assert.Equal(t, tc.status, response.Code)
			assert.Equal(t, "no-store, private", response.Header().Get("Cache-Control"))
		})
	}
}
