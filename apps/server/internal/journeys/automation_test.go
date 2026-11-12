package journeys

import (
	"math"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
)

func TestEstimatedArrivalRequiresContinuousRecentRealSamples(t *testing.T) {
	now := time.Now().UTC()
	device := uuid.New()
	base := []automationSample{}
	for i := range 3 {
		captured := now.Add(-time.Duration(i) * time.Minute)
		base = append(base, automationSample{Device: device, RecordedAt: captured, ReceivedAt: captured, Distance: 100, Accuracy: 50})
	}
	require.True(t, estimatedArrival(base, now))
	for _, tc := range []struct {
		name string
		edit func([]automationSample) []automationSample
	}{
		{"too-few", func(s []automationSample) []automationSample { return s[:2] }},
		{"duplicate-time", func(s []automationSample) []automationSample { s[1] = s[0]; return s }},
		{"no-dwell", func(s []automationSample) []automationSample { s[2].RecordedAt = now.Add(-time.Minute); return s }},
		{"mixed-device", func(s []automationSample) []automationSample { s[1].Device = uuid.New(); return s }},
		{"mock", func(s []automationSample) []automationSample { s[1].Mock = true; return s }},
		{"imprecise", func(s []automationSample) []automationSample { s[1].Accuracy = 51; return s }},
		{"accuracy-overlaps-boundary", func(s []automationSample) []automationSample { s[1].Distance = 101; return s }},
		{"delayed-upload", func(s []automationSample) []automationSample { s[1].ReceivedAt = now.Add(time.Second); return s }},
		{"clock-ahead", func(s []automationSample) []automationSample { s[0].RecordedAt = now.Add(6 * time.Second); return s }},
		{"unordered", func(s []automationSample) []automationSample { s[1], s[0] = s[0], s[1]; return s }},
		{"large-gap", func(s []automationSample) []automationSample {
			s[1].RecordedAt = now.Add(-100 * time.Second)
			s[1].ReceivedAt = s[1].RecordedAt
			return s
		}},
		{"stale-latest", func(s []automationSample) []automationSample {
			for i := range s {
				s[i].RecordedAt = s[i].RecordedAt.Add(-61 * time.Second)
				s[i].ReceivedAt = s[i].RecordedAt
			}
			return s
		}},
		{"nan", func(s []automationSample) []automationSample { s[1].Distance = math.NaN(); return s }},
		{"infinite", func(s []automationSample) []automationSample { s[1].Accuracy = math.Inf(1); return s }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			require.False(t, estimatedArrival(tc.edit(append([]automationSample{}, base...)), now))
		})
	}
	// Duplicate delivery does not manufacture dwell but does not discard valid evidence.
	withDuplicate := append([]automationSample{base[0]}, base...)
	require.True(t, estimatedArrival(withDuplicate, now))
}
