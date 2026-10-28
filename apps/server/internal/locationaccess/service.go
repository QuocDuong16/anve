// Package locationaccess owns explicit audience grants and privacy-filtered
// latest-location reads. Circle membership alone never grants location access.
package locationaccess

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
)

var (
	// ErrForbidden indicates an authenticated caller lacks permission for an operation.
	ErrForbidden = errors.New("location access forbidden")
	// ErrNotFound indicates an audience target does not exist or is not visible to the caller.
	ErrNotFound = errors.New("location audience target not found")
	// ErrConsentOff indicates that the location owner has not enabled location sharing.
	ErrConsentOff = errors.New("location sharing consent is off")
	// ErrNoMembership indicates that the owner and viewer lack a shared active Circle membership.
	ErrNoMembership = errors.New("active shared Circle membership required")
)

// Grant is an owner's explicit, temporal authorization for one Circle member to view location.
type Grant struct {
	ID          uuid.UUID `json:"id"`
	ViewerID    uuid.UUID `json:"viewerId"`
	DisplayName string    `json:"displayName"`
	CircleID    uuid.UUID `json:"circleId"`
	Generation  int64     `json:"generation"`
	VisibleFrom time.Time `json:"visibleFrom"`
	CreatedAt   time.Time `json:"createdAt"`
}

// VisibleLocation contains only the latest location fields authorized for a viewer.
type VisibleLocation struct {
	OwnerID     uuid.UUID  `json:"ownerId"`
	DisplayName string     `json:"displayName"`
	Latitude    *float64   `json:"latitude"`
	Longitude   *float64   `json:"longitude"`
	AccuracyM   *float64   `json:"horizontalAccuracyMeters"`
	RecordedAt  *time.Time `json:"recordedAt"`
	ReceivedAt  *time.Time `json:"receivedAt"`
	AgeMillis   *int64     `json:"ageMillis"`
	Confidence  string     `json:"confidence"`
}

// Store defines persistence operations that enforce location audience authorization.
type Store interface {
	Audience(context.Context, uuid.UUID) ([]Grant, error)
	Grant(context.Context, uuid.UUID, uuid.UUID) (Grant, error)
	Revoke(context.Context, uuid.UUID, uuid.UUID) error
	Latest(context.Context, uuid.UUID, time.Time, time.Time) ([]VisibleLocation, error)
}

// Service applies location-access policy over its persistence store.
type Service struct {
	store     Store
	retention time.Duration
	now       func() time.Time
}

// NewService constructs a location-access service with the configured retention window.
func NewService(store Store, retention time.Duration) *Service {
	return &Service{store: store, retention: retention, now: time.Now}
}

// Audience returns the active viewers explicitly authorized by the owner.
func (s *Service) Audience(ctx context.Context, ownerID uuid.UUID) ([]Grant, error) {
	return s.store.Audience(ctx, ownerID)
}

// Grant authorizes a current Circle member to view the owner's future eligible locations.
func (s *Service) Grant(ctx context.Context, ownerID, viewerID uuid.UUID) (Grant, error) {
	return s.store.Grant(ctx, ownerID, viewerID)
}

// Revoke ends any active grants from the owner to the selected viewer.
func (s *Service) Revoke(ctx context.Context, ownerID, viewerID uuid.UUID) error {
	return s.store.Revoke(ctx, ownerID, viewerID)
}

// Latest returns the latest retained locations the viewer is authorized to see.
func (s *Service) Latest(ctx context.Context, viewerID uuid.UUID) ([]VisibleLocation, error) {
	now := s.now().UTC()
	locations, err := s.store.Latest(ctx, viewerID, now, now.Add(-s.retention))
	if err != nil {
		return nil, err
	}
	for i := range locations {
		location := &locations[i]
		location.Confidence, location.AgeMillis = classifyConfidence(location.RecordedAt, now)
	}
	return locations, nil
}

func classifyConfidence(recordedAt *time.Time, now time.Time) (string, *int64) {
	if recordedAt == nil || recordedAt.After(now) {
		return "UNKNOWN", nil
	}
	age := now.Sub(*recordedAt)
	if age < 0 {
		age = 0
	}
	ageMillis := age.Milliseconds()
	switch {
	case age <= 2*time.Minute:
		return "FRESH", &ageMillis
	case age <= 15*time.Minute:
		return "DELAYED", &ageMillis
	default:
		return "STALE", &ageMillis
	}
}
