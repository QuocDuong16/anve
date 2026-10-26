package auth_test

import (
	"context"
	"sync"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/audit"
	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/circles"
)

// Account deletion against live circle state: an owner of a populated
// circle must never dissolve it implicitly by deleting the account. These
// tests pin the invariant the deletion transaction enforces.

type circleFixture struct {
	svc       *auth.Service
	circles   *circles.Service
	pool      *pgxpool.Pool
	ownerID   uuid.UUID
	memberIDs []uuid.UUID
}

// newCircleFixture signs in an owner plus the given number of members,
// creates one circle and joins every member through a dedicated invite.
func newCircleFixture(t *testing.T, members int) circleFixture {
	t.Helper()
	ctx := context.Background()
	svc, pool := newPGService(t)
	circleSvc := circles.NewService(circles.NewPGStore(pool))

	owner, _, err := svc.DevSignIn(ctx, "owner@x.co", "Owner", devDevice())
	require.NoError(t, err)
	circle, _, err := circleSvc.Create(ctx, owner.ID, "Gia đình")
	require.NoError(t, err)

	f := circleFixture{svc: svc, circles: circleSvc, pool: pool, ownerID: owner.ID}
	for i := 0; i < members; i++ {
		email := "member" + string(rune('a'+i)) + "@x.co"
		member, _, err := svc.DevSignIn(ctx, email, "", auth.DeviceRegistration{
			ClientDeviceID: "device-member-" + string(rune('a'+i)) + "-0001",
		})
		require.NoError(t, err)
		invite, err := circleSvc.CreateInvite(ctx, owner.ID, circle.ID, nil)
		require.NoError(t, err)
		_, _, err = circleSvc.Redeem(ctx, member.ID, invite.Code)
		require.NoError(t, err)
		f.memberIDs = append(f.memberIDs, member.ID)
	}
	return f
}

// Test A: an owner whose circle has other members cannot delete the
// account; nothing is deleted and no false-success audit is written.
func TestPGOwnerDeletionRefusedWhileCircleHasMembers(t *testing.T) {
	f := newCircleFixture(t, 2)
	ctx := context.Background()

	err := f.svc.DeleteAccount(ctx, f.ownerID)
	require.ErrorIs(t, err, auth.ErrCircleHasMembers)

	assert.Equal(t, 1, countRows(t, f.pool, `SELECT count(*) FROM users WHERE id = $1`, f.ownerID),
		"the owner account survives the refused deletion")
	assert.Equal(t, 1, countRows(t, f.pool, `SELECT count(*) FROM circles`))
	assert.Equal(t, 3, countRows(t, f.pool, `SELECT count(*) FROM memberships`),
		"owner and both members keep their memberships")
	assert.NotContains(t, auditActions(t, f.pool), audit.ActionAccountDeleted,
		"a refused deletion must not audit success")
	assert.NotContains(t, auditActions(t, f.pool), audit.ActionCircleDissolved)
}

// Test B: an ordinary member deleting the account leaves the owner's
// circle fully intact.
func TestPGMemberDeletionKeepsCircle(t *testing.T) {
	f := newCircleFixture(t, 1)
	ctx := context.Background()

	require.NoError(t, f.svc.DeleteAccount(ctx, f.memberIDs[0]))

	assert.Equal(t, 1, countRows(t, f.pool, `SELECT count(*) FROM users WHERE id = $1`, f.ownerID))
	assert.Equal(t, 1, countRows(t, f.pool, `SELECT count(*) FROM circles`))
	assert.Equal(t, 1, countRows(t, f.pool, `SELECT count(*) FROM memberships`),
		"only the owner's membership remains")
	assert.Equal(t, 0, countRows(t, f.pool,
		`SELECT count(*) FROM memberships WHERE user_id = $1`, f.memberIDs[0]))
	assert.Equal(t, 0, countRows(t, f.pool,
		`SELECT count(*) FROM sessions WHERE user_id = $1`, f.memberIDs[0]),
		"the deleted member's sessions are gone")
	assert.Contains(t, auditActions(t, f.pool), audit.ActionAccountDeleted)
}

// Test C: an owner whose circle has no other members may delete the
// account; the empty circle goes in the same transaction, audited as a
// dissolution, with no orphan rows left behind.
func TestPGOwnerDeletionRemovesEmptyCircle(t *testing.T) {
	f := newCircleFixture(t, 0)
	ctx := context.Background()

	require.NoError(t, f.svc.DeleteAccount(ctx, f.ownerID))

	assert.Equal(t, 0, countRows(t, f.pool, `SELECT count(*) FROM users`))
	assert.Equal(t, 0, countRows(t, f.pool, `SELECT count(*) FROM circles`))
	assert.Equal(t, 0, countRows(t, f.pool, `SELECT count(*) FROM memberships`))
	assert.Equal(t, 0, countRows(t, f.pool, `SELECT count(*) FROM invites`))
	assert.Equal(t, 0, countRows(t, f.pool, `SELECT count(*) FROM devices`))
	assert.Equal(t, 0, countRows(t, f.pool, `SELECT count(*) FROM sessions`))

	actions := auditActions(t, f.pool)
	assert.Contains(t, actions, audit.ActionCircleDissolved)
	assert.Contains(t, actions, audit.ActionAccountDeleted)
}

// Test D: after an explicit dissolution the owner may delete the account.
func TestPGExplicitDissolutionUnblocksOwnerDeletion(t *testing.T) {
	f := newCircleFixture(t, 1)
	ctx := context.Background()

	require.NoError(t, f.circles.Leave(ctx, f.ownerID, circleIDOf(t, f.pool, f.ownerID), true))
	require.NoError(t, f.svc.DeleteAccount(ctx, f.ownerID))

	assert.Equal(t, 0, countRows(t, f.pool, `SELECT count(*) FROM users WHERE id = $1`, f.ownerID))
	assert.Equal(t, 0, countRows(t, f.pool, `SELECT count(*) FROM circles`))
}

// Test E: owner deletion racing an invite redemption. Exactly one business
// outcome may hold per round: the deletion wins (the redeem fails and no
// rows survive) or the redeem wins (the member joined and the deletion
// refused with ErrCircleHasMembers). Repeated rounds shake out both
// interleavings of the row locks.
func TestPGOwnerDeletionRacesInviteRedemption(t *testing.T) {
	f := newCircleFixture(t, 0)
	ctx := context.Background()

	for round := 0; round < 5; round++ {
		suffix := string(rune('a' + round))
		owner, _, err := f.svc.DevSignIn(ctx, "race-owner-"+suffix+"@x.co", "", auth.DeviceRegistration{
			ClientDeviceID: "device-race-owner-" + suffix + "-001",
		})
		require.NoError(t, err)
		member, _, err := f.svc.DevSignIn(ctx, "race-member-"+suffix+"@x.co", "", auth.DeviceRegistration{
			ClientDeviceID: "device-race-member-" + suffix + "-001",
		})
		require.NoError(t, err)
		circle, _, err := f.circles.Create(ctx, owner.ID, "Race")
		require.NoError(t, err)
		invite, err := f.circles.CreateInvite(ctx, owner.ID, circle.ID, nil)
		require.NoError(t, err)

		var deleteErr, redeemErr error
		var wg sync.WaitGroup
		wg.Add(2)
		go func() {
			defer wg.Done()
			deleteErr = f.svc.DeleteAccount(ctx, owner.ID)
		}()
		go func() {
			defer wg.Done()
			_, _, redeemErr = f.circles.Redeem(ctx, member.ID, invite.Code)
		}()
		wg.Wait()

		deleted := deleteErr == nil
		joined := redeemErr == nil
		assert.False(t, deleted && joined, "round %d: both sides cannot win", round)

		if deleted {
			assert.Error(t, redeemErr, "round %d: the invite died with the circle", round)
			assert.Equal(t, 0, countRows(t, f.pool,
				`SELECT count(*) FROM memberships WHERE user_id = $1`, member.ID), "round %d", round)
		} else {
			require.ErrorIs(t, deleteErr, auth.ErrCircleHasMembers, "round %d", round)
			require.NoError(t, redeemErr, "round %d", round)
			assert.Equal(t, 1, countRows(t, f.pool,
				`SELECT count(*) FROM memberships WHERE user_id = $1 AND role = 'member'`, member.ID),
				"round %d", round)
		}

		// Return to a clean slate so the next round is independent.
		if !deleted {
			require.NoError(t, f.circles.Leave(ctx, member.ID, circle.ID, false))
			require.NoError(t, f.svc.DeleteAccount(ctx, owner.ID))
		}
		require.NoError(t, f.svc.DeleteAccount(ctx, member.ID))
	}

	// No membership may outlive its user anywhere in the database.
	assert.Equal(t, 0, countRows(t, f.pool,
		`SELECT count(*) FROM memberships m
		  WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = m.user_id)`))
}

func circleIDOf(t *testing.T, pool *pgxpool.Pool, userID uuid.UUID) uuid.UUID {
	t.Helper()
	var id uuid.UUID
	require.NoError(t, pool.QueryRow(context.Background(),
		`SELECT c.id FROM circles c JOIN memberships m ON m.circle_id = c.id
		  WHERE m.user_id = $1`, userID).Scan(&id))
	return id
}
