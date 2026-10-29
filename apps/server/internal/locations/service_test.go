package locations

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type fakeStore struct {
	result    BatchResult
	err       error
	calls     int
	lastInput IngestInput
}

func (*fakeStore) CaptureGeneration(context.Context, uuid.UUID) (int64, error) { return 1, nil }

func (f *fakeStore) Ingest(_ context.Context, in IngestInput) (BatchResult, error) {
	f.calls++
	f.lastInput = in
	return f.result, f.err
}

func (f *fakeStore) PurgeOlderThan(context.Context, time.Time) (int64, error) {
	return 0, nil
}

func (f *fakeStore) DeleteHistory(context.Context, uuid.UUID, *time.Time, time.Time) error {
	return nil
}
func (f *fakeStore) RetentionDays(context.Context, uuid.UUID) (int, error)  { return 30, nil }
func (f *fakeStore) SetRetentionDays(context.Context, uuid.UUID, int) error { return nil }

func TestServiceUsesOneServerTimestampAndReportsCounts(t *testing.T) {
	now := time.Date(2026, 10, 26, 12, 0, 0, 0, time.UTC)
	event := validEvent(now)
	store := &fakeStore{result: BatchResult{
		Accepted:   []uuid.UUID{event.EventID},
		ReceivedAt: now,
	}}
	svc := NewServiceWithClock(store, func() time.Time { return now }, MaxEventAge)

	result, err := svc.Ingest(context.Background(), Principal{UserID: uuid.New()}, []Event{event})
	require.NoError(t, err)
	assert.Len(t, result.Accepted, 1)
	assert.Equal(t, now, store.lastInput.ReceivedAt)
	assert.Equal(t, now, result.ReceivedAt)

	snapshot := svc.Metrics().Snapshot()
	assert.Equal(t, int64(1), snapshot.Batches)
	assert.Equal(t, int64(1), snapshot.EventsAccepted)
}

func TestServiceRejectsInvalidBatchBeforeTheStore(t *testing.T) {
	store := &fakeStore{}
	svc := NewService(store)

	_, err := svc.Ingest(context.Background(), Principal{UserID: uuid.New()}, nil)
	require.ErrorIs(t, err, ErrBatchInvalid)
	assert.Zero(t, store.calls, "invalid input must never reach the database")

	snapshot := svc.Metrics().Snapshot()
	assert.Equal(t, int64(1), snapshot.Batches)
	assert.Equal(t, int64(0), snapshot.EventsAccepted)
}

func TestServiceKeepsPolicyRefusalsAndWrapsStorageErrors(t *testing.T) {
	now := time.Now().UTC()
	event := validEvent(now)

	t.Run("consent refusal stays identifiable", func(t *testing.T) {
		store := &fakeStore{err: ErrConsentRequired}
		svc := NewService(store)
		_, err := svc.Ingest(context.Background(), Principal{UserID: uuid.New()}, []Event{event})
		require.ErrorIs(t, err, ErrConsentRequired)
		assert.True(t, IsPolicyRefusal(err))
	})

	t.Run("storage error becomes retryable", func(t *testing.T) {
		store := &fakeStore{err: errors.New("connection reset")}
		svc := NewService(store)
		_, err := svc.Ingest(context.Background(), Principal{UserID: uuid.New()}, []Event{event})
		require.ErrorIs(t, err, ErrTemporarilyUnavailable)
		assert.False(t, IsPermanentIngestionError(err))
		assert.False(t, IsPolicyRefusal(err))
	})
}

func TestPermanentAndPolicyClassification(t *testing.T) {
	assert.True(t, IsPermanentIngestionError(ErrBatchInvalid))
	assert.True(t, IsPermanentIngestionError(ErrBatchTooLarge))
	assert.True(t, IsPermanentIngestionError(ErrEventTooOld))
	assert.False(t, IsPermanentIngestionError(ErrTemporarilyUnavailable))
	assert.True(t, IsPolicyRefusal(ErrConsentRequired))
	assert.True(t, IsPolicyRefusal(ErrCircleRequired))
	assert.True(t, IsPolicyRefusal(ErrDeviceNotAuthorized))
	assert.False(t, IsPolicyRefusal(ErrBatchInvalid))
}

func TestEffectiveRetentionUsesServerMaximum(t *testing.T) {
	service := NewServiceWithRetention(&fakeStore{}, 7*24*time.Hour)
	require.Equal(t, 7, service.EffectiveRetentionDays(30))
	require.Equal(t, 1, service.EffectiveRetentionDays(1))
}
