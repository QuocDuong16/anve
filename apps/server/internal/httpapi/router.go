// Package httpapi wires the HTTP routes of the AnVề API server.
package httpapi

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
)

// HealthResponse is the payload returned by the health endpoints.
type HealthResponse struct {
	Status  string `json:"status"`
	Version string `json:"version"`
	Time    string `json:"time"`
}

// VersionResponse describes the running build.
type VersionResponse struct {
	Version string `json:"version"`
}

// NewRouter builds the HTTP router for the API server.
//
// version is the build version reported by the version and health endpoints.
func NewRouter(version string) chi.Router {
	r := chi.NewRouter()

	r.Use(middleware.RequestID)
	r.Use(middleware.Recoverer)
	// Hard 15s ceiling for the current JSON API. Long-lived connection
	// endpoints (the planned realtime WebSockets) do not fit a per-request
	// deadline and need their own timeout and lifecycle design before they
	// land on this router.
	r.Use(middleware.Timeout(15 * time.Second))
	// middleware.RealIP is deliberately NOT used: it trusts X-Forwarded-For
	// unconditionally, so any client could spoof its own address. A trusted
	// proxy CIDR allowlist is added when the API is deployed behind a proxy.

	r.Get("/healthz", healthHandler(version))
	r.Get("/readyz", healthHandler(version))

	r.Route("/api/v1", func(api chi.Router) {
		api.Get("/version", versionHandler(version))
	})

	return r
}

func healthHandler(version string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, HealthResponse{
			Status:  "ok",
			Version: version,
			Time:    time.Now().UTC().Format(time.RFC3339),
		})
	}
}

func versionHandler(version string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, VersionResponse{Version: version})
	}
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}
