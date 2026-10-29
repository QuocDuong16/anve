package httpapi

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/places"
)

func TestPlaceBodyRejectsMissingFieldsAndAcceptsZeroCoordinates(t *testing.T) {
	for _, tc := range []struct {
		body string
		ok   bool
	}{
		{`{"name":"Home","latitude":0,"longitude":0,"radiusMeters":150}`, true},
		{`{"name":"Home","longitude":0,"radiusMeters":150}`, false},
		{`{"name":"Home","latitude":0,"longitude":0,"radiusMeters":150,"extra":true}`, false},
		{`{"name":"Home","latitude":0,"longitude":0,"radiusMeters":150} {}`, false},
	} {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/places", strings.NewReader(tc.body))
		_, err := decodePlace(httptest.NewRecorder(), req)
		if tc.ok {
			require.NoError(t, err)
		} else {
			require.ErrorIs(t, err, places.ErrInvalid)
		}
	}
}

func TestPlaceActorRejectsAccountSwitch(t *testing.T) {
	owner := uuid.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/places", nil)
	req = req.WithContext(WithAuthContext(req.Context(), AuthContext{User: auth.User{ID: owner}}))
	req.Header.Set("X-Anve-Expected-Owner", uuid.NewString())
	w := httptest.NewRecorder()
	_, ok := placeActor(w, req)
	require.False(t, ok)
	require.Equal(t, http.StatusUnauthorized, w.Code)
}
