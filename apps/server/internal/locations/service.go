package locations

import (
	"context"
	"errors"
	"fmt"
	"math"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
)

// Domain errors. httpapi maps each to a stable HTTP status and error code;
// clients switch on the code, never on the message text.
var (
	// ErrBatchInvalid covers an empty/oversized batch, a malformed event, an
	// out-of-range coordinate or a timestamp in the future.
	ErrBatchInvalid = errors.New("location batch invalid")
	// ErrBatchTooLarge is a batch above MaxBatchEvents.
	ErrBatchTooLarge = errors.New("location batch too large")
	// ErrEventTooOld: at least one capture time is beyond the retention
	// window. Accepting it would store data the product promises to purge.
	ErrEventTooOld = errors.New("location event too old")
	// ErrConsentRequired: server-confirmed location sharing is off.
	ErrConsentRequired = errors.New("location consent required")
	// ErrDeviceNotAuthorized: the session, its device or the claimed client
	// device id does not belong to the authenticated account.
	ErrDeviceNotAuthorized = errors.New("device not authorized")
	// ErrCircleRequired: the account has no active Circle membership, so
	// there is no consented audience for the data.
	ErrCircleRequired = errors.New("location circle required")
	// ErrTemporarilyUnavailable is a storage/transaction failure. The batch
	// was not committed; the client retries the same event ids.
	ErrTemporarilyUnavailable = errors.New("location temporarily unavailable")
)

const (
	// MaxBatchEvents bounds one request. The Android client uploads at most
	// 100 points per batch; 200 leaves retry headroom without turning one
	// request into an unbounded transaction.
	MaxBatchEvents = 200
	// MaxBodyBytes bounds the JSON body (512 KiB). 200 events are roughly
	// 60 KiB, so this rejects abusive bodies long before parsing allocates.
	MaxBodyBytes int64 = 512 << 10
	// MaxEventAge is the accepted backfill window (the default retention).
	// Older points are refused instead of briefly stored and purged.
	MaxEventAge = 30 * 24 * time.Hour
	// MaxFutureSkew tolerates a device clock that is slightly ahead. A
	// larger skew would let a client manufacture future history.
	MaxFutureSkew = 2 * time.Minute
	// MaxAccuracyMeters is a sanity bound, not a quality threshold: the
	// engine applies its own accuracy policy, but absurd values are refused.
	MaxAccuracyMeters = 100_000
	// MaxSpeedMetersPerSecond is a sanity bound for ground transport.
	MaxSpeedMetersPerSecond = 1_000
)

// Store is the persistence contract. Ingest must perform the authorization
// checks and the writes in one transaction; it is safe for concurrent use.
type Store interface {
	Ingest(ctx context.Context, in IngestInput) (BatchResult, error)
	// PurgeOlderThan deletes points captured before cutoff and returns how
	// many rows were removed.
	PurgeOlderThan(ctx context.Context, cutoff time.Time) (int64, error)
}

// Metrics are in-process counters. They are logged with each batch and are
// intentionally aggregate-only: no per-point labels, coordinates or device
// identifiers. A production metrics exporter can read Snapshot later.
type Metrics struct {
	batches         atomic.Int64
	eventsAccepted  atomic.Int64
	eventsDuplicate atomic.Int64
	eventsRejected  atomic.Int64
}

// MetricsSnapshot is a stable copy for logging/export.
type MetricsSnapshot struct {
	Batches         int64
	EventsAccepted  int64
	EventsDuplicate int64
	EventsRejected  int64
}

// Snapshot returns the current counters.
func (m *Metrics) Snapshot() MetricsSnapshot {
	return MetricsSnapshot{
		Batches:         m.batches.Load(),
		EventsAccepted:  m.eventsAccepted.Load(),
		EventsDuplicate: m.eventsDuplicate.Load(),
		EventsRejected:  m.eventsRejected.Load(),
	}
}

// Service implements the ingestion use-case: validation, one server
// timestamp for the whole batch, the store transaction and metrics.
type Service struct {
	store       Store
	now         func() time.Time
	maxEventAge time.Duration
	metrics     Metrics
}

// NewService builds the service with the default backfill window.
func NewService(store Store) *Service {
	return &Service{store: store, now: time.Now, maxEventAge: MaxEventAge}
}

// NewServiceWithClock is the test seam for deterministic timestamp rules.
func NewServiceWithClock(store Store, now func() time.Time, maxEventAge time.Duration) *Service {
	return &Service{store: store, now: now, maxEventAge: maxEventAge}
}

// Metrics exposes the aggregate counters for operational logging.
func (s *Service) Metrics() *Metrics { return &s.metrics }

// Ingest validates the whole batch, then writes it atomically. A single
// invalid event rejects the batch: partial acceptance would make client
// bookkeeping ambiguous without adding safety.
func (s *Service) Ingest(ctx context.Context, principal Principal, events []Event) (BatchResult, error) {
	receivedAt := s.now().UTC()
	if err := validateBatch(events, receivedAt, s.maxEventAge); err != nil {
		s.metrics.batches.Add(1)
		s.metrics.eventsRejected.Add(int64(len(events)))
		return BatchResult{}, err
	}

	result, err := s.store.Ingest(ctx, IngestInput{
		Principal:  principal,
		Events:     events,
		ReceivedAt: receivedAt,
	})
	if err != nil {
		s.metrics.batches.Add(1)
		if IsPermanentIngestionError(err) || IsPolicyRefusal(err) {
			return BatchResult{}, err
		}
		// Storage failure: nothing was committed, so the same event ids can
		// be retried safely (idempotency is per owner event).
		return BatchResult{}, fmt.Errorf("%w: %w", ErrTemporarilyUnavailable, err)
	}
	s.metrics.batches.Add(1)
	s.metrics.eventsAccepted.Add(int64(len(result.Accepted)))
	s.metrics.eventsDuplicate.Add(int64(len(result.Duplicates)))
	return result, nil
}

// validateBatch enforces the contract's field rules. It never normalizes
// values: a device id with padding or an upper-case provider is rejected
// rather than silently rewritten, so the client cannot disagree with what
// was stored.
func validateBatch(events []Event, receivedAt time.Time, maxEventAge time.Duration) error {
	if len(events) == 0 {
		return fmt.Errorf("%w: the batch contains no events", ErrBatchInvalid)
	}
	if len(events) > MaxBatchEvents {
		return fmt.Errorf("%w: %d events, at most %d are allowed", ErrBatchTooLarge, len(events), MaxBatchEvents)
	}

	seen := make(map[uuid.UUID]struct{}, len(events))
	for i := range events {
		e := &events[i]
		index := i + 1
		if e.EventID == uuid.Nil {
			return fmt.Errorf("%w: event %d has no eventId", ErrBatchInvalid, index)
		}
		if _, duplicate := seen[e.EventID]; duplicate {
			return fmt.Errorf("%w: event %d repeats eventId %s inside the batch",
				ErrBatchInvalid, index, e.EventID)
		}
		seen[e.EventID] = struct{}{}

		if len(e.ClientDeviceID) < 8 || len(e.ClientDeviceID) > 64 {
			return fmt.Errorf("%w: event %d has an invalid clientDeviceId", ErrBatchInvalid, index)
		}
		if err := validateProvider(e.Provider); err != nil {
			return fmt.Errorf("%w: event %d: %w", ErrBatchInvalid, index, err)
		}
		if e.SchemaVersion < 1 || e.SchemaVersion > 16 {
			return fmt.Errorf("%w: event %d has an unsupported schemaVersion", ErrBatchInvalid, index)
		}

		if e.RecordedAt.IsZero() {
			return fmt.Errorf("%w: event %d has no recordedAt", ErrBatchInvalid, index)
		}
		recorded := e.RecordedAt.UTC()
		if recorded.After(receivedAt.Add(MaxFutureSkew)) {
			return fmt.Errorf("%w: event %d is captured in the future", ErrBatchInvalid, index)
		}
		if recorded.Before(receivedAt.Add(-maxEventAge)) {
			return fmt.Errorf("%w: event %d is older than the retention window", ErrEventTooOld, index)
		}

		if !finite(e.Latitude) || e.Latitude < -90 || e.Latitude > 90 {
			return fmt.Errorf("%w: event %d has an invalid latitude", ErrBatchInvalid, index)
		}
		if !finite(e.Longitude) || e.Longitude < -180 || e.Longitude > 180 {
			return fmt.Errorf("%w: event %d has an invalid longitude", ErrBatchInvalid, index)
		}
		if !finite(e.HorizontalAccuracyMeters) ||
			e.HorizontalAccuracyMeters < 0 || e.HorizontalAccuracyMeters > MaxAccuracyMeters {
			return fmt.Errorf("%w: event %d has an invalid accuracy", ErrBatchInvalid, index)
		}
		if e.AltitudeMeters != nil && (!finite(*e.AltitudeMeters) || math.Abs(*e.AltitudeMeters) > 1e6) {
			return fmt.Errorf("%w: event %d has an invalid altitude", ErrBatchInvalid, index)
		}
		if e.SpeedMetersPerSecond != nil &&
			(!finite(*e.SpeedMetersPerSecond) ||
				*e.SpeedMetersPerSecond < 0 || *e.SpeedMetersPerSecond > MaxSpeedMetersPerSecond) {
			return fmt.Errorf("%w: event %d has an invalid speed", ErrBatchInvalid, index)
		}
		if e.BearingDegrees != nil &&
			(!finite(*e.BearingDegrees) || *e.BearingDegrees < 0 || *e.BearingDegrees > 360) {
			return fmt.Errorf("%w: event %d has an invalid bearing", ErrBatchInvalid, index)
		}
	}
	return nil
}

func validateProvider(provider string) error {
	if provider == "" || len(provider) > 32 {
		return errors.New("provider must be 1-32 characters")
	}
	for _, r := range provider {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '_' || r == '-' {
			continue
		}
		return errors.New("provider must be lower-case letters, digits, '_' or '-'")
	}
	return nil
}

func finite(v float64) bool {
	return !math.IsNaN(v) && !math.IsInf(v, 0)
}

// IsPermanentIngestionError reports whether retrying the same batch can
// never succeed. Clients drop those points instead of retrying forever.
func IsPermanentIngestionError(err error) bool {
	return errors.Is(err, ErrBatchInvalid) ||
		errors.Is(err, ErrBatchTooLarge) ||
		errors.Is(err, ErrEventTooOld)
}

// IsPolicyRefusal reports whether the server refused the batch because the
// account's consent/device/circle state no longer permits sharing. The
// client must stop tracking and re-check its local state.
func IsPolicyRefusal(err error) bool {
	return errors.Is(err, ErrConsentRequired) ||
		errors.Is(err, ErrDeviceNotAuthorized) ||
		errors.Is(err, ErrCircleRequired)
}
