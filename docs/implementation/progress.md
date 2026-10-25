# Implementation progress — AnVề

> Living document for engineering sessions: what is implemented and
> verified, what is in flight, and the next concrete tasks. Status labels
> follow the product taxonomy (`docs/product/overview.md#7`); "Verified"
> always names the evidence. Never mark a milestone done from documentation
> alone.

Last updated: 2026-10-26 (after M1 backend + Android client).

## Current milestone

**M1 — Authentication, device identity, privacy foundation: Implemented —
Verified (backend + client logic, no on-device field validation).**
M2 (Family Circle) is next.

## Milestones

| Milestone | Status | Notes |
| --- | --- | --- |
| M0 Foundation | Implemented — Verified | CI + Compose smoke test at `de54074`. |
| M1 Auth & privacy | Implemented — Verified (server, JVM-level) | See evidence below; Google path needs real OAuth client (external), on-device run pending. |
| M2 Family Circle | Not started | Contract + schema first. |
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

## Known gaps / external blockers

- **Google OAuth client ID not created** (needs Google Cloud project
  access): the Google path fails closed (`AUTH_NOT_CONFIGURED` / button
  hidden) until `docs/implementation/setup-android.md` is followed. Dev
  login covers local development.
- **No on-device field validation yet** — no physical device/emulator run
  of the app in this session. The Android CI proves build/tests only.
- Refresh-token reuse detection (rotation history) intentionally absent
  (ADR 0002 "Bad"); rotated-out tokens simply fail.

## Next engineering tasks (M2 — Family Circle)

1. OpenAPI: circles, invites, membership endpoints (contract-first).
2. Migration `0003_circles`: circles, memberships (role, joined state),
   invites (code, expiry, single-use), unique-one-circle-per-user MVP
   constraint.
3. `internal/circles` service + store: create/leave circle, invite
   create/accept/reject/revoke, member list; authorization matrix tests
   (cross-circle isolation, expired invite, leave revokes access).
4. Android: circle creation/join screens, invite code entry, member list.
5. Update docs + this file.
