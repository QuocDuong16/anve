package locationaccess

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
)

type historyFake struct {
	confidenceStore
	points []HistoryPoint
}

func (f historyFake) BeginHistory(context.Context, uuid.UUID, uuid.UUID) (int64, error) {
	return 0, nil
}
func (f historyFake) History(_ context.Context, _ HistoryQuery) ([]HistoryPoint, error) {
	return f.points, nil
}

func TestHistoryRejectsUnboundedRangeAndCursorFromAnotherAccount(t *testing.T) {
	now := time.Now().UTC()
	viewer, owner := uuid.New(), uuid.New()
	point := HistoryPoint{EventID: uuid.New(), RecordedAt: now.Add(-time.Minute), ReceivedAt: now}
	service := NewService(historyFake{points: []HistoryPoint{point, point}}, 30*24*time.Hour)
	service.now = func() time.Time { return now }
	_, err := service.History(context.Background(), viewer, owner, now.Add(-72*time.Hour), now, 1, "")
	require.ErrorIs(t, err, ErrHistoryInvalid)
	page, err := service.History(context.Background(), viewer, owner, now.Add(-time.Hour), now, 1, "")
	require.NoError(t, err)
	require.NotNil(t, page.NextCursor)
	_, err = service.History(context.Background(), uuid.New(), owner, now.Add(-time.Hour), now, 1, *page.NextCursor)
	require.ErrorIs(t, err, ErrHistoryInvalid)
}
