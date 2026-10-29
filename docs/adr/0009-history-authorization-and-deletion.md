# ADR 0009 — History authorization and durable deletion

- **Status:** Work in progress; M5 is not yet verified.
- **Date:** 2026-10-29

History reads are bounded to 48 hours and use a keyset ordered by capture
time, server receipt time and event ID. Each page rechecks the active grant,
owner consent, current membership periods and active sharing epoch. A new
grant exposes only points captured and received after its visibility start.
Owners may read their own retained history, including unassociated M3 points;
this does not grant another member access. The cursor is bound to the caller,
owner and range. A first-page request briefly takes the owner lock and advances
an internal generation; ingestion records the generation it observed under its existing
owner share lock. Later uploads are excluded from that cursor, including
out-of-order backfill; a first-page refresh includes them. Deletion, retention
and authorization are rechecked for each page, so data removed or revoked
after the first page is never preserved merely for pagination. A read that
started before a deletion or revoke commits may already hold a PostgreSQL
statement snapshot; data already delivered or in flight cannot be recalled.

Retention is owner-selectable from one to thirty days, additionally bounded
by the server-wide setting. Reads and ingestion enforce it immediately. On a
retention change the old and new policies are compared and the stricter cutoff
is persisted before removing points; increasing retention cannot restore
already-expired time ranges. Periodic cleanup uses the owner policy and
advances a monotonic global cutoff in the same bounded transaction as
deletion, recording removed event IDs. Ingestion share-locks that watermark, and reads enforce it immediately, so increasing the server-wide
limit cannot admit previously expired data.

Deletion records half-open owner-scoped intervals and IDs of deleted rows in
the same transaction as point deletion and metadata-only audit. Ingestion
holds a share lock on the owner row; deletion and retention updates take an
exclusive lock on that same row before writing fences. Thus a batch ordered
first is subsequently removed, and one ordered after sees the fence. A deleted
interval rejects delayed uploads; deleted event IDs are rejected even if the
reported timestamp changes. Rollback of the migration refuses while fences
exist. These fences are account-scoped and cascade on account deletion.

A device-reported capture time is not proof of physical presence. A point
captured before a deletion with an incorrectly future-dated timestamp cannot
be distinguished from a genuinely later capture on another device. Deleted
intervals alone therefore cannot establish the requested absolute guarantee.
Likewise, an unseen offline point captured before a grant can be assigned a
later capture timestamp and uploaded after the grant; checking both reported
capture and server receipt time does not prove that capture happened after
authorization. A capture-generation checkpoint is advanced transactionally
with each new grant, deletion or retention change. Ingestion rejects missing
or obsolete checkpoints, including on unseen event IDs, and grant reads filter
stored points by the checkpoint associated with that grant. Android attaches a
checkpoint only to fixes captured after it received that checkpoint, using the
device's monotonic clock. The checkpoint is process-local, so after restart it
must be fetched again. Offline captures from an old checkpoint may be dropped,
including captures made after the privacy change; the user is informed.
This protects against compliant stale clients, not a client that deliberately
fabricates a fresh event after reauthorizing. Deleted intervals
cannot retract data already delivered to a viewer. Overlapping time
fences are merged and request rates are limited. Deleted
event IDs remain until account deletion to prevent reuse with altered capture
times; their storage growth requires monitoring and a future bounded design
that preserves the same guarantee. Complete race and cross-device tests
are required before M5 is marked verified.

The ingestion contract now requires both an expected-owner header and a
capture generation. Older Android versions cannot upload after deployment;
queued version-one Room rows migrate without a checkpoint and will be rejected
when synchronized. Coordinate server and Android rollout and explain this
privacy-first data loss to users. No legacy fallback may silently accept them.

Route rendering uses opaque segment identities incorporating sharing epoch and
deletion boundaries, and additionally breaks at time gaps, poor accuracy,
invalid samples and implausible movement. It does not infer roads or paths.

The Android History screen keeps at most one 100-point page in memory and
clears it on account change, navigation away or backgrounding. While visible,
it rechecks viewer authorization using the existing latest-location read
every ten seconds; a failed check clears the displayed history and requires a
user refresh. This creates no background polling and does not increase GPS
sampling. A point is labeled as delayed when server receipt trails capture by
more than five minutes. This is a transport delay indicator, not proof of
physical presence or real-time arrival.
