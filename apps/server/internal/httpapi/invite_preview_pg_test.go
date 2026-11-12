package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/circles"
	"github.com/QuocDuong16/anve/server/internal/testdb"
)

func TestPGInvitePreviewIsAuthenticatedAccountBoundAndMinimal(t *testing.T) {
	ctx := context.Background()
	pool := testdb.Scratch(t)
	a := auth.NewService(auth.NewPGStore(pool), nil, true)
	c := circles.NewService(circles.NewPGStore(pool))
	owner, ot, err := a.DevSignIn(ctx, "owner-invite@example.com", "Owner", auth.DeviceRegistration{ClientDeviceID: "owner-invite"})
	require.NoError(t, err)
	joiner, jt, err := a.DevSignIn(ctx, "joiner-invite@example.com", "Joiner", auth.DeviceRegistration{ClientDeviceID: "joiner-invite"})
	require.NoError(t, err)
	circle, _, err := c.Create(ctx, owner.ID, "Nhà mình")
	require.NoError(t, err)
	invite, err := c.CreateInvite(ctx, owner.ID, circle.ID, nil)
	require.NoError(t, err)
	body, err := json.Marshal(map[string]string{"code": invite.Code})
	require.NoError(t, err)
	router := NewRouter(Deps{Auth: a, Circles: c})
	request := func(path, token, expected string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(body))
		if token != "" {
			r.Header.Set("Authorization", "Bearer "+token)
		}
		r.Header.Set("X-Anve-Expected-Owner", expected)
		w := httptest.NewRecorder()
		router.ServeHTTP(w, r)
		return w
	}
	for _, tc := range []struct{ token, expected string }{{"", joiner.ID.String()}, {jt.AccessToken, ""}, {jt.AccessToken, owner.ID.String()}} {
		require.Equal(t, http.StatusUnauthorized, request("/api/v1/invites/preview", tc.token, tc.expected).Code)
	}
	w := request("/api/v1/invites/preview", jt.AccessToken, joiner.ID.String())
	require.Equal(t, http.StatusOK, w.Code, w.Body.String())
	require.Equal(t, "no-store, private", w.Header().Get("Cache-Control"))
	var preview map[string]any
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &preview))
	require.Len(t, preview, 3)
	require.Equal(t, circle.ID.String(), preview["circleId"])
	require.Equal(t, circle.Name, preview["circleName"])
	require.NotContains(t, w.Body.String(), invite.Code)
	require.Equal(t, http.StatusUnauthorized, request("/api/v1/invites/redeem", ot.AccessToken, joiner.ID.String()).Code)
	read := httptest.NewRequest(http.MethodGet, "/api/v1/circles/mine", nil)
	read.Header.Set("Authorization", "Bearer "+ot.AccessToken)
	read.Header.Set("X-Anve-Expected-Owner", joiner.ID.String())
	readResponse := httptest.NewRecorder()
	router.ServeHTTP(readResponse, read)
	require.Equal(t, http.StatusUnauthorized, readResponse.Code)
	w = request("/api/v1/invites/redeem", jt.AccessToken, joiner.ID.String())
	require.Equal(t, http.StatusOK, w.Code, w.Body.String())
	require.Equal(t, http.StatusConflict, request("/api/v1/invites/preview", jt.AccessToken, joiner.ID.String()).Code)
}
