package auth_test

import (
	"context"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/audit"
	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/testdb"
)

// Account deletion and circle creation both touch the same user row through
// a foreign key. These tests stop each transaction at an explicit barrier
// (an observed database lock, never a sleep as an ordering primitive) and
// pin the two possible outcomes.
//
// Circle creation is expressed as the same SQL transaction the production
// CreateCircle runs — insert circle, insert owner membership, audit — so the
// test exercises the real foreign-key and row-lock behaviour without an
// import cycle between auth and circles.

type raceFixture struct {
	authStore *auth.PGStore
	authSvc   *auth.Service
	pool      *pgxpool.Pool
	owner     auth.User
}

func newRaceFixture(t *testing.T) raceFixture {
	t.Helper()
	ctx := context.Background()
	pool := testdb.Scratch(t)
	authStore := auth.NewPGStore(pool)
	f := raceFixture{
		authStore: authStore,
		authSvc:   auth.NewService(authStore, nil, true),
		pool:      pool,
	}
	owner, _, err := f.authSvc.DevSignIn(ctx, "race-owner@x.co", "Owner", devDevice())
	require.NoError(t, err)
	f.owner = owner
	return f
}

// beginCircleCreation opens the creation transaction and holds its rows
// (including the user KEY SHARE) until the caller commits or rolls back.
func beginCircleCreation(ctx context.Context, t *testing.T, pool *pgxpool.Pool, userID uuid.UUID, name string) (pgx.Tx, uuid.UUID) {
	t.Helper()
	tx, err := pool.Begin(ctx)
	require.NoError(t, err)
	var circleID uuid.UUID
	err = tx.QueryRow(ctx,
		`INSERT INTO circles (name, created_by) VALUES ($1, $2) RETURNING id`, name, userID).
		Scan(&circleID)
	require.NoError(t, err)
	_, err = tx.Exec(ctx,
		`INSERT INTO memberships (circle_id, user_id, role) VALUES ($1, $2, 'owner')`,
		circleID, userID)
	require.NoError(t, err)
	require.NoError(t, audit.InTx(ctx, tx, audit.Event{
		ActorUserID: userID, Action: audit.ActionCircleCreated,
		Detail: map[string]any{"circle_id": circleID.String()},
	}))
	return tx, circleID
}

// Create wins: the creation transaction holds its rows (and the user KEY
// SHARE). Deletion blocks on the user lock, then evaluates the committed
// circle — and, because it is an empty owner-only circle, removes it with an
// explicit dissolution audit. That audit proves deletion saw the circle
// instead of cascading an unexamined row.
func TestPGDeleteAccountWaitsForConcurrentCircleCreation(t *testing.T) {
	f := newRaceFixture(t)
	ctx := context.Background()

	createTx, circleID := beginCircleCreation(ctx, t, f.pool, f.owner.ID, "Race")
	defer func() { _ = createTx.Rollback(ctx) }()

	deleteDone := make(chan error, 1)
	go func() { deleteDone <- f.authSvc.DeleteAccount(ctx, f.owner.ID) }()

	// The deletion is now waiting for the creation's KEY SHARE on the user
	// row: the transactions provably overlap at this point.
	waitForLockWait(t, f.pool)
	require.NoError(t, createTx.Commit(ctx))

	require.NoError(t, <-deleteDone)

	assert.Equal(t, 0, countRows(t, f.pool, `SELECT count(*) FROM users`))
	assert.Equal(t, 0, countRows(t, f.pool, `SELECT count(*) FROM circles`))
	assert.Equal(t, 0, countRows(t, f.pool, `SELECT count(*) FROM memberships`))
	assert.Equal(t, 0, countRows(t, f.pool, `SELECT count(*) FROM invites`))

	assert.Equal(t, 1, countRows(t, f.pool,
		`SELECT count(*) FROM audit_log
		  WHERE action = $1 AND detail->>'circle_id' = $2 AND detail->>'via' = 'account_deletion'`,
		audit.ActionCircleDissolved, circleID.String()),
		"deletion must audit the dissolution of the circle it observed")

	actions := auditActions(t, f.pool)
	assert.Equal(t, 1, countAction(actions, audit.ActionCircleCreated))
	assert.Equal(t, 1, countAction(actions, audit.ActionCircleDissolved))
	assert.Equal(t, 1, countAction(actions, audit.ActionAccountDeleted))
}

// Delete wins: the deletion holds the user row lock at the barrier. A
// creation started afterwards must block on the foreign key and then fail —
// it can never report success for a row that the cascade removed.
func TestPGCircleCreationFailsWhenDeletionWins(t *testing.T) {
	f := newRaceFixture(t)
	ctx := context.Background()

	userLocked := make(chan struct{})
	releaseDelete := make(chan struct{})
	var once sync.Once
	f.authStore.SetTestAfterUserLock(func() {
		once.Do(func() { close(userLocked) })
		<-releaseDelete
	})

	deleteDone := make(chan error, 1)
	go func() { deleteDone <- f.authSvc.DeleteAccount(ctx, f.owner.ID) }()

	select {
	case <-userLocked:
	case <-time.After(5 * time.Second):
		t.Fatal("deletion never acquired the user row lock")
	}

	createDone := make(chan error, 1)
	go func() {
		tx, err := f.pool.Begin(ctx)
		if err != nil {
			createDone <- err
			return
		}
		defer func() { _ = tx.Rollback(ctx) }()
		_, err = tx.Exec(ctx,
			`INSERT INTO circles (name, created_by) VALUES ('Too late', $1)`, f.owner.ID)
		createDone <- err
	}()

	waitForLockWait(t, f.pool)
	close(releaseDelete)

	require.NoError(t, <-deleteDone)
	createErr := <-createDone
	require.Error(t, createErr, "creation must not succeed for a deleted account")
	assert.Contains(t, strings.ToLower(createErr.Error()), "foreign key")

	assert.Equal(t, 0, countRows(t, f.pool, `SELECT count(*) FROM users`))
	assert.Equal(t, 0, countRows(t, f.pool, `SELECT count(*) FROM circles`))
	assert.Equal(t, 0, countRows(t, f.pool, `SELECT count(*) FROM memberships`))

	actions := auditActions(t, f.pool)
	assert.NotContains(t, actions, audit.ActionCircleCreated)
	assert.NotContains(t, actions, audit.ActionCircleDissolved)
	assert.Equal(t, 1, countAction(actions, audit.ActionAccountDeleted))
}

// A competing transaction can close a lock cycle: deletion holds the user
// row and waits for the circle, while the competing transaction holds the
// circle and asks for the user row. PostgreSQL aborts one side; the store's
// bounded retry must still finish with a consistent account deletion and a
// single audit row.
func TestPGDeleteAccountRecoversFromDeadlockWithCircleHolder(t *testing.T) {
	f := newRaceFixture(t)
	ctx := context.Background()

	createTx, circleID := beginCircleCreation(ctx, t, f.pool, f.owner.ID, "Deadlock")
	require.NoError(t, createTx.Commit(ctx))

	competing, err := f.pool.Begin(ctx)
	require.NoError(t, err)
	defer func() { _ = competing.Rollback(ctx) }()
	_, err = competing.Exec(ctx, `SELECT id FROM circles WHERE id = $1 FOR UPDATE`, circleID)
	require.NoError(t, err)

	userLocked := make(chan struct{})
	var once sync.Once
	f.authStore.SetTestAfterUserLock(func() {
		once.Do(func() { close(userLocked) })
	})

	deleteDone := make(chan error, 1)
	go func() { deleteDone <- f.authSvc.DeleteAccount(ctx, f.owner.ID) }()

	select {
	case <-userLocked:
	case <-time.After(5 * time.Second):
		t.Fatal("deletion never acquired the user row lock")
	}
	// Deletion is waiting for the circle row held by the competing tx.
	waitForLockWait(t, f.pool)

	// Close the cycle: this waits for the user row held by the deletion, so
	// PostgreSQL detects the deadlock and aborts one transaction. Whichever
	// side loses, rolling the competing transaction back lets the store's
	// retry (or the surviving deletion) complete.
	_, _ = competing.Exec(ctx, `SELECT id FROM users WHERE id = $1 FOR UPDATE`, f.owner.ID)
	require.NoError(t, competing.Rollback(ctx))

	require.NoError(t, <-deleteDone)

	assert.Equal(t, 0, countRows(t, f.pool, `SELECT count(*) FROM users`))
	assert.Equal(t, 0, countRows(t, f.pool, `SELECT count(*) FROM circles`))
	actions := auditActions(t, f.pool)
	assert.Equal(t, 1, countAction(actions, audit.ActionCircleDissolved),
		"a deadlock retry must not duplicate the dissolution audit")
	assert.Equal(t, 1, countAction(actions, audit.ActionAccountDeleted))
}

// waitForLockWait blocks until at least one backend in this scratch database
// is waiting on a lock. It turns "the transaction reached the intended
// blocking point" into an observable condition instead of a sleep.
func waitForLockWait(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		var waiting int
		err := pool.QueryRow(context.Background(),
			`SELECT count(*) FROM pg_stat_activity
			  WHERE datname = current_database() AND wait_event_type = 'Lock'`).
			Scan(&waiting)
		require.NoError(t, err)
		if waiting > 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("timed out waiting for a PostgreSQL lock wait")
}

func countAction(actions []string, action string) int {
	count := 0
	for _, candidate := range actions {
		if candidate == action {
			count++
		}
	}
	return count
}
