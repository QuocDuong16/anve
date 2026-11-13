package httpapi

import (
	"errors"
	"io"
	"mime"
	"net/http"
	"strconv"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"github.com/QuocDuong16/anve/server/internal/avatars"
)

func avatarPrincipal(w http.ResponseWriter, r *http.Request) (avatars.Principal, bool) {
	w.Header().Set("Cache-Control", "no-store, private")
	owner, ok := placeActor(w, r)
	if !ok {
		return avatars.Principal{}, false
	}
	ac, _ := AuthFromContext(r.Context())
	return avatars.Principal{UserID: owner, SessionID: ac.Session.ID}, true
}

func avatarStateHandler(store *avatars.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		p, ok := avatarPrincipal(w, r)
		if !ok {
			return
		}
		state, err := store.State(r.Context(), p)
		if err != nil {
			writeError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, state)
	}
}

func avatarMutationHandler(store *avatars.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		p, ok := avatarPrincipal(w, r)
		if !ok {
			return
		}
		operation, err := uuid.Parse(r.Header.Get("X-Anve-Operation-ID"))
		revision, revisionErr := strconv.ParseInt(r.Header.Get("X-Anve-Expected-Revision"), 10, 64)
		if err != nil || operation == uuid.Nil || revisionErr != nil || revision < 0 {
			writeError(w, r, avatars.ErrInvalid)
			return
		}
		var state avatars.State
		if r.Method == http.MethodDelete {
			state, err = store.Remove(r.Context(), p, operation, revision)
		} else {
			mediaType, _, mediaErr := mime.ParseMediaType(r.Header.Get("Content-Type"))
			if mediaErr != nil || (mediaType != "image/jpeg" && mediaType != "image/png") {
				writeError(w, r, avatars.ErrInvalid)
				return
			}
			input, readErr := io.ReadAll(http.MaxBytesReader(w, r.Body, avatars.MaxInputBytes))
			if readErr != nil {
				var tooLarge *http.MaxBytesError
				if errors.As(readErr, &tooLarge) {
					writeJSON(w, http.StatusRequestEntityTooLarge, errorBody{Code: "AVATAR_INVALID", Message: "Avatar input exceeds 1 MiB."})
				} else {
					writeError(w, r, avatars.ErrInvalid)
				}
				return
			}
			state, err = store.Replace(r.Context(), p, operation, revision, input)
		}
		if err != nil {
			writeError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, state)
	}
}

func avatarImageHandler(store *avatars.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		p, ok := avatarPrincipal(w, r)
		if !ok {
			return
		}
		subject, subjectErr := uuid.Parse(chi.URLParam(r, "userId"))
		imageID, imageErr := uuid.Parse(chi.URLParam(r, "imageId"))
		if subjectErr != nil || imageErr != nil {
			writeError(w, r, avatars.ErrNotFound)
			return
		}
		content, err := store.Image(r.Context(), p, subject, imageID)
		if err != nil {
			writeError(w, r, err)
			return
		}
		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(content)
	}
}
