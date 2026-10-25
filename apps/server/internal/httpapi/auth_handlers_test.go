package httpapi_test

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/audit"
	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/auth/authtest"
	"github.com/QuocDuong16/anve/server/internal/httpapi"
)

// testServer runs the full router with the real service on the fake store —
// exactly what production runs minus PostgreSQL.
type testServer struct {
	router http.Handler
	store  *authtest.Store
}

type signInTokens struct {
	AccessToken  string `json:"accessToken"`
	RefreshToken string `json:"refreshToken"`
}

type userBody struct {
	ID                     string `json:"id"`
	Email                  string `json:"email"`
	DisplayName            string `json:"displayName"`
	LocationSharingEnabled bool   `json:"locationSharingEnabled"`
}

func newTestServer(t *testing.T) *testServer {
	t.Helper()
	store := authtest.NewStore()
	svc := auth.NewService(store, nil, true, &authtest.Recorder{})
	return &testServer{
		router: httpapi.NewRouter(httpapi.Deps{Version: "test", Auth: svc, DB: fakePinger{}}),
		store:  store,
	}
}

// signInDev signs in through the mounted development endpoint and returns
// the token pair.
func (ts *testServer) signInDev(t *testing.T, email string) signInTokens {
	t.Helper()
	return ts.signInDevWith(t, email, "test-device-0001")
}

func (ts *testServer) signInDevWith(t *testing.T, email, deviceID string) signInTokens {
	t.Helper()
	body := map[string]any{
		"email":  email,
		"device": map[string]any{"clientDeviceId": deviceID, "platform": "android"},
	}
	rec := ts.do(t, http.MethodPost, "/api/v1/auth/dev-login", body, "")
	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
	var parsed struct {
		Session signInTokens `json:"session"`
	}
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &parsed))
	return parsed.Session
}

func (ts *testServer) do(t *testing.T, method, path string, body any, token string) *httptest.ResponseRecorder {
	t.Helper()
	var reader *bytes.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		require.NoError(t, err)
		reader = bytes.NewReader(encoded)
	} else {
		reader = bytes.NewReader(nil)
	}
	req := httptest.NewRequest(method, path, reader)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	ts.router.ServeHTTP(rec, req)
	return rec
}

func TestAuthConfigEndpoint(t *testing.T) {
	t.Parallel()
	ts := newTestServer(t)

	rec := ts.do(t, http.MethodGet, "/api/v1/auth/config", nil, "")
	require.Equal(t, http.StatusOK, rec.Code)

	var cfg struct {
		GoogleSignIn bool `json:"googleSignIn"`
		DevLogin     bool `json:"devLogin"`
	}
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &cfg))
	assert.False(t, cfg.GoogleSignIn, "no oauth client configured in tests")
	assert.True(t, cfg.DevLogin, "the test server mounts the dev login")
}

func TestGoogleSignInWithoutConfigurationFailsClosed(t *testing.T) {
	t.Parallel()
	ts := newTestServer(t)

	rec := ts.do(t, http.MethodPost, "/api/v1/auth/google", map[string]any{
		"idToken": "anything",
		"device":  map[string]any{"clientDeviceId": "test-device-0001"},
	}, "")

	require.Equal(t, http.StatusServiceUnavailable, rec.Code)
	var body struct {
		Code string `json:"code"`
	}
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &body))
	assert.Equal(t, "AUTH_NOT_CONFIGURED", body.Code)
}

func TestAuthenticatedFlowOverHTTP(t *testing.T) {
	t.Parallel()
	ts := newTestServer(t)

	tokens := ts.signInDev(t, "mai@example.com")

	rec := ts.do(t, http.MethodGet, "/api/v1/me", nil, tokens.AccessToken)
	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
	var me userBody
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &me))
	assert.Equal(t, "mai@example.com", me.Email)
	assert.False(t, me.LocationSharingEnabled)

	// Consent on is visible in the response.
	rec = ts.do(t, http.MethodPut, "/api/v1/me/location-sharing", map[string]any{"enabled": true}, tokens.AccessToken)
	require.Equal(t, http.StatusOK, rec.Code)
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &me))
	assert.True(t, me.LocationSharingEnabled)

	// Devices list shows the signing-in device as current.
	rec = ts.do(t, http.MethodGet, "/api/v1/devices", nil, tokens.AccessToken)
	require.Equal(t, http.StatusOK, rec.Code)
	var devices struct {
		Devices []struct {
			ID      string `json:"id"`
			Session *struct {
				Current bool `json:"current"`
			} `json:"session"`
		} `json:"devices"`
	}
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &devices))
	require.Len(t, devices.Devices, 1)
	assert.NotNil(t, devices.Devices[0].Session)
	assert.True(t, devices.Devices[0].Session.Current)

	// Sign out kills the session immediately.
	rec = ts.do(t, http.MethodPost, "/api/v1/auth/signout", nil, tokens.AccessToken)
	require.Equal(t, http.StatusNoContent, rec.Code)
	rec = ts.do(t, http.MethodGet, "/api/v1/me", nil, tokens.AccessToken)
	assert.Equal(t, http.StatusUnauthorized, rec.Code, "revocation is enforced per request")
}

func TestRefreshOverHTTPRotatesTokens(t *testing.T) {
	t.Parallel()
	ts := newTestServer(t)

	first := ts.signInDev(t, "a@b.co")
	rec := ts.do(t, http.MethodPost, "/api/v1/auth/refresh", map[string]any{"refreshToken": first.RefreshToken}, "")
	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
	var resp struct {
		Session signInTokens `json:"session"`
	}
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &resp))

	rec = ts.do(t, http.MethodGet, "/api/v1/me", nil, resp.Session.AccessToken)
	require.Equal(t, http.StatusOK, rec.Code)
	rec = ts.do(t, http.MethodGet, "/api/v1/me", nil, first.AccessToken)
	assert.Equal(t, http.StatusUnauthorized, rec.Code, "rotated-out access tokens stop working")

	rec = ts.do(t, http.MethodPost, "/api/v1/auth/refresh", map[string]any{"refreshToken": first.RefreshToken}, "")
	assert.Equal(t, http.StatusUnauthorized, rec.Code, "old refresh tokens cannot be replayed")
}

func TestUnauthenticatedRequestsAreRejected(t *testing.T) {
	t.Parallel()
	ts := newTestServer(t)
	ts.signInDev(t, "a@b.co")

	for _, tc := range []struct {
		name   string
		method string
		path   string
	}{
		{"me", http.MethodGet, "/api/v1/me"},
		{"patch me", http.MethodPatch, "/api/v1/me"},
		{"delete me", http.MethodDelete, "/api/v1/me"},
		{"consent", http.MethodPut, "/api/v1/me/location-sharing"},
		{"devices", http.MethodGet, "/api/v1/devices"},
		{"delete device", http.MethodDelete, "/api/v1/devices/00000000-0000-0000-0000-000000000000"},
		{"signout", http.MethodPost, "/api/v1/auth/signout"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			rec := ts.do(t, tc.method, tc.path, nil, "")
			assert.Equal(t, http.StatusUnauthorized, rec.Code)
		})
	}

	rec := ts.do(t, http.MethodGet, "/api/v1/me", nil, "not-a-real-token")
	assert.Equal(t, http.StatusUnauthorized, rec.Code)
}

func TestCrossUserDeviceAccessIsRefused(t *testing.T) {
	t.Parallel()
	ts := newTestServer(t)

	ts.signInDev(t, "a@x.co")
	tokensB := ts.signInDevWith(t, "b@x.co", "device-of-b-0001")

	rec := ts.do(t, http.MethodGet, "/api/v1/devices", nil, tokensB.AccessToken)
	require.Equal(t, http.StatusOK, rec.Code)
	var devices struct {
		Devices []struct {
			ID string `json:"id"`
		} `json:"devices"`
	}
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &devices))
	require.Len(t, devices.Devices, 1, "user B sees only their own device")

	fabricated := "11111111-1111-1111-1111-111111111111"
	rec = ts.do(t, http.MethodDelete, "/api/v1/devices/"+fabricated, nil, tokensB.AccessToken)
	assert.Equal(t, http.StatusNotFound, rec.Code)

	rec = ts.do(t, http.MethodDelete, "/api/v1/devices/"+devices.Devices[0].ID, nil, tokensB.AccessToken)
	require.Equal(t, http.StatusNoContent, rec.Code)
}

func TestMalformedBodiesAreRejected(t *testing.T) {
	t.Parallel()
	ts := newTestServer(t)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/dev-login", bytes.NewReader([]byte("not json")))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	ts.router.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusBadRequest, rec.Code)

	var body struct {
		Code string `json:"code"`
	}
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &body))
	assert.Equal(t, "VALIDATION", body.Code)
}

func TestDeleteAccountRequiresConfirmation(t *testing.T) {
	t.Parallel()
	ts := newTestServer(t)
	tokens := ts.signInDev(t, "gone@example.com")

	rec := ts.do(t, http.MethodDelete, "/api/v1/me", map[string]any{"confirm": false}, tokens.AccessToken)
	assert.Equal(t, http.StatusBadRequest, rec.Code)

	rec = ts.do(t, http.MethodDelete, "/api/v1/me", map[string]any{"confirm": true}, tokens.AccessToken)
	require.Equal(t, http.StatusNoContent, rec.Code)

	rec = ts.do(t, http.MethodGet, "/api/v1/me", nil, tokens.AccessToken)
	assert.Equal(t, http.StatusUnauthorized, rec.Code, "deletion cascades to sessions")
}

func TestConsentAuditTrailOverHTTP(t *testing.T) {
	t.Parallel()
	ts := newTestServer(t)
	tokens := ts.signInDev(t, "audit@example.com")

	rec := ts.do(t, http.MethodPut, "/api/v1/me/location-sharing", map[string]any{"enabled": true}, tokens.AccessToken)
	require.Equal(t, http.StatusOK, rec.Code)

	actions := ts.store.RecordedActions()
	assert.Contains(t, actions, audit.ActionLocationSharingOn)
	assert.Contains(t, actions, audit.ActionDevSignIn)
}

func TestSignInRateLimit(t *testing.T) {
	t.Parallel()
	ts := newTestServer(t)

	limited := 0
	for i := 0; i < 40; i++ {
		rec := ts.do(t, http.MethodPost, "/api/v1/auth/dev-login", map[string]any{
			"email":  "burst@example.com",
			"device": map[string]any{"clientDeviceId": "burst-device-01"},
		}, "")
		if rec.Code == http.StatusTooManyRequests {
			limited = i
			var body struct {
				Code string `json:"code"`
			}
			require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &body))
			assert.Equal(t, "RATE_LIMITED", body.Code)
			break
		}
	}
	assert.Positive(t, limited, "the limiter must eventually engage")
}

func TestDevLoginNotMountedIs404(t *testing.T) {
	t.Parallel()
	store := authtest.NewStore()
	svc := auth.NewService(store, nil, false, &authtest.Recorder{})
	router := httpapi.NewRouter(httpapi.Deps{Version: "test", Auth: svc, DB: fakePinger{}})

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/dev-login", bytes.NewReader([]byte(`{}`)))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	assert.Equal(t, http.StatusNotFound, rec.Code, "production-style servers never expose the dev login")
}
