# ADR 0002 — Authentication: Google Sign-In with backend-issued opaque sessions

- **Status:** Accepted
- **Date:** 2026-10-25
- **Milestone:** M1

## Context

M1 needs authentication, device identity and a privacy foundation. The product
brief (implementation scope) prefers a low-friction sign-in for families and
names Google Sign-In via Android Credential Manager as the default candidate,
with the Go backend responsible for verifying the identity token, issuing
AnVề's own sessions and authorizing API access.

Requirements that shape the decision:

- Persistent sessions across app restarts, with refresh and rotation.
- Secure, **immediate** revocation (sign out, revoke a device session) — a
  revoked session must stop working even if the client keeps presenting the
  old token.
- Multiple devices per account, each listable and revocable by its owner only.
- No sensitive token stored in plaintext on the device.
- The Google identity token and the AnVề application session token must stay
  separate concepts (the brief calls this out explicitly).
- End-to-end development must be possible before the Google Cloud project
  exists, without faking "integration complete".

## Decision

### Identity provider: Google Sign-In (Credential Manager)

- The Android app uses `androidx.credentials` (Credential Manager) with the
  `googleid` library (`GetGoogleIdOption`) to obtain a Google **ID token**.
- The ID token is sent once to `POST /api/v1/auth/google`.
- The backend verifies the token with the official
  `google.golang.org/api/idtoken` package: signature against Google's JWKS,
  `iss` in the allowed set, `aud` equal to the configured OAuth client ID,
  expiry/clock-skew checks. Verification happens on every sign-in; nothing
  about the Google token is trusted beyond that single call.
- The OAuth client ID is configuration (`AUTH_GOOGLE_CLIENT_ID`), never
  hard-coded. When it is unset, Google sign-in is advertised as disabled and
  the endpoint fails closed with `503 AUTH_NOT_CONFIGURED` — no fake default.

### Session model: opaque server-side tokens, verified per request

- Sign-in (or refresh) creates a **session row** in PostgreSQL bound to one
  user and one device. The client receives two opaque random tokens
  (32 bytes, base64url):
  - an **access token** (short TTL) presented as `Authorization: Bearer …`;
  - a **refresh token** (long TTL) used only against `POST /api/v1/auth/refresh`.
- Only SHA-256 hashes of the tokens are stored. A database leak does not
  yield usable tokens.
- The auth middleware hashes the presented token and looks the session up in
  the database on **every** request (indexed by hash), rejecting revoked or
  expired sessions. This makes revocation immediate — the brief's acceptance
  criteria — at the cost of one cheap indexed query per request, which is the
  right trade for a family-scale product. JWTs were rejected for this reason:
  they cannot be revoked before expiry without server-side state anyway.
- Refresh rotates **both** tokens and updates the row; the previous pair
  becomes invalid. Each device holds one active session; signing in again on
  the same device revokes that device's previous session first.
- Account deletion and sign-out cascade to sessions and devices.

### Device identity

- The app generates a random UUID (stored privately on the device) and sends
  it with every sign-in; the server upserts a `devices` row keyed by
  `(user_id, client_device_id)`, so reinstalling or re-logging on the same
  physical device reuses the row instead of accumulating ghosts.
- Users can list their own devices/sessions and revoke any of them. Listing
  another user's devices is impossible by construction (queries are scoped by
  the authenticated user).

### Development sign-in (explicit debug-only mode)

Before the Google Cloud project is configured, backend and app development
needs a working session flow. `AUTH_DEV_LOGIN_ENABLED=true` (in development)
exposes `POST /api/v1/auth/dev-login`, which signs in an arbitrary email
without a Google token. It is:

- refused at startup unless `ENVIRONMENT` is exactly `development`
  (hardened in ADR 0004: staging, production, mis-cased and unknown names all
  fail), and network-restricted in ADR 0005: loopback listener by default, a
  non-loopback listener needs an explicit opt-in plus a source CIDR
  allowlist, and the route checks the real source address;
- advertised as `devLogin: true` by the public auth-config endpoint so clients
  never guess; release servers never advertise it;
- audited as `auth.dev_signin`.

This is a deliberate development fixture, not a bypass that ships: it cannot
exist in a production process by construction, and it cannot be reached from
an unexpected network.

### Token storage on Android

Tokens are persisted in DataStore, encrypted with an AES-256-GCM key held in
the Android Keystore (hardware-backed where available). No plaintext token is
ever written to disk; the DataStore file lives in the `sharedpref`/`files`
domain already excluded from Android backup by the M0 rules.

## Consequences

**Good**

- Immediate revocation, per-device sessions, replay of old tokens after
  rotation fails — the whole M1 acceptance list falls out of one table.
- No JWT machinery, no signing-key management, no clock-skew trust in the
  client's token.
- Google credentials are the only external dependency of the flow, and the
  backend works end-to-end without them via the audited dev fixture.

**Bad**

- One database lookup per authenticated request (accepted at family scale;
  can be revisited with a short-lived revocation cache if load ever requires).
- Refresh-token reuse detection (rotation history) is not implemented — a
  rotated-out refresh token simply fails. Keeping old hashes for theft
  detection is a possible future hardening.

**Neutral**

- `idtoken` verification adds the `google.golang.org/api` dependency to the
  server; it is the official implementation and caches JWKS.
