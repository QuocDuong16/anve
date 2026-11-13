package circles_test

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/QuocDuong16/anve/server/internal/circles"
)

func TestPreviewDoesNotConsumeInviteOrJoinOrEnableSharing(t *testing.T) {
	svc, authSvc, pool := newStack(t)
	ctx := context.Background()
	owner := signIn(t, authSvc, "owner-preview@example.com")
	joiner := signIn(t, authSvc, "joiner-preview@example.com")
	circle, _, err := svc.Create(ctx, owner, "Nhà mình")
	require.NoError(t, err)
	invite, err := svc.CreateInvite(ctx, owner, circle.ID, nil)
	require.NoError(t, err)
	for range 2 {
		preview, previewErr := svc.Preview(ctx, joiner, invite.Code)
		require.NoError(t, previewErr)
		require.Equal(t, circle.ID, preview.CircleID)
		require.Equal(t, circle.Name, preview.CircleName)
		require.WithinDuration(t, invite.ExpiresAt, preview.ExpiresAt, time.Microsecond)
	}
	_, membership, err := svc.MyCircle(ctx, joiner)
	require.NoError(t, err)
	require.Nil(t, membership)
	_, _, err = svc.Redeem(ctx, joiner, invite.Code)
	require.NoError(t, err)
	var sharing bool
	require.NoError(t, pool.QueryRow(ctx, `SELECT location_sharing_enabled FROM users WHERE id=$1`, joiner).Scan(&sharing))
	require.False(t, sharing)
	_, err = svc.Preview(ctx, joiner, invite.Code)
	require.ErrorIs(t, err, circles.ErrInviteUnavailable)
}

func TestPreviewRejectsExpiredRevokedUnknownAndDeletedCircles(t *testing.T) {
	svc, authSvc, pool := newStack(t)
	ctx := context.Background()
	owner := signIn(t, authSvc, "preview-owner@example.com")
	joiner := signIn(t, authSvc, "preview-joiner@example.com")
	circle, _, err := svc.Create(ctx, owner, "Nhà")
	require.NoError(t, err)
	expired, err := svc.CreateInvite(ctx, owner, circle.ID, nil)
	require.NoError(t, err)
	_, err = pool.Exec(ctx, `UPDATE invites SET expires_at=now()-interval '1 second' WHERE id=$1`, expired.ID)
	require.NoError(t, err)
	revoked, err := svc.CreateInvite(ctx, owner, circle.ID, nil)
	require.NoError(t, err)
	require.NoError(t, svc.RevokeInvite(ctx, owner, revoked.ID))
	valid, err := svc.CreateInvite(ctx, owner, circle.ID, nil)
	require.NoError(t, err)
	_, err = svc.Preview(ctx, uuid.New(), valid.Code)
	require.ErrorIs(t, err, circles.ErrInviteUnavailable)
	for _, code := range []string{expired.Code, revoked.Code, "UNKNOWN123"} {
		_, err = svc.Preview(ctx, joiner, code)
		require.ErrorIs(t, err, circles.ErrInviteUnavailable)
	}
	// A preview never reserves a code: revocation between preview and confirm wins.
	_, err = svc.Preview(ctx, joiner, valid.Code)
	require.NoError(t, err)
	require.NoError(t, svc.RevokeInvite(ctx, owner, valid.ID))
	_, _, err = svc.Redeem(ctx, joiner, valid.Code)
	require.ErrorIs(t, err, circles.ErrInviteUnavailable)
	deleted, err := svc.CreateInvite(ctx, owner, circle.ID, nil)
	require.NoError(t, err)
	require.NoError(t, svc.Leave(ctx, owner, circle.ID, true))
	_, err = svc.Preview(ctx, joiner, deleted.Code)
	require.ErrorIs(t, err, circles.ErrInviteUnavailable)
}
