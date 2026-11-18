package places

import (
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func TestTransitionsRequireDwellAndIgnoreJitter(t *testing.T) {
	start := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	state := fenceState{}
	state, event := observe(state, start, 300, 5, 150)
	require.Empty(t, event)
	state, event = observe(state, start.Add(Dwell), 300, 5, 150)
	require.Equal(t, outside, state.Stable)
	require.Empty(t, event)
	for i, distance := range []float64{153, 165, 155, 170} {
		state, event = observe(state, start.Add(Dwell+time.Duration(i+1)*time.Minute), distance, 5, 150)
		require.Empty(t, event)
		require.Equal(t, outside, state.Stable)
	}
	state, event = observe(state, start.Add(7*time.Minute), 50, 5, 150)
	require.Empty(t, event)
	state, event = observe(state, start.Add(9*time.Minute), 50, 5, 150)
	require.Equal(t, "ENTER", event)
	require.Equal(t, inside, state.Stable)
	state, event = observe(state, start.Add(10*time.Minute), 300, 5, 150)
	require.Empty(t, event)
	state, event = observe(state, start.Add(12*time.Minute), 300, 5, 150)
	require.Equal(t, "EXIT", event)
}

func TestIndoorSmallPlaceEntrySurvivesBufferSamplesAndExpiresStaleCandidate(t *testing.T) {
	start := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	state := fenceState{Stable: outside, LastObserved: start}
	// 50m radius Home with indoor Wi-Fi accuracy (35-60m) and one buffer sample.
	state, event := observe(state, start.Add(time.Minute), 30, 45, 50)
	require.Empty(t, event)
	require.Equal(t, inside, state.Candidate)
	state, event = observe(state, start.Add(2*time.Minute), 55, 50, 50)
	require.Empty(t, event)
	require.Equal(t, inside, state.Candidate)
	state, event = observe(state, start.Add(3*time.Minute), 35, 60, 50)
	require.Equal(t, "ENTER", event)
	require.Equal(t, inside, state.Stable)

	// A candidate stuck in the buffer zone beyond 2*Dwell expires instead of
	// triggering an instant transition much later.
	stale := fenceState{Stable: outside, LastObserved: start}
	stale, _ = observe(stale, start.Add(time.Minute), 40, 40, 50)
	require.Equal(t, inside, stale.Candidate)
	stale, _ = observe(stale, start.Add(6*time.Minute), 60, 40, 50)
	require.Empty(t, stale.Candidate)
	stale, event = observe(stale, start.Add(7*time.Minute), 40, 40, 50)
	require.Empty(t, event)
	require.Equal(t, inside, stale.Candidate)
}

func TestPoorAccuracyLateSamplesAndGapsCannotCreateTransitions(t *testing.T) {
	start := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	state := fenceState{Stable: outside, LastObserved: start}
	state, event := observe(state, start.Add(time.Minute), 10, 101, 150)
	require.Empty(t, event)
	state, event = observe(state, start.Add(2*time.Minute), 10, 5, 150)
	require.Empty(t, event)
	unchanged, event := observe(state, start.Add(time.Minute), 300, 5, 150)
	require.Equal(t, state, unchanged)
	require.Empty(t, event)
	state, event = observe(state, start.Add(30*time.Minute), 10, 5, 150)
	require.Equal(t, unknown, state.Stable)
	require.Empty(t, event)
	state, event = observe(state, start.Add(32*time.Minute), 10, 5, 150)
	require.Equal(t, inside, state.Stable)
	require.Empty(t, event)
}
