package auth

import (
	"context"
	"errors"
	"fmt"

	"google.golang.org/api/idtoken"
)

// ErrGoogleNotConfigured is returned when the server has no OAuth client ID
// configured. Sign-in fails closed in that state.
var ErrGoogleNotConfigured = errors.New("google sign-in is not configured")

// IdentityVerifier turns a provider credential into a verified identity.
// The concrete implementation talks to Google; tests substitute a fake.
type IdentityVerifier interface {
	Verify(ctx context.Context, idToken string) (GoogleIdentity, error)
}

// googleVerifier validates Google ID tokens with the official idtoken
// package: signature against Google's JWKS, issuer and audience checks.
type googleVerifier struct {
	audience string
	validate func(ctx context.Context, idToken, audience string) (*idtoken.Payload, error)
}

// NewGoogleVerifier returns a verifier that only accepts tokens issued for
// the given OAuth client ID.
func NewGoogleVerifier(clientID string) (IdentityVerifier, error) {
	if clientID == "" {
		return nil, ErrGoogleNotConfigured
	}
	return &googleVerifier{
		audience: clientID,
		// Indirection so tests can exercise the payload mapping without
		// talking to Google; production keeps the real idtoken.Validate.
		validate: idtoken.Validate,
	}, nil
}

// Verify implements IdentityVerifier.
func (g *googleVerifier) Verify(ctx context.Context, raw string) (GoogleIdentity, error) {
	payload, err := g.validate(ctx, raw, g.audience)
	if err != nil {
		return GoogleIdentity{}, fmt.Errorf("validate google id token: %w", err)
	}

	getString := func(key string) string {
		if v, ok := payload.Claims[key].(string); ok {
			return v
		}
		return ""
	}
	getBool := func(key string) bool {
		if v, ok := payload.Claims[key].(bool); ok {
			return v
		}
		return false
	}

	identity := GoogleIdentity{
		Sub:           payload.Subject,
		Email:         getString("email"),
		EmailVerified: getBool("email_verified"),
		Name:          getString("name"),
		Picture:       getString("picture"),
	}
	if identity.Sub == "" || identity.Email == "" {
		return GoogleIdentity{}, errors.New("google id token has no subject or email claim")
	}
	// An unverified email must never become an account identity: it would
	// let a Google account claim an address it does not control.
	if !identity.EmailVerified {
		return GoogleIdentity{}, errors.New("google id token email is not verified")
	}
	return identity, nil
}
