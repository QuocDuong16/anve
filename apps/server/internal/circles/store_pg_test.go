package circles_test

import (
	"context"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/audit"
	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/circles"
	"github.com/QuocDuong16/anve/server/internal/testdb"
)

// Integration tests for the circles store against real PostgreSQL: the
// authorization matrix (owner vs member vs outsider), the invite lifecycle
// and the one-circle-per-user invariant. Skipped without
// TEST_DATABASE_URL; CI runs them in the database-integration job.

// newStack wires the circles and auth services on a fresh scratch database
// per test (fully migrated, dropped afterwards) — safe under `go test`'s
// concurrent package execution, unlike shared tables plus TRUNCATE.
func newStack(t *testing.T) (*circles.Service, *auth.Service, *pgxpool.Pool) {
	t.Helper()
	pool := testdb.Scratch(t)
	authSvc := auth.NewService(auth.NewPGStore(pool), nil, true, audit.NewPG(pool))
	circleSvc := circles.NewService(circles.NewPGStore(pool), audit.NewPG(pool))
	return circleSvc, authSvc, pool
}

// signIn creates a user through the development login and returns the id.
func signIn(t *testing.T, authSvc *auth.Service, email string) uuid.UUID {
	t.Helper()
	user, _, err := authSvc.DevSignIn(context.Background(), email, "", auth.DeviceRegistration{
		ClientDeviceID: "device-" + email, Platform: "android",
	})
	require.NoError(t, err)
	return user.ID
}

// setupOwnerMemberInvite builds the canonical fixture: owner + one member
// in a circle, plus the (already burned) invite that admitted the member.
func setupOwnerMemberInvite(t *testing.T) (owner, member uuid.UUID, circle circles.Circle, usedInvite circles.Invite, svc *circles.Service, authSvc *auth.Service) {
	t.Helper()
	svc, authSvc, _ = newStack(t)
	owner = signIn(t, authSvc, "owner@example.com")
	member = signIn(t, authSvc, "member@example.com")

	circle, _, err := svc.Create(context.Background(), owner, "Gia đình")
	require.NoError(t, err)
	invite, err := svc.CreateInvite(context.Background(), owner, circle.ID, nil)
	require.NoError(t, err)
	_, _, err = svc.Redeem(context.Background(), member, invite.Code)
	require.NoError(t, err)
	return owner, member, circle, invite, svc, authSvc
}

func TestCreateCircleMakesOwnerAndAudits(t *testing.T) {
	svc, authSvc, pool := newStack(t)
	owner := signIn(t, authSvc, "a@x.co")

	circle, membership, err := svc.Create(context.Background(), owner, "Nhà mình")
	require.NoError(t, err)
	assert.Equal(t, "owner", membership.Role)
	assert.False(t, circle.ID == uuid.Nil)

	_, _, err = svc.Create(context.Background(), owner, "Nhà khác")
	require.ErrorIs(t, err, circles.ErrActiveCircleExists, "MVP: one circle per user")

	var count int
	require.NoError(t, pool.QueryRow(context.Background(),
		`SELECT count(*) FROM audit_log WHERE action = 'circle.created'`).Scan(&count))
	assert.Equal(t, 1, count)
}

func TestMyCircleEmptyState(t *testing.T) {
	svc, authSvc, _ := newStack(t)
	user := signIn(t, authSvc, "a@x.co")

	circle, membership, err := svc.MyCircle(context.Background(), user)
	require.NoError(t, err)
	assert.Nil(t, membership, "no circle is an empty state, not an error")
	assert.Equal(t, uuid.Nil, circle.ID)
}

func TestAuthorizationMatrix(t *testing.T) {
	owner, member, circle, _, svc, authSvc := setupOwnerMemberInvite(t)
	outsider := signIn(t, authSvc, "outsider@x.co")
	ctx := context.Background()

	t.Run("outsider sees nothing", func(t *testing.T) {
		_, err := svc.CreateInvite(ctx, outsider, circle.ID, nil)
		require.ErrorIs(t, err, auth.ErrNotFound)
		_, err = svc.Rename(ctx, outsider, circle.ID, "X")
		require.ErrorIs(t, err, auth.ErrNotFound)
		_, err = svc.Members(ctx, outsider, circle.ID)
		require.ErrorIs(t, err, auth.ErrNotFound)
		err = svc.Leave(ctx, outsider, circle.ID, true)
		require.ErrorIs(t, err, auth.ErrNotFound)
	})

	t.Run("member reads but cannot administrate", func(t *testing.T) {
		members, err := svc.Members(ctx, member, circle.ID)
		require.NoError(t, err)
		require.Len(t, members, 2)
		for _, m := range members {
			assert.NotEmpty(t, m.DisplayName, "members see display names")
			assert.Contains(t, []string{"owner", "member"}, m.Role)
		}

		_, err = svc.CreateInvite(ctx, member, circle.ID, nil)
		require.ErrorIs(t, err, circles.ErrForbidden)
		_, err = svc.Rename(ctx, member, circle.ID, "X")
		require.ErrorIs(t, err, circles.ErrForbidden)
		_, err = svc.Invites(ctx, member, circle.ID)
		require.ErrorIs(t, err, circles.ErrForbidden)
		err = svc.RemoveMember(ctx, member, circle.ID, owner)
		require.ErrorIs(t, err, circles.ErrForbidden)
	})

	t.Run("owner renames", func(t *testing.T) {
		renamed, err := svc.Rename(ctx, owner, circle.ID, "Nhà mình 2")
		require.NoError(t, err)
		assert.Equal(t, "Nhà mình 2", renamed.Name)
	})
}

func TestRedeemSingleUseAndExpiry(t *testing.T) {
	_, _, _, usedInvite, svc, authSvc := setupOwnerMemberInvite(t)
	latecomer := signIn(t, authSvc, "late@x.co")
	ctx := context.Background()

	_, _, err := svc.Redeem(ctx, latecomer, usedInvite.Code)
	require.ErrorIs(t, err, circles.ErrInviteUnavailable, "burned codes cannot be redeemed")
}

func TestExpiredAndRevokedInvitesAreUnavailable(t *testing.T) {
	svc, authSvc, pool := newStack(t)
	owner := signIn(t, authSvc, "owner@x.co")
	circle, _, err := svc.Create(context.Background(), owner, "Nhà")
	require.NoError(t, err)
	ctx := context.Background()

	expired, err := svc.CreateInvite(ctx, owner, circle.ID, nil)
	require.NoError(t, err)
	_, err = pool.Exec(ctx,
		`UPDATE invites SET expires_at = now() - interval '1 hour' WHERE id = $1`, expired.ID)
	require.NoError(t, err)

	revoked, err := svc.CreateInvite(ctx, owner, circle.ID, nil)
	require.NoError(t, err)
	require.NoError(t, svc.RevokeInvite(ctx, owner, revoked.ID))

	guesser := signIn(t, authSvc, "guesser@x.co")
	for _, code := range []string{expired.Code, revoked.Code, "WRONGCODE12"} {
		_, _, err := svc.Redeem(ctx, guesser, code)
		require.ErrorIs(t, err, circles.ErrInviteUnavailable,
			"expired, revoked and unknown codes are indistinguishable")
	}
}

func TestConcurrentRedeemSingleWinner(t *testing.T) {
	svc, authSvc, _ := newStack(t)
	owner := signIn(t, authSvc, "owner@x.co")
	circle, _, err := svc.Create(context.Background(), owner, "Nhà")
	require.NoError(t, err)
	invite, err := svc.CreateInvite(context.Background(), owner, circle.ID, nil)
	require.NoError(t, err)

	joiners := []uuid.UUID{
		signIn(t, authSvc, "ra@x.co"), signIn(t, authSvc, "rb@x.co"), signIn(t, authSvc, "rc@x.co"),
	}

	var wg sync.WaitGroup
	var winners atomic.Int32
	for _, user := range joiners {
		wg.Add(1)
		go func(u uuid.UUID) {
			defer wg.Done()
			if _, _, err := svc.Redeem(context.Background(), u, invite.Code); err == nil {
				winners.Add(1)
			}
		}(user)
	}
	wg.Wait()
	assert.Equal(t, int32(1), winners.Load(), "a single-use code admits exactly one joiner")
}

func TestRemoveMemberEndsAccess(t *testing.T) {
	owner, member, circle, _, svc, _ := setupOwnerMemberInvite(t)
	ctx := context.Background()

	// The owner cannot remove themselves.
	err := svc.RemoveMember(ctx, owner, circle.ID, owner)
	require.ErrorIs(t, err, auth.ErrValidation)

	require.NoError(t, svc.RemoveMember(ctx, owner, circle.ID, member))

	_, membership, err := svc.MyCircle(ctx, member)
	require.NoError(t, err)
	assert.Nil(t, membership, "a removed member has no circle")

	_, err = svc.Members(ctx, member, circle.ID)
	require.ErrorIs(t, err, auth.ErrNotFound, "removed members cannot read the circle")

	// The one-circle slot is free again.
	_, _, err = svc.Create(ctx, member, "Nhà mới")
	require.NoError(t, err)
}

func TestLeaveAndDissolve(t *testing.T) {
	t.Run("member leaves and owner keeps the circle", func(t *testing.T) {
		owner, member, circle, _, svc, _ := setupOwnerMemberInvite(t)
		require.NoError(t, svc.Leave(context.Background(), member, circle.ID, false))

		_, m, err := svc.MyCircle(context.Background(), member)
		require.NoError(t, err)
		assert.Nil(t, m)

		_, om, err := svc.MyCircle(context.Background(), owner)
		require.NoError(t, err)
		assert.NotNil(t, om)
	})

	t.Run("owner with members must dissolve explicitly", func(t *testing.T) {
		owner, _, circle, _, svc, _ := setupOwnerMemberInvite(t)
		err := svc.Leave(context.Background(), owner, circle.ID, false)
		require.ErrorIs(t, err, circles.ErrOwnerMustDissolve)

		require.NoError(t, svc.Leave(context.Background(), owner, circle.ID, true))
	})

	t.Run("dissolve cascades to everyone", func(t *testing.T) {
		owner, member, circle, _, svc, _ := setupOwnerMemberInvite(t)
		require.NoError(t, svc.Leave(context.Background(), owner, circle.ID, true))

		for _, user := range []uuid.UUID{owner, member} {
			_, m, err := svc.MyCircle(context.Background(), user)
			require.NoError(t, err)
			assert.Nil(t, m)
		}
		_, err := svc.Members(context.Background(), owner, circle.ID)
		require.ErrorIs(t, err, auth.ErrNotFound, "the dissolved circle is unreachable")
	})
}

func TestInviteTTLBounds(t *testing.T) {
	svc, authSvc, _ := newStack(t)
	owner := signIn(t, authSvc, "owner-ttl@x.co")
	circle, _, err := svc.Create(context.Background(), owner, "Nhà")
	require.NoError(t, err)

	tooBig := 169
	_, err = svc.CreateInvite(context.Background(), owner, circle.ID, &tooBig)
	require.ErrorIs(t, err, auth.ErrValidation)

	hours := 2
	invite, err := svc.CreateInvite(context.Background(), owner, circle.ID, &hours)
	require.NoError(t, err)
	assert.WithinDuration(t, time.Now().UTC().Add(2*time.Hour), invite.ExpiresAt, time.Minute)
}

func TestServiceValidation(t *testing.T) {
	svc, authSvc, _ := newStack(t)
	owner := signIn(t, authSvc, "validation@x.co")

	_, _, err := svc.Create(context.Background(), owner, "   ")
	require.ErrorIs(t, err, auth.ErrValidation)

	circle, _, err := svc.Create(context.Background(), owner, "Nhà")
	require.NoError(t, err)
	_, err = svc.Rename(context.Background(), owner, circle.ID, "")
	require.ErrorIs(t, err, auth.ErrValidation)

	_, _, err = svc.Redeem(context.Background(), owner, "abc")
	require.ErrorIs(t, err, auth.ErrValidation, "codes shorter than 4 characters never reach the store")
}
