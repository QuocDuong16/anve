// Package testdb gives integration tests an isolated, fully migrated
// PostgreSQL database. Each call to Scratch creates a uniquely named
// scratch database on the server behind TEST_DATABASE_URL, applies the
// repository's real migrations and drops the database when the test ends.
//
// Per-test databases (not shared tables plus TRUNCATE) are what makes the
// test packages safe to run in parallel — `go test` executes packages
// concurrently, and concurrent TRUNCATEs on shared tables deadlock.
package testdb

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/require"
)

// Scratch returns a pool connected to a fresh, fully migrated scratch
// database. The test is skipped when TEST_DATABASE_URL is unset.
func Scratch(t *testing.T) *pgxpool.Pool {
	t.Helper()

	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL not set; skipping PostgreSQL integration tests")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	admin, err := pgx.Connect(ctx, dsn)
	require.NoError(t, err, "connect to the test database server")

	name := fmt.Sprintf("anve_test_%s", randomSuffix())
	_, err = admin.Exec(ctx, "CREATE DATABASE "+pgx.Identifier{name}.Sanitize())
	require.NoError(t, err, "create scratch database")
	t.Cleanup(func() {
		// FORCE terminates any connection a failed test left open.
		_, _ = admin.Exec(context.Background(),
			"DROP DATABASE "+pgx.Identifier{name}.Sanitize()+" WITH (FORCE)")
		_ = admin.Close(context.Background())
	})

	poolCfg, err := pgxpool.ParseConfig(dsn)
	require.NoError(t, err)
	poolCfg.ConnConfig.Database = name
	// The per-test pool is small and short-lived.
	poolCfg.MaxConns = 4
	pool, err := pgxpool.NewWithConfig(ctx, poolCfg)
	require.NoError(t, err)
	t.Cleanup(pool.Close)

	applyMigrations(t, pool)
	return pool
}

// applyMigrations runs every pending repository migration inside a
// transaction, mirroring the production migrator closely enough for the
// store tests (the migrator itself has its own dedicated tests).
func applyMigrations(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	ctx := context.Background()
	t.Helper()

	dir := migrationsDir()
	entries, err := os.ReadDir(dir)
	require.NoError(t, err, "read migrations directory")

	var files []string
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".up.sql") {
			files = append(files, e.Name())
		}
	}
	sort.Strings(files)
	require.NotEmpty(t, files, "no migrations found — the path is probably wrong")

	_, err = pool.Exec(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		version TEXT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())`)
	require.NoError(t, err)

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
		contents, err := os.ReadFile(filepath.Join(dir, name))
		require.NoError(t, err)
		tx, err := pool.Begin(ctx)
		require.NoError(t, err)
		_, err = tx.Exec(ctx, string(contents))
		require.NoError(t, err, "migration %s failed", name)
		_, err = tx.Exec(ctx, `INSERT INTO schema_migrations (version) VALUES ($1)`, version)
		require.NoError(t, err)
		require.NoError(t, tx.Commit(ctx))
	}
}

// migrationsDir resolves the repository's migration files relative to this
// package (apps/server/internal/testdb).
func migrationsDir() string {
	return filepath.Join("..", "..", "..", "..", "infra", "migrations")
}

func randomSuffix() string {
	buf := make([]byte, 4)
	if _, err := rand.Read(buf); err != nil {
		panic(fmt.Sprintf("generate scratch database suffix: %v", err))
	}
	return hex.EncodeToString(buf)
}
