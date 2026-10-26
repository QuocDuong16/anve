package circles

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/QuocDuong16/anve/server/internal/audit"
	"github.com/QuocDuong16/anve/server/internal/auth"
)

// PGStore is the PostgreSQL implementation of Store.
type PGStore struct {
	pool *pgxpool.Pool
}

// NewPGStore returns a Store backed by the given pool.
func NewPGStore(pool *pgxpool.Pool) *PGStore { return &PGStore{pool: pool} }

// isUniqueViolation reports a PostgreSQL 23505 on the given constraint.
func isUniqueViolation(err error, constraint string) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) &&
		pgErr.Code == "23505" &&
		(constraint == "" || strings.Contains(pgErr.ConstraintName, constraint))
}

// isDeadlock reports a PostgreSQL deadlock or serialization abort. The
// redeem/leave transactions lock invite and membership rows while a
// concurrent account deletion cascades through the same rows — PostgreSQL
// aborts one side, and a retry then observes the committed state.
func isDeadlock(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && (pgErr.Code == "40P01" || pgErr.Code == "40001")
}

const circleColumns = `id, name, created_by, created_at`

func scanCircle(row pgx.Row) (Circle, error) {
	var c Circle
	err := row.Scan(&c.ID, &c.Name, &c.CreatedBy, &c.CreatedAt)
	return c, err
}

// rowQuerier is satisfied by both *pgxpool.Pool and pgx.Tx, letting the
// membership scoping helper run inside or outside a transaction.
type rowQuerier interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

// callerMembership loads the caller's membership (optionally FOR UPDATE).
// Not a member → auth.ErrNotFound; every operation is scoped before
// anything else is read.
func callerMembership(ctx context.Context, q rowQuerier, userID, circleID uuid.UUID, lock bool) (Membership, error) {
	query := `SELECT id, circle_id, user_id, role, joined_at FROM memberships
	           WHERE user_id = $1 AND circle_id = $2`
	if lock {
		query += ` FOR UPDATE`
	}
	var m Membership
	err := q.QueryRow(ctx, query, userID, circleID).
		Scan(&m.ID, &m.CircleID, &m.UserID, &m.Role, &m.JoinedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return Membership{}, fmt.Errorf("%w: not a member of circle %s", auth.ErrNotFound, circleID)
	}
	if err != nil {
		return Membership{}, fmt.Errorf("lookup membership: %w", err)
	}
	return m, nil
}

// CreateCircle inserts the circle and the owner membership atomically. The
// one_active_circle_per_user index turns a second circle into
// ErrActiveCircleExists.
func (s *PGStore) CreateCircle(ctx context.Context, userID uuid.UUID, name string) (Circle, Membership, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Circle{}, Membership{}, fmt.Errorf("begin create circle: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var circle Circle
	err = tx.QueryRow(ctx,
		`INSERT INTO circles (name, created_by) VALUES ($1, $2)
		 RETURNING `+circleColumns, name, userID).
		Scan(&circle.ID, &circle.Name, &circle.CreatedBy, &circle.CreatedAt)
	if err != nil {
		return Circle{}, Membership{}, fmt.Errorf("insert circle: %w", err)
	}

	var membership Membership
	membership.CircleID = circle.ID
	membership.UserID = userID
	membership.Role = "owner"
	err = tx.QueryRow(ctx,
		`INSERT INTO memberships (circle_id, user_id, role)
		 VALUES ($1, $2, 'owner') RETURNING id, joined_at`,
		circle.ID, userID).Scan(&membership.ID, &membership.JoinedAt)
	if isUniqueViolation(err, "one_active_circle_per_user") {
		return Circle{}, Membership{}, ErrActiveCircleExists
	}
	if err != nil {
		return Circle{}, Membership{}, fmt.Errorf("insert owner membership: %w", err)
	}

	if err := audit.InTx(ctx, tx, audit.Event{
		ActorUserID: userID, Action: audit.ActionCircleCreated,
		Detail: map[string]any{"circle_id": circle.ID.String()},
	}); err != nil {
		return Circle{}, Membership{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return Circle{}, Membership{}, fmt.Errorf("commit create circle: %w", err)
	}
	return circle, membership, nil
}

// MyCircle returns the caller's circle and membership, or nils.
func (s *PGStore) MyCircle(ctx context.Context, userID uuid.UUID) (Circle, *Membership, error) {
	var circle Circle
	var membership Membership
	err := s.pool.QueryRow(ctx,
		`SELECT c.id, c.name, c.created_by, c.created_at,
		        m.id, m.circle_id, m.user_id, m.role, m.joined_at
		   FROM memberships m
		   JOIN circles c ON c.id = m.circle_id
		  WHERE m.user_id = $1`, userID).
		Scan(&circle.ID, &circle.Name, &circle.CreatedBy, &circle.CreatedAt,
			&membership.ID, &membership.CircleID, &membership.UserID, &membership.Role, &membership.JoinedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return Circle{}, nil, nil
	}
	if err != nil {
		return Circle{}, nil, fmt.Errorf("lookup my circle: %w", err)
	}
	return circle, &membership, nil
}

// RenameCircle renames a circle the caller belongs to; owner only.
func (s *PGStore) RenameCircle(ctx context.Context, userID, circleID uuid.UUID, name string) (Circle, error) {
	m, err := callerMembership(ctx, s.pool, userID, circleID, false)
	if err != nil {
		return Circle{}, err
	}
	if m.Role != "owner" {
		return Circle{}, ErrForbidden
	}
	circle, err := scanCircle(s.pool.QueryRow(ctx,
		`UPDATE circles SET name = $2 WHERE id = $1 RETURNING `+circleColumns, circleID, name))
	if err != nil {
		return Circle{}, fmt.Errorf("rename circle: %w", err)
	}
	return circle, nil
}

// ListMembers shows members what members may see of each other: display
// name, role, join time — never emails or devices.
func (s *PGStore) ListMembers(ctx context.Context, userID, circleID uuid.UUID) ([]Member, error) {
	if _, err := callerMembership(ctx, s.pool, userID, circleID, false); err != nil {
		return nil, err
	}
	rows, err := s.pool.Query(ctx,
		`SELECT m.user_id, u.display_name, m.role, m.joined_at
		   FROM memberships m
		   JOIN users u ON u.id = m.user_id
		  WHERE m.circle_id = $1
		  ORDER BY m.joined_at`, circleID)
	if err != nil {
		return nil, fmt.Errorf("list members: %w", err)
	}
	defer rows.Close()

	var out []Member
	for rows.Next() {
		var m Member
		if err := rows.Scan(&m.UserID, &m.DisplayName, &m.Role, &m.JoinedAt); err != nil {
			return nil, fmt.Errorf("scan member: %w", err)
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// RemoveMember lets the owner remove an ordinary member. Removing the
// owner is refused — the owner leaves (or dissolves) via LeaveCircle.
func (s *PGStore) RemoveMember(ctx context.Context, ownerID, circleID, targetID uuid.UUID) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin remove member: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	caller, err := callerMembership(ctx, tx, ownerID, circleID, true)
	if err != nil {
		return err
	}
	if caller.Role != "owner" {
		return ErrForbidden
	}
	if targetID == ownerID {
		return fmt.Errorf("%w: owners leave via the leave endpoint", auth.ErrValidation)
	}

	ct, err := tx.Exec(ctx,
		`DELETE FROM memberships WHERE circle_id = $1 AND user_id = $2 AND role = 'member'`,
		circleID, targetID)
	if err != nil {
		return fmt.Errorf("remove member: %w", err)
	}
	if ct.RowsAffected() == 0 {
		return fmt.Errorf("%w: member %s", auth.ErrNotFound, targetID)
	}
	if err := audit.InTx(ctx, tx, audit.Event{
		ActorUserID: ownerID, Action: audit.ActionMemberRemoved, TargetUserID: &targetID,
		Detail: map[string]any{"circle_id": circleID.String()},
	}); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// LeaveCircle removes the caller's membership. An owner with remaining
// members must pass dissolve; dissolving deletes the circle and cascades
// every membership and invite.
func (s *PGStore) LeaveCircle(ctx context.Context, userID, circleID uuid.UUID, dissolve bool) error {
	var err error
	for attempt := 0; attempt < 3; attempt++ {
		err = s.leaveCircle(ctx, userID, circleID, dissolve)
		if !isDeadlock(err) {
			return err
		}
	}
	return err
}

func (s *PGStore) leaveCircle(ctx context.Context, userID, circleID uuid.UUID, dissolve bool) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin leave: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	m, err := callerMembership(ctx, tx, userID, circleID, true)
	if err != nil {
		return err
	}

	if m.Role == "owner" {
		var others int
		if err := tx.QueryRow(ctx,
			`SELECT count(*) FROM memberships WHERE circle_id = $1 AND user_id != $2`,
			circleID, userID).Scan(&others); err != nil {
			return fmt.Errorf("count members: %w", err)
		}
		if others > 0 && !dissolve {
			return ErrOwnerMustDissolve
		}
		// Deleting the circle cascades memberships and invites. Dissolving
		// is the owner's explicit choice; a sole owner leaving dissolves
		// implicitly (nothing else remains).
		if _, err := tx.Exec(ctx, `DELETE FROM circles WHERE id = $1`, circleID); err != nil {
			return fmt.Errorf("dissolve circle: %w", err)
		}
		if err := audit.InTx(ctx, tx, audit.Event{
			ActorUserID: userID, Action: audit.ActionCircleDissolved,
			Detail: map[string]any{"circle_id": circleID.String(), "had_members": others > 0},
		}); err != nil {
			return err
		}
		return tx.Commit(ctx)
	}

	if _, err := tx.Exec(ctx,
		`DELETE FROM memberships WHERE id = $1`, m.ID); err != nil {
		return fmt.Errorf("leave circle: %w", err)
	}
	if err := audit.InTx(ctx, tx, audit.Event{
		ActorUserID: userID, Action: audit.ActionCircleLeft,
		Detail: map[string]any{"circle_id": circleID.String()},
	}); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// CreateInvite issues a single-use code; owner only. A generated code that
// collides is retried once with a fresh random value.
func (s *PGStore) CreateInvite(ctx context.Context, userID, circleID uuid.UUID, expiresAt time.Time) (Invite, error) {
	m, err := callerMembership(ctx, s.pool, userID, circleID, false)
	if err != nil {
		return Invite{}, err
	}
	if m.Role != "owner" {
		return Invite{}, ErrForbidden
	}

	for attempt := 0; attempt < 2; attempt++ {
		code, err := newInviteCode()
		if err != nil {
			return Invite{}, fmt.Errorf("generate invite code: %w", err)
		}
		var invite Invite
		invite.CircleID = circleID
		invite.CreatedBy = userID
		invite.Code = code
		err = s.pool.QueryRow(ctx,
			`INSERT INTO invites (circle_id, code, created_by, expires_at)
			 VALUES ($1, $2, $3, $4)
			 RETURNING id, created_at`,
			circleID, code, userID, expiresAt).Scan(&invite.ID, &invite.CreatedAt)
		if isUniqueViolation(err, "invites_code_key") {
			continue
		}
		if err != nil {
			return Invite{}, fmt.Errorf("insert invite: %w", err)
		}
		invite.ExpiresAt = expiresAt
		return invite, nil
	}
	return Invite{}, fmt.Errorf("invite code collision twice; retry")
}

// ListInvites returns the circle's still-redeemable invites; owner only.
func (s *PGStore) ListInvites(ctx context.Context, userID, circleID uuid.UUID) ([]Invite, error) {
	m, err := callerMembership(ctx, s.pool, userID, circleID, false)
	if err != nil {
		return nil, err
	}
	if m.Role != "owner" {
		return nil, ErrForbidden
	}
	rows, err := s.pool.Query(ctx,
		`SELECT id, circle_id, created_by, code, expires_at, created_at
		   FROM invites
		  WHERE circle_id = $1 AND revoked_at IS NULL AND used_at IS NULL
		    AND expires_at > now()
		  ORDER BY created_at DESC`, circleID)
	if err != nil {
		return nil, fmt.Errorf("list invites: %w", err)
	}
	defer rows.Close()

	var out []Invite
	for rows.Next() {
		var i Invite
		if err := rows.Scan(&i.ID, &i.CircleID, &i.CreatedBy, &i.Code, &i.ExpiresAt, &i.CreatedAt); err != nil {
			return nil, fmt.Errorf("scan invite: %w", err)
		}
		out = append(out, i)
	}
	return out, rows.Err()
}

// RevokeInvite revokes an outstanding invite; owner of its circle only.
func (s *PGStore) RevokeInvite(ctx context.Context, userID, inviteID uuid.UUID) error {
	ct, err := s.pool.Exec(ctx,
		`UPDATE invites SET revoked_at = now()
		  WHERE id = $1 AND revoked_at IS NULL AND used_at IS NULL
		    AND circle_id IN (SELECT circle_id FROM memberships
		                       WHERE user_id = $2 AND role = 'owner')`,
		inviteID, userID)
	if err != nil {
		return fmt.Errorf("revoke invite: %w", err)
	}
	if ct.RowsAffected() == 0 {
		// Either the invite does not exist, is already dead, or belongs to
		// a circle the caller does not own — indistinguishable by design.
		return fmt.Errorf("%w: invite %s", auth.ErrNotFound, inviteID)
	}
	return nil
}

// RedeemInvite joins the caller's circle with a code: validates the code
// state, enforces one-circle-per-user, creates the membership and burns the
// code in one transaction. Concurrent redeems serialize on the invite
// row (FOR UPDATE); the loser sees used_at set and fails cleanly.
func (s *PGStore) RedeemInvite(ctx context.Context, userID uuid.UUID, code string) (Circle, Membership, error) {
	var circle Circle
	var membership Membership
	var err error
	for attempt := 0; attempt < 3; attempt++ {
		circle, membership, err = s.redeemInvite(ctx, userID, code)
		if !isDeadlock(err) {
			return circle, membership, err
		}
	}
	return Circle{}, Membership{}, err
}

func (s *PGStore) redeemInvite(ctx context.Context, userID uuid.UUID, code string) (Circle, Membership, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Circle{}, Membership{}, fmt.Errorf("begin redeem: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var inviteID, circleID uuid.UUID
	err = tx.QueryRow(ctx,
		`SELECT id, circle_id FROM invites
		  WHERE code = $1 AND revoked_at IS NULL AND used_at IS NULL AND expires_at > now()
		  FOR UPDATE`, code).Scan(&inviteID, &circleID)
	if errors.Is(err, pgx.ErrNoRows) {
		return Circle{}, Membership{}, ErrInviteUnavailable
	}
	if err != nil {
		return Circle{}, Membership{}, fmt.Errorf("lookup invite: %w", err)
	}

	// One active circle per user (MVP): the unique index is the backstop,
	// but checking first yields the precise error instead of a 23505.
	var inCircle bool
	if err := tx.QueryRow(ctx,
		`SELECT EXISTS (SELECT 1 FROM memberships WHERE user_id = $1)`, userID).Scan(&inCircle); err != nil {
		return Circle{}, Membership{}, fmt.Errorf("check membership: %w", err)
	}
	if inCircle {
		return Circle{}, Membership{}, ErrActiveCircleExists
	}

	var membership Membership
	membership.CircleID = circleID
	membership.UserID = userID
	membership.Role = "member"
	err = tx.QueryRow(ctx,
		`INSERT INTO memberships (circle_id, user_id, role)
		 VALUES ($1, $2, 'member') RETURNING id, joined_at`,
		circleID, userID).Scan(&membership.ID, &membership.JoinedAt)
	if isUniqueViolation(err, "one_active_circle_per_user") {
		return Circle{}, Membership{}, ErrActiveCircleExists
	}
	if err != nil {
		return Circle{}, Membership{}, fmt.Errorf("insert membership: %w", err)
	}

	if _, err := tx.Exec(ctx,
		`UPDATE invites SET used_at = now(), used_by = $2 WHERE id = $1`,
		inviteID, userID); err != nil {
		return Circle{}, Membership{}, fmt.Errorf("burn invite: %w", err)
	}

	circle, err := scanCircle(tx.QueryRow(ctx,
		`SELECT `+circleColumns+` FROM circles WHERE id = $1`, circleID))
	if err != nil {
		return Circle{}, Membership{}, fmt.Errorf("read circle: %w", err)
	}

	if err := audit.InTx(ctx, tx, audit.Event{
		ActorUserID: userID, Action: audit.ActionCircleJoined,
		Detail: map[string]any{"circle_id": circleID.String(), "invite_id": inviteID.String()},
	}); err != nil {
		return Circle{}, Membership{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return Circle{}, Membership{}, fmt.Errorf("commit redeem: %w", err)
	}
	return circle, membership, nil
}
