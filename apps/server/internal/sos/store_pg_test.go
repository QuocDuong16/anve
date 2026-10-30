package sos_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/auth"
	"github.com/QuocDuong16/anve/server/internal/circles"
	"github.com/QuocDuong16/anve/server/internal/notifications"
	"github.com/QuocDuong16/anve/server/internal/sos"
	"github.com/QuocDuong16/anve/server/internal/testdb"
)

type fakePush struct {
	calls   int
	failure error
	signals []notifications.Signal
}

func (f *fakePush) Send(_ context.Context, _ string, signal notifications.Signal) error {
	f.calls++
	f.signals = append(f.signals, signal)
	return f.failure
}

func TestPGSosExplicitRecipientAndIdempotency(t *testing.T) {
	ctx := context.Background()
	pool := testdb.Scratch(t)
	a := auth.NewService(auth.NewPGStore(pool), nil, true)
	c := circles.NewService(circles.NewPGStore(pool))
	store := sos.NewStore(pool)
	owner, ownerTokens, err := a.DevSignIn(ctx, "owner-sos@example.com", "Owner", auth.DeviceRegistration{ClientDeviceID: "sos-owner"})
	require.NoError(t, err)
	recipient, recipientTokens, err := a.DevSignIn(ctx, "recipient-sos@example.com", "Recipient", auth.DeviceRegistration{ClientDeviceID: "sos-recipient"})
	require.NoError(t, err)
	outsider, outsiderTokens, err := a.DevSignIn(ctx, "outsider-sos@example.com", "Outsider", auth.DeviceRegistration{ClientDeviceID: "sos-outsider"})
	require.NoError(t, err)
	ownerSession, _, err := a.Authenticate(ctx, ownerTokens.AccessToken)
	require.NoError(t, err)
	recipientSession, _, err := a.Authenticate(ctx, recipientTokens.AccessToken)
	require.NoError(t, err)
	outsiderSession, _, err := a.Authenticate(ctx, outsiderTokens.AccessToken)
	require.NoError(t, err)
	ownerPrincipal := sos.Principal{UserID: owner.ID, SessionID: ownerSession.ID, DeviceID: ownerSession.DeviceID}
	recipientPrincipal := sos.Principal{UserID: recipient.ID, SessionID: recipientSession.ID, DeviceID: recipientSession.DeviceID}
	outsiderPrincipal := sos.Principal{UserID: outsider.ID, SessionID: outsiderSession.ID, DeviceID: outsiderSession.DeviceID}
	cancelledBeforeSend := uuid.New()
	_, err = store.Transition(ctx, ownerPrincipal, cancelledBeforeSend, "cancel")
	require.NoError(t, err)
	_, err = store.Transition(ctx, ownerPrincipal, cancelledBeforeSend, "cancel")
	require.NoError(t, err)
	_, _, err = store.Create(ctx, ownerPrincipal, cancelledBeforeSend, time.Now().UTC(), []uuid.UUID{recipient.ID})
	require.ErrorIs(t, err, sos.ErrCancelled)
	require.NoError(t, store.RegisterPushToken(ctx, recipientPrincipal, "test-token"))
	require.NoError(t, store.RegisterPushToken(ctx, recipientPrincipal, "test-token"))
	require.ErrorIs(t, store.RegisterPushToken(ctx, outsiderPrincipal, "test-token"), sos.ErrConflict)
	circle, _, err := c.Create(ctx, owner.ID, "Family")
	require.NoError(t, err)
	invite, err := c.CreateInvite(ctx, owner.ID, circle.ID, nil)
	require.NoError(t, err)
	_, _, err = c.Redeem(ctx, recipient.ID, invite.Code)
	require.NoError(t, err)
	member, _, err := a.DevSignIn(ctx, "unselected-sos@example.com", "Unselected", auth.DeviceRegistration{ClientDeviceID: "sos-unselected"})
	require.NoError(t, err)
	invite, err = c.CreateInvite(ctx, owner.ID, circle.ID, nil)
	require.NoError(t, err)
	_, _, err = c.Redeem(ctx, member.ID, invite.Code)
	require.NoError(t, err)
	id := uuid.New()
	recorded := time.Now().UTC().Truncate(time.Microsecond)
	_, err = pool.Exec(ctx, `CREATE FUNCTION reject_sos_queue() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$;
 CREATE TRIGGER reject_sos_queue BEFORE INSERT ON sos_notification_outbox FOR EACH ROW EXECUTE FUNCTION reject_sos_queue()`)
	require.NoError(t, err)
	_, _, err = store.Create(ctx, ownerPrincipal, id, recorded, []uuid.UUID{recipient.ID})
	require.Error(t, err)
	var rolledBack int
	require.NoError(t, pool.QueryRow(ctx, `SELECT count(*) FROM sos_events WHERE id=$1`, id).Scan(&rolledBack))
	require.Zero(t, rolledBack)
	require.NoError(t, pool.QueryRow(ctx, `SELECT count(*) FROM audit_log WHERE action='sos.created'`).Scan(&rolledBack))
	require.Zero(t, rolledBack)
	_, err = pool.Exec(ctx, `DROP TRIGGER reject_sos_queue ON sos_notification_outbox; DROP FUNCTION reject_sos_queue()`)
	require.NoError(t, err)
	event, created, err := store.Create(ctx, ownerPrincipal, id, recorded, []uuid.UUID{recipient.ID})
	require.NoError(t, err)
	require.True(t, created)
	require.Equal(t, "ACTIVE", event.State)
	_, created, err = store.Create(ctx, ownerPrincipal, id, recorded, []uuid.UUID{recipient.ID})
	require.NoError(t, err)
	require.False(t, created)
	_, _, err = store.Create(ctx, ownerPrincipal, id, recorded.Add(time.Second), []uuid.UUID{recipient.ID})
	require.ErrorIs(t, err, sos.ErrConflict)
	_, err = store.Get(ctx, outsider.ID, id)
	require.ErrorIs(t, err, sos.ErrNotFound)
	_, err = store.Get(ctx, member.ID, id)
	require.ErrorIs(t, err, sos.ErrNotFound, "Circle membership alone must not grant SOS access")
	_, err = store.Transition(ctx, outsiderPrincipal, id, "acknowledge")
	require.ErrorIs(t, err, sos.ErrNotFound)
	event, err = store.Transition(ctx, recipientPrincipal, id, "acknowledge")
	require.NoError(t, err)
	require.NotNil(t, event.AcknowledgedAt)
	_, err = store.Transition(ctx, recipientPrincipal, id, "acknowledge")
	require.NoError(t, err)
	require.NoError(t, a.SignOut(ctx, recipientSession))
	var remainingTokens int
	require.NoError(t, pool.QueryRow(ctx, `SELECT count(*) FROM device_push_tokens WHERE device_id=$1`, recipientSession.DeviceID).Scan(&remainingTokens))
	require.Zero(t, remainingTokens)
	_, err = store.Transition(ctx, recipientPrincipal, id, "acknowledge")
	require.ErrorIs(t, err, auth.ErrUnauthenticated)
	event, err = store.Transition(ctx, ownerPrincipal, id, "resolve")
	require.NoError(t, err)
	require.Equal(t, "RESOLVED", event.State)
	var outboxState string
	require.NoError(t, pool.QueryRow(ctx, `SELECT state FROM sos_notification_outbox WHERE event_id=$1`, id).Scan(&outboxState))
	require.Equal(t, "SUPPRESSED", outboxState)
	_, err = store.Transition(ctx, ownerPrincipal, id, "cancel")
	require.ErrorIs(t, err, sos.ErrConflict)
	var count int
	require.NoError(t, pool.QueryRow(ctx, `SELECT count(*) FROM audit_log WHERE action LIKE 'sos.%'`).Scan(&count))
	require.Equal(t, 4, count)
	require.NoError(t, c.Leave(ctx, recipient.ID, circle.ID, false))
	_, err = store.Get(ctx, recipient.ID, id)
	require.ErrorIs(t, err, sos.ErrNotFound)
	invite, err = c.CreateInvite(ctx, owner.ID, circle.ID, nil)
	require.NoError(t, err)
	_, _, err = c.Redeem(ctx, recipient.ID, invite.Code)
	require.NoError(t, err)
	_, err = store.Get(ctx, recipient.ID, id)
	require.ErrorIs(t, err, sos.ErrNotFound, "rejoining must not restore a prior SOS recipient decision")
}

func TestPGSosDispatchRetryAndProviderAcceptance(t *testing.T) {
	ctx := context.Background()
	pool := testdb.Scratch(t)
	a := auth.NewService(auth.NewPGStore(pool), nil, true)
	c := circles.NewService(circles.NewPGStore(pool))
	store := sos.NewStore(pool)
	owner, ownerTokens, err := a.DevSignIn(ctx, "dispatch-owner@example.com", "Owner", auth.DeviceRegistration{ClientDeviceID: "dispatch-owner"})
	require.NoError(t, err)
	recipient, recipientTokens, err := a.DevSignIn(ctx, "dispatch-recipient@example.com", "Recipient", auth.DeviceRegistration{ClientDeviceID: "dispatch-recipient"})
	require.NoError(t, err)
	ownerSession, _, err := a.Authenticate(ctx, ownerTokens.AccessToken)
	require.NoError(t, err)
	recipientSession, _, err := a.Authenticate(ctx, recipientTokens.AccessToken)
	require.NoError(t, err)
	ownerPrincipal := sos.Principal{UserID: owner.ID, SessionID: ownerSession.ID, DeviceID: ownerSession.DeviceID}
	recipientPrincipal := sos.Principal{UserID: recipient.ID, SessionID: recipientSession.ID, DeviceID: recipientSession.DeviceID}
	circle, _, err := c.Create(ctx, owner.ID, "Family")
	require.NoError(t, err)
	invite, err := c.CreateInvite(ctx, owner.ID, circle.ID, nil)
	require.NoError(t, err)
	_, _, err = c.Redeem(ctx, recipient.ID, invite.Code)
	require.NoError(t, err)
	require.NoError(t, store.RegisterPushToken(ctx, recipientPrincipal, "test-push-token"))
	revokedID := uuid.New()
	_, _, err = store.Create(ctx, ownerPrincipal, revokedID, time.Now().UTC(), []uuid.UUID{recipient.ID})
	require.NoError(t, err)
	require.ErrorIs(t, store.RevokeRecipient(ctx, recipientPrincipal, revokedID, owner.ID), sos.ErrNotFound)
	require.NoError(t, store.RevokeRecipient(ctx, ownerPrincipal, revokedID, recipient.ID))
	require.NoError(t, store.RevokeRecipient(ctx, ownerPrincipal, revokedID, recipient.ID))
	_, err = store.Get(ctx, recipient.ID, revokedID)
	require.ErrorIs(t, err, sos.ErrNotFound)
	_, err = store.Transition(ctx, recipientPrincipal, revokedID, "acknowledge")
	require.ErrorIs(t, err, sos.ErrNotFound)
	var revokedState string
	require.NoError(t, pool.QueryRow(ctx, `SELECT state FROM sos_notification_outbox WHERE event_id=$1`, revokedID).Scan(&revokedState))
	require.Equal(t, "SUPPRESSED", revokedState)
	id := uuid.New()
	_, _, err = store.Create(ctx, ownerPrincipal, id, time.Now().UTC(), []uuid.UUID{recipient.ID})
	require.NoError(t, err)
	provider := &fakePush{failure: errors.New("temporary provider error")}
	processed, err := store.DispatchOne(ctx, provider)
	require.NoError(t, err)
	require.True(t, processed)
	require.Equal(t, 1, provider.calls)
	var state string
	require.NoError(t, pool.QueryRow(ctx, `SELECT state FROM sos_notification_outbox WHERE event_id=$1`, id).Scan(&state))
	require.Equal(t, "QUEUED", state)
	_, err = pool.Exec(ctx, `UPDATE sos_notification_targets SET next_attempt_at=clock_timestamp(); UPDATE sos_notification_outbox SET next_attempt_at=clock_timestamp()`)
	require.NoError(t, err)
	provider.failure = nil
	processed, err = store.DispatchOne(ctx, provider)
	require.NoError(t, err)
	require.True(t, processed)
	require.Equal(t, 2, provider.calls)
	require.Equal(t, notifications.Signal{EventID: id, Kind: "SOS"}, provider.signals[0])
	require.NoError(t, pool.QueryRow(ctx, `SELECT state FROM sos_notification_outbox WHERE event_id=$1`, id).Scan(&state))
	require.Equal(t, "ACCEPTED", state)
	statuses, err := store.Recipients(ctx, owner.ID, id)
	require.NoError(t, err)
	require.Len(t, statuses, 1)
	require.Equal(t, "ACCEPTED", statuses[0].PushState)
	require.NotNil(t, statuses[0].AcceptedAt)
	require.Nil(t, statuses[0].AcknowledgedAt)
	_, err = store.Recipients(ctx, recipient.ID, id)
	require.ErrorIs(t, err, sos.ErrNotFound)
	processed, err = store.DispatchOne(ctx, provider)
	require.NoError(t, err)
	require.False(t, processed)
	require.Equal(t, 2, provider.calls)
	invalidID := uuid.New()
	_, _, err = store.Create(ctx, ownerPrincipal, invalidID, time.Now().UTC(), []uuid.UUID{recipient.ID})
	require.NoError(t, err)
	provider.failure = notifications.ErrInvalidToken
	processed, err = store.DispatchOne(ctx, provider)
	require.NoError(t, err)
	require.True(t, processed)
	var tokenCount int
	require.NoError(t, pool.QueryRow(ctx, `SELECT count(*) FROM device_push_tokens WHERE device_id=$1`, recipientSession.DeviceID).Scan(&tokenCount))
	require.Zero(t, tokenCount)
	require.NoError(t, pool.QueryRow(ctx, `SELECT state FROM sos_notification_outbox WHERE event_id=$1`, invalidID).Scan(&state))
	require.Equal(t, "QUEUED", state)

	require.NoError(t, store.RegisterPushToken(ctx, recipientPrincipal, "replacement-test-token"))
	require.NoError(t, c.Leave(ctx, recipient.ID, circle.ID, false))
	_, err = pool.Exec(ctx, `UPDATE sos_notification_outbox SET next_attempt_at=clock_timestamp() WHERE event_id=$1`, invalidID)
	require.NoError(t, err)
	processed, err = store.DispatchOne(ctx, provider)
	require.NoError(t, err)
	require.True(t, processed)
	require.Equal(t, 3, provider.calls, "a removed recipient must not trigger a new push")
	require.NoError(t, pool.QueryRow(ctx, `SELECT state FROM sos_notification_outbox WHERE event_id=$1`, invalidID).Scan(&state))
	require.Equal(t, "SUPPRESSED", state)
}

type blockingPush struct {
	started chan struct{}
	release chan struct{}
	calls   chan notifications.Signal
}

func (p *blockingPush) Send(_ context.Context, _ string, signal notifications.Signal) error {
	p.calls <- signal
	close(p.started)
	<-p.release
	return nil
}

func TestPGSosCancellationOrdersWithDispatch(t *testing.T) {
	ctx := context.Background()
	pool := testdb.Scratch(t)
	a := auth.NewService(auth.NewPGStore(pool), nil, true)
	c := circles.NewService(circles.NewPGStore(pool))
	store := sos.NewStore(pool)
	owner, ownerTokens, err := a.DevSignIn(ctx, "race-owner@example.com", "Owner", auth.DeviceRegistration{ClientDeviceID: "race-owner"})
	require.NoError(t, err)
	viewer, viewerTokens, err := a.DevSignIn(ctx, "race-viewer@example.com", "Viewer", auth.DeviceRegistration{ClientDeviceID: "race-viewer"})
	require.NoError(t, err)
	os, _, err := a.Authenticate(ctx, ownerTokens.AccessToken)
	require.NoError(t, err)
	vs, _, err := a.Authenticate(ctx, viewerTokens.AccessToken)
	require.NoError(t, err)
	op := sos.Principal{UserID: owner.ID, SessionID: os.ID, DeviceID: os.DeviceID}
	vp := sos.Principal{UserID: viewer.ID, SessionID: vs.ID, DeviceID: vs.DeviceID}
	circle, _, err := c.Create(ctx, owner.ID, "Family")
	require.NoError(t, err)
	invite, err := c.CreateInvite(ctx, owner.ID, circle.ID, nil)
	require.NoError(t, err)
	_, _, err = c.Redeem(ctx, viewer.ID, invite.Code)
	require.NoError(t, err)
	require.NoError(t, store.RegisterPushToken(ctx, vp, "race-token"))
	cancelled := uuid.New()
	_, _, err = store.Create(ctx, op, cancelled, time.Now().UTC(), []uuid.UUID{viewer.ID})
	require.NoError(t, err)
	_, err = store.Transition(ctx, op, cancelled, "cancel")
	require.NoError(t, err)
	provider := &fakePush{}
	processed, err := store.DispatchOne(ctx, provider)
	require.NoError(t, err)
	require.False(t, processed)
	require.Zero(t, provider.calls)
	id := uuid.New()
	_, _, err = store.Create(ctx, op, id, time.Now().UTC(), []uuid.UUID{viewer.ID})
	require.NoError(t, err)
	gateway := &blockingPush{started: make(chan struct{}), release: make(chan struct{}), calls: make(chan notifications.Signal, 2)}
	dispatched := make(chan error, 1)
	cancelledDone := make(chan error, 1)
	go func() { _, err := store.DispatchOne(ctx, gateway); dispatched <- err }()
	<-gateway.started
	go func() { _, err := store.Transition(ctx, op, id, "cancel"); cancelledDone <- err }()
	close(gateway.release)
	require.NoError(t, <-dispatched)
	require.NoError(t, <-cancelledDone)
	event, err := store.Get(ctx, owner.ID, id)
	require.NoError(t, err)
	require.Equal(t, "CANCELLED", event.State)
	processed, err = store.DispatchOne(ctx, gateway)
	require.NoError(t, err)
	require.False(t, processed)
	require.Len(t, gateway.calls, 1, "cancellation must not cause another push")
}

func TestPGSosTargetsMultipleDevicesAndCleansRevokedSessions(t *testing.T) {
	ctx := context.Background()
	pool := testdb.Scratch(t)
	a := auth.NewService(auth.NewPGStore(pool), nil, true)
	c := circles.NewService(circles.NewPGStore(pool))
	store := sos.NewStore(pool)
	owner, ot, err := a.DevSignIn(ctx, "multi-owner@example.com", "Owner", auth.DeviceRegistration{ClientDeviceID: "multi-owner"})
	require.NoError(t, err)
	viewer, vt, err := a.DevSignIn(ctx, "multi-viewer@example.com", "Viewer", auth.DeviceRegistration{ClientDeviceID: "multi-one"})
	require.NoError(t, err)
	viewerAgain, vt2, err := a.DevSignIn(ctx, "multi-viewer@example.com", "Viewer", auth.DeviceRegistration{ClientDeviceID: "multi-two"})
	require.NoError(t, err)
	require.Equal(t, viewer.ID, viewerAgain.ID)
	os, _, err := a.Authenticate(ctx, ot.AccessToken)
	require.NoError(t, err)
	v1, _, err := a.Authenticate(ctx, vt.AccessToken)
	require.NoError(t, err)
	v2, _, err := a.Authenticate(ctx, vt2.AccessToken)
	require.NoError(t, err)
	op := sos.Principal{UserID: owner.ID, SessionID: os.ID, DeviceID: os.DeviceID}
	p1 := sos.Principal{UserID: viewer.ID, SessionID: v1.ID, DeviceID: v1.DeviceID}
	p2 := sos.Principal{UserID: viewer.ID, SessionID: v2.ID, DeviceID: v2.DeviceID}
	circle, _, err := c.Create(ctx, owner.ID, "Family")
	require.NoError(t, err)
	invite, err := c.CreateInvite(ctx, owner.ID, circle.ID, nil)
	require.NoError(t, err)
	_, _, err = c.Redeem(ctx, viewer.ID, invite.Code)
	require.NoError(t, err)
	require.NoError(t, store.RegisterPushToken(ctx, p1, "first-token"))
	require.NoError(t, store.RegisterPushToken(ctx, p2, "second-token"))
	id := uuid.New()
	_, _, err = store.Create(ctx, op, id, time.Now().UTC(), []uuid.UUID{viewer.ID})
	require.NoError(t, err)
	provider := &fakePush{}
	processed, err := store.DispatchOne(ctx, provider)
	require.NoError(t, err)
	require.True(t, processed)
	var state string
	require.NoError(t, pool.QueryRow(ctx, `SELECT state FROM sos_notification_outbox WHERE event_id=$1`, id).Scan(&state))
	require.Equal(t, "QUEUED", state)
	statuses, err := store.Recipients(ctx, owner.ID, id)
	require.NoError(t, err)
	require.Len(t, statuses, 1)
	require.NotNil(t, statuses[0].AcceptedAt, "the first accepted device is recorded while another remains queued")
	_, err = pool.Exec(ctx, `UPDATE sos_notification_outbox SET next_attempt_at=clock_timestamp() WHERE event_id=$1`, id)
	require.NoError(t, err)
	processed, err = store.DispatchOne(ctx, provider)
	require.NoError(t, err)
	require.True(t, processed)
	require.Equal(t, 2, provider.calls)
	require.NoError(t, pool.QueryRow(ctx, `SELECT state FROM sos_notification_outbox WHERE event_id=$1`, id).Scan(&state))
	require.Equal(t, "ACCEPTED", state)
	require.NoError(t, a.SignOut(ctx, v1))
	var count int
	require.NoError(t, pool.QueryRow(ctx, `SELECT count(*) FROM device_push_tokens WHERE owner_user_id=$1`, viewer.ID).Scan(&count))
	require.Equal(t, 1, count)
	registered, err := store.PushTokenRegistered(ctx, p2)
	require.NoError(t, err)
	require.True(t, registered)
}

func TestPGSosRetentionExpiresAndReservesEventID(t *testing.T) {
	ctx := context.Background()
	pool := testdb.Scratch(t)
	a := auth.NewService(auth.NewPGStore(pool), nil, true)
	c := circles.NewService(circles.NewPGStore(pool))
	store := sos.NewStore(pool)
	owner, ot, err := a.DevSignIn(ctx, "retention-owner@example.com", "Owner", auth.DeviceRegistration{ClientDeviceID: "retention-owner"})
	require.NoError(t, err)
	viewer, vt, err := a.DevSignIn(ctx, "retention-viewer@example.com", "Viewer", auth.DeviceRegistration{ClientDeviceID: "retention-viewer"})
	require.NoError(t, err)
	os, _, err := a.Authenticate(ctx, ot.AccessToken)
	require.NoError(t, err)
	vs, _, err := a.Authenticate(ctx, vt.AccessToken)
	require.NoError(t, err)
	op := sos.Principal{UserID: owner.ID, SessionID: os.ID, DeviceID: os.DeviceID}
	vp := sos.Principal{UserID: viewer.ID, SessionID: vs.ID, DeviceID: vs.DeviceID}
	circle, _, err := c.Create(ctx, owner.ID, "Family")
	require.NoError(t, err)
	invite, err := c.CreateInvite(ctx, owner.ID, circle.ID, nil)
	require.NoError(t, err)
	_, _, err = c.Redeem(ctx, viewer.ID, invite.Code)
	require.NoError(t, err)
	id := uuid.New()
	_, _, err = store.Create(ctx, op, id, time.Now().UTC(), []uuid.UUID{viewer.ID})
	require.NoError(t, err)
	_, err = pool.Exec(ctx, `UPDATE sos_events SET received_at=clock_timestamp()-interval '8 days' WHERE id=$1`, id)
	require.NoError(t, err)
	event, err := store.Get(ctx, viewer.ID, id)
	require.NoError(t, err)
	require.Equal(t, "EXPIRED", event.State, "reads enforce expiry before cleanup")
	_, err = store.Transition(ctx, vp, id, "acknowledge")
	require.ErrorIs(t, err, sos.ErrConflict)
	processed, err := store.RetainOne(ctx)
	require.NoError(t, err)
	require.True(t, processed)
	var state string
	require.NoError(t, pool.QueryRow(ctx, `SELECT state FROM sos_notification_outbox WHERE event_id=$1`, id).Scan(&state))
	require.Equal(t, "EXPIRED", state)
	_, err = pool.Exec(ctx, `UPDATE sos_events SET received_at=clock_timestamp()-interval '31 days' WHERE id=$1`, id)
	require.NoError(t, err)
	_, err = store.Get(ctx, owner.ID, id)
	require.ErrorIs(t, err, sos.ErrNotFound, "reads enforce retention before cleanup")
	processed, err = store.RetainOne(ctx)
	require.NoError(t, err)
	require.True(t, processed)
	var retired int
	require.NoError(t, pool.QueryRow(ctx, `SELECT count(*) FROM sos_retired_requests WHERE event_id=$1`, id).Scan(&retired))
	require.Equal(t, 1, retired)
	_, _, err = store.Create(ctx, op, id, time.Now().UTC(), []uuid.UUID{viewer.ID})
	require.ErrorIs(t, err, sos.ErrConflict)
	_, err = store.Transition(ctx, op, id, "cancel")
	require.ErrorIs(t, err, sos.ErrConflict)
}

func TestPGPushTokenCannotMoveFromLiveSessionButCanAfterExpiry(t *testing.T) {
	ctx := context.Background()
	pool := testdb.Scratch(t)
	a := auth.NewService(auth.NewPGStore(pool), nil, true)
	store := sos.NewStore(pool)
	first, ft, err := a.DevSignIn(ctx, "token-first@example.com", "First", auth.DeviceRegistration{ClientDeviceID: "shared-installation"})
	require.NoError(t, err)
	second, st, err := a.DevSignIn(ctx, "token-second@example.com", "Second", auth.DeviceRegistration{ClientDeviceID: "shared-installation"})
	require.NoError(t, err)
	fs, _, err := a.Authenticate(ctx, ft.AccessToken)
	require.NoError(t, err)
	ss, _, err := a.Authenticate(ctx, st.AccessToken)
	require.NoError(t, err)
	fp := sos.Principal{UserID: first.ID, SessionID: fs.ID, DeviceID: fs.DeviceID}
	sp := sos.Principal{UserID: second.ID, SessionID: ss.ID, DeviceID: ss.DeviceID}
	require.NoError(t, store.RegisterPushToken(ctx, fp, "rotating-token"))
	require.ErrorIs(t, store.RegisterPushToken(ctx, sp, "rotating-token"), sos.ErrConflict)
	_, err = pool.Exec(ctx, `UPDATE sessions SET refresh_expires_at=clock_timestamp()-interval '1 second' WHERE id=$1`, fs.ID)
	require.NoError(t, err)
	require.NoError(t, store.RegisterPushToken(ctx, sp, "rotating-token"))
	registered, err := store.PushTokenRegistered(ctx, sp)
	require.NoError(t, err)
	require.True(t, registered)
}
