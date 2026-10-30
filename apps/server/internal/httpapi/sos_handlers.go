package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"github.com/QuocDuong16/anve/server/internal/sos"
)

func sosCollection(store *sos.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		w.Header().Set("Cache-Control", "no-store, private")
		if r.Method == http.MethodGet {
			events, err := store.List(r.Context(), actor)
			if err != nil {
				writeError(w, r, err)
				return
			}
			writeJSON(w, http.StatusOK, struct {
				Events []sos.Event `json:"events"`
			}{events})
			return
		}
		var body struct {
			EventID      uuid.UUID   `json:"eventId"`
			RecordedAt   time.Time   `json:"recordedAt"`
			RecipientIDs []uuid.UUID `json:"recipientIds"`
		}
		r.Body = http.MaxBytesReader(w, r.Body, 8192)
		decoder := json.NewDecoder(r.Body)
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&body); err != nil {
			writeError(w, r, sos.ErrInvalid)
			return
		}
		if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
			writeError(w, r, sos.ErrInvalid)
			return
		}
		ac, _ := AuthFromContext(r.Context())
		principal := sos.Principal{UserID: actor, SessionID: ac.Session.ID, DeviceID: ac.Session.DeviceID}
		event, created, err := store.Create(r.Context(), principal, body.EventID, body.RecordedAt, body.RecipientIDs)
		if err != nil {
			writeError(w, r, err)
			return
		}
		status := http.StatusOK
		if created {
			status = http.StatusCreated
		}
		writeJSON(w, status, event)
	}
}

func sosDetail(store *sos.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		id, err := uuid.Parse(chi.URLParam(r, "eventId"))
		if err != nil || id == uuid.Nil {
			writeError(w, r, sos.ErrInvalid)
			return
		}
		var event sos.Event
		if r.Method == http.MethodGet {
			event, err = store.Get(r.Context(), actor, id)
		} else {
			ac, _ := AuthFromContext(r.Context())
			principal := sos.Principal{UserID: actor, SessionID: ac.Session.ID, DeviceID: ac.Session.DeviceID}
			event, err = store.Transition(r.Context(), principal, id, chi.URLParam(r, "action"))
		}
		if err != nil {
			writeError(w, r, err)
			return
		}
		if r.Method == http.MethodPost && chi.URLParam(r, "action") == "cancel" {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		w.Header().Set("Cache-Control", "no-store, private")
		writeJSON(w, http.StatusOK, event)
	}
}

func pushTokenHandler(store *sos.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		ac, _ := AuthFromContext(r.Context())
		principal := sos.Principal{UserID: actor, SessionID: ac.Session.ID, DeviceID: ac.Session.DeviceID}
		var err error
		switch r.Method {
		case http.MethodGet:
			registered, readErr := store.PushTokenRegistered(r.Context(), principal)
			if readErr != nil {
				writeError(w, r, readErr)
				return
			}
			w.Header().Set("Cache-Control", "no-store, private")
			writeJSON(w, http.StatusOK, struct {
				Registered bool `json:"registered"`
			}{registered})
			return
		case http.MethodDelete:
			err = store.UnregisterPushToken(r.Context(), principal)
		default:
			var body struct {
				Token string `json:"token"`
			}
			r.Body = http.MaxBytesReader(w, r.Body, 8192)
			decoder := json.NewDecoder(r.Body)
			decoder.DisallowUnknownFields()
			if decodeErr := decoder.Decode(&body); decodeErr != nil {
				writeError(w, r, sos.ErrInvalid)
				return
			}
			if decodeErr := decoder.Decode(&struct{}{}); !errors.Is(decodeErr, io.EOF) {
				writeError(w, r, sos.ErrInvalid)
				return
			}
			err = store.RegisterPushToken(r.Context(), principal, body.Token)
		}
		if err != nil {
			writeError(w, r, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

func sosRecipients(store *sos.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		id, err := uuid.Parse(chi.URLParam(r, "eventId"))
		if err != nil || id == uuid.Nil {
			writeError(w, r, sos.ErrInvalid)
			return
		}
		items, err := store.Recipients(r.Context(), actor, id)
		if err != nil {
			writeError(w, r, err)
			return
		}
		w.Header().Set("Cache-Control", "no-store, private")
		writeJSON(w, http.StatusOK, struct {
			Recipients []sos.RecipientStatus `json:"recipients"`
		}{items})
	}
}

func revokeSosRecipient(store *sos.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		id, err := uuid.Parse(chi.URLParam(r, "eventId"))
		if err != nil {
			writeError(w, r, sos.ErrInvalid)
			return
		}
		recipient, err := uuid.Parse(chi.URLParam(r, "recipientId"))
		if err != nil {
			writeError(w, r, sos.ErrInvalid)
			return
		}
		ac, _ := AuthFromContext(r.Context())
		principal := sos.Principal{UserID: actor, SessionID: ac.Session.ID, DeviceID: ac.Session.DeviceID}
		if err = store.RevokeRecipient(r.Context(), principal, id, recipient); err != nil {
			writeError(w, r, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}
