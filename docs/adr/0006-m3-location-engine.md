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
Approximate-only permission is allowed but reported as `DEGRADED`. API 29
requests background permission only after foreground permission; API 30 and
later explain the need, open the supported app-permission Settings page, use
the platform's background-permission option label when available, then read
the OS grant again on return. API 28 does not request the background runtime
permission. Returning without granting leaves the permission missing and
does not trigger another prompt. Permission UI state is refreshed on resume;
Settings-flow behavior still requires device validation.

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
points per batch, five normal batches and 64 total HTTP requests per pass,
oldest-first. Client prevalidation checks each row before upload. If the
server atomically rejects a validation batch, the client uses bounded binary
isolation; only an individually isolated invalid event may be removed. A
batch-level rejection never deletes its valid neighbors. Accepted and
duplicate ids are deleted only after the server acknowledged them; row
attempt metadata is recorded. A lost response is retried with the same
event ids. Transport, 429, 5xx and authorization/policy refusals never enter
validation splitting; retryable failures keep rows and use WorkManager
backoff, while policy refusals stop upload and apply the explicit privacy
purge policy. If the request budget is exhausted, unprocessed rows remain
durable for a later pass.

Upload order is independent of event-time order; the server stores the
original capture time, and history can sort by it.

### 8. Consent revocation is local-first and ordered

The states are deliberately separate: server consent, local consent mirror,
tracking intent, OS permission, provider state, service state, queue state
and upload acknowledgement.

Consent changes use one durable operation per owner account. Each record
contains an operation id, owner id, desired value, expected server version,
generation, retry state and timestamps. It has no coordinates and is
encrypted with Android Keystore separately from the active session. An
operation is never deleted just because its retry credential expires; it
remains unresolved until server confirmation, explicit newer owner intent or
account deletion. A short-lived sealed refresh credential is attached only
when an offline sign-out must wait for consent OFF before revoking that same
session; it is usable only for the owner-scoped consent and sign-out steps.

Consent writes use compare-and-set against `location_sharing_version`. The
server increments that version atomically with the consent update and audit
record. A stale request receives `CONSENT_VERSION_CONFLICT`; its response
cannot overwrite a newer local generation. Reconciliation may acknowledge
the operation when the authoritative state already matches, or retry only
the current generation against the latest version.

When sharing is disabled:

1. persist the owner-scoped OFF operation before network access;
2. stop acquisition and the location foreground service;
3. **purge all unsent location points locally** and prevent new upload
   requests for that owner;
4. attempt the operation; UI separates local stop from server confirmation;
5. remove the operation only after the server confirms the same owner and
   generation, or after an explicit newer owner action.

`Stop Tracking` pauses acquisition on this installation. `Disable Location
Sharing` changes account-wide consent. `Sign Out` revokes this session and
does not implicitly change consent. If OFF was requested first, the refresh
credential is sealed into that owner's operation and session revocation is
sequenced after OFF confirmation. An expired/revoked session leaves the
operation unresolved and requires that owner to authenticate again; the UI
must not claim server revocation. Account B never inherits A's operation or
uses A's credential as an active session.

Local persistence is the acquisition/upload stop point. An HTTP location
batch already in flight may still commit if it acquired the account share
lock before the consent update. If the consent OFF transaction commits
first, the backend's transactional ingestion check refuses later batches.
Client cancellation is not treated as proof that an already-sent request
did not commit.

A local consent mirror records its account. A different account (or a
pre-M3 mirror with unknown provenance) is not trusted for offline start.
Offline start from the last confirmed consent is allowed only when no
owner-scoped consent mutation is pending; the server remains authoritative
and a refusal stops tracking and purges the queue.

Stopping tracking without revoking consent keeps the queue and sync; it is
a pause, not a revocation.

## Consequences

- A location row is always tied to a live session, consenting account and
  active Circle at commit time.
- A server revocation that has committed is never followed by an accepted
  upload. A local OFF immediately blocks new acquisition and requests;
  already in-flight batches follow the server's row-lock order.
- A pending OFF survives offline sign-out and account changes. It stays
  tied to its owner and is not reported as server-confirmed until acknowledged.
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
- Consent mutations are owner-scoped, versioned and serialized at local
  persistence boundaries; HTTP calls do not hold a process-wide mutex.
  PostgreSQL CAS is the cross-device/process ordering boundary.
