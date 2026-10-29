package locationaccess

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// History checks current authorization for every page; older grants never
// authorize access, even if they once covered a point.
func (s *PGStore) History(ctx context.Context, q HistoryQuery) ([]HistoryPoint, error) {
	var afterRecorded, afterReceived any
	var afterID any
	if q.AfterID != uuid.Nil {
		afterRecorded, afterReceived, afterID = q.AfterRecorded, q.AfterReceived, q.AfterID
	}
	rows, err := s.pool.Query(ctx, `SELECT lp.event_id, lp.latitude, lp.longitude, lp.horizontal_accuracy_m,
       lp.recorded_at, lp.received_at, lp.provider, lp.is_mock,
       md5(COALESCE(lp.sharing_epoch_id::text, 'owner') || ':' ||
          (SELECT count(*)::text FROM location_history_deletions d
             WHERE d.owner_user_id = lp.user_id AND upper(d.deleted_range) <= lp.recorded_at))
 FROM location_points lp
 JOIN users history_owner ON history_owner.id = lp.user_id
 WHERE lp.user_id = $2 AND lp.recorded_at >= $3 AND lp.recorded_at < $4 AND lp.history_generation <= $10 AND lp.recorded_at >= $5 AND lp.recorded_at >= COALESCE((SELECT cutoff FROM location_retention_watermark WHERE singleton), '-infinity'::timestamptz) AND lp.recorded_at >= now() - history_owner.location_retention_days * interval '1 day' AND lp.recorded_at <= now()
 AND ($6::timestamptz IS NULL OR (lp.recorded_at, lp.received_at, lp.event_id) > ($6, $7, $8))
 AND ($1 = $2 OR EXISTS (
   SELECT 1 FROM active_location_audience g
   WHERE g.owner_user_id = $2 AND g.viewer_user_id = $1
     AND g.sharing_epoch_id = lp.sharing_epoch_id
     AND lp.capture_generation >= g.capture_generation
     AND lp.recorded_at >= g.visible_from AND lp.received_at >= g.visible_from
     AND (g.visible_until IS NULL OR lp.recorded_at <= g.visible_until)
 )) ORDER BY lp.recorded_at, lp.received_at, lp.event_id LIMIT $9`, q.Viewer, q.Owner, q.From, q.To, q.Cutoff, afterRecorded, afterReceived, afterID, q.Limit, q.Snapshot)
	if err != nil {
		return nil, fmt.Errorf("query authorized history: %w", err)
	}
	defer rows.Close()
	points := []HistoryPoint{}
	for rows.Next() {
		var p HistoryPoint
		if err := rows.Scan(&p.EventID, &p.Latitude, &p.Longitude, &p.AccuracyM, &p.RecordedAt, &p.ReceivedAt, &p.Provider, &p.IsMock, &p.SegmentID); err != nil {
			return nil, fmt.Errorf("scan history point: %w", err)
		}
		p.RecordedAt, p.ReceivedAt = p.RecordedAt.UTC(), p.ReceivedAt.UTC()
		p.Backfill = p.ReceivedAt.Sub(p.RecordedAt) > 5*time.Minute
		points = append(points, p)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	rows.Close()
	if q.Viewer != q.Owner {
		var allowed bool
		err := s.pool.QueryRow(ctx, `SELECT EXISTS (
            SELECT 1 FROM active_location_audience g
            WHERE g.owner_user_id=$1 AND g.viewer_user_id=$2
        )`, q.Owner, q.Viewer).Scan(&allowed)
		if err != nil {
			return nil, fmt.Errorf("check history access: %w", err)
		}
		if !allowed {
			return nil, ErrNotFound
		}
	}
	return points, nil
}

// BeginHistory serializes with the owner's ingestion lock and advances an
// internal snapshot generation. Later uploads cannot enter existing cursors.
func (s *PGStore) BeginHistory(ctx context.Context, viewer, owner uuid.UUID) (int64, error) {
	var snapshot int64
	err := s.pool.QueryRow(ctx, `UPDATE users owner SET location_history_generation = location_history_generation + 1
    WHERE owner.id = $1 AND owner.location_history_generation < 9223372036854775807
    AND ($1 = $2 OR EXISTS (
        SELECT 1 FROM active_location_audience g
        WHERE g.owner_user_id=$1 AND g.viewer_user_id=$2
    )) RETURNING location_history_generation - 1`, owner, viewer).Scan(&snapshot)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, ErrNotFound
	}
	if err != nil {
		return 0, fmt.Errorf("begin history snapshot: %w", err)
	}
	return snapshot, nil
}
