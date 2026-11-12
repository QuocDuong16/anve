# Android signed release procedure

M8-B prepares and verifies artifacts. It does not publish to Google Play or
establish that real sign-in, maps, Firebase, GPS or battery behavior works.
See [ADR 0014](../adr/0014-android-release-gates.md) and
[M8 readiness](../implementation/m8-release-readiness.md) for remaining gates.

## Validation without production credentials

After `mise install`, `make toolchain-check` and Android SDK setup:

```bash
make android-release-config-check
make android-release-check
```

M8-C also compares the resolved production/validation SDK graphs, allowlisted
merged manifest fields and source manifest/XML policy digests with
`packages/privacy/android-release-baseline.json`. The full AAB check compares
the packaged manifest too; its JSON record adds `privacyInventorySha256` and
`privacyManifestVerified`. These mean “matches the reviewed inventory,” not
Play approval, SDK telemetry validation or a complete security audit. See
[ADR 0015](../adr/0015-android-privacy-review-inventory.md),
[the boundary review](../security/m8c-boundary-review.md),
[Data Safety draft](../release/data-safety-draft.md) and
[permission draft](../release/permission-declarations.md).

The first runs build-logic tests, proves missing production inputs are refused
and checks generated resource wiring. The second also builds a minified AAB
with package **com.anve.app.releasevalidation**, a newly generated temporary
key and synthetic client configuration. It validates the bundle format,
package/version, non-debuggable manifest and every content entry's signature
against the certificate pin; wrong-pin, tampered, unsigned and added-unsigned-
entry fixtures must fail. The temporary key is removed even if validation fails.
Production packaging must also reject the synthetic endpoint, and configuration
caching with signing inputs must be refused.
The cached rejection graph must remain reusable after every signing input is
replaced with invalid values: credentials must not be configuration-cache inputs.
The validation AAB and JSON digest/certificate record are uploaded by Android CI;
they are not the production app and cannot verify any provider integration.

CI separately retains `anve-privacy-review`: resolved-coordinate reports and a
sanitized candidate, even when drift fails the build. It contains no raw
manifest XML or client-key values. To review an intentional SDK, permission,
component or source-policy change:

```bash
make android-privacy-candidate
git diff --no-index packages/privacy/android-release-baseline.json apps/android/app/build/outputs/privacy-review/candidate.json
```

The candidate command builds resources with synthetic inputs and writes no
production AAB. It never updates the tracked baseline. Review the diff,
corresponding SDK guidance/data flows and declaration drafts; then update the
baseline and relevant docs together in the same PR. A new manifest enum/type
may also require an explicit parser mapping. Repeat the ordinary release gates.
Do not bypass or automatically learn unexpected drift. The inventory includes
module versions, so dependency-update PRs need this review too.

`make quality-check` includes the shorter configuration check. Android CI runs
the full signed-bundle check plus the existing debug and emulator suites.
Expected rejected builds in negative checks are followed by an explicit overall
success result; a failed assertion still fails the Make target and CI job.

## Production prerequisites

Configure a GitHub environment named **android-release**, restrict it to main
and configure the owner's required reviewers/protection rules. Referencing an
environment in YAML does not create or prove these protections. This repository
does not provision credentials or Play Console settings.

Use a dedicated upload key kept outside the source checkout, with a backed-up
recovery procedure and limited operator access. Obtain its certificate SHA-256
independently and pin it in the environment. The upload certificate is distinct
from the app-signing certificate when Play App Signing is used: configure real
OAuth/Maps/Firebase Android restrictions for the certificates that sign the
installed app, including the Play app-signing certificate. Never put a server
service-account credential into Android client configuration.

Set the following environment variables/secrets in GitHub. The right column
is the input name accepted by local `make android-distribution`.

| GitHub environment setting | Local input |
| --- | --- |
| Variable `ANDROID_API_URL` | `ANVE_API_URL` |
| Variable `ANDROID_GOOGLE_WEB_CLIENT_ID` | `ANVE_GOOGLE_WEB_CLIENT_ID` |
| Secret `ANDROID_MAPS_API_KEY` | `ANVE_MAPS_API_KEY` |
| Variable `ANDROID_FIREBASE_APP_ID` | `ANVE_FIREBASE_APP_ID` |
| Variable `ANDROID_FIREBASE_PROJECT_ID` | `ANVE_FIREBASE_PROJECT_ID` |
| Secret `ANDROID_FIREBASE_API_KEY` | `ANVE_FIREBASE_API_KEY` |
| Variable `ANDROID_FIREBASE_SENDER_ID` | `ANVE_FIREBASE_SENDER_ID` |
| Secret `ANDROID_UPLOAD_KEYSTORE_BASE64` | `ANVE_KEYSTORE_FILE` (absolute path, not base64) |
| Secret `ANDROID_KEYSTORE_PASSWORD` | `ANVE_KEYSTORE_PASSWORD` |
| Variable `ANDROID_KEY_ALIAS` | `ANVE_KEY_ALIAS` |
| Secret `ANDROID_KEY_PASSWORD` | `ANVE_KEY_PASSWORD` |
| Variable `ANDROID_KEYSTORE_TYPE` (default PKCS12; JKS also supported) | `ANVE_KEYSTORE_TYPE` |
| Variable `ANDROID_SIGNING_CERT_SHA256` | `ANVE_SIGNING_CERT_SHA256` |
| Dispatch input `version_code` | `ANVE_VERSION_CODE` |
| Dispatch input `version_name` | `ANVE_VERSION_NAME` |

The API URL must be an explicit HTTPS DNS URL without credentials, query or
fragment. Local addresses and reserved example/test/invalid hosts are refused
for production. This is a syntax gate, not a DNS, TLS or service health check.
The web OAuth audience must match the Go deployment; Firebase app ID and sender
must match. Client keys are embedded in the app and require provider-side
restrictions even when supplied as GitHub secrets.

Choose a versionCode greater than the last published code, within Android's
1–2100000000 bound, and a three-part versionName. The tooling checks syntax;
it cannot inspect Play Console version history. The debug default version and
emulator URL are never substitutes for explicit production inputs.

## Build and review

1. Wait for successful **Go** and **Android** push workflows on the intended
   main commit. Run **Android distribution** manually from main with explicit
   version inputs. Its gate refuses missing, stale, incomplete or unsuccessful
   CI; a newer failed attempt cannot be hidden by an earlier pass.
2. The workflow materializes the upload keystore in runner temporary storage,
   exposes signing credentials only to the build step and runs without Gradle
   configuration/build caches or a reusable daemon. Setup actions receive no
   signing passwords. The key is removed by an `always()` cleanup step and is
   absent from the artifact upload paths.
3. Download the verified AAB and adjacent JSON record. Compare source commit,
   package/version, bundle SHA-256 and upload-certificate fingerprint with the
   intended release. The digest-pinned bundletool validates the format; Java
   JAR verification consumes every content entry and requires the pinned signer.
   The JSON is a build record, **not an attestation or a reproducibility claim**.
4. Archive the release record under the owner's retention policy; Actions
   artifacts expire after seven days. Complete provider, physical-device,
   privacy/Data Safety and permission-declaration evidence before any separate
   store-publication decision. This workflow never uploads to a store.

For an authorized local build, inject the right-column inputs through a secret
manager or private environment, then run `make android-distribution` from a
clean committed checkout. No passwords belong in shell arguments, tracked
files, `gradle.properties` or logs. The wrapper uses explicit environment inputs
and blocks fallback to global Gradle properties. Output defaults to
`apps/android/app/build/outputs/distribution/`. `make android-release` creates
a signed APK under the same production input guard; the verified AAB and its
record are produced by `make android-distribution`.

If legacy `src/main/res/values/config_local.xml` exists, move it to
`src/debug/res/values/config_local.xml` for debug use before release work;
production refuses that legacy file and obtains its OAuth value from explicit
release inputs. Do not delete or overwrite a contributor's local configuration.

## Recovery and evidence limits

Missing configuration, a wrong pin/alias/password, expired certificate or key
inside the checkout must be corrected at its source; do not disable the guard.
Production and validation release tasks require both caches disabled. Cached
debug configuration does not read signing credentials; a cached release graph
fails before the input guard reads them. A failed Gradle configuration can
still create a cache entry, so throwing after reading secrets is insufficient.
Prefer the Make wrappers. Neither synthetic CI nor AAB verification
establishes real Firebase delivery, Google/Maps configuration, OEM lifecycle,
hardware GPS/battery behavior, deployment security or Google Play acceptance.
