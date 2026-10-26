// Package authtest provides an in-memory fake of the auth Store and the
// Google IdentityVerifier. It mirrors the semantics of the PostgreSQL
// implementation — one active session per device, revocation visible to
// Authenticate, every query scoped by user, audit events recorded with the
// business change — so service and handler tests can run without a
// database while still exercising real authorization behavior.
package authtest

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/QuocDuong16/anve/server/internal/audit"
	"github.com/QuocDuong16/anve/server/internal/auth"
)

// Store is the in-memory Store fake.
type Store struct {
	mu         sync.Mutex
	users      map[uuid.UUID]*auth.User
	devices    map[uuid.UUID]*auth.Device
	sessions   map[uuid.UUID]*sessionRow
	auditTrail []audit.Event
}

type sessionRow struct {
	auth.Session
	refreshHash []byte
	accessHash  []byte
	revokedAt   *time.Time
}

// NewStore returns an empty fake store.
func NewStore() *Store {
	return &Store{
		users:    map[uuid.UUID]*auth.User{},
		devices:  map[uuid.UUID]*auth.Device{},
		sessions: map[uuid.UUID]*sessionRow{},
	}
}

// RecordedActions returns the audit event actions seen so far, in order.
func (s *Store) RecordedActions() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]string, 0, len(s.auditTrail))
	for _, e := range s.auditTrail {
		out = append(out, e.Action)
	}
	return out
}

// SignIn mirrors the PG transaction: resolve user, upsert device, revoke
// the device's previous sessions, insert the new one, audit.
func (s *Store) SignIn(_ context.Context, in auth.SignInInput) (auth.User, auth.Session, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	var user *auth.User
	for _, u := range s.users {
		if in.GoogleSub != nil && u.GoogleSub != nil && *u.GoogleSub == *in.GoogleSub {
			user = u
			break
		}
	}
	if user == nil {
		for _, u := range s.users {
			if u.Email == in.Email {
				user = u
				break
			}
		}
	}
	switch {
	case user == nil:
		user = &auth.User{ID: uuid.Must(uuid.NewV7()), Email: in.Email,
			DisplayName: in.DisplayName, AvatarURL: in.AvatarURL, GoogleSub: in.GoogleSub,
			CreatedAt: time.Now().UTC()}
		s.users[user.ID] = user
	case in.GoogleSub != nil:
		// Identity isolation, mirroring the PG store: a Google identity that
		// fell through to the email lookup can only have found an account it
		// must not take over.
		return auth.User{}, auth.Session{}, fmt.Errorf(
			"%w: email %q already belongs to another identity", auth.ErrIdentityConflict, in.Email)
	case user.GoogleSub != nil:
		return auth.User{}, auth.Session{}, fmt.Errorf(
			"%w: email %q belongs to a Google account", auth.ErrIdentityConflict, in.Email)
	}

	var device *auth.Device
	for _, d := range s.devices {
		if d.UserID == user.ID && d.ClientDeviceID == in.Device.ClientDeviceID {
			device = d
			break
		}
	}
	if device == nil {
		device = &auth.Device{ID: uuid.Must(uuid.NewV7()), UserID: user.ID,
			ClientDeviceID: in.Device.ClientDeviceID, Platform: in.Device.Platform,
			Name: in.Device.Name, CreatedAt: time.Now().UTC()}
		s.devices[device.ID] = device
	}
	device.LastSeenAt = time.Now().UTC()

	now := time.Now().UTC()
	for _, sess := range s.sessions {
		if sess.DeviceID == device.ID && sess.revokedAt == nil {
			sess.revokedAt = &now
		}
	}

	row := &sessionRow{
		Session: auth.Session{
			ID: uuid.Must(uuid.NewV7()), UserID: user.ID, DeviceID: device.ID,
			RefreshExpiresAt: in.RefreshExpiresAt, AccessExpiresAt: in.AccessExpiresAt,
			CreatedAt: now, LastUsedAt: now,
		},
		refreshHash: in.RefreshTokenHash,
		accessHash:  in.AccessTokenHash,
	}
	s.sessions[row.ID] = row

	s.auditTrail = append(s.auditTrail, audit.Event{ActorUserID: user.ID, Action: in.AuditAction})
	return *user, row.Session, nil
}

// Authenticate resolves an access-token hash to a live session.
func (s *Store) Authenticate(_ context.Context, accessHash []byte) (auth.Session, auth.User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, row := range s.sessions {
		if string(row.accessHash) == string(accessHash) && row.revokedAt == nil &&
			row.AccessExpiresAt.After(time.Now()) {
			return row.Session, *s.users[row.UserID], nil
		}
	}
	return auth.Session{}, auth.User{}, auth.ErrUnauthenticated
}

// Refresh rotates both hashes of the owning session.
func (s *Store) Refresh(_ context.Context, in auth.RefreshInput) (auth.Session, auth.User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, row := range s.sessions {
		if string(row.refreshHash) == string(in.CurrentRefreshHash) && row.revokedAt == nil &&
			row.RefreshExpiresAt.After(time.Now()) {
			row.refreshHash = in.NewRefreshHash
			row.accessHash = in.NewAccessHash
			row.RefreshExpiresAt = in.NewRefreshExpires
			row.AccessExpiresAt = in.NewAccessExpires
			return row.Session, *s.users[row.UserID], nil
		}
	}
	return auth.Session{}, auth.User{}, auth.ErrUnauthenticated
}

// SignOut revokes one session and records the audit event, mirroring the
// PG store's single transaction.
func (s *Store) SignOut(_ context.Context, sessionID uuid.UUID, _ string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	row, ok := s.sessions[sessionID]
	if !ok || row.revokedAt != nil {
		return nil
	}
	now := time.Now().UTC()
	row.revokedAt = &now
	s.auditTrail = append(s.auditTrail, audit.Event{
		ActorUserID: row.UserID, Action: audit.ActionSignOut,
		Detail: map[string]any{"session_id": sessionID.String()},
	})
	return nil
}

// User returns one account.
func (s *Store) User(_ context.Context, userID uuid.UUID) (auth.User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if u, ok := s.users[userID]; ok {
		return *u, nil
	}
	return auth.User{}, auth.ErrNotFound
}

// UpdateProfile changes the display name.
func (s *Store) UpdateProfile(_ context.Context, userID uuid.UUID, displayName string) (auth.User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if u, ok := s.users[userID]; ok {
		u.DisplayName = displayName
		return *u, nil
	}
	return auth.User{}, auth.ErrNotFound
}

// SetLocationSharing flips consent and audits the change.
func (s *Store) SetLocationSharing(_ context.Context, userID uuid.UUID, enabled bool) (auth.User, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	u, ok := s.users[userID]
	if !ok {
		return auth.User{}, auth.ErrNotFound
	}
	u.LocationSharingEnabled = enabled
	action := audit.ActionLocationSharingOff
	if enabled {
		action = audit.ActionLocationSharingOn
	}
	s.auditTrail = append(s.auditTrail, audit.Event{ActorUserID: userID, Action: action})
	return *u, nil
}

// DeleteUser removes the account and cascades devices/sessions.
func (s *Store) DeleteUser(_ context.Context, userID uuid.UUID) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.users[userID]; !ok {
		return auth.ErrNotFound
	}
	delete(s.users, userID)
	for id, d := range s.devices {
		if d.UserID == userID {
			delete(s.devices, id)
		}
	}
	for id, row := range s.sessions {
		if row.UserID == userID {
			delete(s.sessions, id)
		}
	}
	s.auditTrail = append(s.auditTrail, audit.Event{ActorUserID: userID, Action: audit.ActionAccountDeleted})
	return nil
}

// ListDevices lists the user's devices with their live session.
func (s *Store) ListDevices(_ context.Context, userID uuid.UUID) ([]auth.DeviceWithSession, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []auth.DeviceWithSession
	for _, d := range s.devices {
		if d.UserID != userID {
			continue
		}
		entry := auth.DeviceWithSession{Device: *d}
		for _, row := range s.sessions {
			if row.DeviceID == d.ID && row.revokedAt == nil {
				sess := row.Session
				entry.Session = &sess
			}
		}
		out = append(out, entry)
	}
	return out, nil
}

// DeleteDevice removes a device of the same user only, cascading its
// sessions and recording the audit event.
func (s *Store) DeleteDevice(_ context.Context, userID, deviceID uuid.UUID) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	d, ok := s.devices[deviceID]
	if !ok || d.UserID != userID {
		return auth.ErrNotFound
	}
	delete(s.devices, deviceID)
	for id, row := range s.sessions {
		if row.DeviceID == deviceID {
			delete(s.sessions, id)
		}
	}
	s.auditTrail = append(s.auditTrail, audit.Event{
		ActorUserID: userID, Action: audit.ActionDeviceRevoked,
		Detail: map[string]any{"device_id": deviceID.String()},
	})
	return nil
}

// Verifier is a fake IdentityVerifier.
type Verifier struct {
	Identity auth.GoogleIdentity
	Err      error
}

// Verify implements auth.IdentityVerifier.
func (v Verifier) Verify(context.Context, string) (auth.GoogleIdentity, error) {
	return v.Identity, v.Err
}
