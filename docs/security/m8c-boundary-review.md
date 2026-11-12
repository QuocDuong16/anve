# M8-C — focused release trust-boundary review

Reviewed 2026-11-12 against the M8-C branch based on M8-B main `67eaf03`.
This is a scoped source review and release-preparation record, not a complete
security audit, penetration test or proof that the remaining system is safe.
No live endpoint, production principal, provider credential or physical device
was probed. Existing regression suites are used under the repository's normal
Make/CI workflow; no new exploit harness was run.

## Coverage and evidence

| Boundary and lower-trust input | Reviewed control and concrete source | Existing evidence and limits |
| --- | --- | --- |
| Caller-supplied bearer token → account/session | `RequireAuth` in `internal/httpapi/middleware.go` calls authentication on each request; `PGStore.Authenticate` in `internal/auth/store_pg.go` joins user/session, requires a matching token hash, non-revocation and access expiry. Auth/device touch logs contain IDs and remain separate from sanitized HTTP metrics. | `internal/auth/store_pg_test.go` tests rotation, re-sign-in revocation and account deletion. Provider audience/certificate validation and deployment ingress are separate checks, not inferred from DB tests. |
| Authenticated installation's batch → another account/device or revoked consent | `internal/locations/store_pg.go: Ingest` locks the account, session/device and membership; compares every event's installation ID to that session and enforces retention/deletion fences and capture generation before commit. | `TestPGIngestRefusesConsentOffAndWritesNothing`, revoked-session, revocation ordering and deleted-history replay cases in its PG suite. A malicious reauthorized client can fabricate capture content; a checkpoint does not prove physical presence. |
| Circle member or former viewer → another member's live/history coordinates | `locationaccess/store_pg.go: Grant/Latest`, `history_pg.go: History` and the `active_location_audience` view in migration 0007 bind current consent, membership periods, sharing epoch, selected viewer and checkpoint. History checks current access after reading too. | Explicit-grant, revoke/read, rejoin/epoch and pre-grant capture cases in `locationaccess/store_pg_test.go`. Revocation gates future reads; previously delivered data/screenshots cannot be recalled. |
| Owner's delete request → account, other members and retained records | `auth/store_pg.go: deleteUser` locks the account and owned circles before checking other members; refuses a populated owned Circle and deletes the user transactionally with audit. | `TestPGDeleteAccountCascadesAndKeepsAudit` explicitly asserts the actor UUID remains in audit; race suites cover Circle creation/redeem. Cascade is not provider, log or backup erasure. Public request handling and retention justification remain open. |
| Notification provider/queued work → sensitive content or a stale recipient | `notifications/fcm.go: Send` permits fixed kinds and sends token plus opaque event ID/kind, with no coordinates, destination or person name. The notification/Places/Journey authorization behavior is retained from ADRs 0010–0012 and their PG tests, rather than claimed newly audited end to end here. | Fake-provider dispatch/revocation tests exist in the SOS/Places/Journey suites. Provider acceptance and debug instrumentation do not prove real delivery, current Google project configuration or every minified-app display path. |
| Account switch → a pending FCM registration | `PushRegistrationWorker` in `FirebaseRegistration.kt` checks the expected owner before initialization and again after token retrieval. Manifest messaging auto-init is false. Notification setup invokes registration; the SDK's FID/provider lifetime is a distinct boundary. | Real Firebase startup, token rotation/deletion and OS permission changes need configured runtime observation. No claim is made that account deletion currently erases Firebase installation identifiers. |
| A deliberately exported diagnostic report → another app | `tracking/diagnostics/Diagnostics.kt: safeDiagnosticsReport` formats a fixed allowlist; `DiagnosticsViewModel` binds export to owner/generation and `DiagnosticsScreen` requires preview before `ACTION_SEND`. | M7 tests cover sensitive-field exclusion/owner changes. Report state/relative ages still reveal activity. No automatic report-upload path was identified; sharesheet launch is not delivery evidence. |
| Build/provider config or dependency update → a distributed app | M8-B input/signing guards and signed AAB checks remain mandatory. M8-C compares resolved production/fixture module graphs, reviewed manifest fields and source policy digests; the actual AAB manifest is checked before publishing its artifact record. | Missing/unsafe input, signing-cache, wrong-pin/tamper/unsigned regressions plus M8-C drift tests. An editable baseline is a review contract, not a tamper-proof policy, artifact attestation, vulnerability scanner or provider-validation result. |

No new cross-principal violation was established by these scoped source
traces. That statement is limited to this table; it is not a clean bill of
health for unreviewed code, SDK implementations or infrastructure. Prior
automated evidence is linked in the ADRs/implementation documents and must
remain distinct from the current PR's exact-change CI.

## Source-grounded release gaps

| Gap | Missing evidence and completion path |
| --- | --- |
| SDK data handling exceeds first-party DTOs | Maps metadata/pseudonymous IDs and Firebase Installations must be covered by the Data Safety review. The 207-module graph includes support/platform modules and a measurement connector, not evidence that Firebase Analytics runs. Observe the exact configured SDKs and reconcile their provider disclosures. |
| Audit/backup/provider deletion semantics | Account cascade preserves audit actor UUIDs. Owner must define justified audit/log/backup retention, restores and the public deletion process, and verify provider identity deletion separately. Do not state “all data immediately deleted.” |
| Effective installed-app and external component behavior | The merged package includes SDK boot/biometric/wake permissions and permission-guarded exported components. Source flags do not establish OEM behavior or Google/OS permission ownership. Test the signed installed app and legitimate provider paths. |
| Deployment transport and access | Release requires an explicit HTTPS DNS URL; source cannot prove ingress TLS, trusted proxy policy, metrics isolation, DB/backup access, collector retention or actual log sinks. Owner-operated deployment checks are still required. |
| Play/operator decisions | Privacy/deletion URLs, target age groups, sharing exemptions, provider contracts and declaration/video submission are owner decisions. The policy review notes the announced 2027-01-27 API 37 location change without treating it as already effective. |

Do not assign vulnerability severity to these unresolved facts. They are
release evidence items. Use the [Data Safety draft](../release/data-safety-draft.md),
[permission drafts](../release/permission-declarations.md) and
[M8 readiness](../implementation/m8-release-readiness.md) to close them with
actual source/runtime/operator evidence.

## Inventory check contract

`make android-release-config-check` compares 207 resolved production/fixture
coordinates (including versions), 14 permission declarations, the reviewed
component/intent-action fields, application security flags and non-debug source
manifest/XML policy hashes against the tracked baseline. New or missing SDKs,
permissions/components, lost component permissions and policy edits fail the
check. A full signed-bundle check also compares the allowlisted AAB manifest
fields, and its JSON record names the baseline digest.

It intentionally omits client-key values/raw XML. Resource references are
normalized because AABs use compiled IDs; referenced component booleans are
reported as resource-controlled, not assumed runtime values. Source XML policy
digests are not a semantic inspection of every compiled XML resource or SDK
implementation. SDK version equality does not prove binary reproducibility,
network behavior, final Data Safety classifications or source-code coverage.
The candidate command never overwrites the reviewed baseline.
