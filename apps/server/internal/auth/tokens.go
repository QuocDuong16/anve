package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"time"
)

// Token lifetimes. The access token is short so a leaked bearer header has a
// small window; the refresh token is what keeps the user signed in. Both are
// revoked instantly through the sessions row regardless of remaining TTL.
const (
	AccessTokenTTL  = 30 * time.Minute
	RefreshTokenTTL = 30 * 24 * time.Hour
)

// newToken returns a fresh 256-bit opaque token in URL-safe base64.
func newToken() (plaintext string, hash []byte, err error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", nil, err
	}
	plaintext = base64.RawURLEncoding.EncodeToString(buf)
	return plaintext, hashToken(plaintext), nil
}

// hashToken derives the stored form of a token. Only the hash is persisted;
// the plaintext exists in memory and on the client.
func hashToken(plaintext string) []byte {
	h := sha256.Sum256([]byte(plaintext))
	return h[:]
}
