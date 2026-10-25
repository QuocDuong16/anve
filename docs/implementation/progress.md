# Implementation progress — AnVề

> Living document for engineering sessions: what is implemented and
> verified, what is in flight, and the next concrete tasks. Status labels
> follow the product taxonomy (`docs/product/overview.md#7`); "Verified"
> always names the evidence. Never mark a milestone done from documentation
> alone.

Last updated: 2026-10-26 (after M2 backend + Android client).

## Current milestone

**M1 — Authentication, device identity, privacy foundation: Implemented —
Verified (backend + client logic, no on-device field validation).**
**M2 — Family Circle: Implemented — Verified (backend + client logic).**
M3 (Location Engine) is next.

## Milestones

| Milestone | Status | Notes |
| --- | --- | --- |
| M0 Foundation | Implemented — Verified | CI + Compose smoke test at `de54074`. |
| M1 Auth & privacy | Implemented — Verified (server, JVM-level) | See evidence below; Google path needs real OAuth client (external), on-device run pending. |
| M2 Family Circle | Implemented — Verified (server+client) | Authorization matrix tests in CI; invite deep-link pending. |
| M3 Location Engine | Not started | |
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

## Known gaps / external blockers

- **Google OAuth client ID not created** (needs Google Cloud project
  access): the Google path fails closed (`AUTH_NOT_CONFIGURED` / button
  hidden) until `docs/implementation/setup-android.md` is followed. Dev
  login covers local development.
- **No on-device field validation yet** — no physical device/emulator run
  of the app in this session. The Android CI proves build/tests only.
- Refresh-token reuse detection (rotation history) intentionally absent
  (ADR 0002 "Bad"); rotated-out tokens simply fail.

## Next engineering tasks (M3 — Location Engine)

1. OpenAPI: batch location ingestion endpoint (idempotency keys, device
   ownership, consent enforcement at ingestion).
2. Migration `0004_locations`: location_points with PostGIS geography,
   device+recorded_at+received_at timestamps, dedup constraint.
3. Go `internal/locations`: batch validation, idempotent writes,
   out-of-order acceptance, consent check per batch.
4. Android Location Engine: state machine (IDLE/STATIONARY/MOVING/…),
   FusedLocationProvider behind an interface, foreground service type
   `location`, permission flow (fine → background, prominent disclosure).
5. Room durable queue + SyncManager (batch upload, backoff, dedup,
   retention rules for paused/left states).
6. Tests: fake location provider state machine tests; queue loss/retry/
   restart tests; ingestion idempotency integration tests.
7. Later: invite deep link (currently manual code entry).
