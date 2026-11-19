package batteryalerts

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"math"
	"slices"
	"strconv"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/QuocDuong16/anve/server/internal/audit"
	"github.com/QuocDuong16/anve/server/internal/auth"
)

// Store persists low-battery alerts and enforces PostgreSQL authorization.
type Store struct {
	pool          *pgxpool.Pool
	retentionDays int
}

// NewStore builds a battery alert store capped to at most 7 days of retention.
func NewStore(pool *pgxpool.Pool, retentionDays int) *Store {
	return &Store{pool: pool, retentionDays: max(1, min(7, retentionDays))}
}

func validateRequest(in CreateRequest) ([32]byte, error) {
	if in.EventID == uuid.Nil || (in.Threshold != 15 && in.Threshold != 5) ||
		in.BatteryPercent < 0 || in.BatteryPercent > 100 || in.BatteryPercent > in.Threshold ||
		in.RecordedAt.IsZero() {
		return [32]byte{}, ErrInvalid
	}
	if in.Location != nil {
		loc := in.Location
		if math.IsNaN(loc.Latitude) || math.IsInf(loc.Latitude, 0) || loc.Latitude < -90 || loc.Latitude > 90 ||
			math.IsNaN(loc.Longitude) || math.IsInf(loc.Longitude, 0) || loc.Longitude < -180 || loc.Longitude > 180 ||
			math.IsNaN(loc.HorizontalAccuracyMeters) || math.IsInf(loc.HorizontalAccuracyMeters, 0) ||
			loc.HorizontalAccuracyMeters < 0 || loc.HorizontalAccuracyMeters > 100000 ||
			loc.RecordedAt.IsZero() {
			return [32]byte{}, ErrInvalid
		}
	}
	material := in.EventID.String() + "|" + strconv.Itoa(in.Threshold) + "|" + strconv.Itoa(in.BatteryPercent) + "|" + in.RecordedAt.UTC().Format(time.RFC3339Nano)
	if in.Location != nil {
		material += "|loc:" +
			strconv.FormatFloat(in.Location.Latitude, 'g', -1, 64) + "," +
			strconv.FormatFloat(in.Location.Longitude, 'g', -1, 64) + "," +
			strconv.FormatFloat(in.Location.HorizontalAccuracyMeters, 'g', -1, 64) + "," +
			in.Location.RecordedAt.UTC().Format(time.RFC3339Nano)
	}
	return sha256.Sum256([]byte(material)), nil
}

func lockPrincipal(ctx context.Context, tx pgx.Tx, p Principal) error {
	if p.UserID == uuid.Nil || p.SessionID == uuid.Nil || p.DeviceID == uuid.Nil {
		return auth.ErrUnauthenticated
	}
	var id uuid.UUID
	err := tx.QueryRow(ctx, `SELECT id FROM users WHERE id=$1 FOR UPDATE`, p.UserID).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return auth.ErrUnauthenticated
	}
	if err != nil {
		return fmt.Errorf("lock battery alert account: %w", err)
	}
	err = tx.QueryRow(ctx, `SELECT s.id FROM sessions s JOIN devices d ON d.id=s.device_id AND d.user_id=s.user_id
 WHERE s.id=$1 AND s.user_id=$2 AND s.device_id=$3 AND s.revoked_at IS NULL AND s.access_expires_at>clock_timestamp()
 FOR SHARE OF s,d`, p.SessionID, p.UserID, p.DeviceID).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return auth.ErrUnauthenticated
	}
	if err != nil {
		return fmt.Errorf("lock battery alert session: %w", err)
	}
	return nil
}

func lockIdentity(ctx context.Context, tx pgx.Tx, id uuid.UUID) error {
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended('battery_alert:' || $1::text, 0))`, id.String()); err != nil {
		return fmt.Errorf("lock battery alert identity: %w", err)
	}
	return nil
}

// Create records a low-battery alert idempotently when consent, opt-in
// preference, freshness, and cooldown checks pass, narrowing recipients to an
// active Safe Journey when one exists.
func (s *Store) Create(ctx context.Context, p Principal, in CreateRequest) (Alert, bool, error) {
	hash, err := validateRequest(in)
	if err != nil {
		return Alert{}, false, err
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Alert{}, false, fmt.Errorf("begin battery alert create: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err = lockPrincipal(ctx, tx, p); err != nil {
		return Alert{}, false, err
	}
	if err = lockIdentity(ctx, tx, in.EventID); err != nil {
		return Alert{}, false, err
	}

	var fenceOwner uuid.UUID
	err = tx.QueryRow(ctx, `SELECT owner_user_id FROM low_battery_alert_fences WHERE event_id=$1`, in.EventID).Scan(&fenceOwner)
	if err == nil {
		if fenceOwner != p.UserID {
			return Alert{}, false, ErrNotFound
		}
		var existingHash []byte
		err = tx.QueryRow(ctx, `SELECT request_hash FROM low_battery_alerts WHERE id=$1`, in.EventID).Scan(&existingHash)
		if errors.Is(err, pgx.ErrNoRows) {
			return Alert{}, false, ErrConflict
		}
		if err != nil {
			return Alert{}, false, fmt.Errorf("read battery alert hash: %w", err)
		}
		if !slices.Equal(existingHash, hash[:]) {
			return Alert{}, false, ErrConflict
		}
		alert, readErr := s.read(ctx, tx, p.UserID, in.EventID)
		if readErr != nil {
			return Alert{}, false, readErr
		}
		if err = tx.Commit(ctx); err != nil {
			return Alert{}, false, fmt.Errorf("commit idempotent battery alert: %w", err)
		}
		return alert, false, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return Alert{}, false, fmt.Errorf("read battery alert fence: %w", err)
	}

	var sharingEnabled, lowBatteryEnabled bool
	var captureGeneration int64
	var now time.Time
	err = tx.QueryRow(ctx, `SELECT location_sharing_enabled,low_battery_alerts_enabled,location_capture_generation,clock_timestamp() FROM users WHERE id=$1`, p.UserID).
		Scan(&sharingEnabled, &lowBatteryEnabled, &captureGeneration, &now)
	if err != nil {
		return Alert{}, false, fmt.Errorf("read battery alert user state: %w", err)
	}
	if !sharingEnabled {
		return Alert{}, false, ErrConsentRequired
	}
	if !lowBatteryEnabled {
		return Alert{}, false, ErrPreferenceDisabled
	}
	if in.RecordedAt.After(now.Add(2 * time.Minute)) {
		return Alert{}, false, ErrInvalid
	}
	if in.RecordedAt.Before(now.Add(-MaxFreshnessAge)) {
		return Alert{}, false, ErrTooOld
	}

	loc := in.Location
	if loc != nil {
		if loc.RecordedAt.After(now.Add(2*time.Minute)) || loc.RecordedAt.Before(now.Add(-MaxLocationAge)) {
			return Alert{}, false, ErrInvalid
		}
		var inDeletedRange bool
		if err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM location_history_deletions WHERE owner_user_id=$1 AND deleted_range @> $2::timestamptz)`, p.UserID, loc.RecordedAt.UTC()).
			Scan(&inDeletedRange); err != nil {
			return Alert{}, false, fmt.Errorf("check deleted location range: %w", err)
		}
		if inDeletedRange {
			loc = nil
		}
	}

	var cooldownActive bool
	err = tx.QueryRow(ctx, `SELECT EXISTS(
		SELECT 1 FROM low_battery_alerts
		 WHERE owner_user_id=$1 AND threshold=$2 AND resolved_at IS NULL
		   AND received_at > $3::timestamptz - interval '30 minutes'
	)`, p.UserID, in.Threshold, now).Scan(&cooldownActive)
	if err != nil {
		return Alert{}, false, fmt.Errorf("check low-battery cooldown: %w", err)
	}
	if cooldownActive {
		return Alert{}, false, ErrCooldown
	}

	var circleID, ownerPeriodID, epochID uuid.UUID
	err = tx.QueryRow(ctx, `SELECT om.circle_id,op.id,se.id
		FROM memberships om
		JOIN circle_membership_periods op ON op.membership_id=om.id AND op.ended_at IS NULL
		JOIN location_sharing_epochs se ON se.owner_user_id=om.user_id
			AND se.circle_id=om.circle_id
			AND se.owner_membership_period_id=op.id
			AND se.ended_at IS NULL
		WHERE om.user_id=$1
		FOR SHARE OF om,op,se`, p.UserID).Scan(&circleID, &ownerPeriodID, &epochID)
	if errors.Is(err, pgx.ErrNoRows) {
		var hasMembership bool
		if checkErr := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM memberships WHERE user_id=$1)`, p.UserID).Scan(&hasMembership); checkErr != nil {
			return Alert{}, false, fmt.Errorf("check membership on battery alert: %w", checkErr)
		}
		if !hasMembership {
			return Alert{}, false, ErrCircleRequired
		}
		return Alert{}, false, ErrConsentRequired
	}
	if err != nil {
		return Alert{}, false, fmt.Errorf("lock sharing epoch for battery alert: %w", err)
	}

	type recipientGrant struct {
		userID  uuid.UUID
		grantID uuid.UUID
	}
	scope := ScopeSharing
	var journeyID *uuid.UUID
	var activeJourney uuid.UUID
	err = tx.QueryRow(ctx, `SELECT id FROM current_journeys WHERE owner_user_id=$1 AND effective_state='ACTIVE' LIMIT 1`, p.UserID).Scan(&activeJourney)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return Alert{}, false, fmt.Errorf("check active journey for battery alert: %w", err)
	}

	var recipientRows pgx.Rows
	if err == nil {
		scope = ScopeJourney
		journeyID = &activeJourney
		recipientRows, err = tx.Query(ctx, `SELECT user_id,grant_id FROM authorized_journey_recipients WHERE journey_id=$1 ORDER BY user_id`, activeJourney)
	} else {
		recipientRows, err = tx.Query(ctx, `SELECT viewer_user_id,id FROM active_location_audience
			WHERE owner_user_id=$1 AND sharing_epoch_id=$2 AND visible_from<=clock_timestamp()
			  AND (visible_until IS NULL OR visible_until>clock_timestamp())
			ORDER BY viewer_user_id`, p.UserID, epochID)
	}
	if err != nil {
		return Alert{}, false, fmt.Errorf("resolve battery alert recipients: %w", err)
	}
	var recipients []recipientGrant
	for recipientRows.Next() {
		var item recipientGrant
		if scanErr := recipientRows.Scan(&item.userID, &item.grantID); scanErr != nil {
			recipientRows.Close()
			return Alert{}, false, fmt.Errorf("scan battery alert recipient: %w", scanErr)
		}
		recipients = append(recipients, item)
	}
	if rowsErr := recipientRows.Err(); rowsErr != nil {
		recipientRows.Close()
		return Alert{}, false, fmt.Errorf("iterate battery alert recipients: %w", rowsErr)
	}
	recipientRows.Close()

	if _, err = tx.Exec(ctx, `INSERT INTO low_battery_alert_fences(event_id,owner_user_id) VALUES($1,$2)`, in.EventID, p.UserID); err != nil {
		return Alert{}, false, fmt.Errorf("insert battery alert fence: %w", err)
	}

	var lat, lon, acc pgtype.Float8
	var locRecorded pgtype.Timestamptz
	if loc != nil {
		lat = pgtype.Float8{Float64: loc.Latitude, Valid: true}
		lon = pgtype.Float8{Float64: loc.Longitude, Valid: true}
		acc = pgtype.Float8{Float64: loc.HorizontalAccuracyMeters, Valid: true}
		locRecorded = pgtype.Timestamptz{Time: loc.RecordedAt.UTC(), Valid: true}
	}
	if _, err = tx.Exec(ctx, `INSERT INTO low_battery_alerts(
			id,owner_user_id,device_id,circle_id,owner_period_id,sharing_epoch_id,
			journey_id,scope,threshold,battery_percent,
			latitude,longitude,horizontal_accuracy_m,location_recorded_at,geom,
			capture_generation,recorded_at,received_at,request_hash
		) VALUES(
			$1,$2,$3,$4,$5,$6,
			$7,$8,$9,$10,
			$11,$12,$13,$14,
			CASE WHEN $11::double precision IS NULL THEN NULL ELSE ST_SetSRID(ST_MakePoint($12,$11),4326)::geography END,
			$15,$16,$17,$18
		)`,
		in.EventID, p.UserID, p.DeviceID, circleID, ownerPeriodID, epochID,
		journeyID, scope, in.Threshold, in.BatteryPercent,
		lat, lon, acc, locRecorded,
		captureGeneration, in.RecordedAt.UTC(), now, hash[:],
	); err != nil {
		return Alert{}, false, fmt.Errorf("insert battery alert: %w", err)
	}

	for _, r := range recipients {
		if _, err = tx.Exec(ctx, `INSERT INTO low_battery_alert_recipients(alert_id,recipient_user_id,grant_id) VALUES($1,$2,$3) ON CONFLICT DO NOTHING`,
			in.EventID, r.userID, r.grantID); err != nil {
			return Alert{}, false, fmt.Errorf("insert battery alert recipient: %w", err)
		}
		if _, err = tx.Exec(ctx, `INSERT INTO low_battery_notification_outbox(alert_id,recipient_user_id) VALUES($1,$2) ON CONFLICT DO NOTHING`,
			in.EventID, r.userID); err != nil {
			return Alert{}, false, fmt.Errorf("enqueue battery alert notification: %w", err)
		}
	}

	if err = audit.InTx(ctx, tx, audit.Event{
		ActorUserID: p.UserID,
		Action:      audit.ActionLowBatteryAlertCreated,
		Detail: map[string]any{
			"alert_id":        in.EventID.String(),
			"threshold":       in.Threshold,
			"scope":           scope,
			"has_location":    loc != nil,
			"recipient_count": len(recipients),
		},
	}); err != nil {
		return Alert{}, false, err
	}

	alert, err := s.read(ctx, tx, p.UserID, in.EventID)
	if err != nil {
		return Alert{}, false, err
	}
	if err = tx.Commit(ctx); err != nil {
		return Alert{}, false, fmt.Errorf("commit battery alert: %w", err)
	}
	return alert, true, nil
}

// Resolve silently marks unresolved low-battery alerts for the authenticated
// device as resolved when charging recovers, suppressing queued pushes.
func (s *Store) Resolve(ctx context.Context, p Principal) (int64, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return 0, fmt.Errorf("begin battery alert resolve: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err = lockPrincipal(ctx, tx, p); err != nil {
		return 0, err
	}

	rows, err := tx.Query(ctx, `UPDATE low_battery_alerts
		SET resolved_at = clock_timestamp()
		WHERE owner_user_id=$1 AND device_id=$2 AND resolved_at IS NULL
		RETURNING id`, p.UserID, p.DeviceID)
	if err != nil {
		return 0, fmt.Errorf("resolve battery alerts: %w", err)
	}
	var resolvedIDs []uuid.UUID
	for rows.Next() {
		var id uuid.UUID
		if scanErr := rows.Scan(&id); scanErr != nil {
			rows.Close()
			return 0, fmt.Errorf("scan resolved battery alert: %w", scanErr)
		}
		resolvedIDs = append(resolvedIDs, id)
	}
	if rowsErr := rows.Err(); rowsErr != nil {
		rows.Close()
		return 0, fmt.Errorf("iterate resolved battery alerts: %w", rowsErr)
	}
	rows.Close()

	if len(resolvedIDs) > 0 {
		if _, err = tx.Exec(ctx, `UPDATE low_battery_notification_outbox
			SET state='SUPPRESSED'
			WHERE alert_id = ANY($1) AND state='QUEUED'`, resolvedIDs); err != nil {
			return 0, fmt.Errorf("suppress resolved battery alert notifications: %w", err)
		}
		if err = audit.InTx(ctx, tx, audit.Event{
			ActorUserID: p.UserID,
			Action:      audit.ActionLowBatteryAlertResolved,
			Detail: map[string]any{
				"device_id": p.DeviceID.String(),
				"count":     len(resolvedIDs),
			},
		}); err != nil {
			return 0, err
		}
	}
	if err = tx.Commit(ctx); err != nil {
		return 0, fmt.Errorf("commit battery alert resolve: %w", err)
	}
	return int64(len(resolvedIDs)), nil
}

// Get returns a low-battery alert if actor is the owner or a currently
// authorized recipient within retention.
func (s *Store) Get(ctx context.Context, actor, alertID uuid.UUID) (Alert, error) {
	if actor == uuid.Nil || alertID == uuid.Nil {
		return Alert{}, ErrNotFound
	}
	return s.read(ctx, s.pool, actor, alertID)
}

type querier interface {
	QueryRow(context.Context, string, ...any) pgx.Row
}

func (s *Store) read(ctx context.Context, q querier, actor, alertID uuid.UUID) (Alert, error) {
	var alert Alert
	var journeyID pgtype.UUID
	var resolvedAt pgtype.Timestamptz
	var lat, lon, acc pgtype.Float8
	var locRecorded pgtype.Timestamptz
	err := q.QueryRow(ctx, `SELECT a.id,a.owner_user_id,u.display_name,a.scope,a.journey_id,
			a.threshold,a.battery_percent,a.recorded_at,a.received_at,a.resolved_at,
			CASE WHEN a.owner_user_id=$2
			     THEN (SELECT count(*) FROM low_battery_alert_recipients r WHERE r.alert_id=a.id)
			     ELSE 1 END,
			a.latitude,a.longitude,a.horizontal_accuracy_m,a.location_recorded_at
		FROM low_battery_alerts a
		JOIN users u ON u.id=a.owner_user_id
		WHERE a.id=$1
		  AND a.recorded_at > now() - LEAST($3, u.location_retention_days) * interval '1 day'
		  AND a.recorded_at > COALESCE((SELECT cutoff FROM location_retention_watermark WHERE singleton), '-infinity'::timestamptz)
		  AND (
			a.owner_user_id=$2
			OR (
				u.location_sharing_enabled
				AND EXISTS (
					SELECT 1
					FROM low_battery_alert_recipients ar
					JOIN active_location_audience g
					  ON g.id=ar.grant_id
					 AND g.owner_user_id=a.owner_user_id
					 AND g.viewer_user_id=$2
					 AND g.sharing_epoch_id=a.sharing_epoch_id
					WHERE ar.alert_id=a.id
					  AND ar.recipient_user_id=$2
					  AND a.recorded_at >= g.visible_from
					  AND a.capture_generation >= g.capture_generation
					  AND (
						a.scope='SHARING'
						OR (
							a.scope='JOURNEY'
							AND a.journey_id IS NOT NULL
							AND EXISTS (
								SELECT 1 FROM authorized_journey_recipients jr
								WHERE jr.journey_id=a.journey_id
								  AND jr.user_id=$2
								  AND jr.grant_id=ar.grant_id
							)
						)
					  )
				)
			)
		  )`, alertID, actor, s.retentionDays).Scan(
		&alert.ID, &alert.OwnerID, &alert.OwnerName, &alert.Scope, &journeyID,
		&alert.Threshold, &alert.BatteryPercent, &alert.RecordedAt, &alert.ReceivedAt, &resolvedAt,
		&alert.RecipientCount, &lat, &lon, &acc, &locRecorded,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return Alert{}, ErrNotFound
	}
	if err != nil {
		return Alert{}, fmt.Errorf("read authorized battery alert: %w", err)
	}
	alert.RecordedAt = alert.RecordedAt.UTC()
	alert.ReceivedAt = alert.ReceivedAt.UTC()
	if journeyID.Valid {
		id := uuid.UUID(journeyID.Bytes)
		alert.JourneyID = &id
	}
	if resolvedAt.Valid {
		t := resolvedAt.Time.UTC()
		alert.ResolvedAt = &t
	}
	if lat.Valid {
		alert.Location = &Location{
			Latitude:                 lat.Float64,
			Longitude:                lon.Float64,
			HorizontalAccuracyMeters: acc.Float64,
			RecordedAt:               locRecorded.Time.UTC(),
		}
	}
	return alert, nil
}
