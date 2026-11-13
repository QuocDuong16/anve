package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"image"
	"image/jpeg"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/avatars"
	"github.com/QuocDuong16/anve/server/internal/circles"
	"github.com/QuocDuong16/anve/server/internal/testdb"
)

type avatarFixture struct {
	pool                    *pgxpool.Pool
	auth                    *auth.Service
	circles                 *circles.Service
	store                   *avatars.Store
	router                  http.Handler
	owner, viewer           auth.User
	ownerToken, viewerToken auth.SessionTokens
	principal               avatars.Principal
	circle                  circles.Circle
	image                   []byte
}

func setupAvatar(t *testing.T) avatarFixture {
	t.Helper()
	ctx := context.Background()
	f := avatarFixture{pool: testdb.Scratch(t)}
	f.auth = auth.NewService(auth.NewPGStore(f.pool), nil, true)
	f.circles = circles.NewService(circles.NewPGStore(f.pool))
	f.store = avatars.NewStore(f.pool)
	var err error
	f.owner, f.ownerToken, err = f.auth.DevSignIn(ctx, "avatar-owner@example.test", "An", auth.DeviceRegistration{ClientDeviceID: "avatar-owner"})
	require.NoError(t, err)
	f.viewer, f.viewerToken, err = f.auth.DevSignIn(ctx, "avatar-viewer@example.test", "Mai", auth.DeviceRegistration{ClientDeviceID: "avatar-viewer"})
	require.NoError(t, err)
	session, _, err := f.auth.Authenticate(ctx, f.ownerToken.AccessToken)
	require.NoError(t, err)
	f.principal = avatars.Principal{UserID: f.owner.ID, SessionID: session.ID}
	f.circle, _, err = f.circles.Create(ctx, f.owner.ID, "Family")
	require.NoError(t, err)
	f.router = NewRouter(Deps{Auth: f.auth, Circles: f.circles, Avatars: f.store})
	var imageData bytes.Buffer
	require.NoError(t, jpeg.Encode(&imageData, image.NewRGBA(image.Rect(0, 0, 300, 300)), nil))
	f.image = imageData.Bytes()
	return f
}

func (f avatarFixture) request(method, path, token, expected string, revision int64, operation uuid.UUID, body []byte) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, bytes.NewReader(body))
	r.Header.Set("Authorization", "Bearer "+token)
	r.Header.Set("X-Anve-Expected-Owner", expected)
	r.Header.Set("X-Anve-Expected-Revision", strconv.FormatInt(revision, 10))
	r.Header.Set("X-Anve-Operation-ID", operation.String())
	r.Header.Set("Content-Type", "image/jpeg")
	w := httptest.NewRecorder()
	f.router.ServeHTTP(w, r)
	return w
}

func TestPGAvatarHTTPBindingCurrentMembershipAndDeletion(t *testing.T) {
	f := setupAvatar(t)
	ctx := context.Background()
	operation := uuid.New()
	wrong := f.request(http.MethodPut, "/api/v1/me/avatar", f.ownerToken.AccessToken, f.viewer.ID.String(), 0, operation, f.image)
	require.Equal(t, http.StatusUnauthorized, wrong.Code)
	w := f.request(http.MethodGet, "/api/v1/me/avatar", f.ownerToken.AccessToken, f.owner.ID.String(), 0, operation, nil)
	require.JSONEq(t, `{"revision":0}`, w.Body.String())
	w = f.request(http.MethodPut, "/api/v1/me/avatar", f.ownerToken.AccessToken, f.owner.ID.String(), 0, operation, f.image)
	require.Equal(t, http.StatusOK, w.Code, w.Body.String())
	var state avatars.State
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &state))
	require.Equal(t, int64(1), state.Revision)
	require.Equal(t, &operation, state.ImageID)
	path := "/api/v1/avatars/" + f.owner.ID.String() + "/" + operation.String()
	require.Equal(t, http.StatusUnauthorized, f.request(http.MethodGet, path, "", f.viewer.ID.String(), 0, operation, nil).Code)
	require.Equal(t, http.StatusNotFound, f.request(http.MethodGet, path, f.viewerToken.AccessToken, f.viewer.ID.String(), 0, operation, nil).Code)
	invite, err := f.circles.CreateInvite(ctx, f.owner.ID, f.circle.ID, nil)
	require.NoError(t, err)
	_, _, err = f.circles.Redeem(ctx, f.viewer.ID, invite.Code)
	require.NoError(t, err)
	w = f.request(http.MethodGet, path, f.viewerToken.AccessToken, f.viewer.ID.String(), 0, operation, nil)
	require.Equal(t, http.StatusOK, w.Code, w.Body.String())
	require.Equal(t, "image/jpeg", w.Header().Get("Content-Type"))
	require.Equal(t, "nosniff", w.Header().Get("X-Content-Type-Options"))
	require.Equal(t, "no-store, private", w.Header().Get("Cache-Control"))
	_, err = jpeg.Decode(bytes.NewReader(w.Body.Bytes()))
	require.NoError(t, err)
	members, err := f.circles.Members(ctx, f.viewer.ID, f.circle.ID)
	require.NoError(t, err)
	for _, member := range members {
		if member.UserID == f.owner.ID {
			require.Equal(t, &operation, member.AvatarID)
		}
	}
	require.NoError(t, f.circles.Leave(ctx, f.viewer.ID, f.circle.ID, false))
	require.Equal(t, http.StatusNotFound, f.request(http.MethodGet, path, f.viewerToken.AccessToken, f.viewer.ID.String(), 0, operation, nil).Code)
	remove := uuid.New()
	w = f.request(http.MethodDelete, "/api/v1/me/avatar", f.ownerToken.AccessToken, f.owner.ID.String(), 1, remove, nil)
	require.Equal(t, http.StatusOK, w.Code, w.Body.String())
	state = avatars.State{}
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &state))
	require.Nil(t, state.ImageID)
	require.Equal(t, &remove, state.OperationID)
	require.Equal(t, int64(2), state.Revision)
	require.Equal(t, http.StatusNotFound, f.request(http.MethodGet, path, f.ownerToken.AccessToken, f.owner.ID.String(), 0, operation, nil).Code)
	w = f.request(http.MethodPut, "/api/v1/me/avatar", f.ownerToken.AccessToken, f.owner.ID.String(), 1, uuid.New(), f.image)
	require.Equal(t, http.StatusConflict, w.Code)
	var auditCount int
	require.NoError(t, f.pool.QueryRow(ctx, `SELECT count(*) FROM audit_log WHERE action LIKE 'profile.avatar.%'`).Scan(&auditCount))
	require.Equal(t, 2, auditCount)
	require.NoError(t, f.auth.DeleteAccount(ctx, f.owner.ID))
	require.NoError(t, f.pool.QueryRow(ctx, `SELECT count(*) FROM profile_avatars`).Scan(&auditCount))
	require.Zero(t, auditCount)
}

func TestPGAvatarConcurrentCASAndRevokedSession(t *testing.T) {
	f := setupAvatar(t)
	ctx := context.Background()
	var wg sync.WaitGroup
	results := make(chan error, 2)
	for range 2 {
		wg.Go(func() { _, err := f.store.Replace(ctx, f.principal, uuid.New(), 0, f.image); results <- err })
	}
	wg.Wait()
	close(results)
	confirmed, conflicts := 0, 0
	for err := range results {
		if err == nil {
			confirmed++
		} else {
			require.ErrorIs(t, err, avatars.ErrConflict)
			conflicts++
		}
	}
	require.Equal(t, 1, confirmed)
	require.Equal(t, 1, conflicts)
	state, err := f.store.State(ctx, f.principal)
	require.NoError(t, err)
	require.Equal(t, int64(1), state.Revision)
	_, err = f.pool.Exec(ctx, `UPDATE sessions SET revoked_at=clock_timestamp() WHERE id=$1`, f.principal.SessionID)
	require.NoError(t, err)
	_, err = f.store.Remove(ctx, f.principal, uuid.New(), 1)
	require.ErrorIs(t, err, auth.ErrUnauthenticated)
	var count int
	require.NoError(t, f.pool.QueryRow(ctx, `SELECT count(*) FROM audit_log WHERE action LIKE 'profile.avatar.%'`).Scan(&count))
	require.Equal(t, 1, count)
}

func TestPGAvatarRejectsInvalidAndOversizedBodiesWithoutMutation(t *testing.T) {
	f := setupAvatar(t)
	for _, tc := range []struct {
		body   []byte
		status int
	}{{[]byte("not an image"), 400}, {make([]byte, avatars.MaxInputBytes+1), 413}} {
		w := f.request(http.MethodPut, "/api/v1/me/avatar", f.ownerToken.AccessToken, f.owner.ID.String(), 0, uuid.New(), tc.body)
		require.Equal(t, tc.status, w.Code, w.Body.String())
	}
	state, err := f.store.State(context.Background(), f.principal)
	require.NoError(t, err)
	require.Zero(t, state.Revision)
}
