// Command migrate applies the SQL migrations in infra/migrations to the
// database configured through DATABASE_URL.
//
// Usage:
//
//	migrate up     # apply every pending *.up.sql migration (default)
//	migrate down   # roll back the most recent applied migration
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	slog.SetDefault(logger)

	if err := run(); err != nil {
		logger.Error("migration failed", "error", err)
		os.Exit(1)
	}
}

func run() error {
	logger := slog.Default()

	databaseURL := os.Getenv("DATABASE_URL")
	if databaseURL == "" {
		return errors.New("DATABASE_URL is required")
	}
	migrationsDir := os.Getenv("MIGRATIONS_DIR")
	if migrationsDir == "" {
		migrationsDir = "migrations"
	}

	command := "up"
	if len(os.Args) > 1 {
		command = os.Args[1]
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		return fmt.Errorf("connect to database: %w", err)
	}
	defer pool.Close()

	lock, err := acquireMigrationLock(ctx, pool)
	if err != nil {
		return err
	}
	defer lock.release()

	if err := ensureBookkeepingTable(ctx, pool); err != nil {
		return err
	}

	switch command {
	case "up":
		return migrateUp(ctx, pool, migrationsDir, logger)
	case "down":
		return migrateDown(ctx, pool, migrationsDir, logger)
	default:
		return fmt.Errorf("unknown command %q (expected 'up' or 'down')", command)
	}
}

func ensureBookkeepingTable(ctx context.Context, pool *pgxpool.Pool) error {
	const stmt = `
		CREATE TABLE IF NOT EXISTS schema_migrations (
		    version    TEXT PRIMARY KEY,
		    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
		)`
	if _, err := pool.Exec(ctx, stmt); err != nil {
		return fmt.Errorf("ensure schema_migrations table: %w", err)
	}
	return nil
}

// migration is one migration file pair on disk.
type migration struct {
	version  string
	upPath   string
	downPath string
}

// loadMigrations reads the *.up.sql files from dir, pairing each one with its
// matching *.down.sql when that file exists.
func loadMigrations(dir string) ([]migration, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, fmt.Errorf("read migrations directory %q: %w", dir, err)
	}

	var migrations []migration
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".up.sql") {
			continue
		}
		version := strings.TrimSuffix(entry.Name(), ".up.sql")
		downPath := filepath.Join(dir, version+".down.sql")
		// #nosec G703 -- the migrations directory is operator-controlled, not
		// user input, and the file name comes from os.ReadDir on that same
		// directory, so it cannot escape it.
		if _, err := os.Stat(downPath); err != nil {
			downPath = ""
		}
		migrations = append(migrations, migration{
			version:  version,
			upPath:   filepath.Join(dir, entry.Name()),
			downPath: downPath,
		})
	}

	sort.Slice(migrations, func(i, j int) bool {
		return migrations[i].version < migrations[j].version
	})
	return migrations, nil
}

func appliedVersions(ctx context.Context, pool *pgxpool.Pool) (map[string]bool, error) {
	rows, err := pool.Query(ctx, `SELECT version FROM schema_migrations`)
	if err != nil {
		return nil, fmt.Errorf("query applied migrations: %w", err)
	}
	defer rows.Close()

	applied := make(map[string]bool)
	for rows.Next() {
		var version string
		if err := rows.Scan(&version); err != nil {
			return nil, fmt.Errorf("scan applied migration: %w", err)
		}
		applied[version] = true
	}
	return applied, rows.Err()
}

func migrateUp(ctx context.Context, pool *pgxpool.Pool, dir string, logger *slog.Logger) error {
	migrations, err := loadMigrations(dir)
	if err != nil {
		return err
	}
	applied, err := appliedVersions(ctx, pool)
	if err != nil {
		return err
	}

	for _, m := range migrations {
		if applied[m.version] {
			continue
		}
		if err := applyFile(ctx, pool, m.version, m.upPath, true); err != nil {
			return err
		}
		logger.Info("migration applied", "version", m.version)
	}
	return nil
}

func migrateDown(ctx context.Context, pool *pgxpool.Pool, dir string, logger *slog.Logger) error {
	migrations, err := loadMigrations(dir)
	if err != nil {
		return err
	}
	applied, err := appliedVersions(ctx, pool)
	if err != nil {
		return err
	}

	// Roll back the most recent applied migration.
	for i := len(migrations) - 1; i >= 0; i-- {
		m := migrations[i]
		if !applied[m.version] {
			continue
		}
		if m.downPath == "" {
			return fmt.Errorf("migration %s has no .down.sql file to roll back", m.version)
		}
		if err := applyFile(ctx, pool, m.version, m.downPath, false); err != nil {
			return err
		}
		logger.Info("migration rolled back", "version", m.version)
		return nil
	}

	logger.Info("no applied migration to roll back")
	return nil
}

// applyFile runs one migration file in a transaction and updates the
// bookkeeping table in the same transaction, so a failed migration never
// leaves the database half-migrated.
func applyFile(ctx context.Context, pool *pgxpool.Pool, version, path string, up bool) error {
	// #nosec G304 -- the path is built from the operator-controlled
	// migrations directory, never from request data.
	contents, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("read migration %q: %w", path, err)
	}

	tx, err := pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin transaction for %s: %w", version, err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	if _, err := tx.Exec(ctx, string(contents)); err != nil {
		return fmt.Errorf("execute migration %s: %w", version, err)
	}

	if up {
		_, err = tx.Exec(ctx,
			`INSERT INTO schema_migrations (version) VALUES ($1) ON CONFLICT (version) DO NOTHING`,
			version)
	} else {
		_, err = tx.Exec(ctx, `DELETE FROM schema_migrations WHERE version = $1`, version)
	}
	if err != nil {
		return fmt.Errorf("update schema_migrations for %s: %w", version, err)
	}

	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit migration %s: %w", version, err)
	}
	return nil
}

// migrationLockKey identifies the AnVề migrator in pg_locks. Any constant
// bigint works: pg advisory locks are scoped to one database, so concurrent
// migrators against the same database serialize while different databases
// migrate independently.
const migrationLockKey = 0x616e7665 // "anve"

// migrationLock is a session-level advisory lock held on a dedicated pooled
// connection for the lifetime of one migrator run.
type migrationLock struct {
	conn *pgxpool.Conn
}

// acquireMigrationLock blocks until it holds the migrator advisory lock, so
// two migrator processes can never race: both would read the same applied set
// and could apply the same migration twice in separate transactions. The run
// context bounds the wait — a stuck lock fails the run instead of hanging.
func acquireMigrationLock(ctx context.Context, pool *pgxpool.Pool) (*migrationLock, error) {
	conn, err := pool.Acquire(ctx)
	if err != nil {
		return nil, fmt.Errorf("acquire connection for migration lock: %w", err)
	}
	if _, err := conn.Exec(ctx, `SELECT pg_advisory_lock($1)`, migrationLockKey); err != nil {
		conn.Release()
		return nil, fmt.Errorf("acquire migration advisory lock: %w", err)
	}
	return &migrationLock{conn: conn}, nil
}

func (l *migrationLock) release() {
	// Unlock on the same session that took the lock. WithoutCancel so a
	// timed-out or canceled run still releases the lock instead of leaving it
	// held on an idle pooled connection.
	ctx := context.WithoutCancel(context.Background())
	_, _ = l.conn.Exec(ctx, `SELECT pg_advisory_unlock($1)`, migrationLockKey)
	l.conn.Release()
}
