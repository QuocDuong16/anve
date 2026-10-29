package httpapi

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/locationaccess"
)

type audienceResponse struct {
	Grants []locationaccess.Grant `json:"grants"`
}

type latestLocationsResponse struct {
	Locations []locationaccess.VisibleLocation `json:"locations"`
}

func audienceHandler(svc *locationaccess.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, ok := AuthFromContext(r.Context())
		if !ok {
			writeError(w, r, auth.ErrUnauthenticated)
			return
		}
		grants, err := svc.Audience(r.Context(), ac.User.ID)
		if err != nil {
			writeError(w, r, err)
			return
		}
		if grants == nil {
			grants = []locationaccess.Grant{}
		}
		w.Header().Set("Cache-Control", "no-store, private")
		writeJSON(w, http.StatusOK, audienceResponse{Grants: grants})
	}
}

func grantAudienceHandler(svc *locationaccess.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, ok := AuthFromContext(r.Context())
		if !ok {
			writeError(w, r, auth.ErrUnauthenticated)
			return
		}
		if !matchesExpectedOwner(w, r, ac.User.ID) {
			return
		}
		viewerID, err := uuid.Parse(chi.URLParam(r, "viewerId"))
		if err != nil {
			writeError(w, r, auth.ErrValidation)
			return
		}
		grant, err := svc.Grant(r.Context(), ac.User.ID, viewerID)
		if err != nil {
			writeError(w, r, err)
			return
		}
		w.Header().Set("Cache-Control", "no-store, private")
		writeJSON(w, http.StatusOK, grant)
	}
}

func revokeAudienceHandler(svc *locationaccess.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, ok := AuthFromContext(r.Context())
		if !ok {
			writeError(w, r, auth.ErrUnauthenticated)
			return
		}
		if !matchesExpectedOwner(w, r, ac.User.ID) {
			return
		}
		viewerID, err := uuid.Parse(chi.URLParam(r, "viewerId"))
		if err != nil {
			writeError(w, r, auth.ErrValidation)
			return
		}
		if err := svc.Revoke(r.Context(), ac.User.ID, viewerID); err != nil {
			writeError(w, r, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

func matchesExpectedOwner(w http.ResponseWriter, r *http.Request, authenticatedOwner uuid.UUID) bool {
	expectedOwner, err := uuid.Parse(r.Header.Get("X-Anve-Expected-Owner"))
	if err != nil || expectedOwner != authenticatedOwner {
		writeError(w, r, auth.ErrUnauthenticated)
		return false
	}
	return true
}

func latestVisibleLocationsHandler(svc *locationaccess.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, ok := AuthFromContext(r.Context())
		if !ok {
			writeError(w, r, auth.ErrUnauthenticated)
			return
		}
		locations, err := svc.Latest(r.Context(), ac.User.ID)
		if err != nil {
			writeError(w, r, err)
			return
		}
		if locations == nil {
			locations = []locationaccess.VisibleLocation{}
		}
		payload, err := json.Marshal(latestLocationsResponse{Locations: locations})
		if err != nil {
			writeError(w, r, err)
			return
		}
		// Age advances with server time even when the location resource does
		// not change. Clients advance the last server-computed age using a
		// monotonic clock; confidence transitions still change the validator.
		fingerprintLocations := append([]locationaccess.VisibleLocation(nil), locations...)
		for i := range fingerprintLocations {
			fingerprintLocations[i].AgeMillis = nil
		}
		fingerprint, err := json.Marshal(latestLocationsResponse{Locations: fingerprintLocations})
		if err != nil {
			writeError(w, r, err)
			return
		}
		digest := sha256.New()
		_, _ = digest.Write(ac.User.ID[:])
		_, _ = digest.Write(fingerprint)
		etag := `"` + hex.EncodeToString(digest.Sum(nil)) + `"`
		w.Header().Set("Cache-Control", "no-store, private")
		w.Header().Set("Vary", "Authorization")
		w.Header().Set("ETag", etag)
		if r.Header.Get("If-None-Match") == etag {
			w.WriteHeader(http.StatusNotModified)
			return
		}
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(payload)
	}
}

func historyHandler(svc *locationaccess.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, ok := AuthFromContext(r.Context())
		if !ok {
			writeError(w, r, auth.ErrUnauthenticated)
			return
		}
		if !matchesExpectedOwner(w, r, ac.User.ID) {
			return
		}
		values := r.URL.Query()
		for _, key := range []string{"ownerId", "from", "to"} {
			if len(values[key]) != 1 {
				writeError(w, r, auth.ErrValidation)
				return
			}
		}
		if len(values["limit"]) > 1 || len(values["cursor"]) > 1 {
			writeError(w, r, auth.ErrValidation)
			return
		}
		owner, err := uuid.Parse(values.Get("ownerId"))
		if err != nil {
			writeError(w, r, auth.ErrValidation)
			return
		}
		from, err := time.Parse(time.RFC3339Nano, values.Get("from"))
		if err != nil {
			writeError(w, r, auth.ErrValidation)
			return
		}
		to, err := time.Parse(time.RFC3339Nano, values.Get("to"))
		if err != nil {
			writeError(w, r, auth.ErrValidation)
			return
		}
		limit := 100
		if values.Has("limit") {
			limit, err = strconv.Atoi(values.Get("limit"))
			if err != nil {
				writeError(w, r, auth.ErrValidation)
				return
			}
		}
		page, err := svc.History(r.Context(), ac.User.ID, owner, from, to, limit, values.Get("cursor"))
		if err != nil {
			writeError(w, r, err)
			return
		}
		w.Header().Set("Cache-Control", "no-store, private")
		w.Header().Set("Vary", "Authorization")
		writeJSON(w, http.StatusOK, page)
	}
}
