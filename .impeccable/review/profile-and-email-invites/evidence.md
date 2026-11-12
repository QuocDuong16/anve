# Profile/email validation evidence

Implementation source: ca13b4fc717901e0735598cdad125206f831fa6b, PR #22,
stacked on unmerged PR #21. Follow-up render corrections require new CI.

## Automated validation

- Local `make quality-check android-test-build` passed: 330 JVM tests,
  failures/errors/skips zero. Resource-limited execution used one Gradle
  worker and in-process Kotlin; no checks or pins changed.
- `make server-test-integration` passed with PostgreSQL/PostGIS/race detection
  after migrations 0012/0013: owner/CAS/membership/deletion, email binding,
  creation fences, concurrency, private hides, quotas and current dispatch.
- Go run [43285537412](https://github.com/QuocDuong16/anve/actions/runs/43285537412):
  all three jobs passed; logs inspected.
- Android run [43285537402](https://github.com/QuocDuong16/anve/actions/runs/43285537402):
  all four jobs passed. Downloaded XML: API 35 53/53; API 26 3/3;
  failures/errors/skips zero. JVM/lint/debug build and signing validation passed.
- `make android-privacy-candidate` passed; hosted and local candidate bytes
  equal packages/privacy/android-release-baseline.json. No Android permission,
  SDK graph, component or source-policy change; baseline was not overwritten.

Checkout 4415d4fb2e4ceb7143f4273616a5b1bfcb29ef51 has parents PR21
4b02f0746237323dcc85f4749268378fa2bffcf1 and source ca13b4f. Checkout and source
share tree d935366738a8d4c1cfd4dbae989e70867bee2b1b.

## Signed fixture artifacts

AAB SHA256 cb279c522db37686cfc8f8120d4440f41f8a8e0d9ee1b4acc4f256c37c822466
matches downloaded bytes and the inspected validation record. Record confirms
signature, bundletool and privacy manifest verification with ephemeral key and
isolated com.anve.app.releasevalidation package. Expected negative production
input/pin/tamper checks in logs are assertions, not ignored unexpected failures.
Inventory SHA256 0c48902f606bc8077c995a3df094e4b5a85ab14b29dc5c981300152db721c07a
matches baseline. Signed smoke result confirms fresh launch, background resume,
process recreation, tracking not started, cleanup passed and no owned crash.
realProviderVerified=false and physicalDeviceVerified=false remain explicit.

## Native evidence and review

All eleven PNGs listed in native/manifest.json were opened. They are synthetic
Compose fixtures, not evidence of full production-host/provider execution.
Actual phone viewport 842x1870; light and dark/font130 sheets plus error,
offline, removal, unknown, link mode and recipient context.
Full-frame numerical comp diffs remain failures at that scope: avatar .4075
(missing), email .5579 (drift). They are not fidelity passes. Approved native
reflow, system type, runtime-photo and illustrative-backdrop exceptions are
recorded in plan-exception.md. Fresh native review must judge material issues
independently; formal PLATES/component review remains pending.

## Resolved earlier failures and limits

Earlier runs are not substitutes for final exact-source validation. Failures
included published Maven dependency resolution before tests, legacy EXIF's
5000-byte probe on small valid JPEG, an unspecified email IME action, and an
incorrect absent-orientation-tag assumption. Production decode/IME and fixtures
were corrected without skipping assertions. Initial blank error and inset-less
context captures were rejected and replaced before review. Local memory-limited
Gradle interruptions were retried to an actual full quality pass.

`adb devices -l` is empty. CI does not prove physical GPS/battery/OEM, real
Photo Picker/provider interoperability, OAuth/Maps/Firebase delivery, selected
share-app delivery, backup erasure, public HTTPS/App Links, Play production
signing/distribution or publication. Formal completion is a separate open gate.
