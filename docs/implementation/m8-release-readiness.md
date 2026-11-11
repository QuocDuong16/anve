# M8 — release readiness

M8 is in progress. This is a technical readiness record, not a release approval,
a completed security audit or a claim of Google Play eligibility.

## Current slice: M8-A

Scope: private, bounded HTTP metrics; completion-log allowlist and controlled
panic handling; coordinated HTTP listener startup/draining; operator runbook.
[ADR 0013](../adr/0013-private-http-observability.md) records the boundary and
[the runbook](../operations/observability.md) describes operation and evidence.
Implementation exists on the feature branch. Local `make quality-check` passed;
the binary smoke target and exact-change hosted verification are recorded in its
PR and must be green before delivery. No UI was changed in this slice.

M7-B is merged as `5773724`; main Go/Android CI passed after merge. Manual native
approval and its formal-tool exception remain scoped to M7-B. M7 device/OEM/GPS,
battery and real Firebase delivery are still pending. Automatic arrival/stale
alerts remain Planned, so none of those outcomes gates can be marked passed by
M8 tooling work.

## Readiness inventory and next slices

| Area | Current source evidence | Remaining exit evidence |
| --- | --- | --- |
| API observability | M8-A private process registry, bounded route/method/status labels and controlled HTTP logs | Exact-change CI, deployment scrape/access/retention and incident checks |
| Authentication/privacy | Per-request sessions and store authorization, consent/deletion tests from M1–M7 | Focused release threat-boundary review; not established by the new metrics tests |
| Dependency checks | Go vet/lint/govulncheck/race tests, pinned toolchain and Dependabot | Final release SHA checks; Android dependency/release review and external provider validation |
| Android transport | Main network security config refuses cleartext; debug has a local development overlay | Release API configuration gate and testing against the actual HTTPS deployment |
| Android release configuration | Current build script uses the emulator API URL fallback in defaultConfig; release minification compiles in CI | Explicit release URL/config validation before distributable artifacts; compiler success alone is not readiness |
| Release identity/signing | VersionCode 1/versionName 0.2.0 in build script; no signing configuration in the inspected app build script; current CI builds debug/minified release | Controlled version progression, protected signing inputs, reproducible signed AAB and digest/provenance record |
| Permissions/backup | Manifest declares foreground/background location, location FGS and notifications; private app services; no app-defined receiver that auto-starts tracking. Backup/transfer rules exclude database/sharedpref/DataStore | Device behavior, disclosure/permission review and declarations checked against final package and current Play requirements |
| Data inventory | Product privacy docs distinguish coordinates, selected audience, retention/deletion, audit and opaque push payloads | Data Safety draft reconciled with final dependencies/config, privacy policy/account deletion URLs and owner review |
| Push/maps/auth providers | Source integration and fake/synthetic CI evidence exist | Restricted provider configuration, real sign-in/maps/push and recipient-revocation evidence |
| Performance/error budget | HTTP measurements now have distinct semantics; device/production targets remain proposed | Representative measurements, agreed counting/window/thresholds and actual alert checks |

The Android configuration/signing rows are concrete next implementation work,
not a report that release artifacts are already safe to distribute. Do not
upload to a store or create production credentials from this inventory. Keep
external prerequisites visible rather than supplying placeholder values as real
evidence. The release pipeline should fail closed on missing required inputs
and generate reviewable artifacts before any publication decision.

## Evidence boundaries

The new Go tests exercise dummy requests and local listeners only. CI emulator
checks use synthetic data. Neither verifies production ingress/TLS, collection
retention, OEM background behavior, hardware GPS/battery, real Google/Firebase
configuration or a signed distribution pipeline. Full M8 acceptance remains
pending those separately recorded gates.

## Regression found by the binary smoke

Without DATABASE_URL, main previously put a typed nil `*pgxpool.Pool` into the
`Pinger` interface. The interface was non-nil, so `/readyz` attempted a Ping and
returned 500 through recovery instead of the contracted 503. M8-A assigns that
interface only for a real pool; the no-database binary smoke asserts 503. Router
unit tests with an untyped nil dependency alone had not covered this wiring.
