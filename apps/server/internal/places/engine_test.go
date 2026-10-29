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
	for i, distance := range []float64{148, 153, 149, 152} {
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
