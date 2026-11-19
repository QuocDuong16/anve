// Package batteryalerts implements opt-in low-battery last-breath alerts
// (15% and 5%) during active location sharing or Safe Journey, with strict
// freshness bounds, per-threshold cooldowns, silent charging resolution, and
// opaque push dispatch. Coordinates are never written to operational logs or
// audit rows.
package batteryalerts

import (
	"errors"
	"time"

	"github.com/google/uuid"
)

// Stable domain errors mapped to HTTP error codes by httpapi.
var (
	ErrInvalid            = errors.New("invalid low-battery alert")
	ErrNotFound           = errors.New("low-battery alert unavailable")
	ErrConflict           = errors.New("low-battery alert conflict")
	ErrTooOld             = errors.New("low-battery alert outside freshness window")
	ErrConsentRequired    = errors.New("location sharing consent is off")
	ErrPreferenceDisabled = errors.New("low-battery alerts are disabled")
	ErrCircleRequired     = errors.New("active circle membership required")
	ErrCooldown           = errors.New("low-battery alert cooldown active")
)

// Audience scopes and server-enforced freshness/cooldown bounds.
const (
	ScopeSharing = "SHARING"
	ScopeJourney = "JOURNEY"

	// MaxFreshnessAge rejects delayed offline captures older than 15 minutes
	// so relatives are not alerted hours after recharge.
	MaxFreshnessAge = 15 * time.Minute

	// MaxLocationAge bounds fallback location fixes attached to an alert.
	MaxLocationAge = 30 * time.Minute

	// CooldownWindow throttles repeated alerts at the same threshold.
	CooldownWindow = 30 * time.Minute
)

// Principal identifies the authenticated caller and device from the session.
type Principal struct {
	UserID    uuid.UUID
	SessionID uuid.UUID
	DeviceID  uuid.UUID
}

// Location is an optional coordinate snapshot attached to a low-battery alert.
type Location struct {
	Latitude                 float64   `json:"latitude"`
	Longitude                float64   `json:"longitude"`
	HorizontalAccuracyMeters float64   `json:"horizontalAccuracyMeters"`
	RecordedAt               time.Time `json:"recordedAt"`
}

// CreateRequest submits a client-generated low-battery threshold event.
type CreateRequest struct {
	EventID        uuid.UUID `json:"eventId"`
	Threshold      int       `json:"threshold"`
	BatteryPercent int       `json:"batteryPercent"`
	RecordedAt     time.Time `json:"recordedAt"`
	Location       *Location `json:"location,omitempty"`
}

// Alert is the authorized server-persisted low-battery alert record.
type Alert struct {
	ID             uuid.UUID  `json:"id"`
	OwnerID        uuid.UUID  `json:"ownerId"`
	OwnerName      string     `json:"ownerName"`
	Scope          string     `json:"scope"`
	JourneyID      *uuid.UUID `json:"journeyId,omitempty"`
	Threshold      int        `json:"threshold"`
	BatteryPercent int        `json:"batteryPercent"`
	RecordedAt     time.Time  `json:"recordedAt"`
	ReceivedAt     time.Time  `json:"receivedAt"`
	ResolvedAt     *time.Time `json:"resolvedAt,omitempty"`
	RecipientCount int        `json:"recipientCount"`
	Location       *Location  `json:"location,omitempty"`
}

// Summary is embedded on Live Map and Safe Journey reads for unresolved alerts.
type Summary struct {
	ID                 uuid.UUID  `json:"id"`
	Threshold          int        `json:"threshold"`
	BatteryPercent     int        `json:"batteryPercent"`
	RecordedAt         time.Time  `json:"recordedAt"`
	Latitude           *float64   `json:"latitude,omitempty"`
	Longitude          *float64   `json:"longitude,omitempty"`
	AccuracyM          *float64   `json:"horizontalAccuracyMeters,omitempty"`
	LocationRecordedAt *time.Time `json:"locationRecordedAt,omitempty"`
	JourneyID          *uuid.UUID `json:"journeyId,omitempty"`
}
