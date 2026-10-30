package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/places"
)

func placeActor(w http.ResponseWriter, r *http.Request) (uuid.UUID, bool) {
	ac, ok := AuthFromContext(r.Context())
	if !ok {
		writeError(w, r, auth.ErrUnauthenticated)
		return uuid.Nil, false
	}
	if !matchesExpectedOwner(w, r, ac.User.ID) {
		return uuid.Nil, false
	}
	return ac.User.ID, true
}

func placePath(w http.ResponseWriter, r *http.Request, name string) (uuid.UUID, bool) {
	id, err := uuid.Parse(chi.URLParam(r, name))
	if err != nil || id == uuid.Nil {
		writeError(w, r, places.ErrInvalid)
		return uuid.Nil, false
	}
	return id, true
}

func decodePlace(w http.ResponseWriter, r *http.Request) (places.Input, error) {
	var body struct {
		Name      *string  `json:"name"`
		Latitude  *float64 `json:"latitude"`
		Longitude *float64 `json:"longitude"`
		RadiusM   *float64 `json:"radiusMeters"`
	}
	r.Body = http.MaxBytesReader(w, r.Body, 4096)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&body); err != nil || body.Name == nil || body.Latitude == nil || body.Longitude == nil || body.RadiusM == nil {
		return places.Input{}, places.ErrInvalid
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return places.Input{}, places.ErrInvalid
	}
	return places.Input{Name: *body.Name, Latitude: *body.Latitude, Longitude: *body.Longitude, RadiusM: *body.RadiusM}, nil
}

func listPlacesHandler(store *places.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		result, err := store.List(r.Context(), actor)
		if err != nil {
			writeError(w, r, err)
			return
		}
		w.Header().Set("Cache-Control", "no-store, private")
		writeJSON(w, http.StatusOK, struct {
			Places []places.Place `json:"places"`
		}{result})
	}
}

func createPlaceHandler(store *places.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		input, err := decodePlace(w, r)
		if err == nil {
			var p places.Place
			p, err = store.Create(r.Context(), actor, input)
			if err == nil {
				w.Header().Set("Cache-Control", "no-store, private")
				writeJSON(w, http.StatusCreated, p)
				return
			}
		}
		writeError(w, r, err)
	}
}

func updatePlaceHandler(store *places.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		id, ok := placePath(w, r, "placeId")
		if !ok {
			return
		}
		input, err := decodePlace(w, r)
		if err == nil {
			var p places.Place
			p, err = store.Update(r.Context(), actor, id, input)
			if err == nil {
				w.Header().Set("Cache-Control", "no-store, private")
				writeJSON(w, http.StatusOK, p)
				return
			}
		}
		writeError(w, r, err)
	}
}

func deletePlaceHandler(store *places.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		id, ok := placePath(w, r, "placeId")
		if !ok {
			return
		}
		if err := store.Delete(r.Context(), actor, id); err != nil {
			writeError(w, r, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

func listPlaceAccessHandler(store *places.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		id, ok := placePath(w, r, "placeId")
		if !ok {
			return
		}
		members, err := store.Access(r.Context(), actor, id)
		if err != nil {
			writeError(w, r, err)
			return
		}
		w.Header().Set("Cache-Control", "no-store, private")
		writeJSON(w, http.StatusOK, struct {
			MemberIDs []uuid.UUID `json:"memberIds"`
		}{members})
	}
}

func placeAccessHandler(store *places.Store, allow bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		id, ok := placePath(w, r, "placeId")
		if !ok {
			return
		}
		member, ok := placePath(w, r, "memberId")
		if !ok {
			return
		}
		if err := store.SetAccess(r.Context(), actor, id, member, allow); err != nil {
			writeError(w, r, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

func eligiblePlaceRecipientsHandler(store *places.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		id, ok := placePath(w, r, "placeId")
		if !ok {
			return
		}
		members, err := store.EligibleRecipients(r.Context(), actor, id)
		if err != nil {
			writeError(w, r, err)
			return
		}
		w.Header().Set("Cache-Control", "no-store, private")
		writeJSON(w, http.StatusOK, struct {
			MemberIDs []uuid.UUID `json:"memberIds"`
		}{members})
	}
}

func getPlaceMonitorHandler(store *places.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		id, ok := placePath(w, r, "placeId")
		if !ok {
			return
		}
		monitor, err := store.Monitor(r.Context(), actor, id)
		if err != nil {
			writeError(w, r, err)
			return
		}
		w.Header().Set("Cache-Control", "no-store, private")
		writeJSON(w, http.StatusOK, monitor)
	}
}

func setPlaceMonitorHandler(store *places.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		id, ok := placePath(w, r, "placeId")
		if !ok {
			return
		}
		var body struct {
			Recipients *[]uuid.UUID `json:"recipientIds"`
		}
		r.Body = http.MaxBytesReader(w, r.Body, 8192)
		decoder := json.NewDecoder(r.Body)
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&body); err != nil || body.Recipients == nil {
			writeError(w, r, places.ErrInvalid)
			return
		}
		if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
			writeError(w, r, places.ErrInvalid)
			return
		}
		monitor, err := store.ConfigureMonitor(r.Context(), actor, id, *body.Recipients)
		if err != nil {
			writeError(w, r, err)
			return
		}
		w.Header().Set("Cache-Control", "no-store, private")
		writeJSON(w, http.StatusOK, monitor)
	}
}

func stopPlaceMonitorHandler(store *places.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		id, ok := placePath(w, r, "placeId")
		if !ok {
			return
		}
		if err := store.StopMonitor(r.Context(), actor, id); err != nil {
			writeError(w, r, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

func placeEventsHandler(store *places.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		limit := 50
		if r.URL.Query().Has("limit") {
			var err error
			if len(r.URL.Query()["limit"]) != 1 {
				writeError(w, r, places.ErrInvalid)
				return
			}
			limit, err = strconv.Atoi(r.URL.Query().Get("limit"))
			if err != nil {
				writeError(w, r, places.ErrInvalid)
				return
			}
		}
		events, err := store.Events(r.Context(), actor, limit)
		if err != nil {
			writeError(w, r, err)
			return
		}
		w.Header().Set("Cache-Control", "no-store, private")
		writeJSON(w, http.StatusOK, struct {
			Events []places.Event `json:"events"`
		}{events})
	}
}

func placeEventHandler(store *places.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		actor, ok := placeActor(w, r)
		if !ok {
			return
		}
		id, err := uuid.Parse(chi.URLParam(r, "eventId"))
		if err != nil || id == uuid.Nil {
			writeError(w, r, places.ErrInvalid)
			return
		}
		event, err := store.Event(r.Context(), actor, id)
		if err != nil {
			writeError(w, r, err)
			return
		}
		w.Header().Set("Cache-Control", "no-store, private")
		writeJSON(w, http.StatusOK, event)
	}
}
