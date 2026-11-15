// Package contacts handles landing-page inquiry storage, validation, and
// best-effort multi-channel notifications for AnVề Internal Testing.
package contacts

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/mail"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5/pgxpool"
)

// ErrValidation reports invalid user-supplied contact form fields.
var ErrValidation = errors.New("invalid contact submission")

// Status tracks persistence and notification delivery state for an inquiry.
type Status string

const (
	// StatusNew means the contact record was stored in PostgreSQL.
	StatusNew Status = "NEW"
	// StatusNotified means every configured notification channel succeeded.
	StatusNotified Status = "NOTIFIED"
	// StatusPartialNotify means at least one configured channel succeeded while another failed.
	StatusPartialNotify Status = "PARTIAL_NOTIFY"
	// StatusRead means an operator has reviewed the inquiry.
	StatusRead Status = "READ"
	// StatusArchived means an operator archived the inquiry.
	StatusArchived Status = "ARCHIVED"
)

// Contact represents a stored inquiry returned by the contacts API.
type Contact struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Email     string    `json:"email"`
	Phone     string    `json:"phone"`
	Message   string    `json:"message"`
	Status    Status    `json:"status"`
	CreatedAt time.Time `json:"createdAt"`
}

// CreateInput holds raw user input submitted from the contact form.
type CreateInput struct {
	Name    string `json:"name"`
	Email   string `json:"email"`
	Phone   string `json:"phone"`
	Message string `json:"message"`
}

var phonePattern = regexp.MustCompile(`^[0-9+()\-\.\s]{6,40}$`)

// NormalizeAndValidate trims fields and enforces contract length and format bounds.
func NormalizeAndValidate(in CreateInput) (CreateInput, error) {
	name := strings.TrimSpace(in.Name)
	email := strings.TrimSpace(in.Email)
	phone := strings.TrimSpace(in.Phone)
	message := strings.TrimSpace(in.Message)

	nameLen := utf8.RuneCountInString(name)
	if nameLen < 1 || nameLen > 120 {
		return CreateInput{}, fmt.Errorf("%w: name must be between 1 and 120 characters", ErrValidation)
	}
	if !isValidEmail(email) {
		return CreateInput{}, fmt.Errorf("%w: valid email address is required", ErrValidation)
	}
	if phone != "" && !isValidPhone(phone) {
		return CreateInput{}, fmt.Errorf("%w: phone must contain 6 to 40 phone characters", ErrValidation)
	}
	msgLen := utf8.RuneCountInString(message)
	if msgLen < 1 || msgLen > 2000 {
		return CreateInput{}, fmt.Errorf("%w: message must be between 1 and 2000 characters", ErrValidation)
	}
	return CreateInput{
		Name:    name,
		Email:   email,
		Phone:   phone,
		Message: message,
	}, nil
}

func isValidEmail(raw string) bool {
	if raw == "" || len(raw) > 254 || strings.ContainsAny(raw, " \t\r\n") {
		return false
	}
	addr, err := mail.ParseAddress(raw)
	if err != nil || addr.Address != raw {
		return false
	}
	at := strings.LastIndexByte(raw, '@')
	if at <= 0 || at == len(raw)-1 {
		return false
	}
	domain := raw[at+1:]
	if !strings.Contains(domain, ".") || strings.HasPrefix(domain, ".") || strings.HasSuffix(domain, ".") || strings.Contains(domain, "..") {
		return false
	}
	return true
}

func isValidPhone(raw string) bool {
	if !phonePattern.MatchString(raw) {
		return false
	}
	digits := 0
	for _, r := range raw {
		if r >= '0' && r <= '9' {
			digits++
		}
	}
	return digits >= 6
}

// Store persists contact inquiries and updates notification state.
type Store interface {
	Create(ctx context.Context, input CreateInput) (Contact, error)
	UpdateStatus(ctx context.Context, id string, status Status) error
}

// PGStore stores contact submissions in PostgreSQL.
type PGStore struct {
	pool *pgxpool.Pool
}

// NewPGStore returns a PostgreSQL-backed contacts Store.
func NewPGStore(pool *pgxpool.Pool) *PGStore {
	return &PGStore{pool: pool}
}

// Create inserts a validated contact inquiry with initial status NEW.
func (s *PGStore) Create(ctx context.Context, input CreateInput) (Contact, error) {
	const query = `
		INSERT INTO contacts (name, email, phone, message, status)
		VALUES ($1, $2, $3, $4, 'NEW')
		RETURNING id::text, name, email, phone, message, status, created_at
	`
	var out Contact
	var rawStatus string
	err := s.pool.QueryRow(ctx, query, input.Name, input.Email, input.Phone, input.Message).Scan(
		&out.ID,
		&out.Name,
		&out.Email,
		&out.Phone,
		&out.Message,
		&rawStatus,
		&out.CreatedAt,
	)
	if err != nil {
		return Contact{}, fmt.Errorf("insert contact: %w", err)
	}
	out.Status = Status(rawStatus)
	return out, nil
}

// UpdateStatus updates the delivery status of a stored contact inquiry.
func (s *PGStore) UpdateStatus(ctx context.Context, id string, status Status) error {
	const query = `UPDATE contacts SET status = $2 WHERE id = $1::uuid`
	if _, err := s.pool.Exec(ctx, query, id, string(status)); err != nil {
		return fmt.Errorf("update contact status: %w", err)
	}
	return nil
}

// Service coordinates contact validation, DB storage, and non-fatal notification dispatch.
type Service struct {
	store    Store
	notifier Notifier
	logger   *slog.Logger
}

// NewService constructs a contact Service. notifier may be nil when disabled.
func NewService(store Store, notifier Notifier, logger *slog.Logger) *Service {
	if logger == nil {
		logger = slog.Default()
	}
	return &Service{
		store:    store,
		notifier: notifier,
		logger:   logger,
	}
}

// Submit validates and stores a contact inquiry, then attempts best-effort notifications.
// Notification failures or unconfigured channels never fail persistence or the caller's response.
func (s *Service) Submit(ctx context.Context, input CreateInput) (Contact, error) {
	clean, err := NormalizeAndValidate(input)
	if err != nil {
		return Contact{}, err
	}
	contact, err := s.store.Create(ctx, clean)
	if err != nil {
		return Contact{}, fmt.Errorf("create contact: %w", err)
	}

	if s.notifier == nil {
		return contact, nil
	}

	notifyCtx, cancel := context.WithTimeout(ctx, 6*time.Second)
	defer cancel()

	outcome := s.notifier.Notify(notifyCtx, contact)
	if outcome.EmailErr != nil {
		s.logger.Warn("contact email notification failed", slog.String("contact_id", contact.ID))
	}
	if outcome.TelegramErr != nil {
		s.logger.Warn("contact telegram notification failed", slog.String("contact_id", contact.ID))
	}

	nextStatus := outcome.nextStatus()
	if nextStatus != contact.Status {
		if err := s.store.UpdateStatus(ctx, contact.ID, nextStatus); err != nil {
			s.logger.Warn("contact status update failed", slog.String("contact_id", contact.ID))
		} else {
			contact.Status = nextStatus
		}
	}
	return contact, nil
}
