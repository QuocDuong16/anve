// Package observability owns bounded process-local operational metrics.
package observability

import (
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

const maxRoutes = 512

type routeKey struct{ method, route string }

// Labels are canonical application-owned values safe for completion logs.
type Labels struct{ Method, Route string }

// Registry deliberately excludes default process/runtime collectors and all
// request-supplied label values. Route registration happens before serving.
type Registry struct {
	collectors *prometheus.Registry
	mu         sync.RWMutex
	routes     map[routeKey]struct{}
	requests   *prometheus.CounterVec
	duration   *prometheus.HistogramVec
	inFlight   prometheus.Gauge
	panics     prometheus.Counter
	aborted    prometheus.Counter
}

// New creates an isolated registry without global or process collectors.
func New() *Registry {
	r := &Registry{
		collectors: prometheus.NewRegistry(),
		routes:     make(map[routeKey]struct{}),
		requests: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "anve_http_requests_total", Help: "Completed requests by registered route and response status class.",
		}, []string{"method", "route", "status_class"}),
		duration: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name: "anve_http_request_duration_seconds", Help: "Total HTTP handler duration, not durable ingestion or GPS latency.",
			Buckets: []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 15},
		}, []string{"method", "route"}),
		inFlight: prometheus.NewGauge(prometheus.GaugeOpts{Name: "anve_http_requests_in_flight", Help: "API requests currently executing."}),
		panics:   prometheus.NewCounter(prometheus.CounterOpts{Name: "anve_http_panics_total", Help: "Recovered handler panics excluding net/http intentional aborts."}),
		aborted:  prometheus.NewCounter(prometheus.CounterOpts{Name: "anve_http_aborted_requests_total", Help: "Requests whose response stream was aborted."}),
	}
	r.collectors.MustRegister(r.requests, r.duration, r.inFlight, r.panics, r.aborted)
	return r
}

// RegisterRoute admits only application-owned templates. Request URLs must never
// call this function; a missing or full registration falls back to unmatched.
func (r *Registry) RegisterRoute(method, route string) {
	if canonicalMethod(method) == "OTHER" || !strings.HasPrefix(route, "/") || len(route) > 256 || strings.ContainsAny(route, "\"\n\r\t") {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.routes) < maxRoutes {
		r.routes[routeKey{method, route}] = struct{}{}
	}
}

// Begin admits one in-flight API request; its completion must call End.
func (r *Registry) Begin() { r.inFlight.Inc() }

// End returns the same canonical labels used by metrics, so completion logs
// cannot accidentally fall back to the request's raw path or method.
func (r *Registry) End(method, route string, status int, duration time.Duration, panicked, aborted bool) Labels {
	defer r.inFlight.Dec()
	labels := Labels{Method: canonicalMethod(method), Route: "unmatched"}
	r.mu.RLock()
	_, registered := r.routes[routeKey{labels.Method, route}]
	r.mu.RUnlock()
	if registered {
		labels.Route = route
	}
	if duration < 0 {
		duration = 0
	}
	r.requests.WithLabelValues(labels.Method, labels.Route, statusClass(status)).Inc()
	r.duration.WithLabelValues(labels.Method, labels.Route).Observe(duration.Seconds())
	if panicked {
		r.panics.Inc()
	}
	if aborted {
		r.aborted.Inc()
	}
	return labels
}

func canonicalMethod(method string) string {
	switch method {
	case http.MethodGet, http.MethodHead, http.MethodPost, http.MethodPut, http.MethodPatch, http.MethodDelete, http.MethodOptions, http.MethodConnect, http.MethodTrace:
		return method
	default:
		return "OTHER"
	}
}

func statusClass(status int) string {
	switch {
	case status >= 100 && status < 200:
		return "1xx"
	case status >= 200 && status < 300:
		return "2xx"
	case status >= 300 && status < 400:
		return "3xx"
	case status >= 400 && status < 500:
		return "4xx"
	case status >= 500 && status < 600:
		return "5xx"
	default:
		return "none"
	}
}

// Handler belongs only on the dedicated loopback listener. Scrapes are not
// routed through API instrumentation and do not create recursive traffic.
func (r *Registry) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.Handle("GET /metrics", promhttp.HandlerFor(r.collectors, promhttp.HandlerOpts{
		MaxRequestsInFlight: 2, Timeout: 5 * time.Second, DisableCompression: true,
	}))
	return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		w.Header().Set("Cache-Control", "no-store, private")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		// Literal Host validation also prevents a browser DNS-rebinding
		// origin from reading the operator surface through loopback.
		if !loopbackAuthority(req.RemoteAddr) || !loopbackAuthority(req.Host) || req.Header.Get("Origin") != "" {
			http.Error(w, "Operator access required.", http.StatusForbidden)
			return
		}
		mux.ServeHTTP(w, req)
	})
}

func loopbackAuthority(authority string) bool {
	host := authority
	if parsed, _, err := net.SplitHostPort(authority); err == nil {
		host = parsed
	} else if strings.HasPrefix(host, "[") && strings.HasSuffix(host, "]") {
		host = strings.TrimSuffix(strings.TrimPrefix(host, "["), "]")
	}
	ip, err := netip.ParseAddr(host)
	return err == nil && ip.Zone() == "" && ip.Unmap().IsLoopback()
}
