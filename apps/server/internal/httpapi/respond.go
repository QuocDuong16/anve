package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"

	"github.com/go-chi/chi/v5/middleware"

	"github.com/QuocDuong16/anve/server/internal/auth"
)

// Error codes of the structured error envelope. Stable API surface: clients
// switch on these, so names never change once shipped.
const (
	errCodeUnauthenticated = "UNAUTHENTICATED"
	errCodeValidation      = "VALIDATION"
	errCodeNotFound        = "NOT_FOUND"
	errCodeRateLimited     = "RATE_LIMITED"
	errCodeAuthConfig      = "AUTH_NOT_CONFIGURED"
	errCodeUnavailable     = "SERVICE_UNAVAILABLE"
	errCodeInternal        = "INTERNAL"
)

type errorBody struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	RequestID string `json:"requestId,omitempty"`
}

// writeJSON emits a JSON response body with the given status.
func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

// writeNoContent emits an empty 204 response.
func writeNoContent(w http.ResponseWriter) {
	w.WriteHeader(http.StatusNoContent)
}

// writeError maps a domain error onto the structured envelope. Unknown
// errors become a 500 without leaking internals.
func writeError(w http.ResponseWriter, r *http.Request, err error) {
	body := errorBody{RequestID: middleware.GetReqID(r.Context())}
	switch {
	case errors.Is(err, auth.ErrUnauthenticated):
		body.Code, body.Message = errCodeUnauthenticated, "Authentication required or credentials rejected."
		writeJSON(w, http.StatusUnauthorized, body)
	case errors.Is(err, auth.ErrNotFound):
		body.Code, body.Message = errCodeNotFound, "Resource not found."
		writeJSON(w, http.StatusNotFound, body)
	case errors.Is(err, auth.ErrValidation):
		body.Code = errCodeValidation
		body.Message = "Invalid request: " + err.Error()
		writeJSON(w, http.StatusBadRequest, body)
	case errors.Is(err, auth.ErrGoogleNotConfigured):
		body.Code, body.Message = errCodeAuthConfig, "Google sign-in is not configured on this server."
		writeJSON(w, http.StatusServiceUnavailable, body)
	default:
		body.Code, body.Message = errCodeInternal, "Internal server error."
		writeJSON(w, http.StatusInternalServerError, body)
	}
}

// writeValidationError emits the envelope for handler-level validation
// failures that never reach the service.
func writeValidationError(w http.ResponseWriter, r *http.Request, message string) {
	writeJSON(w, http.StatusBadRequest, errorBody{
		Code:      errCodeValidation,
		Message:   message,
		RequestID: middleware.GetReqID(r.Context()),
	})
}
