// Package locations implements consented location ingestion: batch
// validation, per-request authorization, idempotent PostgreSQL/PostGIS
// writes and retention.
//
// The package never logs coordinates. Operational logs carry ids, counts
// and durations only.
package locations

import (
	"time"

	"github.com/google/uuid"
)

// Event is one location fix captured by an Android device.
//
// RecordedAt is the device clock at capture time. It is stored as received:
// an offline upload days later still describes the moment the device was
// there. ReceivedAt is assigned by the server at write time.
type Event struct {
	EventID                  uuid.UUID
	ClientDeviceID           string
	RecordedAt               time.Time
	Latitude                 float64
	Longitude                float64
	HorizontalAccuracyMeters float64
	AltitudeMeters           *float64
	SpeedMetersPerSecond     *float64
	BearingDegrees           *float64
	Provider                 string
	IsMock                   bool
	SchemaVersion            int
}

// Principal is the authenticated caller of an ingestion request. It comes
// from the session, never from the request body: a client cannot choose the
// user or device it uploads for.
type Principal struct {
	UserID    uuid.UUID
	SessionID uuid.UUID
	DeviceID  uuid.UUID
}

// BatchResult is the atomic outcome of one batch.
type BatchResult struct {
	Accepted   []uuid.UUID
	Duplicates []uuid.UUID
	ReceivedAt time.Time
}

// IngestInput is what the store receives: the principal, the validated
// events and the single server timestamp used for the whole batch.
type IngestInput struct {
	Principal  Principal
	Events     []Event
	ReceivedAt time.Time
}
