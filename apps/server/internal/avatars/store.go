package avatars

import (
	"context"
	"errors"
	"fmt"
	"math"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/QuocDuong16/anve/server/internal/audit"
	"github.com/QuocDuong16/anve/server/internal/auth"
)

// Principal pins the login as well as the account for revocation ordering.
type Principal struct {
	UserID    uuid.UUID
	SessionID uuid.UUID
}

// State retains a revision after removal so stale writes cannot revive a photo.
type State struct {
	Revision    int64      `json:"revision"`
	OperationID *uuid.UUID `json:"operationId,omitempty"`
	ImageID     *uuid.UUID `json:"imageId,omitempty"`
}

// Store serializes account mutations and bounds concurrent image decoding.
type Store struct {
	pool  *pgxpool.Pool
	slots chan struct{}
}

// NewStore shares one processing budget across the endpoint's requests.
func NewStore(pool *pgxpool.Pool) *Store {
	return &Store{pool: pool, slots: make(chan struct{}, 2)}
}

func authorize(ctx context.Context, tx pgx.Tx, p Principal, mutation bool) error {
	lock := "FOR SHARE"
	if mutation {
		lock = "FOR UPDATE"
	}
	var id uuid.UUID
	if err := tx.QueryRow(ctx, "SELECT id FROM users WHERE id=$1 "+lock, p.UserID).Scan(&id); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return auth.ErrUnauthenticated
		}
		return fmt.Errorf("lock avatar owner: %w", err)
	}
	err := tx.QueryRow(ctx, `SELECT s.id FROM sessions s JOIN devices d ON d.id=s.device_id AND d.user_id=s.user_id
 WHERE s.id=$1 AND s.user_id=$2 AND s.revoked_at IS NULL AND s.refresh_expires_at>clock_timestamp()
 FOR SHARE OF s,d`, p.SessionID, p.UserID).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return auth.ErrUnauthenticated
	}
	if err != nil {
		return fmt.Errorf("authorize avatar session: %w", err)
	}
	return nil
}

func readState(ctx context.Context, tx pgx.Tx, owner uuid.UUID) (State, error) {
	var result State
	err := tx.QueryRow(ctx, `SELECT revision,operation_id,image_id FROM profile_avatars WHERE user_id=$1`, owner).
		Scan(&result.Revision, &result.OperationID, &result.ImageID)
	if errors.Is(err, pgx.ErrNoRows) {
		return State{}, nil
	}
	if err != nil {
		return State{}, fmt.Errorf("read avatar state: %w", err)
	}
	return result, nil
}

// State permits read-only reconciliation without replaying an upload/removal.
func (s *Store) State(ctx context.Context, p Principal) (State, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return State{}, fmt.Errorf("begin avatar state: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := authorize(ctx, tx, p, false); err != nil {
		return State{}, err
	}
	state, err := readState(ctx, tx, p.UserID)
	if err != nil {
		return State{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return State{}, fmt.Errorf("commit avatar state: %w", err)
	}
	return state, nil
}

// Replace normalizes pixels and commits only against the reviewed revision.
func (s *Store) Replace(ctx context.Context, p Principal, operation uuid.UUID, expected int64, input []byte) (State, error) {
	if operation == uuid.Nil || expected < 0 || expected == math.MaxInt64 {
		return State{}, ErrInvalid
	}
	select {
	case s.slots <- struct{}{}:
		defer func() { <-s.slots }()
	default:
		return State{}, ErrBusy
	}
	content, err := Normalize(input)
	if err != nil {
		return State{}, err
	}
	return s.mutate(ctx, p, operation, expected, content)
}

// Remove clears bytes while retaining the revision and operation for readback.
func (s *Store) Remove(ctx context.Context, p Principal, operation uuid.UUID, expected int64) (State, error) {
	if operation == uuid.Nil || expected < 0 || expected == math.MaxInt64 {
		return State{}, ErrInvalid
	}
	return s.mutate(ctx, p, operation, expected, nil)
}

func (s *Store) mutate(ctx context.Context, p Principal, operation uuid.UUID, expected int64, content []byte) (State, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return State{}, fmt.Errorf("begin avatar mutation: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := authorize(ctx, tx, p, true); err != nil {
		return State{}, err
	}
	current, err := readState(ctx, tx, p.UserID)
	if err != nil {
		return State{}, err
	}
	if current.Revision != expected || (current.OperationID != nil && *current.OperationID == operation) {
		return State{}, ErrConflict
	}
	result := State{Revision: expected + 1, OperationID: &operation}
	action := audit.ActionAvatarRemoved
	if content != nil {
		result.ImageID = &operation
		action = audit.ActionAvatarChanged
	}
	_, err = tx.Exec(ctx, `INSERT INTO profile_avatars(user_id,revision,operation_id,image_id,content)
 VALUES($1,$2,$3,$4,$5) ON CONFLICT(user_id) DO UPDATE SET revision=EXCLUDED.revision,
 operation_id=EXCLUDED.operation_id,image_id=EXCLUDED.image_id,content=EXCLUDED.content,updated_at=clock_timestamp()`,
		p.UserID, result.Revision, operation, result.ImageID, content)
	if err != nil {
		return State{}, fmt.Errorf("write avatar mutation: %w", err)
	}
	if err := audit.InTx(ctx, tx, audit.Event{ActorUserID: p.UserID, Action: action, Detail: map[string]any{"revision": result.Revision}}); err != nil {
		return State{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return State{}, fmt.Errorf("commit avatar mutation: %w", err)
	}
	return result, nil
}

// Image checks current membership and image identity on every read.
func (s *Store) Image(ctx context.Context, p Principal, subject, imageID uuid.UUID) ([]byte, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, fmt.Errorf("begin avatar image: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := authorize(ctx, tx, p, false); err != nil {
		return nil, err
	}
	var content []byte
	err = tx.QueryRow(ctx, `SELECT a.content FROM profile_avatars a WHERE a.user_id=$1 AND a.image_id=$2
 AND (a.user_id=$3 OR EXISTS(SELECT 1 FROM memberships own JOIN memberships other ON other.circle_id=own.circle_id
 WHERE own.user_id=$3 AND other.user_id=a.user_id))`, subject, imageID, p.UserID).Scan(&content)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("read authorized avatar image: %w", err)
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, fmt.Errorf("commit avatar image: %w", err)
	}
	return content, nil
}
