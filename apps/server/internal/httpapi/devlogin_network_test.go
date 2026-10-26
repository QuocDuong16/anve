package httpapi_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/auth/authtest"
	"github.com/QuocDuong16/anve/server/internal/httpapi"
)

// The development login is a session-minting fixture. These tests pin its
// second gate independently of the listener: the source address must be in
// the configured allowlist, no matter what proxy headers claim.

func devLoginFrom(ts *testServer, remoteAddr string, headers map[string]string) *httptest.ResponseRecorder {
	body := `{"email":"fixture@example.com","device":{"clientDeviceId":"network-test-0001"}}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/dev-login", strings.NewReader(body))
	req.RemoteAddr = remoteAddr
	req.Header.Set("Content-Type", "application/json")
	for name, value := range headers {
		req.Header.Set(name, value)
	}
	rec := httptest.NewRecorder()
	ts.router.ServeHTTP(rec, req)
	return rec
}

func errorCode(t *testing.T, rec *httptest.ResponseRecorder) string {
	t.Helper()
	var body struct {
		Code string `json:"code"`
	}
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &body))
	return body.Code
}

func TestDevLoginAllowsLoopbackSource(t *testing.T) {
	t.Parallel()
	ts := newTestServer(t)

	rec := devLoginFrom(ts, "127.0.0.1:54321", nil)
	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
}

func TestDevLoginAllowsIPv6AndMappedLoopback(t *testing.T) {
	t.Parallel()
	for _, addr := range []string{"[::1]:54321", "[::ffff:127.0.0.1]:54321"} {
		t.Run(addr, func(t *testing.T) {
			ts := newTestServer(t)
			rec := devLoginFrom(ts, addr, nil)
			require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
		})
	}
}

func TestDevLoginRejectsForeignSourceEvenWithSpoofedProxyHeaders(t *testing.T) {
	t.Parallel()
	ts := newTestServer(t)

	rec := devLoginFrom(ts, "192.0.2.10:54321", map[string]string{
		"X-Forwarded-For": "127.0.0.1",
		"X-Real-IP":       "127.0.0.1",
	})
	require.Equal(t, http.StatusForbidden, rec.Code)
	assert.Equal(t, "DEV_LOGIN_NETWORK_DENIED", errorCode(t, rec))
	assert.Empty(t, ts.store.RecordedActions(), "no session or audit row may be created")
}

func TestDevLoginEmptyAllowlistFailsClosed(t *testing.T) {
	t.Parallel()
	ts := newTestServerWithSources(t, nil)

	rec := devLoginFrom(ts, "127.0.0.1:54321", nil)
	require.Equal(t, http.StatusForbidden, rec.Code)
	assert.Equal(t, "DEV_LOGIN_NETWORK_DENIED", errorCode(t, rec))
}

func TestDevLoginAllowsConfiguredContainerSubnet(t *testing.T) {
	t.Parallel()
	ts := newTestServerWithSources(t, []netip.Prefix{
		netip.MustParsePrefix("127.0.0.0/8"),
		netip.MustParsePrefix("172.28.0.0/24"),
	})

	rec := devLoginFrom(ts, "172.28.0.1:41234", nil)
	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
}

func TestDevLoginNotMountedWhenDisabled(t *testing.T) {
	t.Parallel()

	store := authtest.NewStore()
	svc := auth.NewService(store, nil, false)
	router := httpapi.NewRouter(httpapi.Deps{
		Version: "test", Auth: svc, DB: fakePinger{},
		DevLoginAllowedNetworks: loopbackSources,
	})

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/dev-login",
		strings.NewReader(`{"email":"fixture@example.com","device":{"clientDeviceId":"d"}}`))
	req.RemoteAddr = "127.0.0.1:54321"
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	assert.Equal(t, http.StatusNotFound, rec.Code)
	assert.Empty(t, store.RecordedActions())
}
