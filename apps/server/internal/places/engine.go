// Package places implements explicit place access and conservative geofencing.
package places

import (
	"math"
	"time"
)

// Thresholds are deliberately conservative: a transition is an observation,
// not proof of presence or safety. Sparse samples can miss transitions.
const (
	Dwell          = 2 * time.Minute
	MaxGap         = 15 * time.Minute
	MaxAccuracyM   = 100.0
	MinHysteresisM = 25.0
)

type stateName string

const (
	unknown stateName = "UNKNOWN"
	inside  stateName = "INSIDE"
	outside stateName = "OUTSIDE"
)

type fenceState struct {
	Stable       stateName
	Candidate    stateName
	CandidateAt  time.Time
	LastObserved time.Time
}

// observe processes one event-time ordered measurement. Late backfill cannot
// rewind state. A long gap requires a new baseline and creates no transition.
func observe(state fenceState, at time.Time, distanceM, accuracyM, radiusM float64) (fenceState, string) {
	if at.IsZero() || (!state.LastObserved.IsZero() && !at.After(state.LastObserved)) {
		return state, ""
	}
	if state.Stable == "" {
		state.Stable = unknown
	}
	if !state.LastObserved.IsZero() && at.Sub(state.LastObserved) > MaxGap {
		state.Stable, state.Candidate, state.CandidateAt = unknown, "", time.Time{}
	}
	state.LastObserved = at
	if math.IsNaN(distanceM) || math.IsInf(distanceM, 0) || math.IsNaN(accuracyM) || math.IsInf(accuracyM, 0) ||
		distanceM < 0 || accuracyM < 0 || accuracyM > MaxAccuracyM || radiusM < 50 || radiusM > 5000 {
		state.Candidate, state.CandidateAt = "", time.Time{}
		return state, ""
	}
	margin := math.Max(MinHysteresisM, radiusM*0.1)
	var observed stateName
	switch {
	case distanceM+accuracyM <= radiusM-margin:
		observed = inside
	case distanceM-accuracyM >= radiusM+margin:
		observed = outside
	default:
		state.Candidate, state.CandidateAt = "", time.Time{}
		return state, ""
	}
	if observed == state.Stable {
		state.Candidate, state.CandidateAt = "", time.Time{}
		return state, ""
	}
	if state.Candidate != observed {
		state.Candidate, state.CandidateAt = observed, at
		return state, ""
	}
	if at.Sub(state.CandidateAt) < Dwell {
		return state, ""
	}
	previous := state.Stable
	state.Stable, state.Candidate, state.CandidateAt = observed, "", time.Time{}
	if previous == unknown {
		return state, ""
	}
	if observed == inside {
		return state, "ENTER"
	}
	return state, "EXIT"
}

func distanceMeters(lat1, lon1, lat2, lon2 float64) float64 {
	const radians = math.Pi / 180
	phi1, phi2 := lat1*radians, lat2*radians
	deltaLat, deltaLon := (lat2-lat1)*radians, (lon2-lon1)*radians
	a := math.Pow(math.Sin(deltaLat/2), 2) + math.Cos(phi1)*math.Cos(phi2)*math.Pow(math.Sin(deltaLon/2), 2)
	return 12742000 * math.Asin(math.Sqrt(math.Min(1, math.Max(0, a))))
}
