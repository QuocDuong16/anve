package httpapi

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"

	"github.com/QuocDuong16/anve/server/internal/observability"
)

func observeHTTP(metrics *observability.Registry, logger *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			started := time.Now()
			metrics.Begin()
			response := middleware.NewWrapResponseWriter(w, r.ProtoMajor)
			defer func() {
				recovered := recover()
				recoveredErr, isError := recovered.(error)
				intentionalAbort := isError && errors.Is(recoveredErr, http.ErrAbortHandler)
				panicked := recovered != nil && !intentionalAbort
				abort := intentionalAbort || (panicked && response.Status() != 0)
				if panicked && !abort {
					writeJSON(response, http.StatusInternalServerError, errorBody{Code: errCodeInternal, Message: "Internal server error."})
				}
				status := response.Status()
				if status == 0 && !abort {
					status = http.StatusOK
				}
				route := ""
				if routing := chi.RouteContext(r.Context()); routing != nil {
					route = routing.RoutePattern()
				}
				elapsed := time.Since(started)
				labels := metrics.End(r.Method, route, status, elapsed, panicked, abort)
				if logger != nil {
					level := slog.LevelInfo
					if abort {
						level = slog.LevelWarn
					}
					if panicked {
						level = slog.LevelError
					}
					// The allowlist excludes request IDs too: chi accepts a
					// caller-supplied X-Request-ID, which could contain PII.
					logger.Log(context.Background(), level, "http request completed",
						"method", labels.Method, "route", labels.Route, "status", status,
						"duration_ms", elapsed.Milliseconds(), "panicked", panicked, "aborted", abort)
				}
				if abort {
					panic(http.ErrAbortHandler)
				}
			}()
			next.ServeHTTP(response, r)
		})
	}
}
