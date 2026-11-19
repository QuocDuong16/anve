package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"github.com/QuocDuong16/anve/server/internal/batteryalerts"
)

func createBatteryAlertHandler(store *batteryalerts.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		w.Header().Set("Cache-Control", "no-store, private")
		var body struct {
			EventID        uuid.UUID  `json:"eventId"`
			Threshold      *int       `json:"threshold"`
			BatteryPercent *int       `json:"batteryPercent"`
			RecordedAt     *time.Time `json:"recordedAt"`
			Location       *struct {
				Latitude                 *float64   `json:"latitude"`
				Longitude                *float64   `json:"longitude"`
				HorizontalAccuracyMeters *float64   `json:"horizontalAccuracyMeters"`
				RecordedAt               *time.Time `json:"recordedAt"`
			} `json:"location"`
		}
		r.Body = http.MaxBytesReader(w, r.Body, 8192)
		decoder := json.NewDecoder(r.Body)
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&body); err != nil {
			writeError(w, r, batteryalerts.ErrInvalid)
			return
		}
		if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
			writeError(w, r, batteryalerts.ErrInvalid)
			return
		}
		if body.Threshold == nil || body.BatteryPercent == nil || body.RecordedAt == nil {
			writeError(w, r, batteryalerts.ErrInvalid)
			return
		}
		var loc *batteryalerts.Location
		if body.Location != nil {
			if body.Location.Latitude == nil || body.Location.Longitude == nil ||
				body.Location.HorizontalAccuracyMeters == nil || body.Location.RecordedAt == nil {
				writeError(w, r, batteryalerts.ErrInvalid)
				return
			}
			loc = &batteryalerts.Location{
				Latitude:                 *body.Location.Latitude,
				Longitude:                *body.Location.Longitude,
				HorizontalAccuracyMeters: *body.Location.HorizontalAccuracyMeters,
				RecordedAt:               *body.Location.RecordedAt,
			}
		}
		ac, _ := AuthFromContext(r.Context())
		principal := batteryalerts.Principal{UserID: actor, SessionID: ac.Session.ID, DeviceID: ac.Session.DeviceID}
		alert, created, err := store.Create(r.Context(), principal, batteryalerts.CreateRequest{
			EventID:        body.EventID,
			Threshold:      *body.Threshold,
			BatteryPercent: *body.BatteryPercent,
			RecordedAt:     *body.RecordedAt,
			Location:       loc,
		})
		if err != nil {
			writeError(w, r, err)
			return
		}
		status := http.StatusOK
		if created {
			status = http.StatusCreated
		}
		writeJSON(w, status, alert)
	}
}

func resolveBatteryAlertsHandler(store *batteryalerts.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		ac, _ := AuthFromContext(r.Context())
		principal := batteryalerts.Principal{UserID: actor, SessionID: ac.Session.ID, DeviceID: ac.Session.DeviceID}
		if _, err := store.Resolve(r.Context(), principal); err != nil {
			writeError(w, r, err)
			return
		}
		writeNoContent(w)
	}
}

func getBatteryAlertHandler(store *batteryalerts.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		id, err := uuid.Parse(chi.URLParam(r, "alertId"))
		if err != nil || id == uuid.Nil {
			writeError(w, r, batteryalerts.ErrInvalid)
			return
		}
		alert, err := store.Get(r.Context(), actor, id)
		if err != nil {
			writeError(w, r, err)
			return
		}
		w.Header().Set("Cache-Control", "no-store, private")
		writeJSON(w, http.StatusOK, alert)
	}
}
