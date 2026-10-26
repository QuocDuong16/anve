// Package circles implements the Family Circle domain (M2): circles,
// memberships with owner/member roles, and single-use invite codes.
//
// The authorization model keeps administration and data access strictly
// apart: the owner role governs invites, removal and renaming only. It
// grants no location rights whatsoever — location access always originates
// from the data subject's own consent switch (M1). Joining a circle never
// enables sharing.
package circles

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"math/big"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/QuocDuong16/anve/server/internal/auth"
)

// Domain errors mapped onto HTTP status/error codes by httpapi.
var (
	// ErrForbidden: the caller is a member but lacks the owner role.
	ErrForbidden = errors.New("forbidden")
	// ErrActiveCircleExists: the user already belongs to a circle (MVP).
	ErrActiveCircleExists = errors.New("active circle exists")
	// ErrInviteUnavailable: unknown, expired, used or revoked invite code.
	ErrInviteUnavailable = errors.New("invite unavailable")
	// ErrOwnerMustDissolve: an owner with members must confirm dissolution.
	ErrOwnerMustDissolve = errors.New("circle has members")
)

// Invite lifetime bounds (hours); the default matches the contract.
const (
	InviteDefaultTTL = 48 * time.Hour
	InviteMaxTTL     = 7 * 24 * time.Hour
)

// Circle is a family circle.
type Circle struct {
	ID        uuid.UUID
	Name      string
	CreatedBy uuid.UUID
	CreatedAt time.Time
}

// Membership links a user to a circle with a role.
type Membership struct {
	ID       uuid.UUID
	CircleID uuid.UUID
	UserID   uuid.UUID
	Role     string // owner | member
	JoinedAt time.Time
}

// Member is the circle-scoped projection of another user.
type Member struct {
	UserID      uuid.UUID
	DisplayName string
	Role        string
	JoinedAt    time.Time
}

// Invite is a single-use join code.
type Invite struct {
	ID        uuid.UUID
	CircleID  uuid.UUID
	CreatedBy uuid.UUID
	Code      string
	ExpiresAt time.Time
	CreatedAt time.Time
}

// Store is the persistence contract. Every method takes the calling user
// and enforces scoping itself: "not a member of this circle" is
// auth.ErrNotFound, "member but not owner" is ErrForbidden — callers
// cannot leak another circle's data by construction.
type Store interface {
	CreateCircle(ctx context.Context, userID uuid.UUID, name string) (Circle, Membership, error)
	MyCircle(ctx context.Context, userID uuid.UUID) (Circle, *Membership, error)
	RenameCircle(ctx context.Context, userID, circleID uuid.UUID, name string) (Circle, error)
	ListMembers(ctx context.Context, userID, circleID uuid.UUID) ([]Member, error)
	RemoveMember(ctx context.Context, ownerID, circleID, targetID uuid.UUID) error
	LeaveCircle(ctx context.Context, userID, circleID uuid.UUID, dissolve bool) error
	CreateInvite(ctx context.Context, userID, circleID uuid.UUID, ttl time.Time) (Invite, error)
	ListInvites(ctx context.Context, userID, circleID uuid.UUID) ([]Invite, error)
	RevokeInvite(ctx context.Context, userID, inviteID uuid.UUID) error
	RedeemInvite(ctx context.Context, userID uuid.UUID, code string) (Circle, Membership, error)
}

// Service implements the circle use-cases. Audit events are owned by the
// store: each mutation records its event inside the same transaction, so a
// business change and its audit row commit or roll back together (ADR 0004).
type Service struct {
	store Store
}

// NewService builds the circle use-cases on the given store.
func NewService(store Store) *Service {
	return &Service{store: store}
}

// Create makes a new circle with the caller as owner.
func (s *Service) Create(ctx context.Context, userID uuid.UUID, name string) (Circle, Membership, error) {
	name = strings.TrimSpace(name)
	if name == "" || len([]rune(name)) > 80 {
		return Circle{}, Membership{}, fmt.Errorf("%w: circle name must be 1-80 characters", auth.ErrValidation)
	}
	return s.store.CreateCircle(ctx, userID, name)
}

// MyCircle returns the caller's circle and membership, or nils.
func (s *Service) MyCircle(ctx context.Context, userID uuid.UUID) (Circle, *Membership, error) {
	return s.store.MyCircle(ctx, userID)
}

// Rename changes the circle name; owner only.
func (s *Service) Rename(ctx context.Context, userID, circleID uuid.UUID, name string) (Circle, error) {
	name = strings.TrimSpace(name)
	if name == "" || len([]rune(name)) > 80 {
		return Circle{}, fmt.Errorf("%w: circle name must be 1-80 characters", auth.ErrValidation)
	}
	return s.store.RenameCircle(ctx, userID, circleID, name)
}

// Members lists what members may see of each other.
func (s *Service) Members(ctx context.Context, userID, circleID uuid.UUID) ([]Member, error) {
	return s.store.ListMembers(ctx, userID, circleID)
}

// RemoveMember removes an ordinary member; owner only.
func (s *Service) RemoveMember(ctx context.Context, ownerID, circleID, targetID uuid.UUID) error {
	return s.store.RemoveMember(ctx, ownerID, circleID, targetID)
}

// Leave removes the caller; owners of populated circles must dissolve.
func (s *Service) Leave(ctx context.Context, userID, circleID uuid.UUID, dissolve bool) error {
	if err := s.store.LeaveCircle(ctx, userID, circleID, dissolve); err != nil {
		return err
	}
	return nil
}

// CreateInvite issues a code with the given TTL (default 48h, capped at 7d).
func (s *Service) CreateInvite(ctx context.Context, userID, circleID uuid.UUID, expiresInHours *int) (Invite, error) {
	ttl := InviteDefaultTTL
	if expiresInHours != nil {
		if *expiresInHours < 1 || time.Duration(*expiresInHours)*time.Hour > InviteMaxTTL {
			return Invite{}, fmt.Errorf("%w: expiresInHours must be 1-168", auth.ErrValidation)
		}
		ttl = time.Duration(*expiresInHours) * time.Hour
	}
	return s.store.CreateInvite(ctx, userID, circleID, time.Now().UTC().Add(ttl))
}

// Invites lists the circle's outstanding invites; owner only.
func (s *Service) Invites(ctx context.Context, userID, circleID uuid.UUID) ([]Invite, error) {
	return s.store.ListInvites(ctx, userID, circleID)
}

// RevokeInvite kills an outstanding invite; owner only.
func (s *Service) RevokeInvite(ctx context.Context, userID, inviteID uuid.UUID) error {
	return s.store.RevokeInvite(ctx, userID, inviteID)
}

// Redeem joins a circle with an invite code; the store burns the code and
// records the join in the same transaction that creates the membership.
func (s *Service) Redeem(ctx context.Context, userID uuid.UUID, code string) (Circle, Membership, error) {
	code = strings.TrimSpace(code)
	if len(code) < 4 || len(code) > 32 {
		return Circle{}, Membership{}, fmt.Errorf("%w: code must be 4-32 characters", auth.ErrValidation)
	}
	return s.store.RedeemInvite(ctx, userID, code)
}

// codeAlphabet is Crockford-ish base32 without ambiguous glyphs; codes are
// typed by humans from a message.
const codeAlphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

// newInviteCode returns a random 10-character join code.
func newInviteCode() (string, error) {
	buf := make([]byte, 10)
	alphabetSize := big.NewInt(int64(len(codeAlphabet)))
	for i := range buf {
		n, err := rand.Int(rand.Reader, alphabetSize)
		if err != nil {
			return "", err
		}
		buf[i] = codeAlphabet[n.Int64()]
	}
	return string(buf), nil
}
