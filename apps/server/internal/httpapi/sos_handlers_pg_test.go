package httpapi

import (
	"bytes"
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/circles"
	"github.com/QuocDuong16/anve/server/internal/sos"
	"github.com/QuocDuong16/anve/server/internal/testdb"
)

func TestPGSosHandlersAuthenticateAndBindOwner(t *testing.T) {
	ctx := context.Background()
	pool := testdb.Scratch(t)
	a := auth.NewService(auth.NewPGStore(pool), nil, true)
	c := circles.NewService(circles.NewPGStore(pool))
	owner, ownerTokens, err := a.DevSignIn(ctx, "sos-http-owner@example.com", "Owner", auth.DeviceRegistration{ClientDeviceID: "sos-http-owner"})
	require.NoError(t, err)
	recipient, recipientTokens, err := a.DevSignIn(ctx, "sos-http-recipient@example.com", "Recipient", auth.DeviceRegistration{ClientDeviceID: "sos-http-recipient"})
	require.NoError(t, err)
	circle, _, err := c.Create(ctx, owner.ID, "Family")
	require.NoError(t, err)
	invite, err := c.CreateInvite(ctx, owner.ID, circle.ID, nil)
	require.NoError(t, err)
	_, _, err = c.Redeem(ctx, recipient.ID, invite.Code)
	require.NoError(t, err)
	router := NewRouter(Deps{Auth: a, SOS: sos.NewStore(pool)})
	id := uuid.New()
	body := fmt.Sprintf(`{"eventId":%q,"recordedAt":%q,"recipientIds":[%q]}`, id, time.Now().UTC().Format(time.RFC3339Nano), recipient.ID)
	request := func(method, path, token, ownerID, payload string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, path, bytes.NewBufferString(payload))
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("X-Anve-Expected-Owner", ownerID)
		w := httptest.NewRecorder()
		router.ServeHTTP(w, req)
		return w
	}
	path := "/api/v1/sos/" + id.String()
	w := request(http.MethodGet, "/api/v1/notifications/status", ownerTokens.AccessToken, recipient.ID.String(), "")
	require.Equal(t, http.StatusUnauthorized, w.Code)
	w = request(http.MethodGet, "/api/v1/notifications/status", ownerTokens.AccessToken, owner.ID.String(), "")
	require.Equal(t, http.StatusOK, w.Code)
	require.JSONEq(t, `{"configured":false}`, w.Body.String())
	w = request(http.MethodPost, "/api/v1/sos", ownerTokens.AccessToken, recipient.ID.String(), body)
	require.Equal(t, http.StatusUnauthorized, w.Code)
	w = request(http.MethodPost, "/api/v1/sos", ownerTokens.AccessToken, owner.ID.String(), body)
	require.Equal(t, http.StatusCreated, w.Code, w.Body.String())
	w = request(http.MethodPost, "/api/v1/sos", ownerTokens.AccessToken, owner.ID.String(), body)
	require.Equal(t, http.StatusOK, w.Code, w.Body.String())
	w = request(http.MethodPost, path+"/acknowledge", recipientTokens.AccessToken, recipient.ID.String(), "")
	require.Equal(t, http.StatusOK, w.Code, w.Body.String())
}
