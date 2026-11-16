package contacts

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type fakeStore struct {
	mu            sync.Mutex
	created       []Contact
	statusUpdates []struct {
		id     string
		status Status
	}
	createErr error
	updateErr error
}

func (f *fakeStore) Create(_ context.Context, input CreateInput) (Contact, error) {
	if f.createErr != nil {
		return Contact{}, f.createErr
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	c := Contact{
		ID:        "00000000-0000-0000-0000-000000000001",
		Name:      input.Name,
		Email:     input.Email,
		Phone:     input.Phone,
		Message:   input.Message,
		Status:    StatusNew,
		CreatedAt: time.Date(2026, 11, 16, 12, 0, 0, 0, time.UTC),
	}
	f.created = append(f.created, c)
	return c, nil
}

func (f *fakeStore) UpdateStatus(_ context.Context, id string, status Status) error {
	if f.updateErr != nil {
		return f.updateErr
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	f.statusUpdates = append(f.statusUpdates, struct {
		id     string
		status Status
	}{id: id, status: status})
	return nil
}

func TestNormalizeAndValidate(t *testing.T) {
	tests := []struct {
		name    string
		input   CreateInput
		want    CreateInput
		wantErr bool
	}{
		{
			name: "valid trimmed input without phone",
			input: CreateInput{
				Name:    "  Minh Anh  ",
				Email:   " minh.anh@example.com ",
				Phone:   "   ",
				Message: "  Toi muon dang ky thu nghiem som. ",
			},
			want: CreateInput{
				Name:    "Minh Anh",
				Email:   "minh.anh@example.com",
				Phone:   "",
				Message: "Toi muon dang ky thu nghiem som.",
			},
		},
		{
			name: "valid input with phone",
			input: CreateInput{
				Name:    "Hoàng Nam",
				Email:   "nam@example.vn",
				Phone:   "+84 912 345 678",
				Message: "Hợp tác tích hợp thử nghiệm nội bộ.",
			},
			want: CreateInput{
				Name:    "Hoàng Nam",
				Email:   "nam@example.vn",
				Phone:   "+84 912 345 678",
				Message: "Hợp tác tích hợp thử nghiệm nội bộ.",
			},
		},
		{
			name: "blank name rejected",
			input: CreateInput{
				Name:    "   ",
				Email:   "a@example.com",
				Message: "Hello",
			},
			wantErr: true,
		},
		{
			name: "invalid email rejected",
			input: CreateInput{
				Name:    "Lan",
				Email:   "not-an-email",
				Message: "Hello",
			},
			wantErr: true,
		},
		{
			name: "invalid phone characters rejected",
			input: CreateInput{
				Name:    "Lan",
				Email:   "lan@example.com",
				Phone:   "abc123456",
				Message: "Hello",
			},
			wantErr: true,
		},
		{
			name: "short digits phone rejected",
			input: CreateInput{
				Name:    "Lan",
				Email:   "lan@example.com",
				Phone:   "123",
				Message: "Hello",
			},
			wantErr: true,
		},
		{
			name: "empty message rejected",
			input: CreateInput{
				Name:    "Lan",
				Email:   "lan@example.com",
				Message: "  ",
			},
			wantErr: true,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, err := NormalizeAndValidate(tc.input)
			if tc.wantErr {
				require.ErrorIs(t, err, ErrValidation)
				return
			}
			require.NoError(t, err)
			assert.Equal(t, tc.want, got)
		})
	}
}

func TestServiceSubmitUnconfiguredChannelsStillStores(t *testing.T) {
	store := &fakeStore{}
	notifier := NewMultiNotifier(SMTPConfig{}, TelegramConfig{})
	svc := NewService(store, notifier, slog.New(slog.NewTextHandler(io.Discard, nil)))

	got, err := svc.Submit(context.Background(), CreateInput{
		Name:    "An",
		Email:   "an@example.com",
		Message: "Xin tham gia thu nghiem.",
	})
	require.NoError(t, err)
	assert.Equal(t, StatusNew, got.Status)
	require.Len(t, store.created, 1)
	assert.Empty(t, store.statusUpdates)
}

func TestServiceSubmitPartialAndFullNotificationResilience(t *testing.T) {
	var tgMu sync.Mutex
	var tgText string
	tgServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/botsecret-tg-token/sendMessage", r.URL.Path)
		var payload map[string]string
		require.NoError(t, json.NewDecoder(r.Body).Decode(&payload))
		tgMu.Lock()
		tgText = payload["text"]
		tgMu.Unlock()
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer tgServer.Close()

	t.Run("telegram ok while smtp fails stays non-fatal and sets PARTIAL_NOTIFY", func(t *testing.T) {
		store := &fakeStore{}
		notifier := NewMultiNotifier(
			SMTPConfig{Host: "smtp.example.com", Port: 587, NotifyEmail: "team@example.com"},
			TelegramConfig{BotToken: "secret-tg-token", ChatID: "-100123", BaseURL: tgServer.URL},
		)
		notifier.sendEmailFunc = func(context.Context, SMTPConfig, Contact) error {
			return errors.New("smtp connection refused")
		}
		svc := NewService(store, notifier, slog.New(slog.NewTextHandler(io.Discard, nil)))

		got, err := svc.Submit(context.Background(), CreateInput{
			Name:    "Binh",
			Email:   "binh@example.com",
			Phone:   "0912345678",
			Message: "Lien he hop tac.",
		})
		require.NoError(t, err)
		assert.Equal(t, StatusPartialNotify, got.Status)
		require.Len(t, store.created, 1)
		tgMu.Lock()
		assert.Contains(t, tgText, "Binh")
		assert.Contains(t, tgText, "Lien he hop tac.")
		tgMu.Unlock()
	})

	t.Run("both channels configured and succeeding sets NOTIFIED", func(t *testing.T) {
		store := &fakeStore{}
		notifier := NewMultiNotifier(
			SMTPConfig{Host: "smtp.example.com", Port: 587, NotifyEmail: "team@example.com"},
			TelegramConfig{BotToken: "secret-tg-token", ChatID: "-100123", BaseURL: tgServer.URL},
		)
		var emailed bool
		notifier.sendEmailFunc = func(context.Context, SMTPConfig, Contact) error {
			emailed = true
			return nil
		}
		svc := NewService(store, notifier, slog.New(slog.NewTextHandler(io.Discard, nil)))

		got, err := svc.Submit(context.Background(), CreateInput{
			Name:    "Chi",
			Email:   "chi@example.com",
			Message: "Dang ky trai nghiem som.",
		})
		require.NoError(t, err)
		assert.True(t, emailed)
		assert.Equal(t, StatusNotified, got.Status)
	})

	t.Run("telegram HTTP failure never leaks bot token in error", func(t *testing.T) {
		errServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(http.StatusUnauthorized)
		}))
		defer errServer.Close()

		notifier := NewMultiNotifier(
			SMTPConfig{},
			TelegramConfig{BotToken: "super-secret-bot-token", ChatID: "-100123", BaseURL: errServer.URL},
		)
		outcome := notifier.Notify(context.Background(), Contact{
			ID:        "1",
			Name:      "Dung",
			Email:     "dung@example.com",
			Message:   "Test",
			CreatedAt: time.Now().UTC(),
		})
		require.Error(t, outcome.TelegramErr)
		assert.NotContains(t, outcome.TelegramErr.Error(), "super-secret-bot-token")
	})

	t.Run("status update failure still returns created contact", func(t *testing.T) {
		store := &fakeStore{updateErr: errors.New("transient update failure")}
		notifier := NewMultiNotifier(
			SMTPConfig{},
			TelegramConfig{BotToken: "secret-tg-token", ChatID: "-100123", BaseURL: tgServer.URL},
		)
		svc := NewService(store, notifier, slog.New(slog.NewTextHandler(io.Discard, nil)))

		got, err := svc.Submit(context.Background(), CreateInput{
			Name:    "Em",
			Email:   "em@example.com",
			Message: "Test fallback",
		})
		require.NoError(t, err)
		assert.Equal(t, StatusNew, got.Status)
	})
}

func TestSendSMTPEmailMockServer(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	defer func() { _ = ln.Close() }()

	var mailFromCmd string
	var received strings.Builder
	done := make(chan struct{})
	go func() {
		defer close(done)
		conn, acceptErr := ln.Accept()
		if acceptErr != nil {
			return
		}
		defer func() { _ = conn.Close() }()
		_, _ = conn.Write([]byte("220 localhost ESMTP test\r\n"))
		reader := bufio.NewReader(conn)
		inData := false
		for {
			line, readErr := reader.ReadString('\n')
			if readErr != nil {
				return
			}
			if inData {
				if line == ".\r\n" {
					inData = false
					_, _ = conn.Write([]byte("250 OK\r\n"))
					continue
				}
				received.WriteString(line)
				continue
			}
			upper := strings.ToUpper(strings.TrimSpace(line))
			switch {
			case strings.HasPrefix(upper, "EHLO"), strings.HasPrefix(upper, "HELO"):
				_, _ = conn.Write([]byte("250-localhost\r\n250 OK\r\n"))
			case strings.HasPrefix(upper, "MAIL FROM:"):
				mailFromCmd = strings.TrimSpace(line)
				_, _ = conn.Write([]byte("250 OK\r\n"))
			case strings.HasPrefix(upper, "RCPT TO:"):
				_, _ = conn.Write([]byte("250 OK\r\n"))
			case upper == "DATA":
				inData = true
				_, _ = conn.Write([]byte("354 End data with <CR><LF>.<CR><LF>\r\n"))
			case upper == "QUIT":
				_, _ = conn.Write([]byte("221 Bye\r\n"))
				return
			default:
				_, _ = conn.Write([]byte("250 OK\r\n"))
			}
		}
	}()

	_, portStr, err := net.SplitHostPort(ln.Addr().String())
	require.NoError(t, err)
	port, err := strconv.Atoi(portStr)
	require.NoError(t, err)

	err = sendSMTPEmail(context.Background(), SMTPConfig{
		Host:        "127.0.0.1",
		Port:        port,
		From:        "AnVe<no-reply@anve.niand.id.vn>",
		NotifyEmail: "ops@example.com",
	}, Contact{
		ID:        "c-1",
		Name:      "Tester",
		Email:     "tester@example.com",
		Message:   "Xin chao AnVe",
		CreatedAt: time.Date(2026, 11, 16, 12, 0, 0, 0, time.UTC),
	})
	require.NoError(t, err)
	<-done

	body := received.String()
	assert.Equal(t, "MAIL FROM:<no-reply@anve.niand.id.vn>", mailFromCmd)
	assert.Contains(t, body, `From: "AnVe" <no-reply@anve.niand.id.vn>`)
	assert.Contains(t, body, "To: ops@example.com")
	assert.Contains(t, body, "Subject: [AnVe Internal Testing] Lien he moi tu Tester")
	assert.Contains(t, body, "Xin chao AnVe")
}

func TestLandingPageHTMLContainsRequiredContentAndPalette(t *testing.T) {
	html := string(LandingPageHTML)
	assert.Contains(t, html, "AnVe hiện đang trong giai đoạn phát triển và thử nghiệm nội bộ (Internal Testing). Vui lòng để lại thông tin nếu bạn muốn hợp tác hoặc trải nghiệm sớm.")
	assert.Contains(t, html, "#FDF9F2")
	assert.Contains(t, html, "#3D602F")
	assert.Contains(t, html, "#F1DDE4")
	assert.Contains(t, html, "/api/v1/contacts")
}
