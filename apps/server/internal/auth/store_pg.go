package auth

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/QuocDuong16/anve/server/internal/audit"
)

// PGStore is the PostgreSQL implementation of Store.
type PGStore struct {
	pool *pgxpool.Pool
	// testAfterUserLock runs after the deletion transaction has taken the
	// user row lock. It is installed only by export_test.go helpers and is
	// nil in production; it lets concurrency tests stop the transaction at a
	// precise boundary instead of relying on timing.
	testAfterUserLock func()
}

// NewPGStore returns a Store backed by the given pool.
func NewPGStore(pool *pgxpool.Pool) *PGStore {
	return &PGStore{pool: pool}
}

const userColumns = `id, google_sub, email, display_name, avatar_url,
	location_sharing_enabled, location_sharing_version, created_at, updated_at`

func scanUser(row pgx.Row) (User, error) {
	var u User
	err := row.Scan(&u.ID, &u.GoogleSub, &u.Email, &u.DisplayName, &u.AvatarURL,
		&u.LocationSharingEnabled, &u.LocationSharingVersion, &u.CreatedAt, &u.UpdatedAt)
	return u, err
}

// SignIn completes the whole sign-in in one transaction: user upsert,
// device upsert, revocation of the device's previous sessions, new session
// insert and the audit row — a failure leaves none of them behind.
func (s *PGStore) SignIn(ctx context.Context, in SignInInput) (User, Session, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return User{}, Session{}, fmt.Errorf("begin sign-in transaction: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	user, err := resolveUser(ctx, tx, in)
	if err != nil {
		return User{}, Session{}, err
	}

	deviceID, err := upsertDevice(ctx, tx, user.ID, in.Device)
	if err != nil {
		return User{}, Session{}, err
	}

	// One active session per device: a new sign-in on this device supersedes
	// whatever session it held before (token rotation/re-login handling).
	if _, err := tx.Exec(ctx,
		`UPDATE sessions
		    SET revoked_at = now(), revoked_reason = 'superseded'
		  WHERE device_id = $1 AND revoked_at IS NULL`, deviceID); err != nil {
		return User{}, Session{}, fmt.Errorf("revoke previous device sessions: %w", err)
	}

	var session Session
	session.ID = uuid.Must(uuid.NewV7())
	session.UserID = user.ID
	session.DeviceID = deviceID
	err = tx.QueryRow(ctx,
		`INSERT INTO sessions (id, user_id, device_id,
				refresh_token_hash, refresh_expires_at,
				access_token_hash, access_expires_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7)
		 RETURNING created_at, last_used_at`,
		session.ID, session.UserID, session.DeviceID,
		in.RefreshTokenHash, in.RefreshExpiresAt,
		in.AccessTokenHash, in.AccessExpiresAt,
	).Scan(&session.CreatedAt, &session.LastUsedAt)
	if err != nil {
		return User{}, Session{}, fmt.Errorf("insert session: %w", err)
	}
	session.RefreshExpiresAt = in.RefreshExpiresAt
	session.AccessExpiresAt = in.AccessExpiresAt

	if err := audit.InTx(ctx, tx, audit.Event{
		ActorUserID: user.ID,
		Action:      in.AuditAction,
		Detail: map[string]any{
			"device_id": deviceID.String(),
			"platform":  in.Device.Platform,
		},
	}); err != nil {
		return User{}, Session{}, err
	}

	if err := tx.Commit(ctx); err != nil {
		return User{}, Session{}, fmt.Errorf("commit sign-in: %w", err)
	}
	return user, session, nil
}

// resolveUser finds or creates the user for a sign-in inside the caller's
// transaction. Matching order: google_sub first (returning Google users),
// then email — where identity isolation applies: a Google identity never
// takes over a development-created account and the development fixture
// never signs into a Google-verified account, because matching emails are
// not proof that two identities belong to the same subject.
func resolveUser(ctx context.Context, tx pgx.Tx, in SignInInput) (User, error) {
	if in.GoogleSub != nil {
		u, err := scanUser(tx.QueryRow(ctx,
			`SELECT `+userColumns+` FROM users WHERE google_sub = $1`, *in.GoogleSub))
		if err == nil {
			return refreshGoogleProfile(ctx, tx, u, in)
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return User{}, fmt.Errorf("lookup user by google_sub: %w", err)
		}
	}

	u, err := scanUser(tx.QueryRow(ctx,
		`SELECT `+userColumns+` FROM users WHERE email = $1 FOR UPDATE`, in.Email))
	switch {
	case err == nil:
		if in.GoogleSub != nil {
			// A Google identity reaching the email lookup means the account
			// was not created through Google (its google_sub would have
			// matched above) or belongs to another Google subject. Refuse
			// both: no linking by email.
			return User{}, fmt.Errorf("%w: email %q already belongs to another identity", ErrIdentityConflict, in.Email)
		}
		if u.GoogleSub != nil {
			return User{}, fmt.Errorf("%w: email %q belongs to a Google account", ErrIdentityConflict, in.Email)
		}
		return u, nil
	case errors.Is(err, pgx.ErrNoRows):
		// New account. Consent is OFF by default — the privacy foundation.
		sub := in.GoogleSub
		var id uuid.UUID
		if err := tx.QueryRow(ctx,
			`INSERT INTO users (google_sub, email, display_name, avatar_url)
			 VALUES ($1, $2, $3, $4)
			 RETURNING id`,
			sub, in.Email, in.DisplayName, in.AvatarURL).Scan(&id); err != nil {
			return User{}, fmt.Errorf("insert user: %w", err)
		}
		u, err := scanUser(tx.QueryRow(ctx,
			`SELECT `+userColumns+` FROM users WHERE id = $1`, id))
		if err != nil {
			return User{}, fmt.Errorf("read back new user: %w", err)
		}
		return u, nil
	default:
		return User{}, fmt.Errorf("lookup user by email: %w", err)
	}
}

// refreshGoogleProfile keeps display name and avatar in sync with the
// Google profile on later sign-ins; the email stays as created to avoid a
// unique violation when Google rotates addresses.
func refreshGoogleProfile(ctx context.Context, tx pgx.Tx, u User, in SignInInput) (User, error) {
	if _, err := tx.Exec(ctx,
		`UPDATE users SET display_name = $2, avatar_url = COALESCE($3, avatar_url), updated_at = now()
		  WHERE id = $1`,
		u.ID, in.DisplayName, in.AvatarURL); err != nil {
		return User{}, fmt.Errorf("update google profile: %w", err)
	}
	u.DisplayName = in.DisplayName
	if in.AvatarURL != nil {
		u.AvatarURL = in.AvatarURL
	}
	u.UpdatedAt = time.Now().UTC()
	return u, nil
}

func upsertDevice(ctx context.Context, tx pgx.Tx, userID uuid.UUID, d DeviceRegistration) (uuid.UUID, error) {
	name := d.Name
	if name == "" {
		name = "Thiết bị Android"
	}
	var id uuid.UUID
	err := tx.QueryRow(ctx,
		`INSERT INTO devices (user_id, client_device_id, name, platform, app_version)
		 VALUES ($1, $2, $3, $4, $5)
		 ON CONFLICT (user_id, client_device_id) DO UPDATE
		    SET name = EXCLUDED.name,
		        platform = EXCLUDED.platform,
		        app_version = EXCLUDED.app_version,
		        last_seen_at = now()
		 RETURNING id`,
		userID, d.ClientDeviceID, name, platform(d.Platform), d.AppVersion,
	).Scan(&id)
	if err != nil {
		return uuid.Nil, fmt.Errorf("upsert device: %w", err)
	}
	return id, nil
}

func platform(p string) string {
	if p == "" {
		return "android"
	}
	return p
}

// Authenticate resolves an access-token hash to a live session.
func (s *PGStore) Authenticate(ctx context.Context, accessHash []byte) (Session, User, error) {
	var sess Session
	var u User
	err := s.pool.QueryRow(ctx,
		`SELECT s.id, s.user_id, s.device_id, s.refresh_expires_at, s.access_expires_at,
		        s.created_at, s.last_used_at,
		        u.id, u.google_sub, u.email, u.display_name, u.avatar_url,
		        u.location_sharing_enabled, u.location_sharing_version, u.created_at, u.updated_at
		   FROM sessions s
		   JOIN users u ON u.id = s.user_id
		  WHERE s.access_token_hash = $1
		    AND s.revoked_at IS NULL
		    AND s.access_expires_at > now()`,
		accessHash,
	).Scan(&sess.ID, &sess.UserID, &sess.DeviceID, &sess.RefreshExpiresAt, &sess.AccessExpiresAt,
		&sess.CreatedAt, &sess.LastUsedAt,
		&u.ID, &u.GoogleSub, &u.Email, &u.DisplayName, &u.AvatarURL,
		&u.LocationSharingEnabled, &u.LocationSharingVersion, &u.CreatedAt, &u.UpdatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return Session{}, User{}, fmt.Errorf("%w: session not found, revoked or expired", ErrUnauthenticated)
	}
	if err != nil {
		return Session{}, User{}, fmt.Errorf("authenticate: %w", err)
	}

	// last_used_at drives the device list; a write per request is the
	// accepted cost of per-request verification (ADR 0002).
	if _, err := s.pool.Exec(ctx,
		`UPDATE sessions SET last_used_at = now() WHERE id = $1`, sess.ID); err != nil {
		// Observability only: the session itself is valid.
		slog.WarnContext(ctx, "touch session failed", "session_id", sess.ID, "error", err)
	}
	if _, err := s.pool.Exec(ctx,
		`UPDATE devices SET last_seen_at = now() WHERE id = $1`, sess.DeviceID); err != nil {
		slog.WarnContext(ctx, "touch device failed", "device_id", sess.DeviceID, "error", err)
	}
	return sess, u, nil
}

// Refresh rotates both token hashes of the session that owns the current
// refresh hash. FOR UPDATE keeps two concurrent refreshes from racing; the
// loser sees the already-rotated hash and fails as unauthenticated.
func (s *PGStore) Refresh(ctx context.Context, in RefreshInput) (Session, User, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Session{}, User{}, fmt.Errorf("begin refresh transaction: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var sess Session
	var u User
	err = tx.QueryRow(ctx,
		`SELECT s.id, s.user_id, s.device_id, s.refresh_expires_at, s.access_expires_at,
		        s.created_at, s.last_used_at,
		        u.id, u.google_sub, u.email, u.display_name, u.avatar_url,
		        u.location_sharing_enabled, u.location_sharing_version, u.created_at, u.updated_at
		   FROM sessions s
		   JOIN users u ON u.id = s.user_id
		  WHERE s.refresh_token_hash = $1
		    AND s.revoked_at IS NULL
		    AND s.refresh_expires_at > now()
		  FOR UPDATE OF s`,
		in.CurrentRefreshHash,
	).Scan(&sess.ID, &sess.UserID, &sess.DeviceID, &sess.RefreshExpiresAt, &sess.AccessExpiresAt,
		&sess.CreatedAt, &sess.LastUsedAt,
		&u.ID, &u.GoogleSub, &u.Email, &u.DisplayName, &u.AvatarURL,
		&u.LocationSharingEnabled, &u.LocationSharingVersion, &u.CreatedAt, &u.UpdatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return Session{}, User{}, fmt.Errorf("%w: refresh token not found, revoked or rotated", ErrUnauthenticated)
	}
	if err != nil {
		return Session{}, User{}, fmt.Errorf("refresh lookup: %w", err)
	}

	ct, err := tx.Exec(ctx,
		`UPDATE sessions
		    SET refresh_token_hash = $2, refresh_expires_at = $3,
		        access_token_hash = $4, access_expires_at = $5
		  WHERE id = $1`,
		sess.ID, in.NewRefreshHash, in.NewRefreshExpires, in.NewAccessHash, in.NewAccessExpires)
	if err != nil {
		return Session{}, User{}, fmt.Errorf("rotate session tokens: %w", err)
	}
	if ct.RowsAffected() != 1 {
		return Session{}, User{}, fmt.Errorf("%w: session vanished during refresh", ErrUnauthenticated)
	}

	if err := tx.Commit(ctx); err != nil {
		return Session{}, User{}, fmt.Errorf("commit refresh: %w", err)
	}
	sess.RefreshExpiresAt = in.NewRefreshExpires
	sess.AccessExpiresAt = in.NewAccessExpires
	return sess, u, nil
}

// SignOut revokes one session; the revocation and its audit row commit
// together, so a signed-out session can never exist without its record.
func (s *PGStore) SignOut(ctx context.Context, sessionID uuid.UUID, reason string) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin sign-out transaction: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var userID, deviceID uuid.UUID
	err = tx.QueryRow(ctx,
		`UPDATE sessions SET revoked_at = now(), revoked_reason = $2
		  WHERE id = $1 AND revoked_at IS NULL
		 RETURNING user_id, device_id`,
		sessionID, reason).Scan(&userID, &deviceID)
	if errors.Is(err, pgx.ErrNoRows) {
		// Already revoked: revoking twice is a no-op, and no second audit
		// row is written.
		return nil
	}
	if err != nil {
		return fmt.Errorf("revoke session: %w", err)
	}
	if err := audit.InTx(ctx, tx, audit.Event{
		ActorUserID: userID, Action: audit.ActionSignOut,
		Detail: map[string]any{"session_id": sessionID.String(), "device_id": deviceID.String()},
	}); err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit sign-out: %w", err)
	}
	return nil
}

// User returns one account.
func (s *PGStore) User(ctx context.Context, userID uuid.UUID) (User, error) {
	u, err := scanUser(s.pool.QueryRow(ctx,
		`SELECT `+userColumns+` FROM users WHERE id = $1`, userID))
	if errors.Is(err, pgx.ErrNoRows) {
		return User{}, fmt.Errorf("%w: user %s", ErrNotFound, userID)
	}
	if err != nil {
		return User{}, fmt.Errorf("lookup user: %w", err)
	}
	return u, nil
}

// UpdateProfile changes the display name.
func (s *PGStore) UpdateProfile(ctx context.Context, userID uuid.UUID, displayName string) (User, error) {
	u, err := scanUser(s.pool.QueryRow(ctx,
		`UPDATE users SET display_name = $2, updated_at = now()
		  WHERE id = $1
		  RETURNING `+userColumns,
		userID, displayName))
	if errors.Is(err, pgx.ErrNoRows) {
		return User{}, fmt.Errorf("%w: user %s", ErrNotFound, userID)
	}
	if err != nil {
		return User{}, fmt.Errorf("update profile: %w", err)
	}
	return u, nil
}

// SetLocationSharingAtVersion flips the consent switch if its version matches and writes the audit event in
// the same transaction: a consent change without its audit record must
// never commit.
func (s *PGStore) SetLocationSharingAtVersion(ctx context.Context, userID uuid.UUID, enabled bool, expectedVersion int64) (User, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return User{}, fmt.Errorf("begin consent transaction: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	u, err := scanUser(tx.QueryRow(ctx,
		`UPDATE users SET location_sharing_enabled = $2,
		                  location_sharing_version = location_sharing_version + 1,
	                  updated_at = now()
		  WHERE id = $1 AND location_sharing_version = $3
		  RETURNING `+userColumns,
		userID, enabled, expectedVersion))
	if errors.Is(err, pgx.ErrNoRows) {
		var exists bool
		if checkErr := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM users WHERE id = $1)`, userID).Scan(&exists); checkErr != nil {
			return User{}, fmt.Errorf("check user after consent conflict: %w", checkErr)
		}
		if !exists {
			return User{}, fmt.Errorf("%w: user %s", ErrNotFound, userID)
		}
		return User{}, ErrConsentVersionConflict
	}
	if err != nil {
		return User{}, fmt.Errorf("set location sharing: %w", err)
	}

	action := audit.ActionLocationSharingOff
	if enabled {
		action = audit.ActionLocationSharingOn
	}
	if err := audit.InTx(ctx, tx, audit.Event{ActorUserID: userID, Action: action}); err != nil {
		return User{}, err
	}

	if err := tx.Commit(ctx); err != nil {
		return User{}, fmt.Errorf("commit consent change: %w", err)
	}
	return u, nil
}

// DeleteUser removes the account; devices and sessions cascade, and the
// audit rows (no foreign key, by design) survive in the same transaction.
//
// A circle owner with other members must dissolve the circle explicitly
// first: deletion refuses with ErrCircleHasMembers instead of silently
// ending other people's circle. Circles the caller owns alone are removed
// by this transaction, each audited as a dissolution. The account row lock
// serializes the whole decision against circle creation, so a circle that
// was being created concurrently is either fully visible to the member
// count or fails to be created at all.
func (s *PGStore) DeleteUser(ctx context.Context, userID uuid.UUID) error {
	// The user-then-circle lock order can deadlock against a concurrent
	// invite redemption holding the invite row while inserting a membership
	// (each transaction ends up waiting on the other's row locks).
	// PostgreSQL aborts one of them; a retry then sees the committed state
	// and returns the precise outcome either way.
	var err error
	for attempt := 0; attempt < 3; attempt++ {
		err = s.deleteUser(ctx, userID)
		if !isDeadlock(err) {
			return err
		}
	}
	return err
}

// isDeadlock reports a PostgreSQL deadlock or serialization abort.
func isDeadlock(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && (pgErr.Code == "40P01" || pgErr.Code == "40001")
}

func (s *PGStore) deleteUser(ctx context.Context, userID uuid.UUID) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin deletion transaction: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	// Lock the account row first. Every circle creation takes a KEY SHARE
	// lock on this row through its foreign key, so this FOR UPDATE
	// serializes deletion against creation: a creation either commits
	// before this lock is granted (and the circle scan below sees it) or
	// waits here and then fails its foreign key once the user is gone.
	//
	// Without this lock the deletion could scan for owned circles, a
	// concurrent CreateCircle could commit, and the cascade would delete
	// that circle without ever counting its members or auditing its
	// dissolution — both APIs reporting success.
	var lockedID uuid.UUID
	err = tx.QueryRow(ctx,
		`SELECT id FROM users WHERE id = $1 FOR UPDATE`, userID).Scan(&lockedID)
	if errors.Is(err, pgx.ErrNoRows) {
		return fmt.Errorf("%w: user %s", ErrNotFound, userID)
	}
	if err != nil {
		return fmt.Errorf("lock user: %w", err)
	}
	if s.testAfterUserLock != nil {
		s.testAfterUserLock()
	}

	// Lock every circle the caller owns before counting their members. The
	// row lock conflicts with the KEY SHARE that an invite redemption's
	// membership insert needs on the circle row, so a concurrent redeem
	// either commits before the count below (and is seen) or blocks until
	// this transaction commits (and fails on the vanished circle) — there
	// is no window in which a member joins and the deletion still proceeds.
	ownedCircles, err := lockOwnedCircles(ctx, tx, userID)
	if err != nil {
		return err
	}
	for _, circleID := range ownedCircles {
		var others int
		if err := tx.QueryRow(ctx,
			`SELECT count(*) FROM memberships WHERE circle_id = $1 AND user_id != $2`,
			circleID, userID).Scan(&others); err != nil {
			return fmt.Errorf("count members: %w", err)
		}
		if others > 0 {
			return ErrCircleHasMembers
		}
	}

	ct, err := tx.Exec(ctx, `DELETE FROM users WHERE id = $1`, userID)
	if err != nil {
		return fmt.Errorf("delete user: %w", err)
	}
	if ct.RowsAffected() == 0 {
		return fmt.Errorf("%w: user %s", ErrNotFound, userID)
	}
	for _, circleID := range ownedCircles {
		if err := audit.InTx(ctx, tx, audit.Event{
			ActorUserID: userID, Action: audit.ActionCircleDissolved,
			Detail: map[string]any{"circle_id": circleID.String(), "via": "account_deletion"},
		}); err != nil {
			return err
		}
	}
	if err := audit.InTx(ctx, tx, audit.Event{ActorUserID: userID, Action: audit.ActionAccountDeleted}); err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit deletion: %w", err)
	}
	return nil
}

// lockOwnedCircles returns the ids of every circle created by the user,
// locked FOR UPDATE for the surrounding deletion transaction.
func lockOwnedCircles(ctx context.Context, tx pgx.Tx, userID uuid.UUID) ([]uuid.UUID, error) {
	rows, err := tx.Query(ctx,
		`SELECT id FROM circles WHERE created_by = $1 FOR UPDATE`, userID)
	if err != nil {
		return nil, fmt.Errorf("lock owned circles: %w", err)
	}
	defer rows.Close()
	var ids []uuid.UUID
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			return nil, fmt.Errorf("scan owned circle: %w", err)
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

// ListDevices returns every device of the account with its live session (at
// most one: sign-in supersedes the device's previous sessions).
func (s *PGStore) ListDevices(ctx context.Context, userID uuid.UUID) ([]DeviceWithSession, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT d.id, d.user_id, d.client_device_id, d.name, d.platform, d.app_version,
		        d.created_at, d.last_seen_at,
		        s.id, s.created_at, s.last_used_at, s.refresh_expires_at
		   FROM devices d
		   LEFT JOIN sessions s
		          ON s.device_id = d.id
		         AND s.revoked_at IS NULL
		         AND s.refresh_expires_at > now()
		  WHERE d.user_id = $1
		  ORDER BY d.last_seen_at DESC`, userID)
	if err != nil {
		return nil, fmt.Errorf("list devices: %w", err)
	}
	defer rows.Close()

	var out []DeviceWithSession
	for rows.Next() {
		var d Device
		var sessID *uuid.UUID
		var sCreated, sUsed, sExpires *time.Time
		if err := rows.Scan(&d.ID, &d.UserID, &d.ClientDeviceID, &d.Name, &d.Platform,
			&d.AppVersion, &d.CreatedAt, &d.LastSeenAt,
			&sessID, &sCreated, &sUsed, &sExpires); err != nil {
			return nil, fmt.Errorf("scan device row: %w", err)
		}
		entry := DeviceWithSession{Device: d}
		if sessID != nil {
			entry.Session = &Session{
				ID:               *sessID,
				UserID:           d.UserID,
				DeviceID:         d.ID,
				RefreshExpiresAt: *sExpires,
				CreatedAt:        *sCreated,
				LastUsedAt:       *sUsed,
			}
		}
		out = append(out, entry)
	}
	return out, rows.Err()
}

// DeleteDevice removes one device of the same account only. The deletion
// cascades the device's sessions and, together with its audit row, commits
// in one transaction.
func (s *PGStore) DeleteDevice(ctx context.Context, userID, deviceID uuid.UUID) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin delete device: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	ct, err := tx.Exec(ctx,
		`DELETE FROM devices WHERE id = $1 AND user_id = $2`, deviceID, userID)
	if err != nil {
		return fmt.Errorf("delete device: %w", err)
	}
	if ct.RowsAffected() == 0 {
		return fmt.Errorf("%w: device %s", ErrNotFound, deviceID)
	}
	if err := audit.InTx(ctx, tx, audit.Event{
		ActorUserID: userID, Action: audit.ActionDeviceRevoked,
		Detail: map[string]any{"device_id": deviceID.String()},
	}); err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit delete device: %w", err)
	}
	return nil
}
