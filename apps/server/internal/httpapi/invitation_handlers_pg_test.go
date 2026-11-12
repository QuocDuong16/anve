package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/audit"
	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/circles"
	"github.com/QuocDuong16/anve/server/internal/notifications"
)

func invitationFixture(t *testing.T) (avatarFixture, *circles.InvitationStore, circles.InvitationPrincipal, circles.InvitationPrincipal) {
	t.Helper()
	f := setupAvatar(t)
	store := circles.NewInvitationStore(f.pool)
	f.router = NewRouter(Deps{Auth: f.auth, Circles: f.circles, Invitations: store})
	session, _, err := f.auth.Authenticate(context.Background(), f.viewerToken.AccessToken)
	require.NoError(t, err)
	return f, store, circles.InvitationPrincipal{UserID: f.owner.ID, SessionID: f.principal.SessionID}, circles.InvitationPrincipal{UserID: f.viewer.ID, SessionID: session.ID}
}

func TestPGPrivateInvitationHTTPNoEnumerationAndBinding(t *testing.T) {
	f, store, owner, recipient := invitationFixture(t)
	ctx := context.Background()
	create := func(email string) circles.Invite {
		body, _ := json.Marshal(circles.InvitationRequest{RequestID: uuid.New(), Mode: "EMAIL", RecipientEmail: email})
		w := f.request(http.MethodPost, "/api/v1/circles/"+f.circle.ID.String()+"/invitations", f.ownerToken.AccessToken, f.owner.ID.String(), 0, uuid.Nil, body)
		require.Equal(t, 201, w.Code, w.Body.String())
		var fields map[string]any
		require.NoError(t, json.Unmarshal(w.Body.Bytes(), &fields))
		require.Len(t, fields, 7)
		require.Equal(t, true, fields["recipientRestricted"])
		for _, key := range []string{"recipientEmail", "registered", "delivered", "dismissed", "token", "recipientUserId"} {
			require.NotContains(t, fields, key)
		}
		require.Equal(t, "no-store, private", w.Header().Get("Cache-Control"))
		var result inviteBody
		require.NoError(t, json.Unmarshal(w.Body.Bytes(), &result))
		return circles.Invite{ID: uuid.MustParse(result.ID), Code: result.Code}
	}
	known := create("  AVATAR-VIEWER@example.test  ")
	_ = create("unknown@example.test")
	inbox, err := store.Received(ctx, recipient)
	require.NoError(t, err)
	require.Len(t, inbox, 1)
	require.Equal(t, known.ID, inbox[0].ID)
	require.Empty(t, inbox[0].Code)
	require.Equal(t, "An", inbox[0].InviterDisplayName)
	other, err := store.Received(ctx, owner)
	require.NoError(t, err)
	require.Empty(t, other)
	_, err = store.ReceivedOne(ctx, owner, known.ID, false)
	require.ErrorIs(t, err, auth.ErrNotFound)
	_, err = f.circles.Preview(ctx, f.owner.ID, known.Code)
	require.ErrorIs(t, err, circles.ErrInviteUnavailable)
	_, _, err = f.circles.Redeem(ctx, f.owner.ID, known.Code)
	require.ErrorIs(t, err, circles.ErrInviteUnavailable)
	detail, err := store.ReceivedOne(ctx, recipient, known.ID, false)
	require.NoError(t, err)
	require.Equal(t, known.Code, detail.Code)
	_, err = f.circles.Preview(ctx, f.viewer.ID, known.Code)
	require.NoError(t, err)
	_, _, err = f.circles.Redeem(ctx, f.viewer.ID, known.Code)
	require.NoError(t, err)
	_, err = store.ReceivedOne(ctx, recipient, known.ID, false)
	require.ErrorIs(t, err, auth.ErrNotFound)
	_, _, err = f.circles.Redeem(ctx, f.viewer.ID, known.Code)
	require.ErrorIs(t, err, circles.ErrInviteUnavailable)
	var consent bool
	require.NoError(t, f.pool.QueryRow(ctx, `SELECT location_sharing_enabled FROM users WHERE id=$1`, f.viewer.ID).Scan(&consent))
	require.False(t, consent)
}

func TestPGInvitationFenceConcurrentReplayAndRetirement(t *testing.T) {
	f, store, owner, recipient := invitationFixture(t)
	ctx := context.Background()
	request := circles.InvitationRequest{RequestID: uuid.New(), Mode: "LINK"}
	var wg sync.WaitGroup
	type result struct {
		invite  circles.Invite
		created bool
		err     error
	}
	outcomes := make(chan result, 2)
	for range 2 {
		wg.Go(func() { i, c, e := store.Create(ctx, owner, f.circle.ID, request); outcomes <- result{i, c, e} })
	}
	wg.Wait()
	close(outcomes)
	created := 0
	var code string
	for r := range outcomes {
		require.NoError(t, r.err)
		if r.created {
			created++
		}
		if code != "" {
			require.Equal(t, code, r.invite.Code)
		}
		code = r.invite.Code
	}
	require.Equal(t, 1, created)
	changed := request
	changed.Mode = "EMAIL"
	changed.RecipientEmail = "other@example.test"
	_, _, err := store.Create(ctx, owner, f.circle.ID, changed)
	require.ErrorIs(t, err, circles.ErrInvitationConflict)
	_, err = store.Created(ctx, recipient, f.circle.ID, request.RequestID)
	require.ErrorIs(t, err, auth.ErrNotFound)
	_, err = store.Created(ctx, owner, f.circle.ID, request.RequestID)
	require.NoError(t, err)
	require.NoError(t, f.circles.RevokeInvite(ctx, f.owner.ID, request.RequestID))
	_, _, err = store.Create(ctx, owner, f.circle.ID, request)
	require.ErrorIs(t, err, circles.ErrInvitationConflict)
	var count int
	require.NoError(t, f.pool.QueryRow(ctx, `SELECT count(*) FROM audit_log WHERE action=$1`, audit.ActionInviteCreated).Scan(&count))
	require.Equal(t, 1, count)
	require.NoError(t, f.pool.QueryRow(ctx, `SELECT count(*) FROM invites WHERE id=$1`, request.RequestID).Scan(&count))
	require.Equal(t, 1, count)
	_, err = f.pool.Exec(ctx, `UPDATE invites SET expires_at=clock_timestamp()-interval '8 days' WHERE id=$1`, request.RequestID)
	require.NoError(t, err)
	require.NoError(t, store.MaintainInvitations(ctx))
	_, _, err = store.Create(ctx, owner, f.circle.ID, request)
	require.ErrorIs(t, err, circles.ErrInvitationConflict)
	_, err = f.pool.Exec(ctx, `UPDATE sessions SET revoked_at=clock_timestamp() WHERE id=$1`, owner.SessionID)
	require.NoError(t, err)
	_, _, err = store.Create(ctx, owner, f.circle.ID, circles.InvitationRequest{RequestID: uuid.New(), Mode: "LINK"})
	require.ErrorIs(t, err, auth.ErrUnauthenticated)
}

type invitationPush struct {
	tokens  []string
	signals []notifications.Signal
	failure error
}

func (p *invitationPush) Send(_ context.Context, token string, signal notifications.Signal) error {
	p.tokens = append(p.tokens, token)
	p.signals = append(p.signals, signal)
	return p.failure
}

func TestPGInvitationDispatchCurrentTokensPrivateHideAndQuota(t *testing.T) {
	f, store, owner, recipient := invitationFixture(t)
	ctx := context.Background()
	makeInvite := func() circles.Invite {
		i, c, e := store.Create(ctx, owner, f.circle.ID, circles.InvitationRequest{RequestID: uuid.New(), Mode: "EMAIL", RecipientEmail: "avatar-viewer@example.test"})
		require.NoError(t, e)
		require.True(t, c)
		return i
	}
	hidden := makeInvite()
	_, err := store.ReceivedOne(ctx, recipient, hidden.ID, true)
	require.NoError(t, err)
	_, err = f.circles.Preview(ctx, f.viewer.ID, hidden.Code)
	require.NoError(t, err) // hiding is private, not global revocation
	sender, err := store.Created(ctx, owner, f.circle.ID, hidden.ID)
	require.NoError(t, err)
	require.Equal(t, hidden.Code, sender.Code)
	signal := makeInvite()
	push := &invitationPush{}
	done, err := store.DispatchInvitation(ctx, push)
	require.NoError(t, err)
	require.True(t, done)
	require.Empty(t, push.tokens)
	// Recipient with no token remains queued, then uses the current live registration.
	done, err = store.DispatchInvitation(ctx, push)
	require.NoError(t, err)
	require.True(t, done)
	require.Empty(t, push.tokens)
	_, err = f.pool.Exec(ctx, `INSERT INTO device_push_tokens(device_id,owner_user_id,session_id,token) SELECT device_id,user_id,id,'private-token' FROM sessions WHERE id=$1`, recipient.SessionID)
	require.NoError(t, err)
	_, err = f.pool.Exec(ctx, `UPDATE invitation_notification_outbox SET next_attempt_at=clock_timestamp() WHERE invite_id=$1`, signal.ID)
	require.NoError(t, err)
	done, err = store.DispatchInvitation(ctx, push)
	require.NoError(t, err)
	require.True(t, done)
	require.Equal(t, []string{"private-token"}, push.tokens)
	require.Equal(t, notifications.Signal{EventID: signal.ID, Kind: "CIRCLE_INVITE"}, push.signals[0])
	_ = makeInvite()
	limited := makeInvite()
	var count int
	require.NoError(t, f.pool.QueryRow(ctx, `SELECT count(*) FROM invitation_notification_outbox WHERE invite_id=$1`, limited.ID).Scan(&count))
	require.Zero(t, count)
	inbox, err := store.Received(ctx, recipient)
	require.NoError(t, err)
	require.Len(t, inbox, 3) // hidden omitted, quota does not hide invitation
	_, err = f.pool.Exec(ctx, `UPDATE sessions SET revoked_at=clock_timestamp() WHERE id=$1`, recipient.SessionID)
	require.NoError(t, err)
	_, err = store.DispatchInvitation(ctx, push)
	require.NoError(t, err)
	require.Len(t, push.tokens, 1)
	require.NoError(t, f.circles.Leave(ctx, f.owner.ID, f.circle.ID, true))
	_, err = f.pool.Exec(ctx, `UPDATE invitation_notification_outbox SET next_attempt_at=clock_timestamp()`)
	require.NoError(t, err)
	done, err = store.DispatchInvitation(ctx, push)
	require.NoError(t, err)
	require.False(t, done)
}

func TestPGInvitationValidationOwnerBudgetAndAccountDeletion(t *testing.T) {
	f, store, owner, _ := invitationFixture(t)
	ctx := context.Background()
	for _, email := range []string{"", "Name <a@example.test>", "a@example.test\nBcc:b@example.test", strings.Repeat("a", 255) + "@test.example"} {
		_, _, err := store.Create(ctx, owner, f.circle.ID, circles.InvitationRequest{RequestID: uuid.New(), Mode: "EMAIL", RecipientEmail: email})
		require.ErrorIs(t, err, auth.ErrValidation)
	}
	for range 20 {
		_, _, err := store.Create(ctx, owner, f.circle.ID, circles.InvitationRequest{RequestID: uuid.New(), Mode: "LINK"})
		require.NoError(t, err)
	}
	_, _, err := store.Create(ctx, owner, f.circle.ID, circles.InvitationRequest{RequestID: uuid.New(), Mode: "LINK"})
	require.ErrorIs(t, err, circles.ErrInvitationBudget)
	_, err = f.pool.Exec(ctx, `UPDATE invitation_creation_fences SET created_at=$1`, time.Now().Add(-25*time.Hour))
	require.NoError(t, err)
	_, _, err = store.Create(ctx, owner, f.circle.ID, circles.InvitationRequest{RequestID: uuid.New(), Mode: "LINK"})
	require.NoError(t, err)
	require.NoError(t, f.auth.DeleteAccount(ctx, f.owner.ID))
	var count int
	require.NoError(t, f.pool.QueryRow(ctx, `SELECT count(*) FROM invitation_creation_fences`).Scan(&count))
	require.Zero(t, count)
}
