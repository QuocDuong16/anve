// Package audit records privacy-relevant events (who did what, to whom,
// when) into the audit_log table.
//
// The audit log never contains coordinates or token material — only
// identities, actions, timestamps and small non-sensitive details.
//
// Ownership rule (ADR 0004): every business change records exactly one
// event, and it does so from inside the same database transaction as the
// change. There is deliberately no best-effort path: a mandatory audit row
// that could silently go missing after the business commit is worse than a
// failed request. If an event is not worth a row, it is not an audit event.
package audit

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// Action names. They are part of the stable surface consumed by operators,
// so they are declared here rather than scattered as string literals.
const (
	ActionSignIn                                = "auth.signin"
	ActionDevSignIn                             = "auth.dev_signin"
	ActionSignOut                               = "auth.signout"
	ActionSessionRevoked                        = "auth.session_revoked"
	ActionDeviceRevoked                         = "auth.device_revoked"
	ActionAccountDeleted                        = "auth.account_deleted"
	ActionLocationSharingOn                     = "consent.location_sharing.enabled"
	ActionLocationSharingOff                    = "consent.location_sharing.disabled"
	ActionLocationHistoryDeleted                = "location.history.deleted"
	ActionLocationRetentionChanged              = "location.retention.changed"
	ActionLocationAudienceGrantCreated          = "location.audience_grant.created"
	ActionLocationAudienceGrantRevoked          = "location.audience_grant.revoked"
	ActionLocationAudienceGrantClosedConsent    = "location.audience_grant.closed_consent"
	ActionLocationAudienceGrantClosedMembership = "location.audience_grant.closed_membership"
	ActionLocationSharingEpochOpened            = "location.sharing_epoch.opened"
	ActionLocationSharingEpochClosed            = "location.sharing_epoch.closed"

	ActionCircleCreated   = "circle.created"
	ActionCircleRenamed   = "circle.renamed"
	ActionCircleJoined    = "circle.joined"
	ActionCircleLeft      = "circle.left"
	ActionCircleDissolved = "circle.dissolved"
	ActionMemberRemoved   = "circle.member_removed"
	ActionInviteCreated   = "invite.created"
	ActionInviteRevoked   = "invite.revoked"
)

// Event is one auditable action.
type Event struct {
	// ActorUserID is the account that performed the action.
	ActorUserID uuid.UUID
	// Action names the event (one of the Action* constants or a later
	// milestone's addition).
	Action string
	// TargetUserID is the account the action applies to, when different from
	// the actor.
	TargetUserID *uuid.UUID
	// Detail carries small, non-sensitive context (e.g. a device id). It must
	// never contain coordinates or tokens.
	Detail map[string]any
}

// InTx records an audit event on an existing transaction, so the trail is
// atomic with the business change it describes.
func InTx(ctx context.Context, tx pgx.Tx, e Event) error {
	detail, err := json.Marshal(e.Detail)
	if err != nil {
		return fmt.Errorf("marshal audit detail: %w", err)
	}
	_, err = tx.Exec(ctx,
		`INSERT INTO audit_log (actor_user_id, action, target_user_id, detail)
		 VALUES ($1, $2, $3, $4)`,
		e.ActorUserID, e.Action, e.TargetUserID, detail)
	if err != nil {
		return fmt.Errorf("insert audit row: %w", err)
	}
	return nil
}
