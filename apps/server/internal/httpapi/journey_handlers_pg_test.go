package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/circles"
	"github.com/QuocDuong16/anve/server/internal/journeys"
	"github.com/QuocDuong16/anve/server/internal/locationaccess"
	"github.com/QuocDuong16/anve/server/internal/testdb"
)

func TestPGJourneyHTTPBindingAndStrictInput(t *testing.T) {
	ctx := context.Background()
	pool := testdb.Scratch(t)
	a := auth.NewService(auth.NewPGStore(pool), nil, true)
	c := circles.NewService(circles.NewPGStore(pool))
	owner, ot, err := a.DevSignIn(ctx, "journey-http-owner@example.com", "Owner", auth.DeviceRegistration{ClientDeviceID: "journey-http-owner"})
	require.NoError(t, err)
	viewer, vt, err := a.DevSignIn(ctx, "journey-http-viewer@example.com", "Viewer", auth.DeviceRegistration{ClientDeviceID: "journey-http-viewer"})
	require.NoError(t, err)
	circle, _, err := c.Create(ctx, owner.ID, "Family")
	require.NoError(t, err)
	invite, err := c.CreateInvite(ctx, owner.ID, circle.ID, nil)
	require.NoError(t, err)
	_, _, err = c.Redeem(ctx, viewer.ID, invite.Code)
	require.NoError(t, err)
	_, err = a.SetLocationSharing(ctx, owner.ID, true)
	require.NoError(t, err)
	_, err = locationaccess.NewPGStore(pool).Grant(ctx, owner.ID, viewer.ID)
	require.NoError(t, err)
	router := NewRouter(Deps{Auth: a, Journeys: journeys.NewStore(pool, 30)})
	request := func(method, path, token, expected string, payload []byte) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, bytes.NewReader(payload))
		r.Header.Set("Authorization", "Bearer "+token)
		r.Header.Set("X-Anve-Expected-Owner", expected)
		w := httptest.NewRecorder()
		router.ServeHTTP(w, r)
		return w
	}
	in := journeys.CreateRequest{JourneyID: uuid.New(), PreparedAt: time.Now().UTC(), DurationMinutes: 30, Destination: journeys.Destination{Label: "Nhà", Latitude: 0, Longitude: 0}, RecipientIDs: []uuid.UUID{viewer.ID}}
	body, err := json.Marshal(in)
	require.NoError(t, err)
	w := request(http.MethodPost, "/api/v1/journeys", ot.AccessToken, viewer.ID.String(), body)
	require.Equal(t, http.StatusUnauthorized, w.Code)
	var object map[string]any
	require.NoError(t, json.Unmarshal(body, &object))
	delete(object["destination"].(map[string]any), "latitude")
	missing, err := json.Marshal(object)
	require.NoError(t, err)
	w = request(http.MethodPost, "/api/v1/journeys", ot.AccessToken, owner.ID.String(), missing)
	require.Equal(t, http.StatusBadRequest, w.Code)
	w = request(http.MethodPost, "/api/v1/journeys", ot.AccessToken, owner.ID.String(), append(append([]byte{}, body...), []byte(" {}")...))
	require.Equal(t, http.StatusBadRequest, w.Code)
	w = request(http.MethodPost, "/api/v1/journeys", ot.AccessToken, owner.ID.String(), body)
	require.Equal(t, http.StatusCreated, w.Code, w.Body.String())
	require.Equal(t, "no-store, private", w.Header().Get("Cache-Control"))
	w = request(http.MethodPost, "/api/v1/journeys", ot.AccessToken, owner.ID.String(), body)
	require.Equal(t, http.StatusOK, w.Code, w.Body.String())
	path := "/api/v1/journeys/" + in.JourneyID.String()
	w = request(http.MethodGet, path, vt.AccessToken, owner.ID.String(), nil)
	require.Equal(t, http.StatusUnauthorized, w.Code)
	w = request(http.MethodGet, path, vt.AccessToken, viewer.ID.String(), nil)
	require.Equal(t, http.StatusOK, w.Code, w.Body.String())
	require.NotContains(t, w.Body.String(), "recipients")
	w = request(http.MethodPost, path+"/arrive", vt.AccessToken, viewer.ID.String(), nil)
	require.Equal(t, http.StatusNotFound, w.Code)
	w = request(http.MethodPost, path+"/arrive", ot.AccessToken, owner.ID.String(), nil)
	require.Equal(t, http.StatusNoContent, w.Code, w.Body.String())
	w = request(http.MethodPost, path+"/resolve", ot.AccessToken, owner.ID.String(), nil)
	require.Equal(t, http.StatusBadRequest, w.Code)
}
