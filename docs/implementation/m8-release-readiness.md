# M8 — release readiness

M8 is in progress. This is a technical readiness record, not a release approval,
a completed security audit or a claim of Google Play eligibility.

## M8-A: merged and automated-verified

Scope: private, bounded HTTP metrics; completion-log allowlist and controlled
panic handling; coordinated HTTP listener startup/draining; operator runbook.
[ADR 0013](../adr/0013-private-http-observability.md) records the boundary and
[the runbook](../operations/observability.md) describes operation and evidence.
Merged through [PR #17](https://github.com/QuocDuong16/anve/pull/17) as
`6ecf1d2d54ac06f1197bcbd710bde50e081bd6a6`. Local `make quality-check`,
exact-change PR CI and main Go/Android CI passed, including the binary smoke
and emulator suites. No production deployment or UI change is implied.

M7-B is merged as `5773724`; main Go/Android CI passed after merge. Manual native
approval and its formal-tool exception remain scoped to M7-B. M7 device/OEM/GPS,
battery and real Firebase delivery are still pending. Automatic arrival/stale
alerts remain Planned, so none of those outcomes gates can be marked passed by
M8 tooling work.

## M8-B: merged and automated-verified

Merged through [PR #18](https://github.com/QuocDuong16/anve/pull/18) as
`67eaf03ec5715fa28b2557836ec6138b2e6e3d3d`: explicit production configuration/version
and upload-key guard, an isolated minified signed-validation package, format/
signature/certificate verification and a main-only manual distribution workflow.
See [ADR 0014](../adr/0014-android-release-gates.md) and the
[release procedure](../operations/android-release.md). Local `make quality-check` passed, including 306 Android JVM tests and the
release configuration/resource gate. Exact-head Go/Android CI passed on
`6d66037`, including signed AAB checks and 32 emulator tests; downloaded
artifacts matched their digests and signature records. The CI test-merge tree
matched the merged source tree. Real signing credentials, environment policy,
provider/deployment checks and Play submission are external prerequisites.

## Current slice: M8-C

Implemented on the feature branch: a focused source-boundary review,
[Data Safety draft](../release/data-safety-draft.md),
[permission/declaration draft](../release/permission-declarations.md) and a
versioned Android privacy review gate (ADR 0015). The initial resolved graphs
match across production and validation: 207 modules, 14 merged permission
entries and 24 component declarations. Client-key values/raw XML are excluded
from the retained inventory. Short configuration checks compare source policy
hashes and merged fields; signed AAB checks compare the packaged fields too.
Current validation is recorded in this change's PR. These deliverables prepare
owner review; they do not submit Play forms or close provider/device gates.

## Readiness inventory and next slices

| Area | Current source evidence | Remaining exit evidence |
| --- | --- | --- |
| API observability | M8-A private process registry, bounded route/method/status labels and controlled HTTP logs | Deployment scrape/access/retention and incident checks; exact-change CI passed |
| Authentication/privacy | Per-request sessions and store authorization, consent/deletion tests from M1–M7 | Scoped source review prepared in M8-C; wider security/SDK/deployment evidence remains open |
| Dependency checks | Go vet/lint/govulncheck/race tests, pinned toolchain and Dependabot | M8-C resolves and reviews the Android SDK graph; final release SHA and external provider validation remain |
| Android transport | Main network security config refuses cleartext; debug has a local development overlay | M8-B syntax gate implemented; testing against the actual HTTPS deployment pending |
| Android release configuration | Explicit required HTTPS/client inputs, legacy-main-config refusal and isolated fixture variant | Fixture evidence tracked in PR #18; real deployment/client restrictions and integration checks remain |
| Release identity/signing | Explicit versions and external-key/certificate guard; verified AAB plus digest/certificate record; main-only manual workflow | Fixture evidence tracked in PR #18; owner-managed keys/environment policy and Play version progression remain; real distribution has not run |
| Permissions/backup | Merged manifest inventory includes SDK biometric/boot/wake and guarded exports, in addition to app location/notification declarations; source backup rules exclude database/sharedpref/DataStore | Drafts and packaged field gate added; owner disclosure/video, signed runtime/device checks and release-date policy review pending |
| Data inventory | M8-C draft maps first-party data, SDK identities/telemetry, deliberate sharing and retained audit/backup gaps | Owner-approved classifications, provider runtime/retention, public privacy/deletion URLs and Console submission |
| Push/maps/auth providers | Source integration and fake/synthetic CI evidence exist | Restricted provider configuration, real sign-in/maps/push and recipient-revocation evidence |
| Performance/error budget | HTTP measurements now have distinct semantics; device/production targets remain proposed | Representative measurements, agreed counting/window/thresholds and actual alert checks |

The Android configuration/signing rows describe tooling, not evidence that
production artifacts are ready to distribute. Do not
upload to a store or create production credentials from this inventory. Keep
external prerequisites visible rather than supplying placeholder values as real
evidence. The release pipeline should fail closed on missing required inputs
and generate reviewable artifacts before any publication decision.

## Evidence boundaries

The new Go tests exercise dummy requests and local listeners only. CI emulator
checks use synthetic data. Neither verifies production ingress/TLS, collection
retention, OEM background behavior, hardware GPS/battery, real Google/Firebase
configuration or a real-key production distribution. Synthetic signed AAB
verification is recorded separately from provider/device checks. Full M8 acceptance remains
pending those separately recorded gates.

## Regression found by the binary smoke

Without DATABASE_URL, main previously put a typed nil `*pgxpool.Pool` into the
`Pinger` interface. The interface was non-nil, so `/readyz` attempted a Ping and
returned 500 through recovery instead of the contracted 503. M8-A assigns that
interface only for a real pool; the no-database binary smoke asserts 503. Router
unit tests with an untyped nil dependency alone had not covered this wiring.

## CI shutdown regression and reproduction

The first [Go run](https://github.com/QuocDuong16/anve/actions/runs/43107311696)
passed repository/PostGIS checks but failed the race suite's clean-shutdown test.
A 50-repeat local race run reproduced pending `StateNew` connections: the test
client closed unread response bodies, causing transport dials to be abandoned
rather than reusing completed requests. The test now consumes each body to EOF
before closing; its original one-second clean-drain assertion remains intact.
A separate raw TCP case asserts the common drain deadline force-closes a
connection that never sends a request, alongside the existing active-handler
case. No timeout was raised and no assertion or case was skipped.

After the correction, this passed locally:

```bash
make server-test-race SERVER_TEST_RACE_COUNT=50 SERVER_TEST_RACE_PACKAGES=./internal/httpserver
```

The Make target keeps its original defaults (all packages, count 1) while
allowing focused repeated reproduction. Updated exact-head hosted results are
recorded in the PR; the failed initial run is not described as a pass.

## Next slice: M8-D owner evidence and release handoff

Supply and verify operator-owned privacy/deletion URLs and support handling,
retention/backup/provider decisions, final age/distribution choices and the
actual release environment/signing/provider configuration. Run a signed-app
smoke and the physical-device/permission/video matrix with real restricted
provider accounts, then reconcile the drafts against that evidence and the
submission-date policies. The announced 2027-01-27 API 37 location requirement
is tracked in the permission draft. No production credentials, public policy
URLs, store publication or real-device results are invented by M8-C tooling.
