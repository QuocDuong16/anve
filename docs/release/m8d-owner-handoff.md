# M8-D — owner evidence and release handoff

Status: **in progress**, 2026-11-12. This record distinguishes owner-supplied
choices, repository implementation, CI evidence and external verification.
It does not authorize store publication or declare M8 complete.

## Confirmed owner information

The owner supplied these public details in the implementation conversation:

| Field | Confirmed value | Verification boundary |
| --- | --- | --- |
| Operator | Nguyễn Võ Quốc Dương, nickname NianD | Owner-supplied public identity; no legal-entity verification claimed |
| Support/deletion-request email | nvqduong.dev@gmail.com | Mailbox delivery and request handling have not been exercised |
| Intended web domain | anve.niand.id.vn | Planned domain; DNS, hosting and TLS not verified |
| Distribution/signing direction | Google Play with Play App Signing | Console enrollment, upload key and actual signing fingerprints remain pending |
| API/provider readiness | Owner confirmed no HTTPS API or Google Cloud/Firebase project yet | Follow the [first-real-test setup guide](../operations/first-real-test.vi.md); no real provider validation claimed |

Proposed public routes: `https://anve.niand.id.vn/privacy` and
`https://anve.niand.id.vn/delete-account`. These are **planned URLs**, not
published policy/deletion resources. Content drafts are
[privacy policy](privacy-policy.vi.draft.md) and
[deletion instructions](account-deletion.vi.draft.md). Page UI must follow the
repository's Impeccable/comp-first workflow before frontend implementation.
No webpage or DNS change is included in this slice.

## Signed fixture runtime gate

`make android-release-check` now also assembles a minified, non-debuggable
`releaseValidation` APK using the same temporary key as its AAB. `apksigner`
verifies the APK and certificate pin; `aapt2` checks the isolated package and
fixture version. The APK/record have a separate artifact path from the original
AAB/record. The upload key remains temporary and is never uploaded.

Android CI consumes only the current run's `anve-signed-validation-apk` artifact.
It recomputes the APK digest, signature, identity, source commit and inventory
digest before installing. `make android-signed-smoke` requires an explicit
disposable-emulator opt-in, selects one online emulator and verifies its QEMU
property. It refuses an existing fixture installation and never targets the
production package. All device commands use the selected serial.

The runtime scenarios are fresh launch to the unavailable-provider sign-in
state, background/resume (waiting for Launcher after Home before relaunching)
and force-stop/relaunch. Each checks sign-in content,
the absence of the development-login controls and no active tracking service.
Cleanup removes the fixture even after a runtime failure. CI retains a small
JSON result with digests, API/ABI, individual outcomes and cleanup status; failed
Actions runs also retain a screenshot of the fresh unauthenticated synthetic
fixture, allowlisted UI-presence flags and exception types/static stack frames
for diagnosis. Raw exception messages,
UI XML/logcat are not artifacts. Tooling tests cover unexpected signers/identity,
untrusted records, physical/ambiguous targets, existing app data and cleanup.

This is **synthetic signed-fixture emulator evidence**. It does not exercise
Google login, Maps, Firebase delivery, authenticated features, actual Play signing
or physical-device permissions/GPS/battery. The debug instrumentation suite
remains separate. Current local/hosted results belong in this change's PR;
the existence of this procedure is not a successful test run.

The first signed-runtime runs exposed a real shrinker regression: startup
constructs Moshi's legacy pending-revocation adapter, but R8 removed/merged
that private model's constructor and fields. The sanitized crash trace points
from `DataStoreSessionStorage` through Moshi during `Application.onCreate`;
the mapping/usage outputs confirm the removed schema. The app now keeps its
`@JsonClass` models (including legacy formats) for reflective serialization,
and explicitly marks the SOS state enum. This preserves persisted JSON names
without disabling minification. Passing JVM/build/debug tests alone did not
exercise this boundary; signed startup must pass after the fix.

Manual diagnostic dispatches can reuse a previous run's synthetic APK with an
explicit artifact source commit; the report keeps `smokeRunnerCommit` separately.
They skip rebuild/debug jobs and are never release-gate evidence. Normal PR/main
runs bind to their current source and still execute the full workflow.

## Play App Signing handoff

Use Google's generated app-signing key in Play Console as the planned direction.
The developer still needs a dedicated **upload key** to sign the AAB accepted
by Play. Existing `ANDROID_UPLOAD_KEYSTORE_BASE64`/certificate inputs are for
that upload identity. Do not substitute the Play app-signing fingerprint into
the pipeline's upload-certificate pin.

After Console enrollment, record the public upload and app-signing certificate
fingerprints separately in the owner's release record. Register the package
and applicable installed-app certificate fingerprints with OAuth/Maps/Firebase;
verify a build actually installed through a Play test track. A sideloaded APK
signed by the upload key has a different signing identity and cannot establish
that the Play-installed Google integrations work. No private key or password
belongs in an issue, chat or repository.

Sources: [Android app signing](https://developer.android.com/studio/publish/app-signing),
[Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756),
[Google client authentication](https://developers.google.com/android/guides/client-auth).

## Remaining owner/external gates

| Gate | Required evidence | Current state |
| --- | --- | --- |
| Public resources | Working HTTPS privacy/deletion pages and an exercised support-email request | Owner/domain known; content drafted; publication/delivery pending |
| Operator data handling | Hosting region/subprocessors, audit/log/backup retention, restore deletion procedure and provider deletion handling | Decisions and operational evidence pending; no invented time limits |
| Audience/distribution | Target age groups, distribution countries, final Data Safety classifications and permission declaration/video | Owner decisions/Console submission pending |
| Upload/release environment | Owner-controlled upload key/recovery, protected `android-release` environment, verified actual release AAB | Play signing direction chosen; setup/build not yet verified |
| Real providers | HTTPS API, matching OAuth audience, restricted Maps/Firebase clients, Google sign-in, rendered maps, push accepted/delivered/acknowledged and revocation | Provider configuration/runtime not yet verified |
| Play-installed app | Exact track/version/package and Play app-signing fingerprints; repeat real-provider smoke | Pending; do not infer from the synthetic APK |
| Physical devices | Model/OS, permission states, screen-off, process/reboot/OEM behavior, offline consent, hardware GPS and measured battery | Device availability/results pending; follow M7 validation |

For each actual test, record UTC date, source/AAB digest/version, installation
channel and certificate, environment, device/model/OS, scenario and observed
pass/failure. Use consenting test accounts and keep coordinates, tokens and
private account identifiers out of shared screenshots/logs. Link sanitized
evidence and unresolved failures; do not replace pending rows with CI results
from a different evidence tier. Reconcile the Data Safety/permission drafts
and recheck current submission policies only after these facts are known.
