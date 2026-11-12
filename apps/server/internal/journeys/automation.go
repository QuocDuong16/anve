package journeys

import (
	"context"
	"errors"
	"fmt"
	"math"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/QuocDuong16/anve/server/internal/audit"
)

// These conservative engineering defaults are not field-calibrated guarantees.
// They consume already-authorized ingestion; they never start acquisition.
const arrivalRadius = 150.0
const arrivalAccuracy = 50.0
const arrivalDwell = 2 * time.Minute
const arrivalMaxGap = 90 * time.Second
const liveMaxLag = time.Minute

type automationSample struct {
	Device                 uuid.UUID
	RecordedAt, ReceivedAt time.Time
	Distance, Accuracy     float64
	Mock                   bool
}

func timely(s automationSample, now time.Time) bool {
	lag := s.ReceivedAt.Sub(s.RecordedAt)
	return !s.Mock && !s.RecordedAt.After(now.Add(5*time.Second)) && lag >= -5*time.Second && lag <= liveMaxLag
}

// Input is descending in capture time. A bad or mixed-device sample breaks
// the dwell rather than disappearing and bridging an unobserved interval.
func estimatedArrival(samples []automationSample, now time.Time) bool {
	if len(samples) < 3 || now.Sub(samples[0].RecordedAt) > time.Minute {
		return false
	}
	latest := samples[0]
	previous := latest.RecordedAt
	distinct := 0
	for _, s := range samples {
		if s.Device != latest.Device || !timely(s, now) || math.IsNaN(s.Accuracy) || math.IsNaN(s.Distance) || math.IsInf(s.Accuracy, 0) || math.IsInf(s.Distance, 0) || s.Accuracy > arrivalAccuracy || s.Accuracy < 0 || s.Distance < 0 || s.Distance+s.Accuracy > arrivalRadius || previous.Before(s.RecordedAt) || previous.Sub(s.RecordedAt) > arrivalMaxGap {
			return false
		}
		if distinct > 0 && s.RecordedAt.Equal(previous) {
			continue
		}
		distinct++
		if distinct >= 3 && latest.RecordedAt.Sub(s.RecordedAt) >= arrivalDwell {
			return true
		}
		previous = s.RecordedAt
	}
	return false
}

func (s *Store) evaluateAutomation(ctx context.Context, tx pgx.Tx, owner uuid.UUID) error {
	var id uuid.UUID
	var anchor, now time.Time
	var automatic bool
	var minutes, episode int
	var generation int64
	var previousGeneration *int64
	var live, stale *time.Time
	err := tx.QueryRow(ctx, `SELECT j.id,j.automatic_arrival,j.stale_after_minutes,j.automation_generation,u.location_capture_generation,
 j.automation_anchor_at,j.last_live_update_at,j.stale_since,j.stale_episode,clock_timestamp()
 FROM current_journeys j JOIN users u ON u.id=j.owner_user_id WHERE j.owner_user_id=$1 AND j.effective_state='ACTIVE'`, owner).
		Scan(&id, &automatic, &minutes, &previousGeneration, &generation, &anchor, &live, &stale, &episode, &now)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("read journey automation: %w", err)
	}
	if previousGeneration == nil || *previousGeneration != generation {
		live = nil
		stale = nil
		anchor = now
		if _, err = tx.Exec(ctx, `UPDATE journey_notification_outbox SET state='SUPPRESSED' WHERE journey_id=$1 AND kind='STALE' AND state='QUEUED'`, id); err != nil {
			return fmt.Errorf("reset journey automation work: %w", err)
		}
	}
	samples := []automationSample{}
	if automatic || minutes > 0 {
		rows, queryErr := tx.Query(ctx, `SELECT lp.device_id,lp.recorded_at,lp.received_at,ST_Distance(lp.geom,j.destination),lp.horizontal_accuracy_m,lp.is_mock
 FROM journeys j JOIN location_points lp ON lp.user_id=j.owner_user_id AND lp.sharing_epoch_id=j.sharing_epoch_id
 WHERE j.id=$1 AND lp.capture_generation=$2 AND lp.recorded_at>=j.started_at AND lp.recorded_at>=$3
 ORDER BY lp.recorded_at DESC,lp.received_at DESC,lp.event_id DESC LIMIT 300`, id, generation, anchor)
		if queryErr != nil {
			return fmt.Errorf("read automation samples: %w", queryErr)
		}
		for rows.Next() {
			var sample automationSample
			if err = rows.Scan(&sample.Device, &sample.RecordedAt, &sample.ReceivedAt, &sample.Distance, &sample.Accuracy, &sample.Mock); err != nil {
				rows.Close()
				return fmt.Errorf("scan automation sample: %w", err)
			}
			samples = append(samples, sample)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return fmt.Errorf("iterate automation samples: %w", err)
		}
	}
	for _, sample := range samples {
		if timely(sample, now) && !sample.RecordedAt.Before(anchor) && (live == nil || sample.RecordedAt.After(*live)) {
			value := sample.RecordedAt
			live = &value
		}
	}
	if automatic && estimatedArrival(samples, now) {
		_, err = tx.Exec(ctx, `UPDATE journeys SET state='ARRIVED',arrival_source='AUTOMATIC',ended_at=clock_timestamp(),stale_since=NULL,last_live_update_at=$2,automation_generation=$3,automation_anchor_at=$4,evaluated_at=clock_timestamp() WHERE id=$1`, id, live, generation, anchor)
		if err != nil {
			return fmt.Errorf("record estimated arrival: %w", err)
		}
		return recordTransition(ctx, tx, owner, id, "ARRIVED")
	}
	last := anchor
	if live != nil {
		last = *live
	}
	isStale := minutes > 0 && !now.Before(last.Add(time.Duration(minutes)*time.Minute))
	newEpisode := isStale && stale == nil
	if isStale {
		value := last.Add(time.Duration(minutes) * time.Minute)
		stale = &value
	} else {
		stale = nil
	}
	if newEpisode {
		episode++
	}
	_, err = tx.Exec(ctx, `UPDATE journeys SET last_live_update_at=$2,stale_since=$3,stale_episode=$4,automation_generation=$5,automation_anchor_at=$6,evaluated_at=clock_timestamp() WHERE id=$1`, id, live, stale, episode, generation, anchor)
	if err != nil {
		return fmt.Errorf("record journey update health: %w", err)
	}
	if !isStale {
		_, err = tx.Exec(ctx, `UPDATE journey_notification_outbox SET state='SUPPRESSED' WHERE journey_id=$1 AND kind='STALE' AND state='QUEUED'`, id)
		if err != nil {
			return fmt.Errorf("clear recovered journey reminder: %w", err)
		}
	}
	if newEpisode {
		_, err = tx.Exec(ctx, `INSERT INTO journey_notification_outbox(journey_id,recipient_user_id,kind,episode)
 SELECT $1::uuid,user_id,'STALE',$3::integer FROM authorized_journey_recipients WHERE journey_id=$1
 UNION SELECT $1::uuid,$2::uuid,'STALE',$3::integer ON CONFLICT DO NOTHING`, id, owner, episode)
		if err != nil {
			return fmt.Errorf("enqueue journey reminder: %w", err)
		}
		if err = audit.InTx(ctx, tx, audit.Event{ActorUserID: owner, Action: "journey.stale", Detail: map[string]any{"journey_id": id.String(), "episode": episode}}); err != nil {
			return err
		}
	}
	return nil
}
