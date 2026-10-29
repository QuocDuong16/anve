# ADR 0010 — Place monitoring and event processing

- **Status:** Accepted; automated and emulator checks passed on `f127d48`; field validation pending.
- **Date:** 2026-10-29

A Place belongs to its creator and their current Circle membership period.
Only its owner can change or delete it. Sharing a place definition with a
specific current member is a separate decision; it does not activate tracking
and does not authorize any recipient of presence events. A member with access
may explicitly activate monitoring of themselves and choose recipients who
have both their current location grant and access to that place. Each recipient
is tied to the exact audience grant, place-access grant and sharing epoch.
Revocation followed by regrant, rejoin or consent re-enable
does not revive an earlier monitor or recipient authorization. Editing a place
ends its monitors so each subject must explicitly opt in again.

Each monitoring activation advances the subject's capture checkpoint. Points
collected offline under an earlier checkpoint are rejected, even when some are
actually newer. As with History, neither a checkpoint nor a client timestamp
proves physical presence against a deliberately fabricated sample. Account,
Circle period, consent, epoch, place access, monitor, place revision, the
recipient's current explicit grant and original place-access grant are checked
on event reads. Leaving the Circle deletes Places created under that membership
period and invalidates other Place access. Android clears sensitive Place and
event state on screen exit, background and account change; while the screen is
visible it rechecks authorization every ten seconds and clears data when the
recheck fails. No background screen polling is introduced.

The server reuses M3 points; it does not add an Android location collector or
provide OS geofence wakeups. A successful ingestion transaction enqueues at
most one durable work item per point when a monitor exists. A bounded worker
locks the monitored owner, processes at most 100 items per pass and records
state, event and dequeue in one transaction. Worker errors roll back and persist
bounded retry backoff; newer points for that owner wait while unrelated owners
can progress. Duplicate point uploads cannot enqueue duplicate work. Samples
arriving behind the processed event-time watermark are ignored rather than
rewinding state or generating retroactive notifications; this may miss events.

The state machine uses a 25 m or 10% radius hysteresis, whichever is larger,
accuracy no worse than 100 m, two unambiguous samples at least two minutes
apart, and a 15 minute maximum observation gap. A gap requires a fresh
baseline. Boundary uncertainty and mock locations cannot create transitions.
No event is generated for the first baseline. Event time is the confirming
sample time; processing time is separate. The UI describes delayed processing
and evidence from multiple GPS samples, not an emergency or safety guarantee.

Events retain references to their baseline, candidate and confirming points.
Deleting any evidence cascades to dependent events; missing or expired state
evidence resets the next evaluation to an unknown baseline. Event reads apply
retention to every referenced point even before physical cleanup. No raw GPS
coordinates are written to logs or audit events. M5 delivery is an in-app feed;
FCM and push notifications remain outside this milestone.
