# ADR 0016 — Explicit journey automation and invitation previews

- **Status:** Accepted; backend and native Android implementation, approved B/C comps and validation evidence are tracked in [feature completion](../implementation/feature-completion.md).
- **Scope:** Extends ADR 0012. The owner approved automatic arrival being OFF by default and selected the journey owner plus currently authorized selected viewers as reminder recipients.

## Automatic arrival

`automaticArrival` is immutable and explicitly selected for each new journey;
omitting it preserves the manual lifecycle. It never starts location capture.
The server consumes existing post-start points in the journey's sharing epoch
and the owner's current capture generation. A privacy checkpoint resets the
automation evidence window. Expiry and lost authorization take precedence.

The initial engineering rule requires at least three distinct captures from
one device spanning two minutes, each with accuracy at most 50 m and
distance plus accuracy within a 150 m destination radius. No gap may exceed
90 seconds; the latest capture must be at most 60 seconds old. Receipt must
be within 60 seconds of capture (five seconds of clock skew allowed). Mock,
mixed-device, out-of-order or invalid measurements break the evidence run.
Duplicate timestamps cannot manufacture dwell. These thresholds are
conservative implementation defaults, **not field-calibrated guarantees**.

An estimate transitions atomically to ARRIVED with `arrivalSource=AUTOMATIC`;
manual arrival uses MANUAL. Audit and selected-viewer notification work commit
with the transition. The client must label automatic arrival as an estimate,
keep manual arrival available and stop JOURNEY priority through the existing
server-confirmed lease. Ordinary location sharing remains a separate choice.

## Missing updates

`staleAfterMinutes` is per-journey and immutable: 0 disables it; the supported
thresholds are 5, 10, 15 and 30 minutes. It measures absence of fresh, non-mock,
promptly received captures, not absence of movement. With no live sample,
the window begins at journey creation or the latest privacy checkpoint.

One stale episode enqueues one reminder for the owner and each currently
authorized selected viewer. A fresh sample clears the stale state and rearms
a later gap. Episode counters survive recovery to distinguish delayed signals.
State, work creation, one audit event per new episode and recovery suppression
commit under the owner lock.
Bounded maintenance rotates owners and operates without Firebase configured.

The outbox and device-target identity includes the episode counter (zero for
existing lifecycle events). Owner reminders are permitted only for STALE;
every viewer send still locks and rechecks the exact current grant and
membership. Dispatch reevaluates samples before sending; recovery must commit
even if that evaluation suppresses the selected queued job.

FCM contains `eventId`, `kind=JOURNEY_STALE` and the opaque positive `episode`
counter. Other event payloads keep their existing ID/kind shape. There are no
coordinates, destination labels, names or tokens in event data. Android fetches
the authorized journey online and checks ACTIVE, stale state and the same
episode before displaying; dedup includes owner and episode. No claim of an
accident or guaranteed delivery is allowed. Required user copy is
“Hành trình chưa có cập nhật mới — hãy liên hệ”.

Derived timestamps and counters remain journey content under existing
retention/deletion rules. Reads hide stale data after expiry and hide metadata
from a prior capture generation before maintenance catches up. UUID request
fences remain unchanged. Optional default fields are omitted from the server's
canonical request hash, preserving existing manual idempotency across upgrade.

## Invitation links

Initial native links use `anve://invite/<10-character code>` with strict scheme,
authority, alphabet and length checks. Credentials, ports, encoded or extra
path segments, query strings and fragments are rejected. Codes are bearer
capabilities: do not log them, include them in diagnostics or auto-submit them.
Custom schemes are not verified domain ownership; public HTTPS App Links need
separate hosting, app-signing certificate and assetlinks setup before any claim
of verified links. A manual code remains the fallback.

Opening a link must first authenticate, fetch a preview, then require an
explicit confirmation. `POST /api/v1/invites/preview` carries the code in the
body, requires the expected account header and exposes only circle ID, name
and invite expiry. It neither joins nor consumes the invitation. Unknown,
used, revoked and expired codes are indistinguishable. Preview does not
reserve the code: redemption rechecks atomically under the existing single-use
and one-Circle rules. New link clients also bind redemption to the account
that previewed; the optional expected-account header remains compatible with
existing manual clients. Joining never enables location sharing.

Pending invitations stay in application-process memory, bind to the first
authenticated account, expire after 15 minutes and clear on account changes.
They survive configuration changes and sign-in within that process, but never
saved instance state, backup or process death. Each link has an ephemeral
identity so an older callback cannot clear a newer invitation. Secret-bearing
pending values redact their string representation.

A confirmation submits once and then reconciles ambiguous outcomes through
expected-account-bound current membership. Matching target circle and owner
can confirm success; an empty or failed read remains unknown. “Check again”
only reads. No automatic new redemption attempt or claim of success occurs
while server state is uncertain. These helpers do not themselves open a
screen or redeem a link on receipt.

## Evidence boundary

Unit tests and PostgreSQL/PostGIS integration tests exercise synthetic data.
CI emulator checks can validate native rendering and interactions; they do
not establish real GPS accuracy, battery cost, OEM behavior, actual Firebase
delivery, hosted invitation URLs or physical-device reliability. Those remain
separate release gates.
