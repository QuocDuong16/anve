package journeys

import (
	"math"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
)

func TestJourneyInputBounds(t *testing.T) {
	owner, viewer := uuid.New(), uuid.New()
	base := CreateRequest{JourneyID: uuid.New(), PreparedAt: time.Now(), DurationMinutes: 120, Destination: Destination{Label: "Nhà", Latitude: 0, Longitude: 0}, RecipientIDs: []uuid.UUID{viewer}}
	for _, tc := range []struct {
		name string
		edit func(*CreateRequest)
	}{
		{"no-id", func(r *CreateRequest) { r.JourneyID = uuid.Nil }},
		{"no-time", func(r *CreateRequest) { r.PreparedAt = time.Time{} }},
		{"too-short", func(r *CreateRequest) { r.DurationMinutes = 14 }},
		{"too-long", func(r *CreateRequest) { r.DurationMinutes = 481 }},
		{"empty", func(r *CreateRequest) { r.RecipientIDs = nil }},
		{"duplicate", func(r *CreateRequest) { r.RecipientIDs = []uuid.UUID{viewer, viewer} }},
		{"self", func(r *CreateRequest) { r.RecipientIDs = []uuid.UUID{owner} }},
		{"blank", func(r *CreateRequest) { r.Destination.Label = " " }},
		{"control", func(r *CreateRequest) { r.Destination.Label = "Nhà\n" }},
		{"nan", func(r *CreateRequest) { r.Destination.Latitude = math.NaN() }},
		{"infinity", func(r *CreateRequest) { r.Destination.Longitude = math.Inf(1) }},
		{"longitude", func(r *CreateRequest) { r.Destination.Longitude = 181 }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			in := base
			tc.edit(&in)
			_, _, err := validate(in, owner)
			require.ErrorIs(t, err, ErrInvalid)
		})
	}
	_, _, err := validate(base, owner)
	require.NoError(t, err, "valid zero coordinates are accepted")
}
