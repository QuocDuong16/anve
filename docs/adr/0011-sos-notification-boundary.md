# ADR 0011 — SOS authorization and notification boundary

- **Status:** Accepted; implementation verification and external Firebase validation in progress.

An SOS is explicitly created by its owner and explicitly addressed to selected
members. This first contract never attaches coordinates and never starts GPS;
consent OFF therefore does not prevent asking for help or activate tracking.
Each recipient is bound to the exact current Circle membership period and
reads recheck the sender and recipient's current membership. Leaving and
rejoining does not restore access. The sender can revoke an individual
recipient for an existing event; pending push is suppressed, and access cannot
be restored without creating a new SOS. SOS does not contact emergency services.

The client chooses the event ID before submission. A retried ID is only
idempotent when the complete request matches; conflicting reuse must fail.
Cancellation, resolution and acknowledgement are separate authenticated
transitions. Notification acceptance by a provider must remain separate from
recipient acknowledgement and must never be represented as confirmed delivery.
No coordinates or tokens belong in push payloads or operational logs.
Push registration cannot take a token from another live session; an expired or
revoked session's registration can be removed before a new account registers
that token. An offline sign-out may leave the old live registration in place
until revocation reaches the server or the session expires.
An owner may cancel a client event ID before its create commits. The server
records an owner-scoped cancellation fence under an event-ID transaction lock;
later creates using that ID are rejected. This avoids recreating an uncertain
request merely to discover whether it was previously committed. Fences are
retained until account deletion, so their growth requires operational review.
Active SOS events expire after seven days. Reads stop exposing them after 30
days; a bounded cleanup worker removes old payloads, potentially later when a
backlog exists. Reads apply both limits before cleanup runs.
An owner-scoped ID fence survives event cleanup until account deletion to avoid
recreating an old event; fence growth requires operational review.

The Android outbox persists `SENDING` before network admission. After process
interruption it becomes `UNKNOWN`, because the server may have committed the
request; the same event ID can be reconciled or retried without creating a new
event. Cancelling an uncertain request records a cancellation intent and uses
the server fence instead of replaying a create. A signed-out owner's encrypted
queue is parked until that owner signs in again. Unsent requests expire after
seven days; ambiguous requests remain visible for reconciliation rather than
being silently reported as unsent. Automatic cancellation retries also stop
after seven days from the cancellation request; the pending state remains and
the user can explicitly recheck it.

FCM delivery and offline recovery are not implemented by this decision alone.
They require durable owner-scoped storage, authorization at dispatch and
explicit verification before M6 is marked complete.

The work-in-progress HTTP v1 gateway uses Application Default Credentials and
an explicit Firebase project ID. It sends data-only signals containing an
opaque event ID and event kind, never coordinates. A provider 2xx denotes
acceptance only; a timeout after acceptance can cause an at-least-once retry.
Client-side durable notification deduplication is required before enabling it.
When `FCM_PROJECT_ID` and Application Default Credentials are configured, the
server starts the bounded dispatchers. Without configuration, events can be
recorded but notification work remains queued; the Android screen reports that
push is not configured. Configuration or provider acceptance does not prove
device receipt. Runtime integration and end-to-end verification remain in
progress.
Dispatch holds the event and authorization locks through a bounded provider
attempt (up to ten seconds) so a completed revocation precedes subsequent
attempts. This can delay concurrent revocation or deletion while a send is in
flight; a provider-accepted signal cannot be recalled, and the device must
recheck authorization before displaying event information.
