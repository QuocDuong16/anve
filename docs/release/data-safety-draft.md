# AnVề — Data Safety working draft

Status: **Prepared for owner review, not submitted or approved.** Reviewed
2026-11-12 against the M8-C branch based on merged M8-B `67eaf03`.
This inventory covers AnVề flows and the resolved Android SDK graph. It is
not a published privacy policy or a replacement for observing the final
configured app, deployment, SDK behavior and provider retention settings.

The [review baseline](../../packages/privacy/android-release-baseline.json)
records the exact resolved coordinates, merged permission/component fields and
source policy digests. Its initial graph has 207 modules, including platforms
and support libraries; this count does not mean 207 independent collectors.
It is not an SBOM or proof that every resolved component survives shrinking.

## Form interpretation and working answers

Google's [Data Safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469)
includes off-device SDK transfers and pseudonymous data. Do not answer “no
collection” because the app has no advertising feature. Sharing exemptions
for service providers and deliberate user transfers depend on the actual
relationship and flow; owner review must decide them. Treat the rows below
as candidate classifications, not an automatically importable form.

Working answers: data is collected; account identity supports account management;
location and event data support app functionality. Location sharing and push
setup are optional feature choices, while account access is required for the
family features. The source enforces HTTPS for release configuration, but
“all data encrypted in transit” still requires final ingress/provider checks.
AnVề is not end-to-end encrypted: its server can process location data.
Account/history deletion exist in-app; the public deletion-request URL and
the published privacy-policy URL are **not established by this repository**.

## First-party data inventory

“Backend” below means the owner-operated AnVề API and database. Actual host,
region, subprocessors, backups and log retention must be supplied by the owner.

| Data and candidate Play category | Transfer, purpose and recipient | Control, retention and unresolved decisions |
| --- | --- | --- |
| Email, display name, Google subject and AnVề user ID — personal info/name/email/user IDs | Google ID token to backend for verification; backend stores the account profile. Circle members receive display name, user ID, role and join time; the member DTO does not contain email. Account management/functionality. | Sign-in is deliberate. Delete account removes the account and dependent operational rows. Audit records survive; do not promise immediate deletion of every identifier everywhere. |
| Avatar URL from the profile — personal info; assess photos classification if photo content is fetched/displayed | `UserDto.avatarUrl` is a URL, not a photo-upload API. | No evidence of an uploaded photo library. Owner must check the final avatar-rendering/provider path before final category selection. |
| Per-installation ID, device name/platform/app version, session use times and FCM token — device or other IDs, diagnostics/app activity where applicable | Installation registration and account/device security; token to backend and Google/Firebase for notification delivery. Access/refresh credentials are security material, never analytics identifiers or report fields. | App installation ID is not a hardware ID. Sessions are revocable; backend deletion does not prove deletion of SDK installation identifiers or provider logs. |
| Latitude/longitude, accuracy, time, optional altitude/speed/bearing, source and mock marker — precise and approximate location, associated activity | Explicitly started acquisition uploads to backend; authorized viewers receive permitted current/history samples. This is persisted data, not ephemeral processing. | Consent, OS permissions, membership and explicit audience grants apply. Local unsent queue: up to 5,000 points/7 days. Server location retention defaults to 30 days; owner setting and server ceiling bound actual retention. OFF purges unsent points; already delivered data cannot be recalled. |
| Saved Place coordinates/labels, monitoring choices and place events — location, other user content/app activity | Backend stores Places and explicit access/monitoring/recipient selections; event reads and dispatch recheck authorization. | Saved Places need explicit deletion; do not apply the point-history retention window to every Place definition. Events depend on retained evidence. |
| Journey destination label/coordinates, duration, lifecycle times, selected audience — location and other user content/app activity | Backend stores the manually started journey; selected recipients require the original current location grants. Journey is not proof of physical arrival. | Journey content has a maximum seven-day read window, shortened by owner/server limits. History deletion/retention changes purge owner journey content. Minimal retired-ID fences remain until account deletion. |
| SOS event IDs/times, recipient selections, acknowledgements and notification status — app activity/other user content | Backend and explicitly selected recipients; provider payload is opaque event ID plus kind and routing token. | SOS does not attach coordinates or start GPS. Active expiry is seven days; visibility limit is 30 days, with bounded asynchronous cleanup and retired-ID fences. Provider acceptance is distinct from receipt/acknowledgement. |
| Circle membership, roles, invite state and consent/audience changes — user IDs/app interactions | Backend for membership, access management and audit; recipient IDs identify deliberate sharing choices. | Joining a Circle does not enable location sharing. Grant revocation and leave/rejoin do not restore an old selection. Owner must classify user-directed transfers and any applicable sharing exemption. |
| Coordinate-free diagnostic report — diagnostics/app performance and app activity | Generated locally; preview then Android sharesheet to the user's chosen app. No automatic report upload endpoint. | Fixed allowlist excludes coordinates, account/device/event IDs, tokens and raw exceptions. Counts/relative ages can still describe activity. A sharesheet launch does not prove recipient delivery. |
| Operational/audit records — user IDs/app activity/diagnostics as applicable | Backend audit and existing business logs; new HTTP completion metrics use bounded route/method/status data without caller IDs or bodies. | Audit deliberately retains actor UUIDs after account deletion. Collector/log/database backup access, retention, restore deletion handling and justification are owner decisions still open. HTTP metrics are not a comprehensive log audit. |

Source evidence: [API models](../../apps/android/app/src/main/java/com/anve/app/data/api/ApiModels.kt),
[journey models](../../apps/android/app/src/main/java/com/anve/app/data/api/JourneyModels.kt),
[privacy requirements](../product/privacy-and-safety.md), ADRs 0006–0012 and
the [focused boundary review](../security/m8c-boundary-review.md).

## SDK review notes

| Resolved SDK family | Source invocation/configuration | Disclosure and validation work |
| --- | --- | --- |
| Credential Manager 1.6.0, Google ID 1.2.1 and transitive Google auth/FIDO/identity libraries | `GoogleCredentialSource` obtains a Google ID token; AnVề verifies it on its own backend. The dependency graph also contains auth API phone/blockstore modules; presence alone does not prove these APIs are invoked. | Verify actual OAuth consent/data handling and installed-app signing-certificate restrictions. Do not infer SMS/contact collection from a module name, or assume all Google SDKs follow the core-library disclosure. |
| Maps Compose 9.0.1 / Maps SDK 20.0.0 | Map rendering and camera controls use Google Maps. Authorized member markers and viewport selection can be sensitive even when the viewer is not sharing their own location. | [Maps guidance](https://developers.google.com/maps/documentation/android-sdk/play-data-disclosure) describes request/device metadata, SDK crash metrics, IP address, a pseudonymous Maps identifier and configuration-dependent camera interactions. Reconcile location-associated requests, purpose/category, provider terms and retention with the exact SDK/runtime; do not promise that Google only receives tile images. |
| Play services location 21.4.0 | Fused provider behind AnVề's consent/permission/start gates. | Review the location service's own provider disclosures and configured device behavior. The [core Play services page](https://developers.google.com/android/guides/play-data-disclosure) covers base/basement/tasks, not every Google SDK. Provider-specific behavior remains an external evidence item. |
| Firebase Messaging 26.0.1, Installations 20.0.0 and transport support | Manifest auto-init is false. An authenticated owner's notification-setup path initializes Firebase and schedules registration; the worker checks owner before and after token acquisition. | [Firebase guidance](https://firebase.google.com/docs/android/play-data-disclosure) includes FID and Firebase user-agent handling via Installations, messaging app-version data and optional delivery/analytics integrations. `firebase-measurement-connector` is resolved, but Firebase Analytics/Crashlytics/Performance SDKs are not in this baseline. This is not proof of zero SDK telemetry. Check final auto-init, BigQuery/export configuration, FID/token lifecycle and provider deletion separately. |
| WorkManager, Room, DataStore, Compose, Retrofit/OkHttp/Moshi and other support modules | Background work, local state/rendering and AnVề API calls; `ApiFactory` installs no HTTP body logger. | They are not blanket “no data” exemptions. App-defined use is reflected above. SDK boot/wake/job components do not authorize starting hidden location acquisition. Review every changed resolved coordinate. |

Provider guidance generally describes current SDKs; it is not evidence that
the pinned versions or a restricted real key were exercised. No real provider
traffic was captured in this phase.

## Owner decisions before submission

| Required decision/evidence | Current status |
| --- | --- |
| Public privacy-policy URL, operator identity/contact, hosting regions and subprocessors | Owner supplied/published evidence required; no placeholder URL is treated as live. |
| Public account-deletion request resource, identity verification and support handling | Required alongside in-app deletion under [Google's deletion guidance](https://support.google.com/googleplay/android-developer/answer/13327111). In-app Circle-owner refusal must have an understandable resolution; test the actual web request and completion path. |
| Final categories/purposes, required/optional answers and sharing exceptions | Review every first-party and SDK row; no form has been submitted. |
| Audit/fence/log/backup retention, legal justification, restore procedures and SDK identity deletion | Open. Existing cascade tests do not establish provider or backup erasure. |
| Target audience/age groups, Families applicability, distribution countries and safety claims | Owner decision; “family app” alone does not establish a child-directed Play classification or policy eligibility. |
| Real HTTPS deployment, OAuth/Maps/FCM, app-signing restrictions, permission video and physical devices | Pending; follow [permission drafts](permission-declarations.md), M7 validation and the release procedure. |

Retain this draft with the exact source/AAB record. Refresh it whenever SDKs,
permissions, data paths, provider configuration or the release window changes.
