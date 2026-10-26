package circles_test

import (
	"context"
	"strings"
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

// Audit integrity (ADR 0004): every circle mutation writes exactly one row,
// inside the transaction that performs it. Failed operations write none, and
// a rollback takes the audit row with it.

func auditCount(t *testing.T, pool *pgxpool.Pool, action string) int {
	t.Helper()
	var n int
	require.NoError(t, pool.QueryRow(context.Background(),
		`SELECT count(*) FROM audit_log WHERE action = $1`, action).Scan(&n))
	return n
}

func TestAuditOneRowPerCircleMutation(t *testing.T) {
	svc, authSvc, pool := newStack(t)
	ctx := context.Background()
	owner := signIn(t, authSvc, "owner@audit.co")

	circle, _, err := svc.Create(ctx, owner, "Nhà")
	require.NoError(t, err)
	assert.Equal(t, 1, auditCount(t, pool, audit.ActionCircleCreated))

	_, err = svc.Rename(ctx, owner, circle.ID, "Nhà mới")
	require.NoError(t, err)
	assert.Equal(t, 1, auditCount(t, pool, audit.ActionCircleRenamed),
		"rename is recorded exactly once, in the rename transaction")

	invite, err := svc.CreateInvite(ctx, owner, circle.ID, nil)
	require.NoError(t, err)
	assert.Equal(t, 1, auditCount(t, pool, audit.ActionInviteCreated))

	member := signIn(t, authSvc, "member@audit.co")
	_, _, err = svc.Redeem(ctx, member, invite.Code)
	require.NoError(t, err)
	assert.Equal(t, 1, auditCount(t, pool, audit.ActionCircleJoined),
		"redeem must not record the join twice (store transaction only)")

	second, err := svc.CreateInvite(ctx, owner, circle.ID, nil)
	require.NoError(t, err)
	require.NoError(t, svc.RevokeInvite(ctx, owner, second.ID))
	assert.Equal(t, 1, auditCount(t, pool, audit.ActionInviteRevoked))

	require.NoError(t, svc.RemoveMember(ctx, owner, circle.ID, member))
	assert.Equal(t, 1, auditCount(t, pool, audit.ActionMemberRemoved),
		"removal must not record the event twice (store transaction only)")

	require.NoError(t, svc.Leave(ctx, owner, circle.ID, true))
	assert.Equal(t, 1, auditCount(t, pool, audit.ActionCircleDissolved))
}

func TestAuditFailedOperationsWriteNothing(t *testing.T) {
	svc, authSvc, pool := newStack(t)
	ctx := context.Background()
	owner := signIn(t, authSvc, "owner@fail.co")
	member := signIn(t, authSvc, "member@fail.co")
	outsider := signIn(t, authSvc, "outsider@fail.co")

	circle, _, err := svc.Create(ctx, owner, "Nhà")
	require.NoError(t, err)
	invite, err := svc.CreateInvite(ctx, owner, circle.ID, nil)
	require.NoError(t, err)
	_, _, err = svc.Redeem(ctx, member, invite.Code)
	require.NoError(t, err)

	created := auditCount(t, pool, audit.ActionCircleCreated)
	invites := auditCount(t, pool, audit.ActionInviteCreated)
	joined := auditCount(t, pool, audit.ActionCircleJoined)

	// Owner with members must dissolve: no false dissolution row.
	require.ErrorIs(t, svc.Leave(ctx, owner, circle.ID, false), circles.ErrOwnerMustDissolve)
	assert.Equal(t, 0, auditCount(t, pool, audit.ActionCircleDissolved))

	// A burned invite cannot be redeemed again: no second join row.
	_, _, err = svc.Redeem(ctx, outsider, invite.Code)
	require.ErrorIs(t, err, circles.ErrInviteUnavailable)
	assert.Equal(t, joined, auditCount(t, pool, audit.ActionCircleJoined))

	// Outsider operations are refused before any audit write.
	_, err = svc.Rename(ctx, outsider, circle.ID, "X")
	require.ErrorIs(t, err, auth.ErrNotFound)
	require.ErrorIs(t, svc.RemoveMember(ctx, outsider, circle.ID, member), auth.ErrNotFound)
	assert.Equal(t, 0, auditCount(t, pool, audit.ActionCircleRenamed))
	assert.Equal(t, 0, auditCount(t, pool, audit.ActionMemberRemoved))

	// Failed revoke writes nothing.
	require.ErrorIs(t, svc.RevokeInvite(ctx, owner, uuid.New()), auth.ErrNotFound)
	assert.Equal(t, 0, auditCount(t, pool, audit.ActionInviteRevoked))

	// The successful operations above still have exactly one row each.
	assert.Equal(t, created, auditCount(t, pool, audit.ActionCircleCreated))
	assert.Equal(t, invites, auditCount(t, pool, audit.ActionInviteCreated))
}

// A failing mutation must roll back the audit row with it. This is forced
// at the SQL level: the rename violates the name CHECK constraint after the
// audit insert would have happened, so an audit row surviving would mean
// the two are not in the same transaction.
func TestAuditRollsBackWithTheBusinessChange(t *testing.T) {
	svc, authSvc, pool := newStack(t)
	ctx := context.Background()
	owner := signIn(t, authSvc, "owner@rollback.co")

	circle, _, err := svc.Create(ctx, owner, "Nhà")
	require.NoError(t, err)

	// The service trims and validates length; go straight to the store with
	// a name the CHECK constraint rejects (81 characters).
	store := circles.NewPGStore(pool)
	_, err = store.RenameCircle(ctx, owner, circle.ID, strings.Repeat("a", 81))
	require.Error(t, err)
	assert.Equal(t, 0, auditCount(t, pool, audit.ActionCircleRenamed))
	assert.Equal(t, 1, auditCount(t, pool, audit.ActionCircleCreated))
}

func TestAuditConcurrentMutationsRecordOneRowEach(t *testing.T) {
	svc, authSvc, pool := newStack(t)
	ctx := context.Background()
	owner := signIn(t, authSvc, "owner@race.co")

	circle, _, err := svc.Create(ctx, owner, "Nhà")
	require.NoError(t, err)

	// Concurrent invites: each successful one records exactly one row.
	const n = 5
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := svc.CreateInvite(ctx, owner, circle.ID, nil)
			assert.NoError(t, err)
		}()
	}
	wg.Wait()
	assert.Equal(t, n, auditCount(t, pool, audit.ActionInviteCreated))
	assert.Equal(t, n, countRows(t, pool, `SELECT count(*) FROM invites`))
}

func countRows(t *testing.T, pool *pgxpool.Pool, query string, args ...any) int {
	t.Helper()
	var n int
	require.NoError(t, pool.QueryRow(context.Background(), query, args...).Scan(&n))
	return n
}
