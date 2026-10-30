// Package httpapi wires the HTTP routes of the AnVề API server.
package httpapi

import (
	"context"
	"net/http"
	"net/netip"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/circles"
	"github.com/QuocDuong16/anve/server/internal/locationaccess"
	"github.com/QuocDuong16/anve/server/internal/locations"
	"github.com/QuocDuong16/anve/server/internal/places"
	"github.com/QuocDuong16/anve/server/internal/sos"
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

// Pinger reports whether a dependency is alive. *pgxpool.Pool satisfies it.
type Pinger interface {
	Ping(ctx context.Context) error
}

// Deps are the runtime dependencies of the router. Auth is nil when the
// server runs without a database: the business routes then answer 503 so
// the process can still serve health and version probes (ADR: the API
// server starts without a database until the first DB-backed feature).
type Deps struct {
	Version        string
	Auth           *auth.Service
	Circles        *circles.Service
	Locations      *locations.Service
	LocationAccess *locationaccess.Service
	Places         *places.Store
	SOS            *sos.Store
	PushConfigured bool
	DB             Pinger
	// DevLoginAllowedNetworks is the source-address allowlist for the
	// development login. An empty list denies every request: the fixture
	// fails closed rather than trusting the listener configuration alone.
	DevLoginAllowedNetworks []netip.Prefix
}

// authRateLimitPerMinute and authRateLimitBurst blunt sign-in abuse from a
// single address. Generous enough for a family sharing one NAT address.
const (
	authRateLimitPerMinute = 30
	authRateLimitBurst     = 30

	// Location batches are the steady-state traffic: one device uploads at
	// most a few batches per minute, so this allows a family behind one NAT
	// address while still bounding a runaway client. It is an abuse guard,
	// not a quota.
	locationRateLimitPerMinute = 120
	locationRateLimitBurst     = 60
)

// NewRouter builds the HTTP router for the API server.
func NewRouter(deps Deps) chi.Router {
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

	r.Get("/healthz", healthHandler(deps.Version))
	r.Get("/readyz", readyHandler(deps))

	r.Route("/api/v1", func(api chi.Router) {
		// Build metadata does not need the database and stays available in
		// every configuration.
		api.Get("/version", versionHandler(deps.Version))

		if deps.Auth == nil {
			// No database configured: the business endpoints exist in the
			// contract but cannot serve. Answer explicitly rather than 404
			// so a misconfigured deployment is diagnosable from the client.
			api.HandleFunc("/*", func(w http.ResponseWriter, _ *http.Request) {
				writeJSON(w, http.StatusServiceUnavailable, errorBody{
					Code:    errCodeUnavailable,
					Message: "Business endpoints need a configured database.",
				})
			})
			return
		}

		svc := deps.Auth
		authLimiter := newRateLimiter(authRateLimitPerMinute, authRateLimitBurst)

		api.Route("/auth", func(authGroup chi.Router) {
			authGroup.Get("/config", authConfigHandler(svc))
			authGroup.With(RateLimit(authLimiter)).Post("/google", googleSignInHandler(svc))
			authGroup.With(RateLimit(authLimiter)).Post("/refresh", refreshHandler(svc))
			// The development login is only mounted when explicitly enabled
			// in development (ADR 0002, ADR 0005); production never
			// registers it.
			// The network guard is a second, independent gate: even a
			// misconfigured listener cannot expose the fixture to an
			// unexpected source network.
			if svc.DevLoginEnabled() {
				authGroup.With(
					RateLimit(authLimiter),
					RequireDevNetwork(deps.DevLoginAllowedNetworks),
				).Post("/dev-login", devLoginHandler(svc))
			}
		})

		api.Group(func(private chi.Router) {
			private.Use(RequireAuth(svc))

			private.Post("/auth/signout", signOutHandler(svc))
			if deps.SOS != nil {
				limiter := newRateLimiter(authRateLimitPerMinute, authRateLimitBurst)
				private.Get("/notifications/status", func(w http.ResponseWriter, r *http.Request) {
					if _, ok := placeActor(w, r); !ok {
						return
					}
					w.Header().Set("Cache-Control", "no-store, private")
					writeJSON(w, http.StatusOK, struct {
						Configured bool `json:"configured"`
					}{deps.PushConfigured})
				})
				private.With(RateLimit(limiter)).Get("/sos", sosCollection(deps.SOS))
				private.With(RateLimit(limiter)).Post("/sos", sosCollection(deps.SOS))
				private.Get("/sos/{eventId}", sosDetail(deps.SOS))
				private.Get("/sos/{eventId}/recipients", sosRecipients(deps.SOS))
				private.With(RateLimit(limiter)).Delete("/sos/{eventId}/recipients/{recipientId}", revokeSosRecipient(deps.SOS))
				private.With(RateLimit(limiter)).Post("/sos/{eventId}/{action}", sosDetail(deps.SOS))
				private.With(RateLimit(limiter)).Put("/devices/push-token", pushTokenHandler(deps.SOS))
				private.With(RateLimit(limiter)).Get("/devices/push-token", pushTokenHandler(deps.SOS))
				private.With(RateLimit(limiter)).Delete("/devices/push-token", pushTokenHandler(deps.SOS))
			}

			private.Route("/me", func(me chi.Router) {
				me.Get("/", getMeHandler())
				me.Patch("/", updateMeHandler(svc))
				me.Delete("/", deleteMeHandler(svc))
				me.Put("/location-sharing", setLocationSharingHandler(svc))
			})

			private.Route("/devices", func(devices chi.Router) {
				devices.Get("/", listDevicesHandler(svc))
				devices.Delete("/{deviceId}", deleteDeviceHandler(svc))
			})

			if deps.Locations != nil {
				locationSvc := deps.Locations
				private.Get("/me/location-history/retention", historyRetentionHandler(locationSvc))
				historyLimiter := newRateLimiter(locationRateLimitPerMinute, locationRateLimitBurst)
				private.With(RateLimit(historyLimiter)).Get("/me/location-capture-generation", captureGenerationHandler(locationSvc))
				private.With(RateLimit(historyLimiter)).Put("/me/location-history/retention", historyRetentionHandler(locationSvc))
				private.With(RateLimit(historyLimiter)).Delete("/me/location-history", deleteLocationHistoryHandler(locationSvc))
				locationLimiter := newRateLimiter(locationRateLimitPerMinute, locationRateLimitBurst)
				private.With(RateLimit(locationLimiter)).
					Post("/locations/batch", ingestLocationBatchHandler(locationSvc))
			}

			if deps.LocationAccess != nil {
				access := deps.LocationAccess
				private.Get("/location-sharing/audience", audienceHandler(access))
				private.Put("/location-sharing/audience/{viewerId}", grantAudienceHandler(access))
				private.Delete("/location-sharing/audience/{viewerId}", revokeAudienceHandler(access))
				private.Get("/locations/visible/latest", latestVisibleLocationsHandler(access))
				historyReadLimiter := newRateLimiter(locationRateLimitPerMinute, locationRateLimitBurst)
				private.With(RateLimit(historyReadLimiter)).Get("/locations/history", historyHandler(access))
			}

			if deps.Places != nil {
				placeLimiter := newRateLimiter(locationRateLimitPerMinute, locationRateLimitBurst)
				private.With(RateLimit(placeLimiter)).Get("/places", listPlacesHandler(deps.Places))
				private.With(RateLimit(placeLimiter)).Post("/places", createPlaceHandler(deps.Places))
				private.With(RateLimit(placeLimiter)).Get("/places/events", placeEventsHandler(deps.Places))
				private.With(RateLimit(placeLimiter)).Get("/places/events/{eventId}", placeEventHandler(deps.Places))
				private.With(RateLimit(placeLimiter)).Patch("/places/{placeId}", updatePlaceHandler(deps.Places))
				private.With(RateLimit(placeLimiter)).Delete("/places/{placeId}", deletePlaceHandler(deps.Places))
				private.With(RateLimit(placeLimiter)).Get("/places/{placeId}/access", listPlaceAccessHandler(deps.Places))
				private.With(RateLimit(placeLimiter)).Put("/places/{placeId}/access/{memberId}", placeAccessHandler(deps.Places, true))
				private.With(RateLimit(placeLimiter)).Delete("/places/{placeId}/access/{memberId}", placeAccessHandler(deps.Places, false))
				private.With(RateLimit(placeLimiter)).Get("/places/{placeId}/monitor", getPlaceMonitorHandler(deps.Places))
				private.With(RateLimit(placeLimiter)).Get("/places/{placeId}/eligible-recipients", eligiblePlaceRecipientsHandler(deps.Places))
				private.With(RateLimit(placeLimiter)).Put("/places/{placeId}/monitor", setPlaceMonitorHandler(deps.Places))
				private.With(RateLimit(placeLimiter)).Delete("/places/{placeId}/monitor", stopPlaceMonitorHandler(deps.Places))
			}

			if deps.Circles != nil {
				circleSvc := deps.Circles
				private.Route("/circles", func(c chi.Router) {
					c.Post("/", createCircleHandler(circleSvc))
					c.Get("/mine", myCircleHandler(circleSvc))
					c.Patch("/{circleId}", updateCircleHandler(circleSvc))
					c.Get("/{circleId}/members", listMembersHandler(circleSvc))
					c.Post("/{circleId}/members/{userId}/remove", removeMemberHandler(circleSvc))
					c.Post("/{circleId}/leave", leaveCircleHandler(circleSvc))
					c.Post("/{circleId}/invites", createInviteHandler(circleSvc))
					c.Get("/{circleId}/invites", listInvitesHandler(circleSvc))
				})
				private.Route("/invites", func(inv chi.Router) {
					inv.Delete("/{inviteId}", revokeInviteHandler(circleSvc))
					inv.Post("/redeem", redeemInviteHandler(circleSvc))
				})
			}
		})
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

// readyHandler adds the database round-trip to readiness now that the
// business endpoints are database-backed. Without a configured database the
// server is never ready to serve business traffic.
func readyHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if deps.DB == nil {
			writeJSON(w, http.StatusServiceUnavailable, errorBody{
				Code:      errCodeUnavailable,
				Message:   "Database is not configured.",
				RequestID: middleware.GetReqID(r.Context()),
			})
			return
		}
		if err := deps.DB.Ping(r.Context()); err != nil {
			writeJSON(w, http.StatusServiceUnavailable, errorBody{
				Code:      errCodeUnavailable,
				Message:   "Database is not reachable.",
				RequestID: middleware.GetReqID(r.Context()),
			})
			return
		}
		writeJSON(w, http.StatusOK, HealthResponse{
			Status:  "ok",
			Version: deps.Version,
			Time:    time.Now().UTC().Format(time.RFC3339),
		})
	}
}

func versionHandler(version string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, VersionResponse{Version: version})
	}
}
