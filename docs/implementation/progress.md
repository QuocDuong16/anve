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
