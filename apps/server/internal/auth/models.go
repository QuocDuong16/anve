// Package auth implements sign-in, device identity and the session model of
// AnVề (ADR 0002): opaque tokens, hashed at rest, verified against the
// database on every request so revocation is immediate.
package auth

import (
	"time"

	"github.com/google/uuid"
)

// User is an AnVề account.
type User struct {
	ID                     uuid.UUID
	GoogleSub              *string
	Email                  string
	DisplayName            string
	AvatarURL              *string
	LocationSharingEnabled bool
	CreatedAt              time.Time
	UpdatedAt              time.Time
}

// Device is one registered device of an account.
type Device struct {
	ID             uuid.UUID
	UserID         uuid.UUID
	ClientDeviceID string
	Name           string
	Platform       string
	AppVersion     string
	CreatedAt      time.Time
	LastSeenAt     time.Time
}

// Session is the persisted state behind one access/refresh token pair.
// The tokens themselves exist only in the client's hands; the rows keep
// their SHA-256 hashes.
type Session struct {
	ID               uuid.UUID
	UserID           uuid.UUID
	DeviceID         uuid.UUID
	RefreshExpiresAt time.Time
	AccessExpiresAt  time.Time
	CreatedAt        time.Time
	LastUsedAt       time.Time
}

// DeviceRegistration is the client-supplied device description sent with
// every sign-in.
type DeviceRegistration struct {
	ClientDeviceID string
	Name           string
	Platform       string
	AppVersion     string
}

// SessionTokens is the plaintext token pair handed to the client exactly
// once, at sign-in or refresh.
type SessionTokens struct {
	AccessToken      string
	RefreshToken     string
	AccessExpiresAt  time.Time
	RefreshExpiresAt time.Time
}

// DeviceWithSession is a device paired with its active session, as shown in
// the account's device list.
type DeviceWithSession struct {
	Device  Device
	Session *Session
	Current bool // whether Session is the one serving the list request
}

// GoogleIdentity is the verified outcome of a Google ID token check.
type GoogleIdentity struct {
	Sub           string
	Email         string
	EmailVerified bool
	Name          string
	Picture       string
}
