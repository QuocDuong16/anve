package circles

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"net/mail"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/QuocDuong16/anve/server/internal/audit"
	"github.com/QuocDuong16/anve/server/internal/auth"
)

// Creation errors expose only the sender request, never recipient existence.
var (
	ErrInvitationConflict = errors.New("invitation request conflicts or was retired")
	ErrInvitationBudget   = errors.New("invitation sender budget exhausted")
)

// InvitationPrincipal binds private invitation work to a current login.
type InvitationPrincipal struct{ UserID, SessionID uuid.UUID }

// InvitationRequest carries the client fence and explicit delivery mode.
type InvitationRequest struct {
	RequestID      uuid.UUID `json:"requestId"`
	Mode           string    `json:"mode"`
	RecipientEmail string    `json:"recipientEmail,omitempty"`
	ExpiresInHours *int      `json:"expiresInHours,omitempty"`
}

// ReceivedInvitation is private to the addressed account. List responses omit Code.
type ReceivedInvitation struct {
	ID                 uuid.UUID `json:"id"`
	CircleID           uuid.UUID `json:"circleId"`
	CircleName         string    `json:"circleName"`
	InviterDisplayName string    `json:"inviterDisplayName"`
	ExpiresAt          time.Time `json:"expiresAt"`
	Code               string    `json:"code,omitempty"`
}

// InvitationStore owns fenced creation, the private inbox and durable push work.
type InvitationStore struct{ pool *pgxpool.Pool }

// NewInvitationStore uses the same memberships and single-use codes as ordinary invites.
func NewInvitationStore(pool *pgxpool.Pool) *InvitationStore { return &InvitationStore{pool: pool} }

func emailDigest(email string) []byte {
	value := sha256.Sum256([]byte(strings.ToLower(strings.TrimSpace(email))))
	return value[:]
}

func invitationLogin(ctx context.Context, tx pgx.Tx, p InvitationPrincipal, mutation bool) (string, error) {
	lock := "FOR SHARE"
	if mutation {
		lock = "FOR UPDATE"
	}
	var email string
	err := tx.QueryRow(ctx, "SELECT email FROM users WHERE id=$1 "+lock, p.UserID).Scan(&email)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", auth.ErrUnauthenticated
	}
	if err != nil {
		return "", fmt.Errorf("lock invitation account: %w", err)
	}
	var id uuid.UUID
	err = tx.QueryRow(ctx, `SELECT s.id FROM sessions s JOIN devices d ON d.id=s.device_id AND d.user_id=s.user_id
 WHERE s.id=$1 AND s.user_id=$2 AND s.revoked_at IS NULL AND s.refresh_expires_at>clock_timestamp() FOR SHARE OF s,d`, p.SessionID, p.UserID).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", auth.ErrUnauthenticated
	}
	if err != nil {
		return "", fmt.Errorf("authorize invitation login: %w", err)
	}
	return email, nil
}

func invitationOwner(ctx context.Context, tx pgx.Tx, owner, circle uuid.UUID) error {
	var id uuid.UUID
	err := tx.QueryRow(ctx, `SELECT m.id FROM memberships m JOIN circles c ON c.id=m.circle_id
 WHERE m.user_id=$1 AND m.circle_id=$2 AND m.role='owner' AND c.created_by=$1 FOR SHARE OF m,c`, owner, circle).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return auth.ErrNotFound
	}
	if err != nil {
		return fmt.Errorf("authorize invitation owner: %w", err)
	}
	return nil
}

func prepareInvitation(r InvitationRequest) (string, []byte, []byte, int, error) {
	hours := 48
	if r.ExpiresInHours != nil {
		hours = *r.ExpiresInHours
	}
	if r.RequestID == uuid.Nil || hours < 1 || hours > 168 {
		return "", nil, nil, 0, auth.ErrValidation
	}
	email := strings.ToLower(strings.TrimSpace(r.RecipientEmail))
	var digest []byte
	switch r.Mode {
	case "EMAIL":
		parsed, err := mail.ParseAddress(email)
		if err != nil || parsed.Address != email || len(email) > 254 || strings.ContainsAny(email, "\r\n\t ") {
			return "", nil, nil, 0, auth.ErrValidation
		}
		for _, c := range email {
			if c > 127 {
				return "", nil, nil, 0, auth.ErrValidation
			}
		}
		digest = emailDigest(email)
	case "LINK":
		if r.RecipientEmail != "" {
			return "", nil, nil, 0, auth.ErrValidation
		}
	default:
		return "", nil, nil, 0, auth.ErrValidation
	}
	fingerprint := sha256.Sum256(fmt.Appendf(nil, "%s\x00%s\x00%d", r.Mode, email, hours))
	return email, digest, fingerprint[:], hours, nil
}

const privateInviteColumns = `id,circle_id,created_by,code,expires_at,created_at,recipient_email_hash IS NOT NULL`

func readPrivateInvite(ctx context.Context, q rowQuerier, id, owner, circle uuid.UUID) (Invite, error) {
	var i Invite
	err := q.QueryRow(ctx, `SELECT `+privateInviteColumns+` FROM invites WHERE id=$1 AND created_by=$2 AND circle_id=$3
 AND used_at IS NULL AND revoked_at IS NULL AND expires_at>clock_timestamp()`, id, owner, circle).
		Scan(&i.ID, &i.CircleID, &i.CreatedBy, &i.Code, &i.ExpiresAt, &i.CreatedAt, &i.RecipientRestricted)
	if errors.Is(err, pgx.ErrNoRows) {
		return Invite{}, ErrInvitationConflict
	}
	if err != nil {
		return Invite{}, fmt.Errorf("read created invitation: %w", err)
	}
	return i, nil
}

// Create commits one code, audit event and eligible notification per UUID. Unknown
// emails have the same response as registered emails; private quotas affect no fields.
func (s *InvitationStore) Create(ctx context.Context, p InvitationPrincipal, circle uuid.UUID, r InvitationRequest) (Invite, bool, error) {
	email, digest, fingerprint, hours, err := prepareInvitation(r)
	if err != nil {
		return Invite{}, false, err
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Invite{}, false, fmt.Errorf("begin invitation creation: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err = invitationLogin(ctx, tx, p, true); err != nil {
		return Invite{}, false, err
	}
	if err = invitationOwner(ctx, tx, p.UserID, circle); err != nil {
		return Invite{}, false, err
	}
	var previous []byte
	var oldOwner, oldCircle uuid.UUID
	err = tx.QueryRow(ctx, `SELECT owner_user_id,circle_id,input_hash FROM invitation_creation_fences WHERE request_id=$1`, r.RequestID).Scan(&oldOwner, &oldCircle, &previous)
	if err == nil {
		if oldOwner != p.UserID || oldCircle != circle || string(previous) != string(fingerprint) {
			return Invite{}, false, ErrInvitationConflict
		}
		i, err := readPrivateInvite(ctx, tx, r.RequestID, p.UserID, circle)
		if err != nil {
			return Invite{}, false, err
		}
		if err = tx.Commit(ctx); err != nil {
			return Invite{}, false, fmt.Errorf("commit invitation replay read: %w", err)
		}
		return i, false, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return Invite{}, false, fmt.Errorf("read invitation fence: %w", err)
	}
	var count int
	err = tx.QueryRow(ctx, `SELECT count(*) FROM invitation_creation_fences WHERE owner_user_id=$1 AND created_at>clock_timestamp()-interval '24 hours'`, p.UserID).Scan(&count)
	if err != nil {
		return Invite{}, false, fmt.Errorf("read invitation budget: %w", err)
	}
	if count >= 20 {
		return Invite{}, false, ErrInvitationBudget
	}
	_, err = tx.Exec(ctx, `INSERT INTO invitation_creation_fences(request_id,owner_user_id,circle_id,input_hash) VALUES($1,$2,$3,$4)`, r.RequestID, p.UserID, circle, fingerprint)
	if isUniqueViolation(err, "invitation_creation_fences_pkey") {
		return Invite{}, false, ErrInvitationConflict
	}
	if err != nil {
		return Invite{}, false, fmt.Errorf("write invitation fence: %w", err)
	}
	code, err := newInviteCode()
	if err != nil {
		return Invite{}, false, fmt.Errorf("generate addressed invite: %w", err)
	}
	_, err = tx.Exec(ctx, `INSERT INTO invites(id,circle_id,created_by,code,expires_at,recipient_email_hash)
 VALUES($1,$2,$3,$4,clock_timestamp()+make_interval(hours=>$5),$6)`, r.RequestID, circle, p.UserID, code, hours, digest)
	if err != nil {
		return Invite{}, false, fmt.Errorf("insert addressed invite: %w", err)
	}
	if digest != nil {
		// Apply the same internal budget to every normalized target, including unknowns.
		var attempts int
		err = tx.QueryRow(ctx, `INSERT INTO invitation_notification_budgets(email_hash,budget_day) VALUES($1,(clock_timestamp() AT TIME ZONE 'UTC')::date)
 ON CONFLICT(email_hash,budget_day) DO UPDATE SET attempts=invitation_notification_budgets.attempts+1 RETURNING attempts`, digest).Scan(&attempts)
		if err != nil {
			return Invite{}, false, fmt.Errorf("budget invitation signal: %w", err)
		}
		_, err = tx.Exec(ctx, `INSERT INTO invitation_notification_outbox(invite_id,recipient_user_id)
 SELECT $1,id FROM users WHERE lower(trim(email))=$2 AND id<>$3 AND $4<=3`, r.RequestID, email, p.UserID, attempts)
		if err != nil {
			return Invite{}, false, fmt.Errorf("enqueue invitation signal: %w", err)
		}
	}
	if err = audit.InTx(ctx, tx, audit.Event{ActorUserID: p.UserID, Action: audit.ActionInviteCreated, Detail: map[string]any{"circle_id": circle.String(), "invite_id": r.RequestID.String()}}); err != nil {
		return Invite{}, false, err
	}
	i, err := readPrivateInvite(ctx, tx, r.RequestID, p.UserID, circle)
	if err != nil {
		return Invite{}, false, err
	}
	if err = tx.Commit(ctx); err != nil {
		return Invite{}, false, fmt.Errorf("commit invitation creation: %w", err)
	}
	return i, true, nil
}

// Created is owner-bound read-only reconciliation; missing/retired UUIDs never recreate content.
func (s *InvitationStore) Created(ctx context.Context, p InvitationPrincipal, circle, id uuid.UUID) (Invite, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Invite{}, fmt.Errorf("begin invitation readback: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err = invitationLogin(ctx, tx, p, false); err != nil {
		return Invite{}, err
	}
	if err = invitationOwner(ctx, tx, p.UserID, circle); err != nil {
		return Invite{}, err
	}
	i, err := readPrivateInvite(ctx, tx, id, p.UserID, circle)
	if err != nil {
		return Invite{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return Invite{}, fmt.Errorf("commit invitation readback: %w", err)
	}
	return i, nil
}

const receivedQuery = `SELECT i.id,c.id,c.name,u.display_name,i.expires_at,i.code FROM invites i JOIN circles c ON c.id=i.circle_id
 JOIN users u ON u.id=i.created_by JOIN memberships m ON m.circle_id=c.id AND m.user_id=u.id AND m.role='owner'
 WHERE i.recipient_email_hash=$1 AND i.used_at IS NULL AND i.revoked_at IS NULL AND i.expires_at>clock_timestamp()
 AND NOT EXISTS(SELECT 1 FROM invitation_private_hides h WHERE h.invite_id=i.id AND h.recipient_user_id=$2)`

// Received returns at most 20 active, addressed invitations without capability codes.
func (s *InvitationStore) Received(ctx context.Context, p InvitationPrincipal) ([]ReceivedInvitation, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, fmt.Errorf("begin invitation inbox: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	email, err := invitationLogin(ctx, tx, p, false)
	if err != nil {
		return nil, err
	}
	rows, err := tx.Query(ctx, receivedQuery+` ORDER BY i.created_at DESC,i.id LIMIT 20`, emailDigest(email), p.UserID)
	if err != nil {
		return nil, fmt.Errorf("read invitation inbox: %w", err)
	}
	result := []ReceivedInvitation{}
	for rows.Next() {
		var i ReceivedInvitation
		if err = rows.Scan(&i.ID, &i.CircleID, &i.CircleName, &i.InviterDisplayName, &i.ExpiresAt, &i.Code); err != nil {
			rows.Close()
			return nil, fmt.Errorf("scan invitation inbox: %w", err)
		}
		i.Code = ""
		result = append(result, i)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, fmt.Errorf("iterate invitation inbox: %w", err)
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, fmt.Errorf("commit invitation inbox: %w", err)
	}
	return result, nil
}

// ReceivedOne revalidates the recipient before exposing a code or hiding the entry.
func (s *InvitationStore) ReceivedOne(ctx context.Context, p InvitationPrincipal, id uuid.UUID, hide bool) (ReceivedInvitation, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return ReceivedInvitation{}, fmt.Errorf("begin received invitation: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	email, err := invitationLogin(ctx, tx, p, hide)
	if err != nil {
		return ReceivedInvitation{}, err
	}
	var i ReceivedInvitation
	err = tx.QueryRow(ctx, receivedQuery+` AND i.id=$3 FOR SHARE OF i,m,c`, emailDigest(email), p.UserID, id).
		Scan(&i.ID, &i.CircleID, &i.CircleName, &i.InviterDisplayName, &i.ExpiresAt, &i.Code)
	if errors.Is(err, pgx.ErrNoRows) {
		return ReceivedInvitation{}, auth.ErrNotFound
	}
	if err != nil {
		return ReceivedInvitation{}, fmt.Errorf("read received invitation: %w", err)
	}
	if hide {
		_, err = tx.Exec(ctx, `INSERT INTO invitation_private_hides(invite_id,recipient_user_id) VALUES($1,$2) ON CONFLICT DO NOTHING`, id, p.UserID)
		if err != nil {
			return ReceivedInvitation{}, fmt.Errorf("hide private invitation: %w", err)
		}
	}
	if err = tx.Commit(ctx); err != nil {
		return ReceivedInvitation{}, fmt.Errorf("commit received invitation: %w", err)
	}
	return i, nil
}
