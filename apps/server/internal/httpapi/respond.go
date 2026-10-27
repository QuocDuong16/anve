package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"

	"github.com/go-chi/chi/v5/middleware"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/circles"
	"github.com/QuocDuong16/anve/server/internal/locations"
)

// Error codes of the structured error envelope. Stable API surface: clients
// switch on these, so names never change once shipped.
const (
	errCodeUnauthenticated        = "UNAUTHENTICATED"
	errCodeValidation             = "VALIDATION"
	errCodeNotFound               = "NOT_FOUND"
	errCodeRateLimited            = "RATE_LIMITED"
	errCodeAuthConfig             = "AUTH_NOT_CONFIGURED"
	errCodeUnavailable            = "SERVICE_UNAVAILABLE"
	errCodeInternal               = "INTERNAL"
	errCodeConsentVersionConflict = "CONSENT_VERSION_CONFLICT"
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
	case errors.Is(err, auth.ErrCircleHasMembers):
		body.Code = "CIRCLE_HAS_MEMBERS"
		body.Message = "The account owns a circle that still has members. Dissolve the circle before deleting the account."
		writeJSON(w, http.StatusConflict, body)
	case errors.Is(err, auth.ErrIdentityConflict):
		body.Code = "IDENTITY_CONFLICT"
		body.Message = "The email already belongs to an account of another identity type."
		writeJSON(w, http.StatusConflict, body)
	case errors.Is(err, auth.ErrConsentVersionConflict):
		body.Code, body.Message = errCodeConsentVersionConflict, "The consent state changed; read it again before retrying."
		writeJSON(w, http.StatusConflict, body)
	case errors.Is(err, circles.ErrForbidden):
		body.Code, body.Message = "FORBIDDEN", "This action requires the circle owner role."
		writeJSON(w, http.StatusForbidden, body)
	case errors.Is(err, circles.ErrActiveCircleExists):
		body.Code, body.Message = "ACTIVE_CIRCLE_EXISTS", "The account already belongs to a circle."
		writeJSON(w, http.StatusConflict, body)
	case errors.Is(err, circles.ErrInviteUnavailable):
		body.Code, body.Message = "INVITE_UNAVAILABLE", "The invite code is unknown, expired or already used."
		writeJSON(w, http.StatusConflict, body)
	case errors.Is(err, circles.ErrOwnerMustDissolve):
		body.Code, body.Message = "CIRCLE_HAS_MEMBERS", "The owner must confirm dissolution with dissolve=true."
		writeJSON(w, http.StatusConflict, body)
	case errors.Is(err, locations.ErrBatchTooLarge):
		body.Code, body.Message = "LOCATION_BATCH_TOO_LARGE", "The batch exceeds the allowed number of events."
		writeJSON(w, http.StatusBadRequest, body)
	case errors.Is(err, locations.ErrEventTooOld):
		body.Code, body.Message = "LOCATION_EVENT_TOO_OLD", "The location is older than the retention window."
		writeJSON(w, http.StatusBadRequest, body)
	case errors.Is(err, locations.ErrBatchInvalid):
		body.Code, body.Message = "LOCATION_BATCH_INVALID", "The location batch is invalid: "+err.Error()
		writeJSON(w, http.StatusBadRequest, body)
	case errors.Is(err, locations.ErrConsentRequired):
		body.Code, body.Message = "LOCATION_CONSENT_REQUIRED", "Location sharing is disabled for this account."
		writeJSON(w, http.StatusConflict, body)
	case errors.Is(err, locations.ErrCircleRequired):
		body.Code, body.Message = "LOCATION_CIRCLE_REQUIRED", "The account has no active Family Circle."
		writeJSON(w, http.StatusConflict, body)
	case errors.Is(err, locations.ErrDeviceNotAuthorized):
		body.Code, body.Message = "DEVICE_NOT_AUTHORIZED", "The session or device is not authorized for this batch."
		writeJSON(w, http.StatusForbidden, body)
	case errors.Is(err, locations.ErrTemporarilyUnavailable):
		body.Code, body.Message = "LOCATION_TEMPORARILY_UNAVAILABLE", "The location batch was not stored. Retry later."
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
