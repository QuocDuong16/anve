package locations

import (
	"errors"
	"math"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func validEvent(now time.Time) Event {
	accuracy := 12.5
	speed := 1.4
	bearing := 271.0
	altitude := 10.0
	return Event{
		EventID:                  uuid.Must(uuid.NewV7()),
		ClientDeviceID:           "device-0001",
		RecordedAt:               now.Add(-time.Minute),
		Latitude:                 10.7769,
		Longitude:                106.7009,
		HorizontalAccuracyMeters: accuracy,
		AltitudeMeters:           &altitude,
		SpeedMetersPerSecond:     &speed,
		BearingDegrees:           &bearing,
		Provider:                 "fused",
		SchemaVersion:            1,
	}
}

func TestValidateBatchAcceptsWellFormedEvents(t *testing.T) {
	now := time.Date(2026, 10, 26, 12, 0, 0, 0, time.UTC)
	events := []Event{validEvent(now)}
	events[0].RecordedAt = now // exactly now is allowed
	require.NoError(t, validateBatch(events, now, MaxEventAge))
}

func TestValidateBatchRejectsInvalidEvents(t *testing.T) {
	now := time.Date(2026, 10, 26, 12, 0, 0, 0, time.UTC)

	tests := []struct {
		name   string
		mutate func(*Event)
		fields []Event
		want   error
	}{
		{name: "empty batch", fields: []Event{}, want: ErrBatchInvalid},
		{
			name:   "missing event id",
			mutate: func(e *Event) { e.EventID = uuid.Nil },
			want:   ErrBatchInvalid,
		},
		{
			name:   "short device id",
			mutate: func(e *Event) { e.ClientDeviceID = "short" },
			want:   ErrBatchInvalid,
		},
		{
			name:   "long device id",
			mutate: func(e *Event) { e.ClientDeviceID = string(make([]byte, 65)) },
			want:   ErrBatchInvalid,
		},
		{
			name:   "upper-case provider",
			mutate: func(e *Event) { e.Provider = "Fused" },
			want:   ErrBatchInvalid,
		},
		{
			name:   "empty provider",
			mutate: func(e *Event) { e.Provider = "" },
			want:   ErrBatchInvalid,
		},
		{
			name:   "unsupported schema version",
			mutate: func(e *Event) { e.SchemaVersion = 17 },
			want:   ErrBatchInvalid,
		},
		{
			name:   "missing recordedAt",
			mutate: func(e *Event) { e.RecordedAt = time.Time{} },
			want:   ErrBatchInvalid,
		},
		{
			name:   "future timestamp beyond skew",
			mutate: func(e *Event) { e.RecordedAt = now.Add(MaxFutureSkew + time.Second) },
			want:   ErrBatchInvalid,
		},
		{
			name:   "timestamp older than retention",
			mutate: func(e *Event) { e.RecordedAt = now.Add(-MaxEventAge - time.Second) },
			want:   ErrEventTooOld,
		},
		{
			name:   "latitude out of range",
			mutate: func(e *Event) { e.Latitude = 90.1 },
			want:   ErrBatchInvalid,
		},
		{
			name:   "latitude NaN",
			mutate: func(e *Event) { e.Latitude = math.NaN() },
			want:   ErrBatchInvalid,
		},
		{
			name:   "longitude infinity",
			mutate: func(e *Event) { e.Longitude = math.Inf(1) },
			want:   ErrBatchInvalid,
		},
		{
			name:   "negative accuracy",
			mutate: func(e *Event) { e.HorizontalAccuracyMeters = -1 },
			want:   ErrBatchInvalid,
		},
		{
			name:   "absurd accuracy",
			mutate: func(e *Event) { e.HorizontalAccuracyMeters = MaxAccuracyMeters + 1 },
			want:   ErrBatchInvalid,
		},
		{
			name: "negative speed",
			mutate: func(e *Event) {
				value := -0.1
				e.SpeedMetersPerSecond = &value
			},
			want: ErrBatchInvalid,
		},
		{
			name:   "absurd altitude",
			mutate: func(e *Event) { v := 2e6; e.AltitudeMeters = &v },
			want:   ErrBatchInvalid,
		},
		{
			name:   "bearing above 360",
			mutate: func(e *Event) { v := 360.1; e.BearingDegrees = &v },
			want:   ErrBatchInvalid,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			events := tc.fields
			if events == nil {
				event := validEvent(now)
				if tc.mutate != nil {
					tc.mutate(&event)
				}
				events = []Event{event}
			}
			err := validateBatch(events, now, MaxEventAge)
			require.Error(t, err)
			assert.True(t, errors.Is(err, tc.want), "got %v, want %v", err, tc.want)
		})
	}
}

func TestValidateBatchRejectsRepeatedEventIDInsideBatch(t *testing.T) {
	now := time.Now().UTC()
	event := validEvent(now)
	err := validateBatch([]Event{event, event}, now, MaxEventAge)
	require.ErrorIs(t, err, ErrBatchInvalid)
	assert.Contains(t, err.Error(), "repeats eventId")
}

func TestValidateBatchBounds(t *testing.T) {
	now := time.Now().UTC()
	events := make([]Event, MaxBatchEvents)
	for i := range events {
		events[i] = validEvent(now)
	}
	require.NoError(t, validateBatch(events, now, MaxEventAge))

	events = append(events, validEvent(now))
	require.ErrorIs(t, validateBatch(events, now, MaxEventAge), ErrBatchTooLarge)
}
