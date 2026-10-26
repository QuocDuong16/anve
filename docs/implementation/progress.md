# Implementation progress — AnVề

> Living document for engineering sessions: what is implemented and
> verified, what is in flight, and the next concrete tasks. Status labels
> follow the product taxonomy (`docs/product/overview.md#7`); "Verified"
> always names the evidence. Never mark a milestone done from documentation
> alone.

Last updated: 2026-10-26 (after M3 Location Engine).

## Current milestone

**M1 — Authentication, device identity, privacy foundation: Implemented —
Verified (backend + client logic, no on-device field validation).**
**M2 — Family Circle: Implemented — Verified (backend + client logic).**
**M3 — Location Engine, consented ingestion, offline sync: Implemented —
Automated Verified; Pending Field Validation.**
M4 (Live Map / realtime / confidence) is next, not started.

## Milestones

| Milestone | Status | Notes |
| --- | --- | --- |
| M0 Foundation | Implemented — Verified | CI + Compose smoke test at `de54074`. |
| M1 Auth & privacy | Implemented — Verified (server, JVM-level) | See evidence below; Google path needs real OAuth client (external), on-device run pending. |
| M2 Family Circle | Implemented — Verified (server+client) | Authorization matrix tests in CI; invite deep-link pending. |
| M3 Location Engine | Implemented — Automated Verified | Server + Android + runtime HTTP smoke; field validation pending (battery/background/OEM). |
| M4 Live Map | Not started | |
| M5 History & Places | Not started | |
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
- Contract: `packages/api-contracts/openapi.yaml` 0.3.0 —
  `POST /api/v1/locations/batch` with atomic semantics, limits and stable
  error codes.
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
    network-constrained sync (100 per batch, 5 batches per pass),
    at-least-once + server idempotency, consent-revocation-first ordering,
    policy-refusal stop/purge.
  - `ui/tracking/`: Vietnamese tracking screen with prominent disclosure,
    staged permission requests, separate consent vs actual-tracking state,
    last fix/accuracy/upload, queue count, connectivity, battery saver,
    diagnostics, start/stop/resume.
  - 148 JVM tests pass; lint clean of errors; debug APK and minified release
    build compile.
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
  session was revoked (ADR 0005 §3).
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
- **Points are not tagged with a Circle.** M3 has no read path; M4/M5 must
  define audience/history authorization (current membership must not grant
  retroactive access, and leaving a Circle must not leave a hidden viewing
  channel) before exposing locations.
- **WorkManager scheduling is OS-bounded.** A force-stopped app or an OEM
  that blocks background work may delay revocation/sync until the next
  launch. Nothing is claimed beyond WorkManager's guarantees.
- **No wakeup measurement** via public Android APIs; metrics cover
  callbacks, accuracy distribution, state time, queue and uploads.

## Next engineering tasks (M4 — Live Map, not started)

1. Contract + authorization for reading latest/visible locations; per-viewer
   consent enforced on every read.
2. Realtime transport decision (WebSocket vs SSE/polling) and hub.
3. Confidence states Fresh/Delayed/Stale/Offline/Unknown.
4. Live Map UI, last-known location labels, no realtime guarantees.
5. Later: invite deep link (currently manual code entry).
