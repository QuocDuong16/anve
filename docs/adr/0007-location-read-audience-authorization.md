# ADR 0007 — Temporal audience grants for location reads

- **Status:** Accepted for M4 design; implementation not started
- **Date:** 2026-10-27
- **Scope:** M4/M5 location read authorization. This ADR adds no read API,
  WebSocket, map or location-confidence implementation.

## Context

M3 stores location points by owner and device. Ingestion verifies account
consent, active device/session and Circle membership in one transaction, but
points do not carry a Circle or viewer grant. That is sufficient for write
authorization and is not a read authorization model.

The product invariant is that current Circle membership does not grant access
to every point captured before a viewer joined. A read model must also make
consent OFF, leaving, dissolution, rejoining, retention and cached last-known
data revoke access consistently.

## Options considered

| Model | Privacy and history | Complexity and query cost | Revocation and future circles |
| --- | --- | --- | --- |
| Membership time window only | Prevents viewing before a membership start, but does not record the owner's consent window or a per-viewer sharing decision. | Low; range predicates on membership dates. | Membership changes are clear, but consent OFF and re-enable history are difficult to distinguish. One-circle MVP only. |
| Owner sharing epochs only | Separates points captured before and after each ON/OFF cycle. Does not by itself limit a particular viewer or a later join. | Low to moderate; one epoch lookup per point. | Consent revocation is clear; audience changes and multi-circle reads need more state. |
| Circle-scoped audience grants bounded by membership periods | Records which owner explicitly shared with which viewer, in which Circle, and during which interval. Epochs prevent history crossing an OFF/ON boundary. A later join starts visibility at the new grant time. | Moderate; indexed joins on owner, viewer, Circle, epoch and event time. | Revocation closes grants and epochs; new membership periods and multiple Circles can be represented independently. |
| Immutable audience snapshot on every point | Strongest point-in-time audience evidence and straightforward historical audit. | Highest write amplification and storage cost; every point fans out to each viewer/grant. | Simple point-level revocation only if grants remain checked at read time; supports multiple Circles but makes changes and retention more expensive. |

## Decision

M4 will use **circle-scoped audience grants bounded by membership periods and
sharing epochs**. A grant is explicit for an owner/viewer pair and cannot be
inferred from Circle role or current membership.

1. **Membership periods are immutable intervals.** Joining opens a period;
   leaving or removal closes it. Rejoining creates a new period. Circle
   dissolution closes every active period and grant for that Circle.
2. **Sharing epochs record the owner's consent interval for a Circle.** An
   explicit enable opens a new epoch; disable closes it transactionally with
   the account consent update. Re-enabling never reopens an old epoch.
3. **Audience grants identify the viewer.** The owner explicitly confirms
   which current Circle members may see location. A member who joins later
   receives no access until the owner grants it. The grant's `visible_from`
   is no earlier than both the new membership start and the explicit grant
   time.
4. **Each new point is linked to the active sharing epoch/audience grant in
   the ingestion transaction.** A point without a valid active epoch is not
   eligible for Circle reads. Existing M3 points receive no audience grant
   during migration and are therefore not readable through M4 by default.
5. **Every read checks the intersection of time windows:** point capture
   time must be at or after the epoch start, viewer grant start and current
   membership-period start, and before the earliest epoch end, grant end,
   membership end, Circle dissolution or retention cutoff. Current membership
   alone is never sufficient.
6. **Revocation closes access at the server.** Account consent OFF closes
   all active sharing epochs and their grants in the same transaction. A
   member leaving, being removed or a Circle dissolving closes the matching
   viewer grants. Rejoining requires a new period and fresh explicit grant.
7. **Last-known caches preserve authorization metadata.** Cached values carry
   the source point/epoch/grant version and are served only after the same
   current authorization check. Revoke, leave, removal, dissolution and
   retention invalidate the relevant cache entries. A cache never extends
   access beyond the source grant.
8. **Future WebSocket delivery uses the same policy.** Subscriptions are
   scoped to owner/viewer/Circle/grant generation; every event is checked
   against the active grant before delivery. Revocation terminates the
   subscription and prevents queued events from escaping afterward. Realtime
   delivery cannot authorize more than an HTTP read.

For MVP there is one active Circle per user, but the owner/viewer/Circle keys
are explicit so a later multi-Circle product does not rely on a single
membership shortcut.

## M4 migration proposal

Ship an additive migration before any read endpoint:

- add `membership_periods` and backfill only the currently active membership
  from its existing creation time; future joins/leaves write interval rows;
- add `location_sharing_epochs` and explicit owner-to-viewer
  `location_audience_grants` with start/end timestamps and a generation;
- add a nullable epoch reference and point-to-audience association for new
  ingested data;
- do **not** backfill epochs or grants for existing M3 points, so no new
  member receives pre-join history by migration;
- index owner/Circle/capture time, viewer/Circle/grant interval and point
  audience keys; validate all read paths with cross-owner and temporal
  authorization tests before exposing them.

The exact API response and whether a product action grants to all current
members or selected members remain M4 contract/product work. The default is
deny until the owner makes that choice.

## Consequences

- Historical access is bounded by consent, the viewer's explicit grant and
  the viewer's membership interval. Joining or rejoining does not unlock old
  points.
- Revocation requires transactional grant closure and cache invalidation;
  a stale cache or WebSocket subscription is not an authorization source.
- The design adds interval tables and joins but keeps point writes compact
  for the current one-Circle MVP. Full per-point immutable audience snapshots
  remain an option if later audit or multi-Circle requirements justify their
  storage and write cost.
- M4 must implement migrations, transaction ordering, authorization tests and
  cache/subscription revocation before maps or realtime reads. No M4 runtime
  feature is implemented by this ADR.
