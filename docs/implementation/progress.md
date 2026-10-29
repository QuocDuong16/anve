# Implementation progress — AnVề

> Living document for engineering sessions: what is implemented and
> verified, what is in flight, and the next concrete tasks. Status labels
> follow the product taxonomy (`docs/product/overview.md#7`); "Verified"
> always names the evidence. Never mark a milestone done from documentation
> alone.

Last updated: 2026-10-28 (M4 authorized Live Map implementation).

## Current milestone

**M1 — Authentication, device identity, privacy foundation: Implemented —
Verified (backend + client logic, no on-device field validation).**
**M2 — Family Circle: Implemented — Verified (backend + client logic).**
**M3 — Location Engine, consented ingestion, offline sync: Implemented —
Automated Verified; Pending Field Validation.**
**M4 — Live Map, temporal audience authorization and confidence: Implemented —
Automated Verified; Pending Runtime/Device Validation.** Conditional polling is
near-real-time snapshot convergence, not server push.

## Milestones

| Milestone | Status | Notes |
| --- | --- | --- |
| M0 Foundation | Implemented — Verified | CI + Compose smoke test at `de54074`. |
| M1 Auth & privacy | Implemented — Verified (server, JVM-level) | See evidence below; Google path needs real OAuth client (external), on-device run pending. |
| M2 Family Circle | Implemented — Verified (server+client) | Authorization matrix tests in CI; invite deep-link pending. |
| M3 Location Engine | Implemented — Automated Verified | Server + Android + runtime HTTP smoke; field validation pending (battery/background/OEM). |
| M4 Live Map | Implemented — Automated Verified; Pending Runtime/Device Validation | Explicit temporal grants, latest read API, conditional polling, Android map/sharing UI |
| M5 History & Places | Implemented — Automated Verified; device validation pending | Go/Android CI and emulator passed on `f127d48`. |
| M6 SOS & FCM | Not started | |
| M7 Journey & diagnostics | Not started | |
| M8 Release hardening | Not started | |

## M1 evidence

- ADRs: `docs/adr/0002` (Google Sign-In + opaque sessions), `0003` (Moshi).
- Contract: `packages/api-contracts/openapi.yaml` v0.2.0 — auth, me,
  consent, devices endpoints, structured error envelope.
- Backend: migration `0002_auth_identity` (users with consent switch OFF by
  default, devices keyed by client device id, sessions storing only
  SHA-256 token hashes, audit_log without FK by design);
  `internal/auth` (service, PG store, Google verifier), `internal/audit`,
  auth middleware + rate limiting + error envelope in `internal/httpapi`,
  `readyz` now DB-backed, dev-login refused in production at config load.
- Tests: unit (service + handlers over in-memory store mirroring PG
  semantics) and PostGIS integration (sign-in/supersession, rotation race
  single winner, cross-user device isolation, expiry predicate, cascade
  delete, audit survival) — the latter run in CI's database-integration
  job and locally via `make server-test-integration`.
- Runtime smoke (2026-10-25, local Compose stack): dev sign-in → me →
  consent toggle → device list (current session flagged) → refresh
  rotation (old access token immediately rejected) → 401 envelope with
  requestId.
- Android: Retrofit/Moshi client (ADR 0003), Keystore-sealed DataStore
  session storage, SessionInterceptor (attach → one refresh-retry on 401,
  rotation-aware), Credential Manager Google sign-in with config
  placeholder, sign-in screen (Google + debug-only dev login gated on
  server config), home screen (profile, consent switch, device list with
  revoke, sign out, account deletion), navigation from splash.
- Android tests: MockWebServer suite for the 401/refresh/rollback
  contract, ViewModel flows over the real repository; `make android-check`
  green (lint + 20 unit tests + debug APK); `assembleRelease` green
  (minified, Moshi keep-rules).

## M2 evidence (added 2026-10-26)

- Contract: circles/members/invites endpoints (21 paths total).
- Migration `0003_circles`: one-circle-per-user unique index is the MVP
  invariant; invites single-use with expiry + revocation.
- `internal/circles`: store-level authorization (membership checked before
  any read; owner role governs administration only). Redeem is atomic
  (FOR UPDATE serializes concurrent redeems — single winner, tested).
  Owner leaving a populated circle requires explicit dissolve (cascades).
  Joining never touches the consent switch.
- Integration tests in CI + local: authorization matrix (outsider/member/
  owner), invite lifecycle (expired/used/revoked indistinguishable),
  concurrent redeem, removal ends access + frees the one-circle slot,
  dissolve cascade, audit rows.
- HTTP smoke on local stack: full lifecycle verified (create → invite →
  redeem → burned code → FORBIDDEN → CIRCLE_HAS_MEMBERS → dissolve →
  members see no circle).
- Android: circle tab (create/join by code, members, owner invites, leave/
  dissolve with confirmations), 26 unit tests green; fixed a real bug found
  by tests (`orThrow` rejected 204 no-content responses).

## M1/M2 hardening (2026-10-26)

A correctness/security pass over M1/M2 before M3. Every decision below is
recorded in [ADR 0004](../adr/0004-m1-m2-hardening.md).

| Fix | Root cause | Evidence |
| --- | --- | --- |
| Account deletion refuses to dissolve a populated circle | `circles.created_by ON DELETE CASCADE` cascaded through the circle when the owner row was deleted | 5 PostgreSQL integration tests: owner-with-members refused and nothing deleted (A), member deletion keeps circle (B), empty-circle owner deletion removes circle with `circle.dissolved` audit (C), explicit dissolve unblocks deletion (D), deletion racing invite redemption has exactly one winner (E) |
| Deletion is race-safe | Lock order between deletion (circle) and redemption (invite) can deadlock | `FOR UPDATE` on owned circles closes the TOCTOU window; bounded retry on PostgreSQL `40P01`/`40001`; Test E |
| Android keeps the session when deletion fails | `storage.clear()` sat in a `finally` | Repository tests: 400/401/409/500/transport failure/concurrent/double-tap keep the session; success clears it; unknown outcome reconciles through the 401 refresh chain and signs out only when the account is gone |
| Two audit rows per business event | Stores used `InTx` and services also used `BestEffort` for removal and redemption; rename/invite create/revoke were best-effort only | Single ownership rule (ADR 0004 §3); integration tests: one row per mutation, none for failed operations, rollback with the business change, one row per concurrent invite |
| Development login could mount outside development | Gate compared `ENVIRONMENT == "production"` | Allowlist of exactly `development`; config tests cover production, staging, `prod`, `Development`, `local`; endpoint not mounted when disabled |
| Dev fixture could take over a Google account | Email-match linking ran in both directions | `IDENTITY_CONFLICT` in both directions; service tests; contract documents 409 |
| Network failure navigated to sign-in | `HomeUiState.signedOut` (`!loading && user == null`) drove navigation | Navigation now follows the session flow only; ViewModel test proves a failed `/me` keeps the user with an error and retry |
| Offline sign-out claimed more than it did | Local session cleared with no record of the undelivered revocation | `SignOutOutcome`; sealed 7-day pending revocation retried at app start (repository tests incl. process restart); dialog states the limitation |
| DataStore was eligible for backup/transfer | Rules excluded `sharedpref` and `database`, but DataStore lives in `file/datastore` | Both rule sets exclude `file/datastore` (cloud backup + device transfer); merged manifest verified |
| Device id semantics were wrong in the comment | Claimed stability across reinstall; private storage is deleted | Semantics documented as installation identity; injectable store covered by JVM tests |
| Rapid consent toggles could diverge from the server | Optimistic updates with no ordering | Toggles serialized; tests for rapid sequence and mid-sequence failure roll back to the last confirmed state |
| `deleteMe` could never be invoked | Retrofit rejects `@Body` on `@DELETE`; the proxy threw at call time, so CI could not see it | Request declared via `@HTTP(method = DELETE, hasBody = true)`; repository test asserts the verb, path and confirmation body |

Verification levels for this pass:

- **Implemented + Automated Verified** — Go unit tests, PostgreSQL/PostGIS
  integration tests (race detector, per-test scratch databases), Android
  JVM tests via MockWebServer, `make quality-check`, CI.
- **Runtime Verified (2026-10-26, local stack)** — deletion lifecycle via
  HTTP on the real binary: owner with member → 409 `CIRCLE_HAS_MEMBERS`,
  owner session still valid (200 on `/me`), explicit dissolve → 204,
  deletion → 204; the audit trail contains exactly one row per event
  (`auth.dev_signin` ×2, `circle.created`, `invite.created`,
  `circle.joined`, `circle.dissolved`, `auth.account_deleted`) and no
  `account_deleted` from the refused attempt. Also verified at startup:
  `ENVIRONMENT=staging` + `AUTH_DEV_LOGIN_ENABLED=true` refuses to start
  with the allowlist error.
- **Pending Device Validation** — offline sign-out retry, backup
  exclusion and the deletion dialogs still need a physical-device pass;
  the Android repository/ViewModel suites cover the logic but not the
  OS-level behavior.
- **External Integration Blocked** — Google OAuth client (unchanged).

## M1/M2 final hardening (2026-10-26, second pass)

A follow-up review of the same scope found four remaining issues. Decisions
are recorded in [ADR 0005](../adr/0005-network-boundary-and-offline-revocation.md).

| Finding | Root cause | Fix and evidence |
| --- | --- | --- |
| Development login could be exposed to the network | The environment flag said nothing about the listener; the server bound every interface and Compose published `0.0.0.0` | `LISTEN_ADDR` (loopback by default, mandatory outside development); non-loopback dev login requires an explicit opt-in plus a CIDR allowlist; a route-level source check never trusts proxy headers; catch-all prefixes are refused; Compose publishes loopback only and pins `172.28.0.0/24` for the container source; `compose-lint` guards the mappings. Config tests, router tests (foreign source with spoofed `X-Forwarded-For` → 403), runtime smoke |
| Offline sign-outs overwrote each other | One DataStore key held one record, so account B's sign-out replaced account A's | Bounded (8) encrypted queue, independent ids, in-place token rotation, 7-day expiry per record, atomic read-merge-write; retry at app start walks every record, drops 401s, reschedules transport/server failures with 30s→6h backoff. Repository tests A–G: two accounts offline, partial success, process restart, rotation failure then later retry, expiry, active-session isolation, concurrent retry + sign-out |
| A bare 401 could be reported as a confirmed revocation | The repository treated `UNAUTHENTICATED` as "session already dead", even when the interceptor's refresh had failed transiently | Confirmation now requires the refresh token to be rejected too (the interceptor clears storage); a transient refresh failure stays `LocalOnly`, and a rotation before the failed retry queues the **new** refresh token. Interceptor-wired repository tests: dead refresh → confirmed, 500 refresh → pending, rotated-then-500 → new token queued |
| Deletion raced circle creation | Deletion locked owned circles but not the account row; a circle committed after the scan was cascaded away with no member count or dissolution audit while both APIs reported success | `SELECT … FOR UPDATE` on the account row before the circle scan; creation's FK `KEY SHARE` now serializes against it. Deterministic PostgreSQL tests: create-wins (exactly one `circle.dissolved` with `via=account_deletion`), delete-wins (creation fails on the foreign key, no `circle.created`), forced deadlock recovered by the bounded retry with one audit row |
| Offline sign-out feedback disappeared before the user saw it | The message lived in Home screen state, which the session clear disposed immediately | App-scoped `SessionNoticeStore`; the repository publishes before clearing the session; Sign-In renders a dismissible card with the exact limitation, and a successful sign-in clears it. Repository test proves publish-before-clear and that a new sign-in clears a stale notice; ViewModel test proves the Home state no longer owns the message |

Verification levels for this pass:

- **Implemented + Automated Verified** — Go config/router tests, PostgreSQL
  integration tests including the deterministic deletion/creation races
  (race detector, scratch databases), Android JVM repository/ViewModel/queue
  tests, `make quality-check`, CI.
- **Runtime Verified (2026-10-26, local binary + Compose stack)** — with
  `ENVIRONMENT=development AUTH_DEV_LOGIN_ENABLED=true` and no
  `LISTEN_ADDR`, the process bound `127.0.0.1:18081` only and dev login from
  loopback returned 200. Startup refused `0.0.0.0` + dev login without the
  opt-in, refused `0.0.0.0/0` in the allowlist, and refused a non-development
  environment without `LISTEN_ADDR`. The explicit network mode
  (`AUTH_DEV_LOGIN_ALLOW_NETWORK=true` + `127.0.0.0/8,172.28.0.0/24`) bound
  the requested address and served the fixture from an allowed source. The
  rebuilt Compose stack publishes both ports on `127.0.0.1` only, pins
  `anve-edge` to `172.28.0.0/24`, reports `devLogin: false` by default, and
  served a dev login through the published loopback port when the container
  was started with the opt-in and the pinned-subnet allowlist (`200`, then
  reverted to the default configuration).
- **Pending Device Validation** — emulator/real-device connectivity,
  on-device backup exclusion, the Sign-In notice presentation and the
  offline-retry pass across process death still need a physical-device run;
  the JVM suites cover the logic, not the OS behavior.

## M3 evidence (2026-10-26)

- ADR: [`docs/adr/0006-m3-location-engine.md`](../adr/0006-m3-location-engine.md)
  (ingestion, authorization locks, retention, adaptive policy, FGS lifecycle,
  Room threat model, sync/consent ordering).
- Contract: `packages/api-contracts/openapi.yaml` 0.4.0 —
  `POST /api/v1/locations/batch` with atomic semantics, limits and stable
  error codes; consent writes carry `expectedVersion` and return a monotonic
  `locationSharingVersion` for compare-and-set.
- Migration `0005_location_consent_version`: non-negative per-account CAS
  version, incremented atomically with consent change and audit record.
- Migration `0004_locations`: PostGIS geography, unique
  `(user_id, event_id)`, user/device/capture-time/GIST indexes, FK cascade.
- `internal/locations`: validation, transactional authorization, idempotent
  inserts, retention purge, aggregate metrics log. PostgreSQL/PostGIS
  integration tests (race detector): happy path/timestamps/geography,
  per-owner idempotency (including a second device), consent/Circle/device
  refusals, revoked session, account deletion cascade, backfill order,
  retention boundary, concurrent same event, consent-revocation lock
  ordering, device-revocation lock ordering.
- Android:
  - `tracking/`: `LocationProvider` + Fused implementation,
    `AdaptiveTrackingPolicy`, `TrackingManager`, `TrackingService`
    (`foregroundServiceType=location`, persistent notification + stop
    action, `START_NOT_STICKY`), permission/location/connectivity adapters,
    persisted intent + recovery state, persisted `TrackingMetrics` counters
    (callbacks/rejections/accuracy buckets/state time/uploads/service
    lifetime; no coordinates).
  - `data/location/`: Room `location_queue` (owner-scoped, 5,000/7-day
    bounds, exported schema, no destructive migration), WorkManager unique
    network-constrained sync (100 per batch, 5 normal batches, 64 total HTTP
    requests per pass), at-least-once + server idempotency, bounded binary
    isolation for atomic validation rejection, owner-scoped consent outbox
    and upload-start coordination, policy-refusal stop/purge.
  - `ui/tracking/`: Vietnamese tracking screen with prominent disclosure,
    staged permission requests, separate consent vs actual-tracking state,
    last fix/accuracy/upload, queue count, connectivity, battery saver,
    diagnostics, start/stop/resume.
  - 179 JVM tests pass after M3 correctness/privacy hardening; lint/debug and
    minified release checks are recorded below for this final pass.
- Reliability prerequisites: WorkManager scheduling for pending
  revocations, first retry backoff corrected to 30s (off-by-one fix),
  uniqueness/idempotence regression tests.
- Runtime smoke (local Go binary + PostgreSQL/PostGIS, 2026-10-26):
  dev login → Circle → consent ON → batch of 2 accepted → identical retry
  returns 2 duplicates and adds no rows; foreign device claim 403
  `DEVICE_NOT_AUTHORIZED`; no-Circle account 409 `LOCATION_CIRCLE_REQUIRED`;
  consent OFF then batch 409 `LOCATION_CONSENT_REQUIRED` with no new row;
  unauthenticated batch 401; account deletion removed all rows; retention
  startup with `LOCATION_RETENTION_DAYS=1` deleted a 2-day-old row and
  logged the count.
- Not verified on a device/emulator: screen-off behavior, Battery Saver,
  Doze/OEM kills, reboot recovery, notification visibility, permission
  revocation UI, ADB/emulator connectivity, real battery drain. These are
  **Pending Field Validation**, not PASS.

## M3 correctness/privacy hardening (2026-10-27)

- Confirmed and fixed batch-wide loss: a permanent atomic validation rejection
  now splits within a 64-request pass budget, acknowledges accepted/duplicate
  ids only, and drops only a rejected singleton. Client prevalidation removes
  only the affected row. JVM regressions cover one invalid among 100, first/
  last/multiple invalid events, oversized batches, expiry, 500/lost response,
  process interruption, bounded requests and OFF during isolation.
- Confirmed and fixed background permission flow: API 28 has no background
  runtime request; API 29 requests it only after foreground; API 30+ displays
  education then opens supported app Settings and refreshes on resume. API 33,
  34 and later are covered by pure decision tests. No emulator/device is
  attached, so Settings/FGS behavior remains **Pending Device Validation**.
- Confirmed and fixed offline consent loss: owner-scoped encrypted outbox
  operations survive local session clear, preserve only a bounded credential
  TTL, and become explicit owner-auth-required state when no usable session
  remains. Sign-out waits for a pending OFF; concurrent sign-out/OFF keeps the
  operation unresolved if sign-out wins. Account B cannot execute A's consent
  operation as its session.
- Added app-scoped consent mutation and request-start coordination. Room
  upload admission and consent request admission serialize against durable
  intent writes, but no network call holds the coordinator. PostgreSQL
  `location_sharing_version` CAS is the cross-process/device ordering
  boundary; stale workers cannot overwrite newer local generations.
- ADR 0007 accepts circle-scoped audience grants bounded by membership
  periods and sharing epochs. Existing M3 points receive no grants by default;
  no M4 read API, map, or WebSocket was implemented.
- Local verification (2026-10-27): Android JVM `179/179` passed; full
  `make quality-check` passed (toolchain, Go format/tidy/vet/lint/
  govulncheck/tests/build, Android lint/tests/debug APK, OpenAPI, Dependabot
  and Compose checks); `make android-release` passed with minification; and
  `make server-test-integration` passed the Go race-enabled migration,
  PostgreSQL/PostGIS auth, circle and location suites. Android lint reported
  0 errors and 11 warnings, including the guarded API 29 background-permission
  constant and existing dependency/manifest recommendations. `govulncheck`
  found no reachable code vulnerability and reported one required-module
  advisory outside the analyzed call graph. `git diff --check` passed.
- No Android device/emulator is attached (`adb devices -l` is empty), so
  background Settings, FGS behavior, Room/Keystore restart persistence and
  field/battery behavior remain **Pending Device Validation**. Hosted CI is
  separate evidence and is checked after push.

## M3 final consent safety hardening (2026-10-27)

- **P1-A Confirmed & Fixed:** Android no longer rebases a pending ON/OFF after
  `CONSENT_VERSION_CONFLICT`. An authenticated server snapshot that already
  matches is reconciled without another write/audit. A differing state/version
  is persisted as an owner-scoped conflict and parked; Home and Tracking show
  the authoritative state and require a fresh explicit action/new operation.
  The PostgreSQL CAS and audit transaction remain unchanged; integration tests
  already prove one winner for concurrent same-version writes.
- **P1-B Confirmed & Fixed:** OFF closes the app-scoped acquisition/upload gate,
  invalidates starts, cancels provider callbacks and requests service stop
  before DataStore/Room work. A narrow non-cancellable section attempts the
  durable outbox, local stop flags and queue purge; cancellation is rethrown
  afterward and no HTTP is held non-cancellable. Storage, queue-purge and
  service-stop failures stay visible and do not reopen upload admission. If all
  storage writes fail and the process dies, no durable OFF can be guaranteed;
  the UI does not claim server confirmation.
- Added deterministic regressions for stale ON/OFF, matching-state
  reconciliation, explicit fresh operation IDs, delayed worker/process resume,
  stale `/me` versus OFF/start, storage write failure, cancellation, Room purge
  failure, service-stop failure, and worker OFF immediately before HTTP
  admission. Home also restores conflict state and the authoritative server
  toggle after process recreation.
- Targeted Android JVM regressions pass for `AuthRepositoryTest`,
  `ConsentMutationCoordinatorTest`, `LocationSyncEngineTest`,
  `TrackingManagerTest` and `HomeViewModelTest`. `make quality-check` passes
  (Go format/tidy/vet/lint/vulnerability scan/tests/build, Android lint/full
  JVM suite/debug APK, API and repository checks); `make server-test-race`,
  `make server-test-integration` against PostgreSQL/PostGIS, and
  `make android-release` also pass. The first concurrent release attempt lost
  its Gradle daemon; an isolated rerun completed successfully. GitHub Actions
  passed at implementation commit `55cc6d1`: [Go](https://github.com/QuocDuong16/anve/actions/runs/40492500413)
  and [Android](https://github.com/QuocDuong16/anve/actions/runs/40492500322).
- On-device service-stop, provider cancellation and process-restart behavior
  remain **Pending Device Validation**; no GPS field or battery validation is
  claimed.

## Known gaps / external blockers

- **Google OAuth client ID not created** (needs Google Cloud project
  access): the Google path fails closed (`AUTH_NOT_CONFIGURED` / button
  hidden) until `docs/implementation/setup-android.md` is followed. Dev
  login covers local development.
- **No on-device field validation yet** — no physical device/emulator run
  of the app in this session. The Android CI proves build/tests only.
- Refresh-token reuse detection (rotation history) intentionally absent
  (ADR 0002 "Bad"); rotated-out tokens simply fail.
- **Offline revocation is best-effort.** If the device never reconnects, the
  server-side session lives until its refresh token expires. If the process
  dies between a successful rotation and persisting the new token, the next
  retry receives 401 and drops the record; the app does not claim the
  session was revoked (ADR 0005 §3). Location-sharing OFF is a separate
  owner-scoped operation: it remains unresolved after its narrow credential
  expires and requires the same owner to authenticate again; it is never
  reported as server-confirmed from local intent alone (ADR 0006 §8).
- **Development login network mode trusts the allowlisted network.** Any
  host inside `AUTH_DEV_LOGIN_ALLOWED_CIDRS` can mint sessions while the
  fixture is enabled. Compose limits this to the pinned bridge and publishes
  loopback only; keep the CIDR as narrow as possible and prefer a tunnel.

## Known M3 limitations (not blockers to M4 code, blockers to "fully verified")

- **No on-device field validation yet.** No emulator or physical device was
  available in this session. Battery, screen-off, Doze, reboot, OEM FGS
  kills, GPS-disabled and permission-revocation behaviour are unproven.
- **Battery figures are unmeasured.** Interval/displacement thresholds in
  `AdaptiveTrackingPolicy` are engineering defaults, not calibrated optima.
- **Room is not app-level encrypted in M3.** Documented threat model and
  revisit criterion in ADR 0006 §6; file is app-private, backup-excluded and
  short-lived.
- **Retention is a provisional 30 days** (`LOCATION_RETENTION_DAYS`), not a
  complete legal retention policy; M5 adds user-selectable history retention.
- **Ingestion requires one active Circle membership** (MVP invariant); a
  consenting account with no Circle is refused rather than collecting data
  with no audience.
- **M3 points remain unassociated with a sharing epoch.** They are intentionally
  excluded from M4 latest reads; only points ingested into an active M4 sharing
  epoch can become eligible for an explicit viewer grant.
- **WorkManager scheduling is OS-bounded.** A force-stopped app or an OEM
  that blocks background work may delay revocation/sync until the next
  launch. Nothing is claimed beyond WorkManager's guarantees.
- **No wakeup measurement** via public Android APIs; metrics cover
  callbacks, accuracy distribution, state time, queue and uploads.

## M4 implementation (2026-10-27)

- Additive migration `0006_location_audience`: conservative active membership
  period backfill, sharing epochs, explicit owner/viewer grants, point-to-epoch
  association for new ingestion only. Existing M3 points remain NULL and
  unreadable. Membership delete and Circle dissolution close grants/epochs and
  audit before cascades.
- Consent CAS opens a fresh epoch on ON and transactionally closes epochs and
  active grants on OFF. Circle enter opens an epoch only when the owner has
  server-confirmed consent ON; leaving/removal/rejoin does not resurrect a grant.
- `locationaccess` implements owner-only grant/list/revoke and latest-location
  reads. PostgreSQL enforces active owner/viewer membership periods, explicit
  grant, epoch, capture-time grant boundary and retention in the read query.
  API paths are `/api/v1/location-sharing/audience[/{viewerId}]` and
  `/api/v1/locations/visible/latest`; mutation requests carry an expected-owner
  header to reject account-switch races.
- Confidence uses server time and sample capture time: Fresh ≤2m, Delayed ≤15m,
  then Stale; no sample or future time is Unknown. Offline is not inferred.
  Android advances age from a monotonic clock and only downgrades confidence.
- ADR 0008 selects foreground HTTPS conditional polling every 10 seconds with
  ETag, bounded retry backoff and no persistent location cache. This is not
  WebSocket/SSE push and makes no multi-replica realtime claim.
- Android adds Maps Compose map tab, authorized markers, accuracy circles,
  member selection/recenter, connection/freshness card and per-viewer grant
  controls. Missing Maps key shows an actionable unavailable state; no key is
  committed. Expected-owner headers protect late grant/revoke calls across
  account switches.
- M3 P2 fixes: the tracking UI's effective local consent requires an open
  owner-scoped privacy gate, while `serverConsentEnabled` remains a separate
  authoritative snapshot. OFF closes acquisition/upload admission before
  persistence; refresh after mirror/outbox failure cannot show local consent
  as active. Owner transitions do not reopen a gate or inherit queue/operations.
  Regression tests cover A OFF → B sign-in, storage-failure refresh and stale
  callbacks/admission.
- **Automated evidence (2026-10-28):** `make quality-check` PASS (Go format,
  vet, lint, vulnerability scan, unit tests/build; Android lint, JVM tests,
  debug APK; API contract/dependency/repository checks); `make server-test-race`
  PASS across all Go packages; `make server-test-integration` PASS against
  PostgreSQL/PostGIS including migrations and temporal authorization races;
  `make android-release` PASS (minified unsigned release compile).
- `govulncheck` found no reachable vulnerabilities; it reports 3 advisories in
  required modules that are not called by this code. Review on dependency updates.
- **Runtime/device evidence:** Pending. No emulator/physical device and no
  restricted Maps API key were used to verify Google map tiles, marker render,
  screen lifecycle, or battery use.

## M4 final reliability and concurrency fix (2026-10-28)

- Audience grant/revoke mutations now distinguish `Confirmed`, `Rejected` and
  `UnknownOutcome`. Transport errors, timeouts and 5xx are unknown; the client
  never replays a mutation without an idempotency key. It reads the authoritative
  audience state after the attempt. A failed reconciliation stays pending and
  explicitly unresolved; an unknown revoke never claims access was removed.
- Audience/latest reads carry the expected owner through the Android session
  interceptor on every request. A sign-out/account switch cannot mix one
  owner's initial `/me` response with another owner's Circle or grant list.
  Old owner and old request-generation responses are ignored.
- Sharing snapshots use monotonically increasing request generations. Grant
  mutations are single-flight per viewer; different viewers remain concurrent.
  Pending state is released only after a current authoritative snapshot is
  applied, while stale refreshes cannot replace it. Account changes clear the
  prior account's map and sharing state.
- PostgreSQL integration regressions cover concurrent grant/revoke, concurrent
  duplicate grant, grant versus viewer leave, reads begun after revoke commit,
  and ETag changes after grant, revoke, consent OFF and Circle leave. The
  existing temporal-membership/epoch suite continues to assert no stale-point
  leakage.
- **Local automated evidence (2026-10-28):** `make quality-check` PASS;
  `make server-test-integration` PASS with Postgres/PostGIS and the race detector
  including `internal/httpapi`; `make server-test-race` PASS across Go packages;
  Android JVM tests (219/219), lint/debug build PASS; `make android-release`
  PASS (minified unsigned compile). GitHub Actions status is checked and
  reported for the exact final pushed SHA; local results are not used as a
  substitute.
- No device/emulator or configured Maps key was used. Map rendering, Android
  lifecycle behavior and battery use remain **Pending Runtime/Device Validation**.

## M4 audience recovery follow-up (2026-10-29)

- An authoritative audience read that differs from an unknown mutation now shows the server state and permits a fresh explicit action. Failed reads remain unresolved; no mutation is automatically replayed. Automated verification for this follow-up is recorded by its final CI runs. Map runtime and device validation remain pending.

## M5 history implementation (2026-10-29)

M5-A is automated-verified; physical-device validation remains pending. It adds
owner-bound deletion and retention, interval and event-ID fences, an
owner-serialized history snapshot, and a shared current-audience database
view. History pages are limited to 48 hours and ordered by capture time,
receipt time and event ID. An Android History screen provides bounded pages,
route segmentation, date selection and owner controls. Foreground access is
rechecked and sensitive state clears when the screen stops or the account
changes. PostGIS tests cover authorization, concurrent ingestion, backfill,
retention and deletion; JVM and APK checks have been run during development.
Local M5-A work passed `make quality-check` with Gradle build cache disabled,
`make server-test-race`, and `make server-test-integration` against
PostgreSQL/PostGIS. Android JVM tests reported 237 passing tests, and the
minified release and instrumentation APK compiled with the cache disabled.
OpenAPI was also checked with `openapi-spec-validator`. These are local results,
not hosted CI, emulator execution, or physical-device evidence.
The retention cleanup locks candidate owners before point rows; a deterministic
PostgreSQL test verifies concurrent deletion does not invert that lock order.

M5-A Go CI and Android CI, including the headless emulator, passed on
`f747863db3aa765c5439a54674994e5ae636a30a`. Physical-device validation is
pending. The agreed privacy-first checkpoint rejects unverified
offline points from before deletion, retention changes or a new grant, even if
some were genuinely captured afterward. Local race tests cover the checkpoint;
a malicious reauthorized client can
fabricate capture data. Deleted event-ID fences remain until account deletion and their growth
needs operational review. See ADR 0009 for the design limits.

## M5 Places and geofencing implementation (2026-10-29)

M5-B adds explicitly shared Place definitions, subject-controlled monitoring
and individually chosen event recipients. An event recipient must also hold
the subject's current location grant and access to the Place; revoked grants or
place access cannot be restored silently. A bounded, durable server worker uses
existing M3 samples with accuracy limits, hysteresis, dwell and gap handling;
no Android geofence wakeup or push is implied. The in-app feed rechecks current
authorization and retention, and deleting an evidence point deletes dependent
events. Places UI and regression tests are implemented. Local
`make quality-check`, `make server-test-race` and `make server-test-integration` passed; the latter used
PostgreSQL/PostGIS, including migration lifecycle, event authorization,
retention, worker retries and concurrent revocation. Android JVM tests reported
244 passing tests, with lint and debug APK build passing. OpenAPI validation passed.
The instrumentation APK and minified unsigned release also compiled locally.
Go CI and Android CI (including the headless emulator and minified release)
passed on `f127d48b85e24cc128f20ae91fbb4ca1982cff07`:
[Go](https://github.com/QuocDuong16/anve/actions/runs/40910107090),
[Android](https://github.com/QuocDuong16/anve/actions/runs/40910107294).
Physical-device, real-GPS, Maps-key and battery validation remain pending.
See ADR 0010.
