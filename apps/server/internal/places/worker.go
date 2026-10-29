package places

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

const workerBatchSize = 100

// RunWorker drains a durable post-commit queue. It never starts GPS collection;
// a restart retries unprocessed points and the unique event key prevents replay.
func RunWorker(ctx context.Context, store *Store, logger *slog.Logger) {
	pass := func() {
		for i := 0; i < workerBatchSize; i++ {
			workCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
			processed, err := store.ProcessOne(workCtx)
			cancel()
			if err != nil {
				if ctx.Err() == nil {
					// Database errors can include row values; do not log raw GPS.
					logger.Error("place evaluation failed")
				}
				return
			}
			if !processed {
				return
			}
		}
	}
	pass()
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			pass()
		}
	}
}

// ProcessOne locks the monitored owner before queue/monitor rows, matching
// consent, history deletion and ingestion ordering. Events and dequeue commit
// in the same transaction. Stale samples never rewind state.
func (s *Store) ProcessOne(ctx context.Context) (processed bool, resultErr error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return false, fmt.Errorf("begin place evaluation: %w", err)
	}
	var pointID int64
	defer func() {
		_ = tx.Rollback(ctx)
		if resultErr != nil && pointID != 0 && !errors.Is(ctx.Err(), context.Canceled) {
			// A poison point must not starve unrelated owners. Persist bounded
			// backoff outside the rolled-back evaluation transaction.
			retryCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 2*time.Second)
			defer cancel()
			_, _ = s.pool.Exec(retryCtx, `UPDATE place_event_queue SET attempts=LEAST(attempts+1,100),
 next_attempt_at=clock_timestamp()+make_interval(secs => LEAST(300, (1 << LEAST(attempts,8)))) WHERE point_id=$1`, pointID)
		}
	}()
	var owner uuid.UUID
	err = tx.QueryRow(ctx, `SELECT q.point_id,q.owner_user_id FROM place_event_queue q
 WHERE q.recorded_at <= clock_timestamp() AND q.next_attempt_at <= clock_timestamp()
 AND NOT EXISTS (SELECT 1 FROM place_event_queue earlier WHERE earlier.owner_user_id=q.owner_user_id
   AND (earlier.recorded_at,earlier.point_id)<(q.recorded_at,q.point_id))
 ORDER BY q.recorded_at,q.point_id LIMIT 1`).Scan(&pointID, &owner)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("select place work: %w", err)
	}
	if err := lockOwner(ctx, tx, owner); err != nil {
		if errors.Is(err, ErrNotFound) {
			return true, nil
		}
		return false, err
	}
	// A backfill transaction may have committed an earlier point while this
	// worker waited for the owner. Recheck under the owner lock before taking
	// the originally selected item so already-committed work remains ordered.
	var firstID int64
	var due bool
	err = tx.QueryRow(ctx, `SELECT point_id,next_attempt_at<=clock_timestamp() FROM place_event_queue
 WHERE owner_user_id=$1 ORDER BY recorded_at,point_id LIMIT 1`, owner).Scan(&firstID, &due)
	if errors.Is(err, pgx.ErrNoRows) {
		return true, nil
	}
	if err != nil {
		return false, fmt.Errorf("recheck place work ordering: %w", err)
	}
	if firstID != pointID || !due {
		return true, nil
	}
	var latitude, longitude, accuracy float64
	var mock bool
	var recorded, received time.Time
	var epoch *uuid.UUID
	var generation int64
	err = tx.QueryRow(ctx, `SELECT lp.latitude,lp.longitude,lp.horizontal_accuracy_m,lp.is_mock,lp.recorded_at,lp.received_at,lp.sharing_epoch_id,lp.capture_generation
 FROM place_event_queue q JOIN location_points lp ON lp.id=q.point_id AND lp.user_id=q.owner_user_id WHERE q.point_id=$1 AND q.owner_user_id=$2 FOR UPDATE OF q SKIP LOCKED`, pointID, owner).
		Scan(&latitude, &longitude, &accuracy, &mock, &recorded, &received, &epoch, &generation)
	if errors.Is(err, pgx.ErrNoRows) {
		return true, nil
	}
	if err != nil {
		return false, fmt.Errorf("lock place work: %w", err)
	}
	if mock {
		accuracy = MaxAccuracyM + 1
	}
	rows, err := tx.Query(ctx, `SELECT pm.id,pm.stable_state,pm.candidate_state,pm.candidate_since,pm.last_observed_at,
 CASE WHEN sp.recorded_at >= GREATEST(now()-u.location_retention_days*interval '1 day',$6::timestamptz,COALESCE((SELECT cutoff FROM location_retention_watermark WHERE singleton),'-infinity'::timestamptz)) THEN pm.stable_point_id END,
 CASE WHEN cp.recorded_at >= GREATEST(now()-u.location_retention_days*interval '1 day',$6::timestamptz,COALESCE((SELECT cutoff FROM location_retention_watermark WHERE singleton),'-infinity'::timestamptz)) THEN pm.candidate_point_id END,
 p.latitude,p.longitude,p.radius_m
 FROM place_monitors pm JOIN places p ON p.id=pm.place_id AND p.revision=pm.place_revision
 JOIN users u ON u.id=pm.owner_user_id AND u.location_sharing_enabled
 LEFT JOIN location_points sp ON sp.id=pm.stable_point_id
 LEFT JOIN location_points cp ON cp.id=pm.candidate_point_id
 JOIN location_sharing_epochs e ON e.id=pm.sharing_epoch_id AND e.ended_at IS NULL
 JOIN circle_membership_periods mp ON mp.id=pm.owner_period_id AND mp.ended_at IS NULL
 JOIN memberships m ON m.id=mp.membership_id AND m.user_id=pm.owner_user_id AND m.circle_id=p.circle_id
 JOIN circle_membership_periods pp ON pp.id=p.owner_membership_period_id AND pp.ended_at IS NULL
 JOIN memberships po ON po.id=pp.membership_id AND po.user_id=p.owner_user_id AND po.circle_id=p.circle_id
 WHERE pm.owner_user_id=$1 AND pm.revoked_at IS NULL AND pm.sharing_epoch_id=$2
 AND pm.capture_generation <= $3 AND pm.started_at <= $4 AND pm.started_at <= $5
 AND $4 >= now()-u.location_retention_days*interval '1 day' AND $4 >= $6
 AND $4 >= COALESCE((SELECT cutoff FROM location_retention_watermark WHERE singleton),'-infinity'::timestamptz)
 AND (pm.owner_user_id=p.owner_user_id OR EXISTS (SELECT 1 FROM place_access a
  WHERE a.place_id=p.id AND a.member_user_id=pm.owner_user_id AND a.member_period_id=pm.owner_period_id AND a.revoked_at IS NULL))
 ORDER BY pm.id LIMIT 20 FOR UPDATE OF pm`, owner, epoch, generation, recorded, received, time.Now().UTC().Add(-s.retention))
	if err != nil {
		return false, fmt.Errorf("select authorized monitors: %w", err)
	}
	type pending struct {
		id             uuid.UUID
		state          fenceState
		stablePoint    *int64
		candidatePoint *int64
		lat            float64
		lon            float64
		radius         float64
	}
	var monitors []pending
	for rows.Next() {
		var p pending
		var candidate *string
		var candidateAt, last *time.Time
		if err := rows.Scan(&p.id, &p.state.Stable, &candidate, &candidateAt, &last, &p.stablePoint, &p.candidatePoint, &p.lat, &p.lon, &p.radius); err != nil {
			rows.Close()
			return false, fmt.Errorf("scan place monitor: %w", err)
		}
		if candidate != nil {
			p.state.Candidate = stateName(*candidate)
		}
		if candidateAt != nil {
			p.state.CandidateAt = *candidateAt
		}
		if last != nil {
			p.state.LastObserved = *last
		}
		monitors = append(monitors, p)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return false, fmt.Errorf("iterate place monitors: %w", err)
	}
	rows.Close()
	for _, monitor := range monitors {
		if monitor.state.Stable != unknown && monitor.stablePoint == nil {
			monitor.state.Stable, monitor.state.Candidate, monitor.state.CandidateAt = unknown, "", time.Time{}
		}
		if monitor.state.Candidate != "" && monitor.candidatePoint == nil {
			monitor.state.Candidate, monitor.state.CandidateAt = "", time.Time{}
		}
		previous := monitor.state
		baseline, evidence := monitor.stablePoint, monitor.candidatePoint
		state, transition := observe(monitor.state, recorded, distanceMeters(latitude, longitude, monitor.lat, monitor.lon), accuracy, monitor.radius)
		var candidate, candidateAt any
		if state.Candidate != "" {
			candidate, candidateAt = string(state.Candidate), state.CandidateAt
			if state.Candidate != previous.Candidate || !state.CandidateAt.Equal(previous.CandidateAt) {
				monitor.candidatePoint = &pointID
			}
		} else {
			monitor.candidatePoint = nil
		}
		if state.Stable != previous.Stable {
			if state.Stable == unknown {
				monitor.stablePoint = nil
			} else {
				monitor.stablePoint = &pointID
			}
		}
		if _, err := tx.Exec(ctx, `UPDATE place_monitors SET stable_state=$2,candidate_state=$3,candidate_since=$4,last_observed_at=$5,stable_point_id=$6,candidate_point_id=$7 WHERE id=$1`, monitor.id, string(state.Stable), candidate, candidateAt, state.LastObserved, monitor.stablePoint, monitor.candidatePoint); err != nil {
			return false, fmt.Errorf("update place state: %w", err)
		}
		if transition != "" {
			if baseline == nil || evidence == nil {
				return false, fmt.Errorf("missing transition evidence")
			}
			if _, err := tx.Exec(ctx, `INSERT INTO place_events(monitor_id,point_id,baseline_point_id,evidence_point_id,transition,recorded_at) VALUES($1,$2,$3,$4,$5,$6) ON CONFLICT DO NOTHING`, monitor.id, pointID, *baseline, *evidence, transition, recorded.UTC()); err != nil {
				return false, fmt.Errorf("record place transition: %w", err)
			}
		}
	}
	if _, err := tx.Exec(ctx, `DELETE FROM place_event_queue WHERE point_id=$1`, pointID); err != nil {
		return false, fmt.Errorf("finish place work: %w", err)
	}
	if err := tx.Commit(ctx); err != nil {
		return false, fmt.Errorf("commit place evaluation: %w", err)
	}
	return true, nil
}
