# ADR 0006 — Location ingestion, adaptive engine, offline delivery and consent revocation

- **Status:** Accepted
- **Date:** 2026-10-26
- **Scope:** M3 (Location Engine, ingestion, offline queue, consent semantics).
  Amends nothing in M0–M2; it builds on ADR 0002 (sessions), ADR 0004
  (audit/consent ownership) and ADR 0005 (offline revocation, deletion).

## Context

M3 turns AnVề from an account/circle product into a consent-driven location
product. The risky parts are not "get a coordinate": they are the boundaries
around it.

- Android may kill the process, deny or revoke permissions, disable location,
  apply Battery Saver, restrict foreground-service starts, or run without
  Play Services. A tracker that only works while a screen is open would be
  dishonest.
- Location is the most sensitive data in the product. Consent must be
  enforced by the server inside the write transaction, not by a UI toggle or
  a long-lived cache.
- An offline device can disagree with the server for hours. A revocation
  must stop collection immediately and must never be followed by a silent
  backfill when the network returns.
- At-least-once delivery over a mobile network is unavoidable; the server
  has to make retries idempotent without keeping a memory cache.
- M5 history is not implemented. Storing GPS indefinitely "for later" would
  violate data minimisation.

## Decision

### 1. Atomic, authenticated batch ingestion

`POST /api/v1/locations/batch` accepts 1–200 events and 512 KiB of body.

Each event carries a client-generated UUID (`eventId`), the session's
`clientDeviceId`, the original `recordedAt`, coordinates, accuracy, optional
motion metadata, provider, mock flag and schema version. There is no user id
in the body: identity comes only from the opaque session.

The batch is **atomic**: HTTP 200 means every event is durably committed or
already existed. There is no partial acceptance, so a client never has to
interpret a sub-batch failure. `ON CONFLICT (user_id, event_id) DO NOTHING`
implements idempotency in PostgreSQL; a retry after a lost response returns
the ids in `duplicates` and creates no row. The unique key is the owner and
the event, deliberately not the device: two installs of the same account
retrying one event still produce one row.

The server writes its own `received_at` and preserves `recorded_at`
unchanged, so an offline backfill keeps event-time order. Future capture
times beyond two minutes and capture times older than the retention window
are refused. The accepted backfill window and the retention window are the
same number, so data is never stored only to be purged immediately.

### 2. Authorization is inside the ingestion transaction

The store performs, in order:

1. `SELECT users … FOR SHARE` — serializes with consent changes and account
   deletion. Consent that committed first is visible; a batch that acquired
   the lock first commits while consent was still on.
2. `SELECT sessions JOIN devices … FOR SHARE OF s, d` — a sign-out or device
   revocation either commits first (no live session, refused) or waits, then
   cascades; no location row outlives the session that authorized it.
3. `clientDeviceId` equality — the session fixes the device; the body may
   only name that same installation. A circle owner can never upload as a
   member.
4. `SELECT membership … FOR SHARE` — a circle leave either commits first
   (refused) or waits. An active Circle is required: without an audience
   there is no product purpose for collecting the data.
5. Idempotent inserts, then commit.

Every location row references the account and device with
`ON DELETE CASCADE`; account or device deletion leaves no orphan.
There is no consent cache. The authoritative check happens once per batch
inside the transaction, and a refusal is a stable error code
(`LOCATION_CONSENT_REQUIRED`, `LOCATION_CIRCLE_REQUIRED`,
`DEVICE_NOT_AUTHORIZED`).

### 3. PostGIS schema and retention

`location_points` stores the original event id, owner, device, both
timestamps, latitude/longitude, a `GEOGRAPHY(Point, 4326)` column, accuracy,
optional altitude/speed/bearing, provider, mock flag and schema version.
Indexes cover per-user history, per-device diagnostics, capture-time
retention and a GIST index for future spatial queries. The unique
`(user_id, event_id)` index is the idempotency mechanism.

Retention is **30 days by default**, configurable with
`LOCATION_RETENTION_DAYS` (1–365), measured from capture time. The API runs
a purge at startup and hourly. This is a temporary, data-minimising policy
while M5 has no history UI; it is not a claim that every legal retention
obligation is satisfied.

### 4. Two independent state dimensions, adaptive requests

The engine does not use one enum. The UI and diagnostics expose:

- **Motion:** `IDLE`, `STATIONARY`, `MOVING`, `JOURNEY`.
- **Operational:** `ACTIVE`, `DEGRADED`, `OFFLINE`, `PAUSED` (plus
  `TrackingStatus.running/activelyTracking`).

`JOURNEY` is a policy hook for M7 and is never inferred in M3.

`AdaptiveTrackingPolicy` is pure Kotlin, driven by a fake clock in tests:

- request high accuracy only while moving/journey;
- stationary uses a balanced request every 5 minutes / 50 m;
- moving enters at 1.4 m/s or 50 m displacement and leaves after two
  minutes below 0.5 m/s (hysteresis);
- fixes older than two minutes, less accurate than 500 m, out of order, or
  same-place jitter inside one minute are rejected;
- Battery Saver doubles interval and displacement rather than silently
  stopping, and the UI reports the limitation.

These thresholds are **engineering defaults**, not measured optima. Field
calibration is still required, and no battery-saving claim is made until
real measurements exist.

`TrackingMetrics` persists aggregate counters (callbacks, accepted/rejected
by reason, accuracy buckets, time per motion/operational state, upload
batches/points, service starts/lifetime). They contain no coordinates and
are the measurement hook for later field calibration. Wakeup counts are not
measurable through public Android APIs and are not claimed.

The provider is behind `LocationProvider`; production wraps Play Services
FusedLocationProvider. `getLastLocation()` is never used as realtime data.
Callbacks are unregistered when the flow collector is cancelled.

### 5. Foreground service and process-death honesty

`TrackingService` declares `foregroundServiceType="location"`, shows a
persistent low-importance notification with a stop action, and returns
`START_NOT_STICKY`. It is started only from a visible user action or an
explicit resume.

There is no `BOOT_COMPLETED` receiver and no background-start workaround.
The user's intent and confirmed consent are persisted; a fresh process that
finds `tracking_requested=true` reports `recoveryRequired` and waits for the
user. The service never hides that it is running. A failed
`startForeground`/`startForegroundService` stops the flow and reports
`SERVICE_START_FAILED`; the UI does not show success merely because the
start call was issued.

Permissions are staged: prominent disclosure, foreground location,
background location (separately, only after an explanation), notifications.
Approximate-only permission is allowed but reported as `DEGRADED`.

### 6. Durable, owner-scoped Room queue

`location_queue` stores one row per accepted fix before it is considered
captured. `eventId` is the primary key; `ownerUserId` scopes every read and
write. The queue is bounded to 5,000 points and 7 days of capture time,
dropping the oldest overflow. Room schema version 1 is exported and has no
destructive fallback: a future schema change must ship a real migration,
because silently dropping the queue loses captured data.

**At rest, the Room database is not additionally encrypted in M3.** The
threat model and residual risk are explicit:

- the file is in app-private storage, protected by the Android sandbox and
  full-disk/file-based encryption when the device is locked;
- the `database` domain is excluded from cloud backup and device transfer in
  both rule sets;
- data is short-lived (7 days on device, 30 days on the server) and deleted
  on consent revocation, sign-out, account switch or account deletion;
- a rooted device, a debug build, or forensic extraction of an unlocked
  device can read the file. Keystore encryption of Room (SQLCipher or an
  application-level sealed payload) is the decision point when M5 history
  or longer local retention is introduced; it is not claimed now.

### 7. At-least-once sync with server idempotency

WorkManager runs a unique, network-constrained job. It uploads at most 100
points per batch, five batches per pass, oldest-first. Accepted and
duplicate ids are deleted only after the server acknowledged them; row
attempt metadata is recorded. A lost response is retried with the same
event ids. Transport, 429 and 5xx keep the rows and ask WorkManager for
exponential backoff. A permanent validation error drops only that batch; it
never blocks the rest of the queue. Five hundred points are drained per
pass and the worker asks for another pass if work remains.

Upload order is independent of event-time order; the server stores the
original capture time, and history can sort by it.

### 8. Consent revocation is local-first and ordered

The states are deliberately separate: server consent, local consent mirror,
tracking intent, OS permission, provider state, service state, queue state
and upload acknowledgement.

When sharing is disabled:

1. a durable `pending_consent_revocation` flag is written;
2. acquisition and the foreground service stop;
3. **all unsent points are deleted locally**;
4. the network call is attempted; a failure keeps the flag;
5. the sync worker refuses to upload anything while the flag is set and
   delivers `PUT /me/location-sharing {enabled:false}` first;
6. only a confirmed response clears the flag and the local consent mirror.

Enabling consent is reported only after the server confirms it, and a
successful enable clears an undelivered revocation intent. A local consent
mirror records the account it belongs to; a different account (or a
pre-M3 mirror with unknown provenance) is not trusted for an offline start.
A pending revocation intent is tied to the account that created it: if a
different account signs in before it is delivered, the intent is discarded
and never applied to the new account's consent. Offline start from the last
confirmed consent is allowed, but the server remains authoritative: a
refusal stops tracking and purges the queue.

Stopping tracking without revoking consent keeps the queue and sync; it is
a pause, not a revocation.

## Consequences

- A location row is always tied to a live session, consenting account and
  active Circle at commit time.
- A revocation that has committed is never followed by an upload; an
  offline revocation may reach the server late, and the UI says so.
- Process death, reboot, OEM kills and Permission revocation produce a
  visible recovery state instead of a false "tracking" claim.
- Stored data is bounded in time and count on both device and server; no
  history feature exists yet.
- The Room database is not app-level encrypted; that residual risk is
  documented and has an explicit revisit criterion.
- Battery and reliability figures remain **unmeasured**. Field validation on
  Pixel/One UI/HyperOS, Battery Saver, screen-off, network loss, GPS loss,
  app kill and reboot is required before M3 is called fully verified.
- Multi-circle remains out of scope; ingestion requires exactly the MVP's
  one active Circle membership.
- M3 stores points per **user/device**, not per Circle (`circle_id` is not
  denormalised into `location_points`). Before M4/M5 expose any read path,
  the audience/history model must be decided: current membership must not
  retroactively grant access to points captured before a member joined, and
  leaving a Circle must not leave a hidden viewing channel. The ingestion
  check above proves an active Circle existed at write time; read
  authorization is a separate, not-yet-built decision.
