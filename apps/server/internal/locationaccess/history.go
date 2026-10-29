package locationaccess

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"time"

	"github.com/google/uuid"
)

// HistoryPoint is a capture, with its separate server receipt time.
type HistoryPoint struct {
	EventID    uuid.UUID `json:"eventId"`
	Latitude   float64   `json:"latitude"`
	Longitude  float64   `json:"longitude"`
	AccuracyM  float64   `json:"horizontalAccuracyMeters"`
	RecordedAt time.Time `json:"recordedAt"`
	ReceivedAt time.Time `json:"receivedAt"`
	Provider   string    `json:"provider"`
	IsMock     bool      `json:"isMock"`
	Backfill   bool      `json:"backfill"`
	SegmentID  string    `json:"segmentId"`
}

// HistoryPage is one bounded page of authorized points.
type HistoryPage struct {
	Points     []HistoryPoint `json:"points"`
	NextCursor *string        `json:"nextCursor,omitempty"`
}

// HistoryQuery carries a validated keyset and authorization context.
type HistoryQuery struct {
	Snapshot                     int64
	Viewer, Owner                uuid.UUID
	From, To, Cutoff             time.Time
	AfterRecorded, AfterReceived time.Time
	AfterID                      uuid.UUID
	Limit                        int
}

type historyCursor struct {
	Snapshot int64     `json:"s"`
	Viewer   uuid.UUID `json:"v"`
	Owner    uuid.UUID `json:"o"`
	From     time.Time `json:"f"`
	To       time.Time `json:"t"`
	Recorded time.Time `json:"r"`
	Received time.Time `json:"i"`
	Event    uuid.UUID `json:"e"`
}

// ErrHistoryInvalid indicates a malformed range, limit, or cursor.
var ErrHistoryInvalid = errors.New("invalid history query")

// History enforces bounds and binds a keyset cursor to the original query.
func (s *Service) History(ctx context.Context, viewer, owner uuid.UUID, from, to time.Time, limit int, cursor string) (HistoryPage, error) {
	now := s.now().UTC()
	from, to = from.UTC(), to.UTC()
	if viewer == uuid.Nil || owner == uuid.Nil || from.IsZero() || to.IsZero() || !from.Before(to) || to.Sub(from) > 48*time.Hour || from.After(now) || to.After(now.Add(48*time.Hour)) || limit < 1 || limit > 200 || len(cursor) > 2048 {
		return HistoryPage{}, ErrHistoryInvalid
	}
	q := HistoryQuery{Viewer: viewer, Owner: owner, From: from, To: to, Cutoff: now.Add(-s.retention), Limit: limit + 1}
	if cursor == "" {
		snapshot, err := s.store.BeginHistory(ctx, viewer, owner)
		if err != nil {
			return HistoryPage{}, err
		}
		q.Snapshot = snapshot
	}
	if cursor != "" {
		raw, err := base64.RawURLEncoding.DecodeString(cursor)
		if err != nil {
			return HistoryPage{}, ErrHistoryInvalid
		}
		var c historyCursor
		if json.Unmarshal(raw, &c) != nil || c.Viewer != viewer || c.Owner != owner || !c.From.Equal(from) || !c.To.Equal(to) || c.Recorded.Before(from) || !c.Recorded.Before(to) || c.Event == uuid.Nil || c.Received.IsZero() || c.Snapshot < 0 {
			return HistoryPage{}, ErrHistoryInvalid
		}
		q.AfterRecorded, q.AfterReceived, q.AfterID = c.Recorded, c.Received, c.Event
		q.Snapshot = c.Snapshot
	}
	points, err := s.store.History(ctx, q)
	if err != nil {
		return HistoryPage{}, err
	}
	if points == nil {
		points = []HistoryPoint{}
	}
	page := HistoryPage{Points: points}
	if len(points) > limit {
		page.Points = points[:limit]
		last := page.Points[len(page.Points)-1]
		raw, err := json.Marshal(historyCursor{Snapshot: q.Snapshot, Viewer: viewer, Owner: owner, From: from, To: to, Recorded: last.RecordedAt, Received: last.ReceivedAt, Event: last.EventID})
		if err != nil {
			return HistoryPage{}, err
		}
		value := base64.RawURLEncoding.EncodeToString(raw)
		page.NextCursor = &value
	}
	return page, nil
}
