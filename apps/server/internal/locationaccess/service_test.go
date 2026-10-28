package locationaccess

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
)

type confidenceStore struct {
	locations []VisibleLocation
}

func (s confidenceStore) Audience(context.Context, uuid.UUID) ([]Grant, error) {
	return nil, nil
}

func (s confidenceStore) Grant(context.Context, uuid.UUID, uuid.UUID) (Grant, error) {
	return Grant{}, nil
}

func (s confidenceStore) Revoke(context.Context, uuid.UUID, uuid.UUID) error { return nil }

func (s confidenceStore) Latest(context.Context, uuid.UUID, time.Time, time.Time) ([]VisibleLocation, error) {
	return s.locations, nil
}

func TestLatestClassifiesLocationConfidenceFromCaptureTime(t *testing.T) {
	now := time.Date(2026, 10, 27, 12, 0, 0, 0, time.UTC)
	tests := []struct {
		name       string
		recordedAt *time.Time
		confidence string
		ageMillis  *int64
	}{
		{name: "missing sample", confidence: "UNKNOWN"},
		{name: "fresh accurate sample", recordedAt: timePointer(now.Add(-time.Minute)), confidence: "FRESH", ageMillis: intPointer(60_000)},
		{name: "fresh approximate sample", recordedAt: timePointer(now.Add(-2 * time.Minute)), confidence: "FRESH", ageMillis: intPointer(120_000)},
		{name: "delayed sample", recordedAt: timePointer(now.Add(-5 * time.Minute)), confidence: "DELAYED", ageMillis: intPointer(300_000)},
		{name: "stale sample", recordedAt: timePointer(now.Add(-16 * time.Minute)), confidence: "STALE", ageMillis: intPointer(960_000)},
		{name: "future client clock", recordedAt: timePointer(now.Add(time.Minute)), confidence: "UNKNOWN"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			service := NewService(confidenceStore{locations: []VisibleLocation{{RecordedAt: test.recordedAt}}}, 30*24*time.Hour)
			service.now = func() time.Time { return now }

			got, err := service.Latest(context.Background(), uuid.New())

			require.NoError(t, err)
			require.Len(t, got, 1)
			require.Equal(t, test.confidence, got[0].Confidence)
			require.Equal(t, test.ageMillis, got[0].AgeMillis)
		})
	}
}

func timePointer(value time.Time) *time.Time { return &value }

func intPointer(value int64) *int64 { return &value }
