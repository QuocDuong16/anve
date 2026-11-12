package notifications

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
)

func TestFCMDataOnlyAndErrorClassification(t *testing.T) {
	status := http.StatusOK
	var payload map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		require.NoError(t, json.NewDecoder(r.Body).Decode(&payload))
		w.WriteHeader(status)
		if status == http.StatusNotFound {
			_, _ = w.Write([]byte(`{"error":{"details":[{"errorCode":"UNREGISTERED"}]}}`))
		}
	}))
	defer server.Close()
	f := &FCM{client: server.Client(), endpoint: server.URL}
	signal := Signal{EventID: uuid.New(), Kind: "SOS"}
	require.NoError(t, f.Send(context.Background(), "private-test-token", signal))
	message := payload["message"].(map[string]any)
	data := message["data"].(map[string]any)
	require.Len(t, data, 2)
	require.Equal(t, signal.EventID.String(), data["eventId"])
	raw, err := json.Marshal(data)
	require.NoError(t, err)
	require.NotContains(t, strings.ToLower(string(raw)), "latitude")
	require.NotContains(t, strings.ToLower(string(raw)), "longitude")
	status = http.StatusNotFound
	require.ErrorIs(t, f.Send(context.Background(), "private-test-token", signal), ErrInvalidToken)
	status = http.StatusServiceUnavailable
	err = f.Send(context.Background(), "private-test-token", signal)
	require.Error(t, err)
	require.NotContains(t, err.Error(), "private-test-token")
}

func TestFCMStaleEpisodeIsOpaqueAndStrictlyValidated(t *testing.T) {
	var data map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var payload struct {
			Message struct {
				Data map[string]any `json:"data"`
			} `json:"message"`
		}
		require.NoError(t, json.NewDecoder(r.Body).Decode(&payload))
		data = payload.Message.Data
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	f := &FCM{client: server.Client(), endpoint: server.URL}
	id := uuid.New()
	require.NoError(t, f.Send(context.Background(), "fake-token", Signal{EventID: id, Kind: "JOURNEY_STALE", Episode: 2}))
	require.Equal(t, map[string]any{"eventId": id.String(), "kind": "JOURNEY_STALE", "episode": "2"}, data)
	for _, signal := range []Signal{
		{EventID: id, Kind: "JOURNEY_STALE"},
		{EventID: id, Kind: "JOURNEY_STALE", Episode: -1},
		{EventID: id, Kind: "JOURNEY_ACTIVE", Episode: 2},
		{EventID: id, Kind: "SOS", Episode: 2},
	} {
		require.Error(t, f.Send(context.Background(), "fake-token", signal))
	}
}
