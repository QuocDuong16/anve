package httpapi

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5/middleware"
	"github.com/google/uuid"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/locations"
)

// batchRequest mirrors LocationBatchRequest in the OpenAPI contract. The
// numeric fields that are required are pointers so a missing value is
// distinguishable from a legitimate zero (0,0 is a real coordinate).
type batchRequest struct {
	Events []eventRequest `json:"events"`
}

type eventRequest struct {
	EventID                  string   `json:"eventId"`
	CaptureGeneration        int64    `json:"captureGeneration"`
	ClientDeviceID           string   `json:"clientDeviceId"`
	RecordedAt               string   `json:"recordedAt"`
	Latitude                 *float64 `json:"latitude"`
	Longitude                *float64 `json:"longitude"`
	HorizontalAccuracyMeters *float64 `json:"horizontalAccuracyMeters"`
	AltitudeMeters           *float64 `json:"altitudeMeters"`
	SpeedMetersPerSecond     *float64 `json:"speedMetersPerSecond"`
	BearingDegrees           *float64 `json:"bearingDegrees"`
	Provider                 string   `json:"provider"`
	IsMock                   bool     `json:"isMock"`
	SchemaVersion            int      `json:"schemaVersion"`
}

type locationBatchResponse struct {
	Accepted   []uuid.UUID `json:"accepted"`
	Duplicates []uuid.UUID `json:"duplicates"`
	ReceivedAt time.Time   `json:"receivedAt"`
}

// ingestLocationBatchHandler is the only write path for location data.
// Authorization comes exclusively from the authenticated session: the body
// carries no user id, and the client device id is verified against the
// session by the store.
func ingestLocationBatchHandler(svc *locations.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, ok := AuthFromContext(r.Context())
		if !ok {
			writeError(w, r, auth.ErrUnauthenticated)
			return
		}
		if !matchesExpectedOwner(w, r, ac.User.ID) {
			return
		}

		events, err := decodeBatch(w, r)
		if err != nil {
			var tooLarge *http.MaxBytesError
			if errors.As(err, &tooLarge) {
				writeJSON(w, http.StatusRequestEntityTooLarge, errorBody{
					Code:      "LOCATION_BATCH_TOO_LARGE",
					Message:   "The request body exceeds the batch size limit.",
					RequestID: middleware.GetReqID(r.Context()),
				})
				return
			}
			writeError(w, r, err)
			return
		}

		started := time.Now()
		result, err := svc.Ingest(r.Context(), locations.Principal{
			UserID:    ac.User.ID,
			SessionID: ac.Session.ID,
			DeviceID:  ac.Session.DeviceID,
		}, events)
		if err != nil {
			// Log only ids, counts and a stable code. Even an error string
			// can echo a database row value, so the message is not logged;
			// the client receives it in the response envelope.
			slog.WarnContext(r.Context(), "location batch rejected",
				"user_id", ac.User.ID,
				"device_id", ac.Session.DeviceID,
				"events", len(events),
				"error_code", locationErrorCode(err),
			)
			writeError(w, r, err)
			return
		}

		accepted := result.Accepted
		if accepted == nil {
			accepted = []uuid.UUID{}
		}
		duplicates := result.Duplicates
		if duplicates == nil {
			duplicates = []uuid.UUID{}
		}

		metrics := svc.Metrics().Snapshot()
		slog.InfoContext(r.Context(), "location batch ingested",
			"user_id", ac.User.ID,
			"device_id", ac.Session.DeviceID,
			"accepted", len(accepted),
			"duplicates", len(duplicates),
			"duration_ms", time.Since(started).Milliseconds(),
			// Aggregate operational counters, never per-point labels or
			// coordinates.
			"batches_total", metrics.Batches,
			"events_accepted_total", metrics.EventsAccepted,
			"events_duplicate_total", metrics.EventsDuplicate,
			"events_rejected_total", metrics.EventsRejected,
		)
		writeJSON(w, http.StatusOK, locationBatchResponse{
			Accepted:   accepted,
			Duplicates: duplicates,
			ReceivedAt: result.ReceivedAt,
		})
	}
}

// locationErrorCode is the log-safe classification of an ingestion error.
// It intentionally matches the API codes rather than exposing raw messages.
func locationErrorCode(err error) string {
	switch {
	case errors.Is(err, locations.ErrBatchTooLarge):
		return "LOCATION_BATCH_TOO_LARGE"
	case errors.Is(err, locations.ErrHistoryDeleted):
		return "LOCATION_HISTORY_DELETED"
	case errors.Is(err, locations.ErrCaptureGeneration):
		return "LOCATION_CAPTURE_GENERATION_EXPIRED"
	case errors.Is(err, locations.ErrEventTooOld):
		return "LOCATION_EVENT_TOO_OLD"
	case errors.Is(err, locations.ErrBatchInvalid):
		return "LOCATION_BATCH_INVALID"
	case errors.Is(err, locations.ErrConsentRequired):
		return "LOCATION_CONSENT_REQUIRED"
	case errors.Is(err, locations.ErrCircleRequired):
		return "LOCATION_CIRCLE_REQUIRED"
	case errors.Is(err, locations.ErrDeviceNotAuthorized):
		return "DEVICE_NOT_AUTHORIZED"
	case errors.Is(err, locations.ErrTemporarilyUnavailable):
		return "LOCATION_TEMPORARILY_UNAVAILABLE"
	default:
		return "INTERNAL"
	}
}

// decodeBatch enforces the body-size limit and turns the wire shape into
// domain events. Every parse failure is a batch-invalid error, so clients
// never have to interpret free text or a bare HTTP status.
func decodeBatch(w http.ResponseWriter, r *http.Request) ([]locations.Event, error) {
	r.Body = http.MaxBytesReader(w, r.Body, locations.MaxBodyBytes)

	var body batchRequest
	decoder := json.NewDecoder(r.Body)
	if err := decoder.Decode(&body); err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			return nil, err
		}
		return nil, fmt.Errorf("%w: the body is not a valid batch", locations.ErrBatchInvalid)
	}
	// Exactly one JSON value per request: a second object would silently be
	// ignored otherwise.
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return nil, fmt.Errorf("%w: the body contains trailing data", locations.ErrBatchInvalid)
	}

	events := make([]locations.Event, 0, len(body.Events))
	for i, raw := range body.Events {
		event, err := raw.toEvent()
		if err != nil {
			return nil, fmt.Errorf("%w: event %d: %w", locations.ErrBatchInvalid, i+1, err)
		}
		events = append(events, event)
	}
	return events, nil
}

func (r eventRequest) toEvent() (locations.Event, error) {
	eventID, err := uuid.Parse(r.EventID)
	if err != nil {
		return locations.Event{}, errors.New("eventId must be a UUID")
	}
	recordedAt, err := time.Parse(time.RFC3339Nano, r.RecordedAt)
	if err != nil {
		return locations.Event{}, errors.New("recordedAt must be an RFC 3339 timestamp")
	}
	if r.Latitude == nil || r.Longitude == nil || r.HorizontalAccuracyMeters == nil {
		return locations.Event{}, errors.New("latitude, longitude and horizontalAccuracyMeters are required")
	}
	schemaVersion := r.SchemaVersion
	if schemaVersion == 0 {
		schemaVersion = 1
	}
	return locations.Event{
		EventID:                  eventID,
		CaptureGeneration:        r.CaptureGeneration,
		ClientDeviceID:           r.ClientDeviceID,
		RecordedAt:               recordedAt.UTC(),
		Latitude:                 *r.Latitude,
		Longitude:                *r.Longitude,
		HorizontalAccuracyMeters: *r.HorizontalAccuracyMeters,
		AltitudeMeters:           r.AltitudeMeters,
		SpeedMetersPerSecond:     r.SpeedMetersPerSecond,
		BearingDegrees:           r.BearingDegrees,
		Provider:                 r.Provider,
		IsMock:                   r.IsMock,
		SchemaVersion:            schemaVersion,
	}, nil
}

func deleteLocationHistoryHandler(svc *locations.Service) http.HandlerFunc {
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
		if len(values["to"]) != 1 || len(values["from"]) > 1 {
			writeError(w, r, auth.ErrValidation)
			return
		}
		to, err := time.Parse(time.RFC3339Nano, values.Get("to"))
		if err != nil {
			writeError(w, r, auth.ErrValidation)
			return
		}
		var from *time.Time
		if values.Has("from") {
			parsed, err := time.Parse(time.RFC3339Nano, values.Get("from"))
			if err != nil {
				writeError(w, r, auth.ErrValidation)
				return
			}
			from = &parsed
		}
		if err := svc.DeleteHistory(r.Context(), ac.User.ID, from, to); err != nil {
			writeError(w, r, err)
			return
		}
		w.Header().Set("Cache-Control", "no-store, private")
		w.WriteHeader(http.StatusNoContent)
	}
}

func captureGenerationHandler(svc *locations.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, ok := AuthFromContext(r.Context())
		if !ok {
			writeError(w, r, auth.ErrUnauthenticated)
			return
		}
		if !matchesExpectedOwner(w, r, ac.User.ID) {
			return
		}
		generation, err := svc.CaptureGeneration(r.Context(), ac.User.ID)
		if err != nil {
			writeError(w, r, err)
			return
		}
		w.Header().Set("Cache-Control", "no-store, private")
		writeJSON(w, http.StatusOK, struct {
			Generation int64 `json:"generation"`
		}{generation})
	}
}

func historyRetentionHandler(svc *locations.Service) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ac, ok := AuthFromContext(r.Context())
		if !ok {
			writeError(w, r, auth.ErrUnauthenticated)
			return
		}
		if !matchesExpectedOwner(w, r, ac.User.ID) {
			return
		}
		if r.Method == http.MethodPut {
			r.Body = http.MaxBytesReader(w, r.Body, 1024)
			var body struct {
				Days int `json:"days"`
			}
			decoder := json.NewDecoder(r.Body)
			decoder.DisallowUnknownFields()
			if err := decoder.Decode(&body); err != nil {
				writeError(w, r, auth.ErrValidation)
				return
			}
			if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
				writeError(w, r, auth.ErrValidation)
				return
			}
			if err := svc.SetRetentionDays(r.Context(), ac.User.ID, body.Days); err != nil {
				writeError(w, r, err)
				return
			}
		}
		days, err := svc.RetentionDays(r.Context(), ac.User.ID)
		if err != nil {
			writeError(w, r, err)
			return
		}
		w.Header().Set("Cache-Control", "no-store, private")
		writeJSON(w, http.StatusOK, struct {
			Days          int `json:"days"`
			EffectiveDays int `json:"effectiveDays"`
		}{days, svc.EffectiveRetentionDays(days)})
	}
}
