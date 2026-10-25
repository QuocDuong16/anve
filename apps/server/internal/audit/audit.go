// Package audit records privacy-relevant events (who did what, to whom,
// when) into the audit_log table.
//
// The audit log never contains coordinates or token material — only
// identities, actions, timestamps and small non-sensitive details.
package audit

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Action names. They are part of the stable surface consumed by operators,
// so they are declared here rather than scattered as string literals.
const (
	ActionSignIn             = "auth.signin"
	ActionDevSignIn          = "auth.dev_signin"
	ActionSignOut            = "auth.signout"
	ActionSessionRevoked     = "auth.session_revoked"
	ActionDeviceRevoked      = "auth.device_revoked"
	ActionAccountDeleted     = "auth.account_deleted"
	ActionLocationSharingOn  = "consent.location_sharing.enabled"
	ActionLocationSharingOff = "consent.location_sharing.disabled"
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

// Recorder stores audit events. Implementations must be safe for concurrent
// use.
type Recorder interface {
	Record(ctx context.Context, e Event) error
}

// PG records audit events into the audit_log table.
type PG struct {
	pool *pgxpool.Pool
}

// NewPG returns a PostgreSQL-backed recorder.
func NewPG(pool *pgxpool.Pool) *PG {
	return &PG{pool: pool}
}

// Record inserts one audit row.
func (p *PG) Record(ctx context.Context, e Event) error {
	detail, err := json.Marshal(e.Detail)
	if err != nil {
		return fmt.Errorf("marshal audit detail: %w", err)
	}
	_, err = p.pool.Exec(ctx,
		`INSERT INTO audit_log (actor_user_id, action, target_user_id, detail)
		 VALUES ($1, $2, $3, $4)`,
		e.ActorUserID, e.Action, e.TargetUserID, detail)
	if err != nil {
		return fmt.Errorf("insert audit row: %w", err)
	}
	return nil
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

// BestEffort records an event and, on failure, logs instead of failing the
// caller's operation. Used where the operation itself already committed and
// a missing audit row must not roll back user-visible state.
func BestEffort(ctx context.Context, r Recorder, e Event) {
	if err := r.Record(ctx, e); err != nil {
		slog.WarnContext(ctx, "audit record failed", "action", e.Action, "error", err)
	}
}
