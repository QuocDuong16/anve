package auth_test

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/audit"
	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/auth/authtest"
)

func devDevice() auth.DeviceRegistration {
	return auth.DeviceRegistration{ClientDeviceID: "device-0001", Name: "Pixel 8", Platform: "android", AppVersion: "0.2.0"}
}

// newTestService wires the real service on the in-memory store; google
// defaults to nil (Google disabled), dev login is mounted.
func newTestService(store auth.Store, verifier auth.IdentityVerifier) *auth.Service {
	return auth.NewService(store, verifier, true)
}

func TestDevSignInIssuesSession(t *testing.T) {
	t.Parallel()
	store := authtest.NewStore()
	svc := newTestService(store, authtest.Verifier{})

	user, tokens, err := svc.DevSignIn(context.Background(), "Me@Example.com", "", devDevice())
	require.NoError(t, err)
	assert.NotEmpty(t, tokens.AccessToken)
	assert.NotEmpty(t, tokens.RefreshToken)
	assert.NotEqual(t, tokens.AccessToken, tokens.RefreshToken)
	assert.False(t, user.LocationSharingEnabled, "consent must default to OFF")
	assert.Equal(t, "me@example.com", user.Email, "email is normalized")
	assert.Equal(t, "me", user.DisplayName, "display name derives from the lowercased email local part")

	// The session token authenticates.
	_, authed, err := svc.Authenticate(context.Background(), tokens.AccessToken)
	require.NoError(t, err)
	assert.Equal(t, user.ID, authed.ID)
}

func TestDevSignInDisabledWhenNotMounted(t *testing.T) {
	t.Parallel()
	svc := auth.NewService(authtest.NewStore(), nil, false)

	_, _, err := svc.DevSignIn(context.Background(), "a@b.co", "", devDevice())
	require.ErrorIs(t, err, auth.ErrNotFound)
}

func TestDevSignInValidation(t *testing.T) {
	t.Parallel()
	svc := newTestService(authtest.NewStore(), authtest.Verifier{})

	tests := []struct {
		name   string
		email  string
		device auth.DeviceRegistration
	}{
		{"empty email", "", devDevice()},
		{"no at", "plainaddress", devDevice()},
		{"double at", "a@@b.co", devDevice()},
		{"short device id", "a@b.co", auth.DeviceRegistration{ClientDeviceID: "short"}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			_, _, err := svc.DevSignIn(context.Background(), tc.email, "", tc.device)
			require.ErrorIs(t, err, auth.ErrValidation)
		})
	}
}

func TestGoogleSignInRequiresConfiguration(t *testing.T) {
	t.Parallel()
	svc := auth.NewService(authtest.NewStore(), nil, false)

	_, _, err := svc.SignInWithGoogle(context.Background(), "token", devDevice())
	require.ErrorIs(t, err, auth.ErrGoogleNotConfigured)
}

func TestGoogleSignInRejectsFailedVerification(t *testing.T) {
	t.Parallel()
	svc := newTestService(authtest.NewStore(), authtest.Verifier{Err: errors.New("bad signature")})

	_, _, err := svc.SignInWithGoogle(context.Background(), "token", devDevice())
	require.ErrorIs(t, err, auth.ErrUnauthenticated)
}

func TestGoogleAndDevIdentitiesAreIsolated(t *testing.T) {
	t.Parallel()

	// A development account exists first; a Google sign-in for the same
	// email must not take it over — matching emails are not proof of the
	// same subject (ADR 0004).
	store := authtest.NewStore()
	devSvc := newTestService(store, authtest.Verifier{})
	_, _, err := devSvc.DevSignIn(context.Background(), "mai@example.com", "Mai", devDevice())
	require.NoError(t, err)

	googleSvc := newTestService(store, authtest.Verifier{Identity: auth.GoogleIdentity{
		Sub: "google-sub-123", Email: "mai@example.com", EmailVerified: true, Name: "Mai A",
	}})
	_, _, err = googleSvc.SignInWithGoogle(context.Background(), "gtoken", devDevice())
	require.ErrorIs(t, err, auth.ErrIdentityConflict, "google may not enter a dev-created account")

	// And the reverse: the development fixture may not sign into a
	// Google-verified account.
	googleOnly := authtest.NewStore()
	googleSvc2 := newTestService(googleOnly, authtest.Verifier{Identity: auth.GoogleIdentity{
		Sub: "google-sub-456", Email: "ba@example.com", EmailVerified: true, Name: "Ba",
	}})
	_, _, err = googleSvc2.SignInWithGoogle(context.Background(), "gtoken", devDevice())
	require.NoError(t, err)

	devSvc2 := newTestService(googleOnly, authtest.Verifier{})
	_, _, err = devSvc2.DevSignIn(context.Background(), "ba@example.com", "Ba", devDevice())
	require.ErrorIs(t, err, auth.ErrIdentityConflict, "dev login may not enter a Google account")
}

func TestRefreshRotatesTokens(t *testing.T) {
	t.Parallel()
	svc := newTestService(authtest.NewStore(), authtest.Verifier{})

	_, tokens, err := svc.DevSignIn(context.Background(), "a@b.co", "", devDevice())
	require.NoError(t, err)

	rotated, err := svc.Refresh(context.Background(), tokens.RefreshToken)
	require.NoError(t, err)
	assert.NotEqual(t, tokens.AccessToken, rotated.AccessToken)
	assert.NotEqual(t, tokens.RefreshToken, rotated.RefreshToken)

	// Rotation replaces the stored hashes, so both old tokens stop working.
	_, _, err = svc.Authenticate(context.Background(), tokens.AccessToken)
	require.ErrorIs(t, err, auth.ErrUnauthenticated)
	_, err = svc.Refresh(context.Background(), tokens.RefreshToken)
	require.ErrorIs(t, err, auth.ErrUnauthenticated)

	// The new pair works.
	_, _, err = svc.Authenticate(context.Background(), rotated.AccessToken)
	require.NoError(t, err)
}

func TestSecondSignInOnSameDeviceSupersedesOldSession(t *testing.T) {
	t.Parallel()
	svc := newTestService(authtest.NewStore(), authtest.Verifier{})

	_, first, err := svc.DevSignIn(context.Background(), "a@b.co", "", devDevice())
	require.NoError(t, err)
	_, second, err := svc.DevSignIn(context.Background(), "a@b.co", "", devDevice())
	require.NoError(t, err)

	_, _, err = svc.Authenticate(context.Background(), first.AccessToken)
	require.ErrorIs(t, err, auth.ErrUnauthenticated, "the previous session of the device is revoked")
	_, _, err = svc.Authenticate(context.Background(), second.AccessToken)
	require.NoError(t, err)
}

func TestTwoDevicesHoldIndependentSessions(t *testing.T) {
	t.Parallel()
	svc := newTestService(authtest.NewStore(), authtest.Verifier{})

	other := auth.DeviceRegistration{ClientDeviceID: "device-0002", Platform: "android"}
	_, desktopTokens, err := svc.DevSignIn(context.Background(), "a@b.co", "", devDevice())
	require.NoError(t, err)
	_, phoneTokens, err := svc.DevSignIn(context.Background(), "a@b.co", "", other)
	require.NoError(t, err)

	// Both sessions are live at once.
	_, _, err = svc.Authenticate(context.Background(), desktopTokens.AccessToken)
	require.NoError(t, err)
	_, _, err = svc.Authenticate(context.Background(), phoneTokens.AccessToken)
	require.NoError(t, err)
}

func TestSignOutRevokesSession(t *testing.T) {
	t.Parallel()
	svc := newTestService(authtest.NewStore(), authtest.Verifier{})

	_, tokens, err := svc.DevSignIn(context.Background(), "a@b.co", "", devDevice())
	require.NoError(t, err)
	session, _, err := svc.Authenticate(context.Background(), tokens.AccessToken)
	require.NoError(t, err)

	require.NoError(t, svc.SignOut(context.Background(), session))
	_, _, err = svc.Authenticate(context.Background(), tokens.AccessToken)
	require.ErrorIs(t, err, auth.ErrUnauthenticated)

	// Even the refresh token cannot resurrect a signed-out session.
	_, err = svc.Refresh(context.Background(), tokens.RefreshToken)
	require.ErrorIs(t, err, auth.ErrUnauthenticated)
}

func TestDeleteAccountRemovesEverything(t *testing.T) {
	t.Parallel()
	svc := newTestService(authtest.NewStore(), authtest.Verifier{})

	user, tokens, err := svc.DevSignIn(context.Background(), "a@b.co", "", devDevice())
	require.NoError(t, err)

	require.NoError(t, svc.DeleteAccount(context.Background(), user.ID))

	_, _, err = svc.Authenticate(context.Background(), tokens.AccessToken)
	require.ErrorIs(t, err, auth.ErrUnauthenticated)
	_, err = svc.Profile(context.Background(), user.ID)
	require.ErrorIs(t, err, auth.ErrNotFound)
}

func TestSetLocationSharingAuditsBothDirections(t *testing.T) {
	t.Parallel()
	store := authtest.NewStore()
	svc := newTestService(store, authtest.Verifier{})

	user, _, err := svc.DevSignIn(context.Background(), "a@b.co", "", devDevice())
	require.NoError(t, err)

	on, err := svc.SetLocationSharing(context.Background(), user.ID, true)
	require.NoError(t, err)
	assert.True(t, on.LocationSharingEnabled)

	off, err := svc.SetLocationSharing(context.Background(), user.ID, false)
	require.NoError(t, err)
	assert.False(t, off.LocationSharingEnabled)

	assert.Contains(t, store.RecordedActions(), audit.ActionLocationSharingOn)
	assert.Contains(t, store.RecordedActions(), audit.ActionLocationSharingOff)
}

func TestRevokeDeviceIsScopedToOwner(t *testing.T) {
	t.Parallel()
	store := authtest.NewStore()
	svc := newTestService(store, authtest.Verifier{})

	_, tokensA, err := svc.DevSignIn(context.Background(), "a@x.co", "", devDevice())
	require.NoError(t, err)
	_, _, err = svc.DevSignIn(context.Background(), "b@x.co", "", auth.DeviceRegistration{ClientDeviceID: "device-b-01"})
	require.NoError(t, err)

	devices, err := svc.ListDevices(context.Background(), mustUser(t, svc, tokensA.AccessToken).ID, uuid.Nil)
	require.NoError(t, err)
	require.Len(t, devices, 1, "user A sees only their own device")

	// A cannot touch B's device id: scoped delete yields ErrNotFound.
	var deviceB uuid.UUID
	for _, d := range mustList(t, svc, "b@x.co") {
		deviceB = d
	}
	err = svc.RevokeDevice(context.Background(), mustUser(t, svc, tokensA.AccessToken).ID, deviceB)
	require.ErrorIs(t, err, auth.ErrNotFound)
}

func TestUpdateProfileValidation(t *testing.T) {
	t.Parallel()
	svc := newTestService(authtest.NewStore(), authtest.Verifier{})
	user, _, err := svc.DevSignIn(context.Background(), "a@b.co", "", devDevice())
	require.NoError(t, err)

	_, err = svc.UpdateProfile(context.Background(), user.ID, "")
	require.ErrorIs(t, err, auth.ErrValidation)
	_, err = svc.UpdateProfile(context.Background(), user.ID, "   ")
	require.ErrorIs(t, err, auth.ErrValidation)

	updated, err := svc.UpdateProfile(context.Background(), user.ID, "  Mai Anh  ")
	require.NoError(t, err)
	assert.Equal(t, "Mai Anh", updated.DisplayName, "display name is trimmed")
}

func mustUser(t *testing.T, svc *auth.Service, accessToken string) auth.User {
	t.Helper()
	_, u, err := svc.Authenticate(context.Background(), accessToken)
	require.NoError(t, err)
	return u
}

// mustList signs in freshly (creating a session) and returns the user's
// device ids.
func mustList(t *testing.T, svc *auth.Service, email string) []uuid.UUID {
	t.Helper()
	_, tokens, err := svc.DevSignIn(context.Background(), email, "", auth.DeviceRegistration{ClientDeviceID: "device-b-01"})
	require.NoError(t, err)
	user := mustUser(t, svc, tokens.AccessToken)
	devices, err := svc.ListDevices(context.Background(), user.ID, uuid.Nil)
	require.NoError(t, err)
	ids := make([]uuid.UUID, 0, len(devices))
	for _, d := range devices {
		ids = append(ids, d.Device.ID)
	}
	return ids
}
