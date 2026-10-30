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
