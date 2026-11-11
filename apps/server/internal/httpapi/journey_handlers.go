package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"github.com/QuocDuong16/anve/server/internal/journeys"
)

func journeyPrincipal(w http.ResponseWriter, r *http.Request) (journeys.Principal, bool) {
	actor, ok := placeActor(w, r)
	if !ok {
		return journeys.Principal{}, false
	}
	ac, _ := AuthFromContext(r.Context())
	w.Header().Set("Cache-Control", "no-store, private")
	return journeys.Principal{UserID: actor, SessionID: ac.Session.ID, DeviceID: ac.Session.DeviceID}, true
}

func journeyCollection(store *journeys.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		p, ok := journeyPrincipal(w, r)
		if !ok {
			return
		}
		if r.Method == http.MethodGet {
			items, err := store.List(r.Context(), p.UserID)
			if err != nil {
				writeError(w, r, err)
				return
			}
			writeJSON(w, http.StatusOK, struct {
				Journeys []journeys.Journey `json:"journeys"`
			}{items})
			return
		}
		var body struct {
			JourneyID       uuid.UUID `json:"journeyId"`
			PreparedAt      time.Time `json:"preparedAt"`
			DurationMinutes int       `json:"durationMinutes"`
			Destination     struct {
				Label     string   `json:"label"`
				Latitude  *float64 `json:"latitude"`
				Longitude *float64 `json:"longitude"`
			} `json:"destination"`
			RecipientIDs []uuid.UUID `json:"recipientIds"`
		}
		r.Body = http.MaxBytesReader(w, r.Body, 8192)
		decoder := json.NewDecoder(r.Body)
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&body); err != nil || body.Destination.Latitude == nil || body.Destination.Longitude == nil {
			writeError(w, r, journeys.ErrInvalid)
			return
		}
		if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
			writeError(w, r, journeys.ErrInvalid)
			return
		}
		j, created, err := store.Create(r.Context(), p, journeys.CreateRequest{JourneyID: body.JourneyID, PreparedAt: body.PreparedAt, DurationMinutes: body.DurationMinutes, RecipientIDs: body.RecipientIDs,
			Destination: journeys.Destination{Label: body.Destination.Label, Latitude: *body.Destination.Latitude, Longitude: *body.Destination.Longitude}})
		if err != nil {
			writeError(w, r, err)
			return
		}
		status := http.StatusOK
		if created {
			status = http.StatusCreated
		}
		writeJSON(w, status, j)
	}
}

func journeyDetail(store *journeys.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		p, ok := journeyPrincipal(w, r)
		if !ok {
			return
		}
		id, err := uuid.Parse(chi.URLParam(r, "journeyId"))
		if err != nil || id == uuid.Nil {
			writeError(w, r, journeys.ErrInvalid)
			return
		}
		if r.Method == http.MethodGet {
			j, err := store.Get(r.Context(), p.UserID, id)
			if err != nil {
				writeError(w, r, err)
				return
			}
			writeJSON(w, http.StatusOK, j)
			return
		}
		if r.Method == http.MethodDelete {
			recipient, parseErr := uuid.Parse(chi.URLParam(r, "recipientId"))
			if parseErr != nil {
				writeError(w, r, journeys.ErrInvalid)
				return
			}
			err = store.RevokeRecipient(r.Context(), p, id, recipient)
		} else {
			err = store.Transition(r.Context(), p, id, chi.URLParam(r, "action"))
		}
		if err != nil {
			writeError(w, r, err)
			return
		}
		writeNoContent(w)
	}
}
