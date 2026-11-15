package contacts

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/mail"
	"net/smtp"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	defaultTelegramBaseURL = "https://api.telegram.org"
	notifyTimeout          = 5 * time.Second
)

// NotifyOutcome describes best-effort delivery across configured channels.
type NotifyOutcome struct {
	EmailConfigured    bool
	EmailSent          bool
	EmailErr           error
	TelegramConfigured bool
	TelegramSent       bool
	TelegramErr        error
}

func (o NotifyOutcome) nextStatus() Status {
	if !o.EmailSent && !o.TelegramSent {
		return StatusNew
	}
	if o.EmailErr != nil || o.TelegramErr != nil {
		return StatusPartialNotify
	}
	return StatusNotified
}

// Notifier dispatches alerts about newly submitted contact inquiries.
type Notifier interface {
	Notify(ctx context.Context, contact Contact) NotifyOutcome
}

// SMTPConfig configures email notifications over SMTP.
type SMTPConfig struct {
	Host        string
	Port        int
	User        string
	Pass        string
	NotifyEmail string
}

// Configured reports whether minimum SMTP host, port, and recipient exist.
func (c SMTPConfig) Configured() bool {
	return strings.TrimSpace(c.Host) != "" &&
		c.Port > 0 &&
		c.Port <= 65535 &&
		strings.TrimSpace(c.NotifyEmail) != ""
}

// TelegramConfig configures Telegram Bot notifications via sendMessage.
type TelegramConfig struct {
	BotToken string
	ChatID   string
	BaseURL  string
}

// Configured reports whether bot token and target chat ID are present.
func (c TelegramConfig) Configured() bool {
	return strings.TrimSpace(c.BotToken) != "" && strings.TrimSpace(c.ChatID) != ""
}

// MultiNotifier dispatches contact alerts concurrently via Email (SMTP) and Telegram Bot.
type MultiNotifier struct {
	smtpCfg       SMTPConfig
	telegramCfg   TelegramConfig
	telegramURL   string
	httpClient    *http.Client
	sendEmailFunc func(ctx context.Context, cfg SMTPConfig, contact Contact) error
}

// NewMultiNotifier creates a Notifier that gracefully skips any unconfigured channel.
func NewMultiNotifier(smtpCfg SMTPConfig, telegramCfg TelegramConfig) *MultiNotifier {
	base := strings.TrimRight(strings.TrimSpace(telegramCfg.BaseURL), "/")
	if base == "" {
		base = defaultTelegramBaseURL
	}
	var tgEndpoint string
	if telegramCfg.Configured() {
		tgEndpoint = base + "/bot" + url.PathEscape(strings.TrimSpace(telegramCfg.BotToken)) + "/sendMessage"
	}
	return &MultiNotifier{
		smtpCfg:       smtpCfg,
		telegramCfg:   telegramCfg,
		telegramURL:   tgEndpoint,
		httpClient:    &http.Client{Timeout: notifyTimeout},
		sendEmailFunc: sendSMTPEmail,
	}
}

// Notify delivers a contact alert to any configured channels without failing if one errors.
func (m *MultiNotifier) Notify(ctx context.Context, contact Contact) NotifyOutcome {
	var outcome NotifyOutcome
	var wg sync.WaitGroup

	if m.smtpCfg.Configured() {
		outcome.EmailConfigured = true
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := m.sendEmailFunc(ctx, m.smtpCfg, contact); err != nil {
				outcome.EmailErr = err
			} else {
				outcome.EmailSent = true
			}
		}()
	}

	if m.telegramCfg.Configured() && m.telegramURL != "" {
		outcome.TelegramConfigured = true
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := m.sendTelegramMessage(ctx, contact); err != nil {
				outcome.TelegramErr = err
			} else {
				outcome.TelegramSent = true
			}
		}()
	}

	wg.Wait()
	return outcome
}

func (m *MultiNotifier) sendTelegramMessage(ctx context.Context, contact Contact) error {
	payload, err := json.Marshal(map[string]string{
		"chat_id": strings.TrimSpace(m.telegramCfg.ChatID),
		"text":    formatNotificationText(contact),
	})
	if err != nil {
		return fmt.Errorf("encode telegram payload: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, m.telegramURL, bytes.NewReader(payload))
	if err != nil {
		return errors.New("build telegram request failed")
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := m.httpClient.Do(req) // #nosec G704 -- endpoint is derived at startup from operator TelegramConfig.
	if err != nil {
		return errors.New("telegram transport error")
	}
	defer func() { _ = resp.Body.Close() }()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("telegram rejected request with status %d", resp.StatusCode)
	}
	return nil
}

func sendSMTPEmail(ctx context.Context, cfg SMTPConfig, contact Contact) error {
	host := strings.TrimSpace(cfg.Host)
	recipient := strings.TrimSpace(cfg.NotifyEmail)
	user := strings.TrimSpace(cfg.User)

	if hasCRLF(host) || hasCRLF(recipient) || hasCRLF(user) {
		return errors.New("smtp configuration contains forbidden control characters")
	}
	if _, err := mail.ParseAddress(recipient); err != nil {
		return fmt.Errorf("invalid NOTIFY_EMAIL: %w", err)
	}

	sender := recipient
	if user != "" {
		if _, err := mail.ParseAddress(user); err == nil {
			sender = user
		}
	}

	addr := net.JoinHostPort(host, strconv.Itoa(cfg.Port))
	dialer := &net.Dialer{Timeout: notifyTimeout}
	var conn net.Conn
	var err error
	if cfg.Port == 465 {
		tlsDialer := &tls.Dialer{
			NetDialer: dialer,
			Config:    &tls.Config{ServerName: host, MinVersion: tls.VersionTLS12},
		}
		conn, err = tlsDialer.DialContext(ctx, "tcp", addr)
	} else {
		conn, err = dialer.DialContext(ctx, "tcp", addr)
	}
	if err != nil {
		return fmt.Errorf("dial smtp server: %w", err)
	}

	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	} else {
		_ = conn.SetDeadline(time.Now().Add(notifyTimeout))
	}

	client, err := smtp.NewClient(conn, host)
	if err != nil {
		_ = conn.Close()
		return fmt.Errorf("initialize smtp client: %w", err)
	}
	defer func() { _ = client.Close() }()

	if cfg.Port != 465 {
		if ok, _ := client.Extension("STARTTLS"); ok {
			if err := client.StartTLS(&tls.Config{ServerName: host, MinVersion: tls.VersionTLS12}); err != nil {
				return fmt.Errorf("smtp starttls: %w", err)
			}
		}
	}

	if user != "" || cfg.Pass != "" {
		if ok, _ := client.Extension("AUTH"); ok {
			if err := client.Auth(smtp.PlainAuth("", user, cfg.Pass, host)); err != nil {
				return fmt.Errorf("smtp auth: %w", err)
			}
		}
	}

	if err := client.Mail(sender); err != nil {
		return fmt.Errorf("smtp mail from: %w", err)
	}
	if err := client.Rcpt(recipient); err != nil {
		return fmt.Errorf("smtp rcpt to: %w", err)
	}

	wc, err := client.Data()
	if err != nil {
		return fmt.Errorf("smtp data: %w", err)
	}
	body := buildEmailMessage(sender, recipient, contact)
	if _, err := wc.Write([]byte(body)); err != nil {
		_ = wc.Close()
		return fmt.Errorf("smtp write body: %w", err)
	}
	if err := wc.Close(); err != nil {
		return fmt.Errorf("smtp close data: %w", err)
	}
	if err := client.Quit(); err != nil {
		return fmt.Errorf("smtp quit: %w", err)
	}
	return nil
}

func buildEmailMessage(from, to string, contact Contact) string {
	var b strings.Builder
	b.WriteString("From: " + from + "\r\n")
	b.WriteString("To: " + to + "\r\n")
	b.WriteString("Subject: [AnVe Internal Testing] Lien he moi tu " + sanitizeHeaderValue(contact.Name) + "\r\n")
	b.WriteString("MIME-Version: 1.0\r\n")
	b.WriteString("Content-Type: text/plain; charset=UTF-8\r\n")
	b.WriteString("\r\n")
	b.WriteString(formatNotificationText(contact))
	b.WriteString("\r\n")
	return b.String()
}

func formatNotificationText(c Contact) string {
	phone := c.Phone
	if phone == "" {
		phone = "(khong cung cap)"
	}
	return fmt.Sprintf(
		"AnVe Internal Testing — Lien he / Hop tac moi\n"+
			"ID: %s\n"+
			"Ho ten: %s\n"+
			"Email: %s\n"+
			"So dien thoai: %s\n"+
			"Thoi gian: %s\n\n"+
			"Loi nhan:\n%s",
		c.ID,
		c.Name,
		c.Email,
		phone,
		c.CreatedAt.UTC().Format(time.RFC3339),
		c.Message,
	)
}

func hasCRLF(s string) bool {
	return strings.ContainsAny(s, "\r\n")
}

func sanitizeHeaderValue(s string) string {
	r := strings.NewReplacer("\r", " ", "\n", " ")
	return strings.TrimSpace(r.Replace(s))
}
