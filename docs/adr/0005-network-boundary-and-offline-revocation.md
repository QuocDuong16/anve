# ADR 0005 — Development network boundary, durable offline revocation, deletion serialization

- **Status:** Accepted
- **Date:** 2026-10-26
- **Amends:** ADR 0002 (development login, session lifecycle) and ADR 0004
  (account deletion).

## Context

A second M1/M2 review, after the hardening recorded in ADR 0004, found four
remaining reliability and security problems:

1. The development login was gated on `ENVIRONMENT=development` and an
   explicit flag, but the server listened on every interface
   (`Addr: ":" + Port`) and Compose published the API on `0.0.0.0`. A dev
   process could therefore be reachable from the LAN and mint sessions for
   arbitrary emails. The environment flag alone does not describe network
   exposure.
2. Offline sign-out stored exactly one pending revocation under one
   DataStore key. Signing out account A offline, then account B offline,
   overwrote A's record — A's session could stay alive server-side with no
   remaining retry.
3. Account deletion locked the owned circles but not the account row, so a
   concurrent circle creation could commit after the circle scan. The
   cascade then removed the new circle without counting its members or
   writing a dissolution audit, while both APIs reported success.
4. The offline sign-out message lived in the Home screen's state, but
   clearing the session immediately navigated to Sign-In and disposed that
   state. The user never saw the limitation.

## Decision

### 1. Listener exposure is explicit; development defaults to loopback

`LISTEN_ADDR` is the exact bind address.

- Development without `LISTEN_ADDR`: `127.0.0.1:<PORT>`.
- Staging/production/unknown environments: `LISTEN_ADDR` is mandatory; the
  server refuses to start without it.
- Compose: the API container listens on `0.0.0.0:8080` (it must accept the
  port mapping) but publishes `127.0.0.1:${API_PORT}:8080` on the host, and
  the edge network is pinned to `172.28.0.0/24` so the development-login
  allowlist can name it deterministically.
- `scripts/check_compose_ports.py` (Make target `compose-lint`, CI
  repository job) fails if any Compose port is published beyond loopback.

A bind address is not treated as the only defense: see the source allowlist
below.

### 2. Development login has three independent gates

1. **Environment:** exactly `development`; staging, production, mis-cased
   and unknown names refuse startup.
2. **Network:** the listener must be loopback, or the operator must set
   `AUTH_DEV_LOGIN_ALLOW_NETWORK=true` *and* an explicit
   `AUTH_DEV_LOGIN_ALLOWED_CIDRS`. Catch-all prefixes (`0.0.0.0/0`,
   `::/0`) are refused. The HTTP route additionally checks the request's
   `RemoteAddr` against the same allowlist; `X-Forwarded-For` and
   `X-Real-IP` are never trusted (the router does not install
   `middleware.RealIP`). An empty allowlist denies every request.
3. **Mounting:** the route is not registered unless the flag is true.

No shared secret is embedded in the app or the repository: a secret in a
debug APK is not a security boundary for an endpoint reachable from an
untrusted network. Remote development is expected to use `adb reverse` or
an SSH tunnel to a loopback listener. If the fixture is enabled on a
non-loopback listener, every host inside the allowlisted CIDR can mint
sessions — keep that CIDR as narrow as the deployment allows (Compose uses
the pinned bridge subnet, never the LAN).

### 3. Pending revocations are a bounded encrypted queue

`SessionStorage` stores a JSON array of `PendingRevocation` records under
one DataStore key, sealed as a whole with the existing Keystore cipher.

- One record per undelivered sign-out, with a random local id (never an
  email, display name or token in clear).
- Rotation replaces the token in place by id; records never overwrite each
  other.
- At most 8 records; the oldest is dropped when the bound is exceeded.
- 7-day expiry from the original sign-out, preserved across rotation.
- Read-merge-write happens inside one DataStore `edit`, so concurrent
  sign-outs cannot lose a record and a crash leaves the old or the new
  queue, never a partial one.
- The retry engine processes records independently: refresh (a 401 means the
  session is already dead and the record is dropped), persist the rotated
  token atomically before using it, revoke with the new access token, and
  reschedule on transport/server errors with 30s→6h exponential backoff.
  One failing record never blocks the queue.
- The old single-record key is migrated on read; its id is derived from the
  original timestamp so a removal still matches.

This is **best-effort** revocation. If the device never reconnects, the
session stays alive until its refresh token expires. If the process dies
between a successful rotation and persisting the new token, the old token
is dead and the next retry gets 401; the record is dropped because the
session can no longer be revived — the server-side fate of that session is
not claimed either way.

### 4. Account deletion locks the account row first

`DeleteUser` takes `SELECT ... FOR UPDATE` on the user row before scanning
owned circles and counting members. Every circle creation takes a `KEY
SHARE` lock on that row through its foreign key, so deletion and creation
are serialized:

- creation commits first → the circle scan sees it, counts members, and
  either refuses the deletion or removes an empty owner-only circle with an
  explicit `circle.dissolved` audit;
- deletion commits first → the creation's foreign-key check fails and the
  API never reports success for a row that was cascaded away.

Three deterministic PostgreSQL tests pin the outcomes, using an observed
`pg_stat_activity` lock wait as the ordering barrier (the polling loop is a
timeout mechanism, not the ordering primitive): create-wins (dissolution
audited exactly once), delete-wins (creation fails on the foreign key, no
`circle.created` audit), and a forced deadlock recovered by the existing
bounded retry with a single audit row.

### 5. Offline sign-out feedback is app-scoped and one-shot

`AuthRepository.signOut` publishes a `SessionNotice` to an app-scoped
`SessionNoticeStore` *before* clearing the session. The Sign-In destination
renders it as a dismissible card; a successful sign-in clears it, so a new
account never inherits an old message. The notice contains only a kind and
a local id — no account identifier or token. It is deliberately not
persisted across process death: the durable part is the revocation queue,
while the message is a transient explanation. Recomposition and rotation
show the same single notice because it lives in the application container,
not in a disposed screen.

A bare `401` from sign-out is not treated as confirmation: it can mean the
access token expired while the refresh token is still valid. The session is
confirmed dead only when the refresh token was rejected too (the
interceptor clears storage). A transient refresh failure keeps the sign-out
`LocalOnly`; if the pair rotated before the retried sign-out failed, the
repository queues the **rotated** refresh token, because the captured one is
already dead.

## Consequences

- Non-development deployments must now set `LISTEN_ADDR`; this is a startup
  failure, not a silent fallback.
- Compose pins `172.28.0.0/24` for the edge network. A host with an existing
  network on that subnet must change the pin (and the allowlist together).
- The development login remains a local tool; enabling it on a non-loopback
  listener requires deliberate configuration and a narrow allowlist. It is
  still not a public authentication provider.
- Offline revocation is bounded by 7 days and by the number of records, and
  it is explicitly best-effort. The UI says so instead of claiming a
  server-side revocation.
- Field validation remains necessary: emulator connectivity, on-device
  backup exclusion and the Sign-In notice presentation are not proven by
  JVM tests.
