# ADR 0004 — M1/M2 hardening: deletion, identity isolation, audit ownership

- **Status:** Accepted
- **Date:** 2026-10-26
- **Supersedes:** nothing; amends ADR 0002 where noted.

## Context

A correctness and security review of M1 (authentication, device identity,
privacy foundation) and M2 (Family Circle) ran before M3 (Location Engine)
began. It found integrity problems that automation had not caught: account
deletion could silently dissolve a populated circle, the local session was
discarded even when the server rejected the deletion, two layers recorded
the same audit event, the development login was gated on "not production"
rather than on an explicit development environment, navigation treated a
network failure as a sign-out, and the backup rules did not cover the
DataStore files that hold the encrypted session.

M3 will build on exactly these foundations — authentication, consent,
circle authorization and session lifecycle — so they must be sound first.

## Decision

### 1. Account deletion never dissolves a populated circle

`DELETE /api/v1/me` locks the circles the caller owns (`FOR UPDATE`),
counts their other members, and refuses with 409 `CIRCLE_HAS_MEMBERS`
while any remain. The only path to ending a populated circle is the
explicit dissolve action of M2. Circles the caller owns alone are removed
by the same transaction and audited as `circle.dissolved` (detail
`via=account_deletion`), followed by `auth.account_deleted`.

The row lock closes the TOCTOU window against a concurrent invite
redemption: a redeem needs `KEY SHARE` on the circle row, so it either
commits before the count (and is seen) or blocks until deletion commits
(and fails on the vanished circle). The two transactions can deadlock on
the invite row, so the store retries a bounded number of times on
PostgreSQL `40P01`/`40001`. `LeaveCircle` and `RedeemInvite` do the same.

### 2. Development identities are isolated from Google identities

Both sign-in paths may return 409 `IDENTITY_CONFLICT`:

- a Google sign-in whose verified email belongs to an account with no
  `google_sub` (i.e. created through the development fixture) is refused;
- the development fixture refuses an email that belongs to a
  Google-verified account.

Matching emails are not proof of the same subject. The previous linking
behavior let a development login create an account for
`victim@gmail.com`, after which the real owner's Google sign-in would be
funneled into that account while the fixture session stayed live — an
account takeover of a real Google identity by construction.

The fixture is additionally an allowlist of one: the server refuses to
start with `AUTH_DEV_LOGIN_ENABLED=true` unless `ENVIRONMENT` is exactly
`development` (staging, production, mis-cased and unknown names are
rejected), and the route is not mounted when the flag is false. Release
builds never render the form (`BuildConfig.DEBUG`), which is UI hygiene,
not the security boundary — the boundary is the server check.

### 3. One audit event per business change, in its transaction

Every business mutation records exactly one audit row from inside the
same database transaction. There is no best-effort path: a mandatory
event that can silently go missing after the business commit is worse
than a failed request.

| Event | Audit |
| --- | --- |
| Sign-in / dev sign-in | `auth.signin` / `auth.dev_signin`, in the sign-in transaction |
| Sign-out | `auth.signout`, in the revocation transaction (repeat is a no-op) |
| Device revoke | `auth.device_revoked`, in the deletion transaction |
| Consent on/off | `consent.location_sharing.*`, in the update transaction |
| Account deletion | `auth.account_deleted` (+ `circle.dissolved` per empty owned circle) |
| Circle create/rename/join/leave/dissolve | one row each, in the mutation transaction |
| Member removal, invite create/revoke | one row each, in the mutation transaction |

Session supersession on re-login is covered by the sign-in event (detail
`device_id`); it is not a separate event. Reads, profile reads and device
listing are not audited. The audit log stores identities, action names,
timestamps and small ids — never tokens, emails beyond the actor id, or
coordinates.

### 4. Session lifecycle distinguishes local state from server truth

Android sign-out always removes the local session (the user asked for it,
and it must work offline), but the repository reports which outcome was
achieved:

- backend confirmed, or already rejected the session (`401`): nothing
  pending;
- backend unreachable or a 5xx: a **pending revocation** is stored —
  Keystore-sealed refresh token plus a timestamp, in its own DataStore
  key, never usable as a session. On a later launch the app rotates the
  old refresh token once and revokes the session; a rejected refresh
  clears the record. The record expires after seven days and a new
  sign-in never deletes it.

Account deletion clears the local session only after a 2xx response. Any
HTTP failure keeps the account usable and retryable. A transport failure
is an unknown outcome, not a success: the app keeps the session and
reconciles with an authenticated read — if the server had already deleted
the account, that read is rejected and the interceptor clears the local
session.

Navigation derives from the session storage flow only. Screen data
(`/me`) failing does not mean signed out; the account screen shows an
error with retry instead of navigating to sign-in.

### 5. Sensitive local storage stays off backup and transfer

Preferences DataStore keeps its files in `files/datastore/`, which
belongs to the `file` backup domain, not `sharedpref` and not `database`.
Both rule sets (`backup_rules.xml` for API < 31 and
`data_extraction_rules.xml` for API 31+, cloud backup and device
transfer) exclude `file/datastore` in addition to the preference and
database domains. The Keystore key never travels with the blob, so a
restored blob would be undecryptable — the ciphertext must still never
leave the device.

Device identity is an **app installation identity**: stable across app
restarts, process death, updates and sign-out/sign-in, regenerated by a
reinstall or factory reset. No Android hardware identifier is read. The
server treats it the same way (a `devices` row per installation).

### 6. Consent is not tracking, and the UI must say so

Until M3, the consent switch records the data subject's agreement only.
Distinct states that the product must not conflate:

| State | Meaning today |
| --- | --- |
| Sharing consent enabled | server-side preference, audited |
| OS location permission granted | not requested yet (M3) |
| Location Engine active | does not exist yet (M3) |
| Upload succeeded | does not exist yet (M3) |
| Realtime distribution available | does not exist yet (M4) |

The account screen states that this build does not collect location yet.
Rapid consent toggles are serialized in the client so the final switch
state always equals the last state the backend confirmed; a failed toggle
rolls back to that confirmed state.

## Consequences

**Good**

- M3 can rely on authorization, consent, audit and session semantics that
  have been exercised against failure, concurrency and security edge
  cases.
- Account deletion and circle dissolution have exactly one meaning each,
  both on the wire and in the database.
- Every privacy-relevant business change has exactly one audit row, or
  the change did not happen.

**Bad**

- A deadlock retry is visible as slightly higher latency in the rare
  owner-deletion-vs-redemption race; PostgreSQL aborts one side instead of
  corrupting state.
- The pending-revocation retry only runs at app start; a session whose
  revocation was never delivered stays valid until it expires if the app
  is never opened again. This is documented in the sign-out dialog. A
  WorkManager-based retry is a candidate for M6, when the app gains a
  notification-worthy background surface.
- The audit row is the price of the business write; an audit insert
  failure fails the request. That is the intended trade-off.

**Neutral**

- The development fixture remains available for local work, but with a
  tighter blast radius and no path into Google accounts.
