package places

import (
	"errors"
	"math"
	"strings"
	"time"

	"github.com/google/uuid"
)

var (
	// ErrInvalid denotes an invalid geometry or authorization selection.
	ErrInvalid = errors.New("invalid place configuration")
	// ErrNotFound hides missing and inaccessible place resources alike.
	ErrNotFound = errors.New("place unavailable")
	// ErrNoCircle means the actor has no current Circle membership.
	ErrNoCircle = errors.New("active circle required")
)

// Input is the complete place geometry; updating it invalidates monitors.
type Input struct {
	Name      string  `json:"name"`
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
	RadiusM   float64 `json:"radiusMeters"`
}

func (i *Input) validate() bool {
	i.Name = strings.TrimSpace(i.Name)
	return len([]rune(i.Name)) >= 1 && len([]rune(i.Name)) <= 80 &&
		!math.IsNaN(i.Latitude) && !math.IsInf(i.Latitude, 0) && i.Latitude >= -90 && i.Latitude <= 90 &&
		!math.IsNaN(i.Longitude) && !math.IsInf(i.Longitude, 0) && i.Longitude >= -180 && i.Longitude <= 180 &&
		!math.IsNaN(i.RadiusM) && !math.IsInf(i.RadiusM, 0) && i.RadiusM >= 50 && i.RadiusM <= 5000
}

// Place is an explicitly owned Circle place.
type Place struct {
	ID       uuid.UUID `json:"id"`
	OwnerID  uuid.UUID `json:"ownerId"`
	CircleID uuid.UUID `json:"circleId"`
	Input
	CreatedAt time.Time `json:"createdAt"`
	UpdatedAt time.Time `json:"updatedAt"`
}

// Monitor describes only the authenticated owner's monitoring decision.
type Monitor struct {
	PlaceID      uuid.UUID   `json:"placeId"`
	OwnerID      uuid.UUID   `json:"ownerId"`
	RecipientIDs []uuid.UUID `json:"recipientIds"`
	Active       bool        `json:"active"`
}

// Event is the minimal authorized in-app transition metadata.
type Event struct {
	ID          uuid.UUID `json:"id"`
	PlaceID     uuid.UUID `json:"placeId"`
	PlaceName   string    `json:"placeName"`
	OwnerID     uuid.UUID `json:"ownerId"`
	OwnerName   string    `json:"ownerName"`
	Type        string    `json:"type"`
	RecordedAt  time.Time `json:"recordedAt"`
	ProcessedAt time.Time `json:"processedAt"`
	Delayed     bool      `json:"delayed"`
	Confidence  string    `json:"confidence"`
}
