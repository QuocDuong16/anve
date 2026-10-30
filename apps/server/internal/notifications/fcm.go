// Package notifications provides privacy-minimizing push delivery primitives.
package notifications

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/google/uuid"
	"golang.org/x/oauth2/google"
)

// ErrInvalidToken means FCM reports that a registration no longer exists.
var ErrInvalidToken = errors.New("push registration is no longer valid")

// Signal contains only an opaque event identifier and a type, never GPS.
type Signal struct {
	EventID uuid.UUID
	Kind    string
}

// Gateway reports provider acceptance, not delivery or acknowledgement.
type Gateway interface {
	Send(context.Context, string, Signal) error
}

// FCM sends data-only signals using the FCM HTTP v1 API.
type FCM struct {
	client   *http.Client
	endpoint string
}

// NewFCM uses Application Default Credentials; credentials are never embedded.
func NewFCM(ctx context.Context, project string) (*FCM, error) {
	if !validProject(project) {
		return nil, errors.New("invalid firebase project")
	}
	client, err := google.DefaultClient(ctx, "https://www.googleapis.com/auth/firebase.messaging")
	if err != nil {
		return nil, fmt.Errorf("initialize firebase credentials: %w", err)
	}
	client.Timeout = 10 * time.Second
	return &FCM{client: client, endpoint: "https://fcm.googleapis.com/v1/projects/" + url.PathEscape(project) + "/messages:send"}, nil
}

func validProject(project string) bool {
	if len(project) < 1 || len(project) > 63 {
		return false
	}
	for _, c := range project {
		if (c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '-' {
			return false
		}
	}
	return true
}

// Send returns nil only when FCM accepted the request. Unknown failures must
// be retried with a stable event ID; clients must also deduplicate display.
func (f *FCM) Send(ctx context.Context, token string, signal Signal) error {
	if token == "" || signal.EventID == uuid.Nil || (signal.Kind != "SOS" && signal.Kind != "PLACE") {
		return errors.New("invalid push request")
	}
	body, err := json.Marshal(map[string]any{"message": map[string]any{
		"token": token, "data": map[string]string{"eventId": signal.EventID.String(), "kind": signal.Kind},
		"android": map[string]any{"priority": "high", "ttl": "3600s"},
	}})
	if err != nil {
		return fmt.Errorf("encode push request: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, f.endpoint, bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("create push request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	response, err := f.client.Do(req)
	if err != nil {
		return errors.New("push transport unavailable")
	}
	defer func() { _ = response.Body.Close() }()
	if response.StatusCode >= 200 && response.StatusCode < 300 {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 8192))
		return nil
	}
	var failure struct {
		Error struct {
			Details []struct {
				ErrorCode string `json:"errorCode"`
			} `json:"details"`
		} `json:"error"`
	}
	_ = json.NewDecoder(io.LimitReader(response.Body, 8192)).Decode(&failure)
	for _, detail := range failure.Error.Details {
		if strings.EqualFold(detail.ErrorCode, "UNREGISTERED") {
			return ErrInvalidToken
		}
	}
	return fmt.Errorf("push provider returned status %d", response.StatusCode)
}
