package auth_test

import (
	"context"
	"crypto/sha256"
	"os"
	"path/filepath"
	"sort"
	"strings"
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
)

// These tests exercise the real PostgreSQL store: transactions, scoping by
// user, revocation semantics and audit atomicity. They are skipped unless
// TEST_DATABASE_URL points at a PostGIS-capable server (the same pattern as
// the migrator integration tests; CI runs them in the database-integration
// job).

func databaseURL() string {
	return os.Getenv("TEST_DATABASE_URL")
}

// newTestDB applies every pending migration from infra/migrations and
// returns a pool plus a cleanup that truncates the business tables. The
// migrator binary owns migrations in production; the test re-applies the
// same files so schema drift fails loudly here.
func newTestDB(t *testing.T) *pgxpool.Pool {
	t.Helper()
	url := databaseURL()
	if url == "" {
		t.Skip("TEST_DATABASE_URL not set; skipping PostgreSQL integration tests")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	pool, err := pgxpool.New(ctx, url)
	require.NoError(t, err)
	t.Cleanup(pool.Close)

	_, err = pool.Exec(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		version TEXT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())`)
	require.NoError(t, err)

	migrationsDir := filepath.Join("..", "..", "..", "..", "infra", "migrations")
	entries, err := os.ReadDir(migrationsDir)
	require.NoError(t, err)

	var files []string
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".up.sql") {
			files = append(files, e.Name())
		}
	}
	sort.Strings(files)

	for _, name := range files {
		version := strings.TrimSuffix(name, ".up.sql")
		var applied bool
		require.NoError(t, pool.QueryRow(ctx,
			`SELECT EXISTS (SELECT 1 FROM schema_migrations WHERE version = $1)`, version).Scan(&applied))
		if applied {
			continue
		}
		// #nosec G304 -- the path is built from the operator-controlled
		// migrations directory of this repository, never from request data.
		contents, err := os.ReadFile(filepath.Join(migrationsDir, name))
		require.NoError(t, err)
		tx, err := pool.Begin(ctx)
		require.NoError(t, err)
		_, err = tx.Exec(ctx, string(contents))
		require.NoError(t, err, "migration %s failed", name)
		_, err = tx.Exec(ctx, `INSERT INTO schema_migrations (version) VALUES ($1)`, version)
		require.NoError(t, err)
		require.NoError(t, tx.Commit(ctx))
	}

	t.Cleanup(func() {
		cleanupCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		// audit_log first is irrelevant (no FKs); CASCADE handles the rest.
		_, err := pool.Exec(cleanupCtx,
			`TRUNCATE audit_log, sessions, devices, users RESTART IDENTITY CASCADE`)
		if err != nil {
			t.Logf("truncate cleanup failed: %v", err)
		}
	})

	// Start every test from empty tables.
	_, err = pool.Exec(ctx, `TRUNCATE audit_log, sessions, devices, users RESTART IDENTITY CASCADE`)
	require.NoError(t, err)

	return pool
}

func newPGService(t *testing.T) (*auth.Service, *pgxpool.Pool) {
	t.Helper()
	pool := newTestDB(t)
	store := auth.NewPGStore(pool)
	recorder := audit.NewPG(pool)
	return auth.NewService(store, nil, true, recorder), pool
}

func auditActions(t *testing.T, pool *pgxpool.Pool) []string {
	t.Helper()
	rows, err := pool.Query(context.Background(), `SELECT action FROM audit_log ORDER BY id`)
	require.NoError(t, err)
	defer rows.Close()
	var out []string
	for rows.Next() {
		var action string
		require.NoError(t, rows.Scan(&action))
		out = append(out, action)
	}
	return out
}

func TestPGSignInCreatesUserDeviceSessionAndAudit(t *testing.T) {
	svc, pool := newPGService(t)
	ctx := context.Background()

	user, tokens, err := svc.DevSignIn(ctx, "cha@example.com", "Cha", devDevice())
	require.NoError(t, err)
	assert.False(t, user.LocationSharingEnabled, "consent defaults to OFF at the database level")

	_, authed, err := svc.Authenticate(ctx, tokens.AccessToken)
	require.NoError(t, err)
	assert.Equal(t, user.ID, authed.ID)

	actions := auditActions(t, pool)
	assert.Contains(t, actions, audit.ActionDevSignIn)

	// Only SHA-256 hashes are stored — never plaintext tokens.
	var rawCount int
	require.NoError(t, pool.QueryRow(ctx,
		`SELECT count(*) FROM sessions
		  WHERE refresh_token_hash = $1 OR access_token_hash = $2`,
		tokens.RefreshToken, tokens.AccessToken).Scan(&rawCount))
	assert.Zero(t, rawCount, "token plaintext must never be stored")
}

func TestPGReSignInSameDeviceRevokesPreviousSession(t *testing.T) {
	svc, pool := newPGService(t)
	ctx := context.Background()

	_, first, err := svc.DevSignIn(ctx, "a@b.co", "", devDevice())
	require.NoError(t, err)
	_, second, err := svc.DevSignIn(ctx, "a@b.co", "", devDevice())
	require.NoError(t, err)
	assert.Equal(t, 1, countRows(t, pool, "SELECT count(*) FROM users"), "same account, not a duplicate")
	assert.Equal(t, 1, countRows(t, pool, "SELECT count(*) FROM devices"), "same device row is reused")

	_, _, err = svc.Authenticate(ctx, first.AccessToken)
	require.ErrorIs(t, err, auth.ErrUnauthenticated)
	_, _, err = svc.Authenticate(ctx, second.AccessToken)
	require.NoError(t, err)

	var revokedReason string
	require.NoError(t, pool.QueryRow(ctx,
		`SELECT revoked_reason FROM sessions s
		  JOIN devices d ON d.id = s.device_id
		  WHERE d.client_device_id = $1 AND s.access_token_hash != $2`,
		devDevice().ClientDeviceID, hashOf(t, second.AccessToken)).
		Scan(&revokedReason))
	assert.Equal(t, "superseded", revokedReason)
}

func hashOf(t *testing.T, token string) []byte {
	t.Helper()
	digest := sha256.Sum256([]byte(token))
	return digest[:]
}

func countRows(t *testing.T, pool *pgxpool.Pool, query string, args ...any) int {
	t.Helper()
	var n int
	require.NoError(t, pool.QueryRow(context.Background(), query, args...).Scan(&n))
	return n
}

func TestPGRefreshRotationAndReplay(t *testing.T) {
	svc, _ := newPGService(t)
	ctx := context.Background()

	_, tokens, err := svc.DevSignIn(ctx, "a@b.co", "", devDevice())
	require.NoError(t, err)

	rotated, err := svc.Refresh(ctx, tokens.RefreshToken)
	require.NoError(t, err)

	_, err = svc.Refresh(ctx, tokens.RefreshToken)
	require.ErrorIs(t, err, auth.ErrUnauthenticated, "a rotated-out refresh token cannot be replayed")

	_, _, err = svc.Authenticate(ctx, rotated.AccessToken)
	require.NoError(t, err)
}

// Two concurrent refreshes with the same token: exactly one may win. The
// FOR UPDATE lock in the store serializes them; the loser sees the rotated
// hash and fails — the theft-reuse signal of ADR 0002's rotation model.
func TestPGConcurrentRefreshSingleWinner(t *testing.T) {
	svc, _ := newPGService(t)
	ctx := context.Background()

	_, tokens, err := svc.DevSignIn(ctx, "a@b.co", "", devDevice())
	require.NoError(t, err)

	var wg sync.WaitGroup
	var successes atomic.Int32
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := svc.Refresh(ctx, tokens.RefreshToken); err == nil {
				successes.Add(1)
			}
		}()
	}
	wg.Wait()
	assert.Equal(t, int32(1), successes.Load(), "exactly one concurrent refresh wins")
}

func TestPGCrossUserDeviceIsolation(t *testing.T) {
	svc, _ := newPGService(t)
	ctx := context.Background()

	_, tokensA, err := svc.DevSignIn(ctx, "a@x.co", "", auth.DeviceRegistration{ClientDeviceID: "device-aaaa-1"})
	require.NoError(t, err)
	_, _, err = svc.DevSignIn(ctx, "b@x.co", "", auth.DeviceRegistration{ClientDeviceID: "device-bbbb-1"})
	require.NoError(t, err)

	// A sees exactly one device: their own.
	devicesA, err := svc.ListDevices(ctx, userOf(t, svc, tokensA.AccessToken), sessionOf(t, svc, tokensA.AccessToken).ID)
	require.NoError(t, err)
	require.Len(t, devicesA, 1)

	// A cannot learn anything about B's device: probing ids yields nothing.
	deviceB := firstDeviceID(t, svc, "b@x.co")
	err = svc.RevokeDevice(ctx, userOf(t, svc, tokensA.AccessToken), deviceB)
	require.ErrorIs(t, err, auth.ErrNotFound)

	// And the probe did not delete B's device either.
	devicesB, err := svc.ListDevices(ctx, signInAgain(t, svc, "b@x.co"), uuid.Nil)
	require.NoError(t, err)
	assert.Len(t, devicesB, 1)
}

func TestPGExpiredAccessTokenIsRejected(t *testing.T) {
	svc, pool := newPGService(t)
	ctx := context.Background()

	_, tokens, err := svc.DevSignIn(ctx, "a@b.co", "", devDevice())
	require.NoError(t, err)
	sess := sessionOf(t, svc, tokens.AccessToken)

	// Push the access window into the past directly — no need to wait out
	// the real TTL to prove the expiry predicate runs in SQL.
	_, err = pool.Exec(ctx,
		`UPDATE sessions SET access_expires_at = now() - interval '1 minute' WHERE id = $1`, sess.ID)
	require.NoError(t, err)

	_, _, err = svc.Authenticate(ctx, tokens.AccessToken)
	require.ErrorIs(t, err, auth.ErrUnauthenticated)
}

func TestPGConsentChangeIsAtomicWithAudit(t *testing.T) {
	svc, pool := newPGService(t)
	ctx := context.Background()

	user, _, err := svc.DevSignIn(ctx, "a@b.co", "", devDevice())
	require.NoError(t, err)

	_, err = svc.SetLocationSharing(ctx, user.ID, true)
	require.NoError(t, err)
	actions := auditActions(t, pool)
	assert.Contains(t, actions, audit.ActionLocationSharingOn)

	stored, err := svc.Profile(ctx, user.ID)
	require.NoError(t, err)
	assert.True(t, stored.LocationSharingEnabled)
}

func TestPGDeleteAccountCascadesAndKeepsAudit(t *testing.T) {
	svc, pool := newPGService(t)
	ctx := context.Background()

	user, tokens, err := svc.DevSignIn(ctx, "gone@x.co", "", devDevice())
	require.NoError(t, err)
	require.NoError(t, svc.DeleteAccount(ctx, user.ID))

	assert.Equal(t, 0, countRows(t, pool, `SELECT count(*) FROM users WHERE id = $1`, user.ID))
	assert.Equal(t, 0, countRows(t, pool, `SELECT count(*) FROM sessions`))
	assert.Equal(t, 0, countRows(t, pool, `SELECT count(*) FROM devices`))

	_, _, err = svc.Authenticate(ctx, tokens.AccessToken)
	require.ErrorIs(t, err, auth.ErrUnauthenticated)

	// The audit trail deliberately survives the deletion (no FK, ADR 0002).
	actions := auditActions(t, pool)
	assert.Contains(t, actions, audit.ActionAccountDeleted)

	var actor string
	require.NoError(t, pool.QueryRow(ctx,
		`SELECT actor_user_id::text FROM audit_log WHERE action = $1`, audit.ActionAccountDeleted).Scan(&actor))
	assert.Equal(t, user.ID.String(), actor)
}

func TestPGSignOutAndRefreshAfterRevocation(t *testing.T) {
	svc, _ := newPGService(t)
	ctx := context.Background()

	_, tokens, err := svc.DevSignIn(ctx, "a@b.co", "", devDevice())
	require.NoError(t, err)
	sess := sessionOf(t, svc, tokens.AccessToken)

	require.NoError(t, svc.SignOut(ctx, sess))

	_, _, err = svc.Authenticate(ctx, tokens.AccessToken)
	require.ErrorIs(t, err, auth.ErrUnauthenticated)
	_, err = svc.Refresh(ctx, tokens.RefreshToken)
	require.ErrorIs(t, err, auth.ErrUnauthenticated, "a signed-out session cannot be refreshed back to life")
}

// ── small helpers ────────────────────────────────────────────────────────────

func userOf(t *testing.T, svc *auth.Service, accessToken string) uuid.UUID {
	t.Helper()
	_, u, err := svc.Authenticate(context.Background(), accessToken)
	require.NoError(t, err)
	return u.ID
}

func sessionOf(t *testing.T, svc *auth.Service, accessToken string) auth.Session {
	t.Helper()
	sess, _, err := svc.Authenticate(context.Background(), accessToken)
	require.NoError(t, err)
	return sess
}

// signInAgain returns the user id after a fresh dev sign-in.
func signInAgain(t *testing.T, svc *auth.Service, email string) uuid.UUID {
	t.Helper()
	_, tokens, err := svc.DevSignIn(context.Background(), email, "", auth.DeviceRegistration{ClientDeviceID: "device-bbbb-1"})
	require.NoError(t, err)
	return userOf(t, svc, tokens.AccessToken)
}

func firstDeviceID(t *testing.T, svc *auth.Service, email string) uuid.UUID {
	t.Helper()
	userID := signInAgain(t, svc, email)
	devices, err := svc.ListDevices(context.Background(), userID, uuid.Nil)
	require.NoError(t, err)
	require.NotEmpty(t, devices)
	return devices[0].Device.ID
}
