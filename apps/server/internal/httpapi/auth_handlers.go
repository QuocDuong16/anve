package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"github.com/QuocDuong16/anve/server/internal/auth"
)

// maxAuthBodyBytes bounds the auth request bodies; they are tiny JSON
// documents, so anything larger is noise or an attack.
const maxAuthBodyBytes = 8 * 1024

// ── request/response payloads ────────────────────────────────────────────────

type deviceRegistrationBody struct {
	ClientDeviceID string `json:"clientDeviceId"`
	Name           string `json:"name"`
	Platform       string `json:"platform"`
	AppVersion     string `json:"appVersion"`
}

func (d deviceRegistrationBody) toDomain() auth.DeviceRegistration {
	return auth.DeviceRegistration{
		ClientDeviceID: d.ClientDeviceID,
		Name:           d.Name,
		Platform:       d.Platform,
		AppVersion:     d.AppVersion,
	}
}

type googleSignInRequest struct {
	IDToken string                 `json:"idToken"`
	Device  deviceRegistrationBody `json:"device"`
}

type devLoginRequest struct {
	Email       string                 `json:"email"`
	DisplayName string                 `json:"displayName"`
	Device      deviceRegistrationBody `json:"device"`
}

type refreshRequest struct {
	RefreshToken string `json:"refreshToken"`
}

type updateMeRequest struct {
	DisplayName *string `json:"displayName"`
}

type confirmDeleteRequest struct {
	Confirm *bool `json:"confirm"`
}

type locationSharingRequest struct {
	Enabled *bool `json:"enabled"`
}

type userResponse struct {
	ID                     string  `json:"id"`
	Email                  string  `json:"email"`
	DisplayName            string  `json:"displayName"`
	AvatarURL              *string `json:"avatarUrl,omitempty"`
	LocationSharingEnabled bool    `json:"locationSharingEnabled"`
	CreatedAt              string  `json:"createdAt"`
}

type sessionTokensResponse struct {
	AccessToken      string `json:"accessToken"`
	RefreshToken     string `json:"refreshToken"`
	AccessExpiresAt  string `json:"accessExpiresAt"`
	RefreshExpiresAt string `json:"refreshExpiresAt"`
}

type signInResponse struct {
	User    userResponse          `json:"user"`
	Session sessionTokensResponse `json:"session"`
}

type refreshResponse struct {
	Session sessionTokensResponse `json:"session"`
}

type authConfigResponse struct {
	GoogleSignIn bool `json:"googleSignIn"`
	DevLogin     bool `json:"devLogin"`
}

type deviceSessionResponse struct {
	ID         string `json:"id"`
	CreatedAt  string `json:"createdAt"`
	LastUsedAt string `json:"lastUsedAt"`
	ExpiresAt  string `json:"expiresAt"`
	Current    bool   `json:"current"`
}

type deviceResponse struct {
	ID         string                 `json:"id"`
	Name       string                 `json:"name"`
	Platform   string                 `json:"platform"`
	AppVersion string                 `json:"appVersion,omitempty"`
	CreatedAt  string                 `json:"createdAt"`
	LastSeenAt string                 `json:"lastSeenAt"`
	Session    *deviceSessionResponse `json:"session,omitempty"`
}

type deviceListResponse struct {
	Devices []deviceResponse `json:"devices"`
}

func toUserResponse(u auth.User) userResponse {
	return userResponse{
		ID:                     u.ID.String(),
		Email:                  u.Email,
		DisplayName:            u.DisplayName,
		AvatarURL:              u.AvatarURL,
		LocationSharingEnabled: u.LocationSharingEnabled,
		CreatedAt:              u.CreatedAt.UTC().Format(timeFormat),
	}
}

func toSessionResponse(t auth.SessionTokens) sessionTokensResponse {
	return sessionTokensResponse{
		AccessToken:      t.AccessToken,
		RefreshToken:     t.RefreshToken,
		AccessExpiresAt:  t.AccessExpiresAt.UTC().Format(timeFormat),
		RefreshExpiresAt: t.RefreshExpiresAt.UTC().Format(timeFormat),
	}
}

// timeFormat is the RFC 3339 layout used by every timestamp in responses.
const timeFormat = "2006-01-02T15:04:05Z07:00"

// ── handlers ─────────────────────────────────────────────────────────────────

func authConfigHandler(svc *auth.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, authConfigResponse{
			GoogleSignIn: svc.GoogleEnabled(),
			DevLogin:     svc.DevLoginEnabled(),
		})
	}
}

func googleSignInHandler(svc *auth.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var body googleSignInRequest
		if !decodeAuthBody(w, r, &body) {
			return
		}
		user, tokens, err := svc.SignInWithGoogle(r.Context(), body.IDToken, body.Device.toDomain())
		if err != nil {
			writeError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, signInResponse{
			User:    toUserResponse(user),
			Session: toSessionResponse(tokens),
		})
	}
}

func devLoginHandler(svc *auth.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var body devLoginRequest
		if !decodeAuthBody(w, r, &body) {
			return
		}
		user, tokens, err := svc.DevSignIn(r.Context(), body.Email, body.DisplayName, body.Device.toDomain())
		if err != nil {
			writeError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, signInResponse{
			User:    toUserResponse(user),
			Session: toSessionResponse(tokens),
		})
	}
}

func refreshHandler(svc *auth.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var body refreshRequest
		if !decodeAuthBody(w, r, &body) {
			return
		}
		tokens, err := svc.Refresh(r.Context(), body.RefreshToken)
		if err != nil {
			writeError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, refreshResponse{Session: toSessionResponse(tokens)})
	}
}

func signOutHandler(svc *auth.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, ok := AuthFromContext(r.Context())
		if !ok {
			writeError(w, r, auth.ErrUnauthenticated)
			return
		}
		if err := svc.SignOut(r.Context(), ac.Session); err != nil {
			writeError(w, r, err)
			return
		}
		writeNoContent(w)
	}
}

func getMeHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, _ := AuthFromContext(r.Context())
		writeJSON(w, http.StatusOK, toUserResponse(ac.User))
	}
}

func updateMeHandler(svc *auth.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, _ := AuthFromContext(r.Context())
		var body updateMeRequest
		if !decodeAuthBody(w, r, &body) {
			return
		}
		if body.DisplayName == nil {
			writeValidationError(w, r, "displayName is required.")
			return
		}
		user, err := svc.UpdateProfile(r.Context(), ac.User.ID, *body.DisplayName)
		if err != nil {
			writeError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, toUserResponse(user))
	}
}

func deleteMeHandler(svc *auth.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, _ := AuthFromContext(r.Context())
		var body confirmDeleteRequest
		if !decodeAuthBody(w, r, &body) {
			return
		}
		if body.Confirm == nil || !*body.Confirm {
			writeValidationError(w, r, "confirm must be true to delete the account.")
			return
		}
		if err := svc.DeleteAccount(r.Context(), ac.User.ID); err != nil {
			writeError(w, r, err)
			return
		}
		writeNoContent(w)
	}
}

func setLocationSharingHandler(svc *auth.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, _ := AuthFromContext(r.Context())
		var body locationSharingRequest
		if !decodeAuthBody(w, r, &body) {
			return
		}
		if body.Enabled == nil {
			writeValidationError(w, r, "enabled is required.")
			return
		}
		user, err := svc.SetLocationSharing(r.Context(), ac.User.ID, *body.Enabled)
		if err != nil {
			writeError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, toUserResponse(user))
	}
}

func listDevicesHandler(svc *auth.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, _ := AuthFromContext(r.Context())
		devices, err := svc.ListDevices(r.Context(), ac.User.ID, ac.Session.ID)
		if err != nil {
			writeError(w, r, err)
			return
		}
		out := make([]deviceResponse, 0, len(devices))
		for _, d := range devices {
			entry := deviceResponse{
				ID:         d.Device.ID.String(),
				Name:       d.Device.Name,
				Platform:   d.Device.Platform,
				AppVersion: d.Device.AppVersion,
				CreatedAt:  d.Device.CreatedAt.UTC().Format(timeFormat),
				LastSeenAt: d.Device.LastSeenAt.UTC().Format(timeFormat),
			}
			if d.Session != nil {
				entry.Session = &deviceSessionResponse{
					ID:         d.Session.ID.String(),
					CreatedAt:  d.Session.CreatedAt.UTC().Format(timeFormat),
					LastUsedAt: d.Session.LastUsedAt.UTC().Format(timeFormat),
					ExpiresAt:  d.Session.RefreshExpiresAt.UTC().Format(timeFormat),
					Current:    d.Current,
				}
			}
			out = append(out, entry)
		}
		writeJSON(w, http.StatusOK, deviceListResponse{Devices: out})
	}
}

func deleteDeviceHandler(svc *auth.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, _ := AuthFromContext(r.Context())
		deviceID, err := uuid.Parse(chi.URLParam(r, "deviceId"))
		if err != nil {
			writeValidationError(w, r, "deviceId must be a UUID.")
			return
		}
		if err := svc.RevokeDevice(r.Context(), ac.User.ID, deviceID); err != nil {
			writeError(w, r, err)
			return
		}
		writeNoContent(w)
	}
}

// decodeAuthBody decodes a bounded JSON body; malformed or oversized
// payloads answer 400 without reaching the service.
func decodeAuthBody(w http.ResponseWriter, r *http.Request, dst any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxAuthBodyBytes)
	if err := json.NewDecoder(r.Body).Decode(dst); err != nil {
		var maxErr *http.MaxBytesError
		switch {
		case errors.As(err, &maxErr):
			writeValidationError(w, r, "Request body too large.")
		default:
			writeValidationError(w, r, "Request body must be valid JSON.")
		}
		return false
	}
	return true
}
