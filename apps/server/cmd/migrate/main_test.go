// Integration tests for the migrator against a real PostgreSQL/PostGIS
// server. They are skipped unless TEST_DATABASE_URL points at an existing
// database whose user may create databases — for the local Compose stack:
//
//	make docker-up
//	make server-test-integration
//
// Each test migrates its own scratch database (PostGIS extensions are
// per-database), so the URL may point at any existing database on a
// PostGIS-capable server, e.g. the postgis/postgis image the Compose stack
// and the CI job both use.
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const testDatabaseURLEnv = "TEST_DATABASE_URL"

// scratchDatabase opens the server behind TEST_DATABASE_URL, creates a
// uniquely named scratch database there, and returns a pool connected to it
// plus a connection config for that scratch database (open further
// independent sessions against the same scratch database with
// pgx.ConnectConfig on a Copy(), e.g. to simulate a second migrator). The
// database is dropped when the test ends.
func scratchDatabase(t *testing.T) (*pgxpool.Pool, *pgx.ConnConfig) {
	t.Helper()

	dsn := os.Getenv(testDatabaseURLEnv)
	if dsn == "" {
		t.Skipf("%s is not set; skipping migration integration test", testDatabaseURLEnv)
	}

	ctx := context.Background()
	admin, err := pgx.ConnectConfig(ctx, mustParseConfig(t, dsn))
	require.NoError(t, err, "connect to the test database server")

	name := fmt.Sprintf("anve_migrate_test_%s", randomSuffix(t))
	_, err = admin.Exec(ctx, "CREATE DATABASE "+pgx.Identifier{name}.Sanitize())
	require.NoError(t, err, "create scratch database")
	t.Cleanup(func() {
		// FORCE terminates any connection a failed test left open.
		_, _ = admin.Exec(context.Background(),
			"DROP DATABASE "+pgx.Identifier{name}.Sanitize()+" WITH (FORCE)")
		_ = admin.Close(context.Background())
	})

	poolCfg, err := pgxpool.ParseConfig(dsn)
	require.NoError(t, err, "parse %s into a pool config", testDatabaseURLEnv)
	poolCfg.ConnConfig.Database = name
	scratchCfg := poolCfg.ConnConfig.Copy()
	pool, err := pgxpool.NewWithConfig(ctx, poolCfg)
	require.NoError(t, err, "connect to scratch database")
	t.Cleanup(pool.Close)

	return pool, scratchCfg
}

func mustParseConfig(t *testing.T, dsn string) *pgx.ConnConfig {
	t.Helper()
	cfg, err := pgx.ParseConfig(dsn)
	require.NoError(t, err, "parse %s", testDatabaseURLEnv)
	return cfg
}

func randomSuffix(t *testing.T) string {
	t.Helper()
	buf := make([]byte, 4)
	if _, err := rand.Read(buf); err != nil {
		t.Fatalf("generate scratch database suffix: %v", err)
	}
	return hex.EncodeToString(buf)
}

// migrationsDir resolves the repository's real migration files relative to
// this package directory (apps/server/cmd/migrate).
func migrationsDir() string {
	return filepath.Join("..", "..", "..", "..", "infra", "migrations")
}

func testLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func writeFile(t *testing.T, dir, name, contents string) {
	t.Helper()
	require.NoError(t, os.WriteFile(filepath.Join(dir, name), []byte(contents), 0o600))
}

func appliedVersionsFor(t *testing.T, pool *pgxpool.Pool) []string {
	t.Helper()
	rows, err := pool.Query(context.Background(),
		`SELECT version FROM schema_migrations ORDER BY version`)
	require.NoError(t, err)
	defer rows.Close()

	var versions []string
	for rows.Next() {
		var version string
		require.NoError(t, rows.Scan(&version))
		versions = append(versions, version)
	}
	require.NoError(t, rows.Err())
	return versions
}

func assertAppliedVersions(t *testing.T, pool *pgxpool.Pool, want ...string) {
	t.Helper()
	assert.Equal(t, want, appliedVersionsFor(t, pool))
}

func assertTable(t *testing.T, pool *pgxpool.Pool, name string, want bool) {
	t.Helper()
	var exists bool
	err := pool.QueryRow(context.Background(),
		`SELECT EXISTS (
		    SELECT 1 FROM information_schema.tables
		    WHERE table_schema = current_schema() AND table_name = $1
		 )`, name).Scan(&exists)
	require.NoError(t, err)
	assert.Equal(t, want, exists, "unexpected existence state for table %s", name)
}

func assertExtensionsInstalled(t *testing.T, pool *pgxpool.Pool, want ...string) {
	t.Helper()
	rows, err := pool.Query(context.Background(), `SELECT extname FROM pg_extension`)
	require.NoError(t, err)
	defer rows.Close()

	installed := make(map[string]bool)
	for rows.Next() {
		var name string
		require.NoError(t, rows.Scan(&name))
		installed[name] = true
	}
	require.NoError(t, rows.Err())

	for _, name := range want {
		assert.Contains(t, installed, name, "extension %s should be installed", name)
	}
}

// TestMigrationLifecycle exercises the real repository migrations on a fresh
// database: up, up again (idempotence), down, up. The expected version list
// comes from the migration files themselves, so the test keeps working as
// migrations accumulate. The full roll-down step is the regression for the
// schema_migrations bug: rolling back migrations must never drop the
// engine's bookkeeping table.
func TestMigrationLifecycle(t *testing.T) {
	pool, _ := scratchDatabase(t)
	ctx := context.Background()
	dir := migrationsDir()

	migrations, err := loadMigrations(dir)
	require.NoError(t, err)
	require.NotEmpty(t, migrations, "the repository must ship at least one migration")
	allVersions := make([]string, len(migrations))
	for i, m := range migrations {
		allVersions[i] = m.version
	}

	require.NoError(t, ensureBookkeepingTable(ctx, pool))
	require.NoError(t, migrateUp(ctx, pool, dir, testLogger()))
	assertAppliedVersions(t, pool, allVersions...)
	assertExtensionsInstalled(t, pool, "postgis", "uuid-ossp", "pg_trgm")

	// A second up must be a no-op, not a duplicate bookkeeping row.
	require.NoError(t, migrateUp(ctx, pool, dir, testLogger()))
	assertAppliedVersions(t, pool, allVersions...)

	// One down rolls back exactly the most recent migration.
	require.NoError(t, migrateDown(ctx, pool, dir, testLogger()))
	assertAppliedVersions(t, pool, allVersions[:len(allVersions)-1]...)

	// Rolling all the way down empties the bookkeeping but keeps the
	// engine-owned table (and, per 0001's deliberate no-op, the extensions).
	for range len(allVersions) - 1 {
		require.NoError(t, migrateDown(ctx, pool, dir, testLogger()))
	}
	assertAppliedVersions(t, pool)
	assertTable(t, pool, "schema_migrations", true)
	assertExtensionsInstalled(t, pool, "postgis", "uuid-ossp", "pg_trgm")

	// Up after a full roll-down re-applies cleanly.
	require.NoError(t, migrateUp(ctx, pool, dir, testLogger()))
	assertAppliedVersions(t, pool, allVersions...)
}

// TestFailedMigrationLeavesNoTrace proves a migration file is atomic: when
// its second statement fails, the first statement and the bookkeeping row
// roll back with the transaction.
func TestFailedMigrationLeavesNoTrace(t *testing.T) {
	pool, _ := scratchDatabase(t)
	ctx := context.Background()

	dir := t.TempDir()
	writeFile(t, dir, "0001_good.up.sql", "CREATE TABLE good_table (id int);")
	writeFile(t, dir, "0001_good.down.sql", "DROP TABLE good_table;")
	writeFile(t, dir, "0002_broken.up.sql",
		"CREATE TABLE broken_table (id int);\nTHIS IS NOT SQL;")
	writeFile(t, dir, "0002_broken.down.sql", "DROP TABLE broken_table;")

	require.NoError(t, ensureBookkeepingTable(ctx, pool))
	err := migrateUp(ctx, pool, dir, testLogger())
	require.Error(t, err, "the broken migration must fail")
	assert.Contains(t, err.Error(), "0002_broken")

	assertAppliedVersions(t, pool, "0001_good")
	assertTable(t, pool, "good_table", true)
	assertTable(t, pool, "broken_table", false)
}

// TestMigrateDownRequiresDownFile keeps the rule that a migration without a
// .down.sql file cannot be rolled back.
func TestMigrateDownRequiresDownFile(t *testing.T) {
	pool, _ := scratchDatabase(t)
	ctx := context.Background()

	dir := t.TempDir()
	writeFile(t, dir, "0001_stuck.up.sql", "SELECT 1;")

	require.NoError(t, ensureBookkeepingTable(ctx, pool))
	require.NoError(t, migrateUp(ctx, pool, dir, testLogger()))

	err := migrateDown(ctx, pool, dir, testLogger())
	require.Error(t, err)
	assert.Contains(t, err.Error(), "no .down.sql")
}

// TestMigrateDownWithoutAppliedMigrationsIsNoop covers the empty-bookkeeping
// branch of migrateDown.
func TestMigrateDownWithoutAppliedMigrationsIsNoop(t *testing.T) {
	pool, _ := scratchDatabase(t)
	ctx := context.Background()

	require.NoError(t, ensureBookkeepingTable(ctx, pool))
	require.NoError(t, migrateDown(ctx, pool, migrationsDir(), testLogger()))
	assertAppliedVersions(t, pool)
}

// TestMigrationLockBlocksConcurrentMigrator proves the advisory lock
// serializes migrator runs against the same database: a second run waits
// (until its context expires) and succeeds once the holder releases.
func TestMigrationLockBlocksConcurrentMigrator(t *testing.T) {
	pool, cfg := scratchDatabase(t)
	ctx := context.Background()

	// An independent session holds the lock, standing in for another
	// migrator run.
	rival, err := pgx.ConnectConfig(ctx, cfg.Copy())
	require.NoError(t, err)
	t.Cleanup(func() { _ = rival.Close(ctx) })
	_, err = rival.Exec(ctx, `SELECT pg_advisory_lock($1)`, migrationLockKey)
	require.NoError(t, err)

	waitCtx, cancel := context.WithTimeout(ctx, 500*time.Millisecond)
	_, err = acquireMigrationLock(waitCtx, pool)
	cancel()
	require.Error(t, err, "the migrator must wait for the lock, not run concurrently")
	require.ErrorIs(t, err, context.DeadlineExceeded)

	_, err = rival.Exec(ctx, `SELECT pg_advisory_unlock($1)`, migrationLockKey)
	require.NoError(t, err)

	lock, err := acquireMigrationLock(ctx, pool)
	require.NoError(t, err)
	lock.release()
}
