# ADR 0012 — Explicit Safe Journey lifecycle

- **Status:** Accepted for implementation; verification recorded in progress.md.
- **Scope:** M7-B first release. The user selected manual arrival and the
  steps-led native composition (option B) on 2026-11-11.

## Lifecycle and admission

A journey is server-authoritative: ACTIVE → ARRIVED, ENDED, CANCELLED or EXPIRED.
ARRIVED is an explicit owner assertion, never an inference from GPS. There is
at most one active journey per owner, across devices. The owner selects a
duration from 15 minutes to 8 hours; expiry is an upper bound, not an ETA.
Destination and audience additions are immutable: end and explicitly start a
new journey to change them. Individual recipients can be removed immediately.

Creation needs a currently authenticated device, server-confirmed location
consent, a current Circle/sharing epoch and at least one explicitly selected
active location-audience grant. Journey selection narrows those grants; it
never creates or expands them. Each selection binds to the exact grant ID, so
re-granting or leaving/rejoining cannot restore a prior journey invitation.
Owner reads survive consent OFF for reconciliation; recipient reads require
current authorization even after arrival. No GPS acquisition starts on a push,
reboot, process recreation or response to an old account.

## Unknown outcomes and stopping

Clients choose a stable journey UUID and immutable request before admission.
The request includes a preparation timestamp, acceptable for two minutes, so
fresh offline creation is refused. Exact retries reconcile an existing request
without renewing its expiry. Conflicting UUID reuse fails. Cancellation can
install an owner-bound fence before creation commits; a later create cannot
resurrect it. Terminal transitions are idempotent only for the same terminal
state. A timeout or 5xx remains unknown until an owner-bound read or same-ID
operation resolves it. No automatic rebase, new ID or success message.

Ending locally immediately removes JOURNEY priority; server-confirmed ordinary
sharing remains a separate choice. Consent OFF, lost membership, expiry, no
remaining selected authorized recipient, or a device/session switch removes
priority. Local cancellation intent must be durable before sending and is
parked for the original owner across account changes. No fresh process resumes
acquisition automatically; the user explicitly resumes ordinary tracking.

## Data minimization and retention

Store destination, bounded label, timestamps, state, exact grant references,
request hash and notification work; never duplicate location points or routes.
Progress uses current M4-authorized samples and their timestamps. Straight-line
distance is not route distance, progress percentage or time to arrival.
Journey content is readable for at most seven days and never beyond the
owner/server location-retention boundary. History deletion or an owner retention
change deletes all that owner's journey content, conservatively including its
destination; a minimal UUID fence prevents recreation until account deletion.
Account deletion cascades content and fences. Audit contains IDs/state only,
never destination labels, coordinates or recipient names.

## Notifications and evidence

Each lifecycle transition creates bounded, durable per-recipient notification
work in the same transaction. Dispatch rechecks the exact selected grant,
current consent/membership, journey retention and state; revocation suppresses
work. FCM contains an opaque journey ID and a lifecycle kind only. Provider
acceptance is not delivery or a read receipt. Client display rechecks access.

Automatic arrival and stale-journey alerts remain **Planned** until accuracy,
dwell, gaps and alert thresholds have field evidence. Synthetic tests and CI
emulators cannot prove real GPS, battery, OEM behavior or Firebase delivery.
