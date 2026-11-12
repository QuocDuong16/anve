# AnVề — Android permission and Play declaration draft

Status: **Prepared, owner review and runtime/submission evidence pending.**
Policy/source review date: 2026-11-12. Read alongside the
[Data Safety draft](data-safety-draft.md) and
[Android release procedure](../operations/android-release.md).
No permissions or app screens were changed for M8-C.

## Manifest inventory and purpose

The reviewed merged release manifest has 14 permission entries. The source app
manifest alone has eight; SDKs contribute the rest. The machine-readable
[baseline](../../packages/privacy/android-release-baseline.json) also records
24 component declarations, including six exported entries.

| Permission | Existing purpose and evidence | Owner/runtime check |
| --- | --- | --- |
| `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` | User-started family location sharing; account/Circle functionality remains available without location permission. Accuracy is carried with samples; approximate-only is a supported state. | Demonstrate reduced precision, denial and revocation. Justify precision for the advertised core feature using real measurements; a compiler pass is insufficient. |
| `ACCESS_BACKGROUND_LOCATION` | Continued consented updates when the app is not visible; disclosure and foreground grant precede the background request. API 30+ uses the supported Settings path. | Prove the feature needs background access, show the disclosure and system permission flow in the required video, and test screen-off/OEM behavior. Do not claim automatic arrival/stale alerts as justification; those features remain Planned. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | Private `TrackingService`, type `location`, persistent notification/stop action and explicit start/resume; no app-defined tracking boot receiver. | Verify allowed starts, stop behavior, permission loss, notification visibility and the final OS/target combinations. |
| `POST_NOTIFICATIONS` | Tracking visibility plus chosen SOS/place/journey notifications. The app explains notification setup; FCM acceptance is not receipt. | Test denial, later revocation, process death and real Firebase delivery. OS notification permission is distinct from location consent and event audience. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Authenticated AnVề API traffic, provider SDKs, conditional polling and offline sync scheduling. | Observe actual HTTPS/provider destinations and offline recovery. Release network-security source refuses cleartext; deployment TLS remains separate. |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | Inherited credential SDK declarations; no first-party biometric capture API was identified in this review. | Check the sign-in/device credential path. A permission declaration does not imply that AnVề receives fingerprint or face templates. |
| `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` | Inherited WorkManager/SDK scheduling support. Merged SDK receivers can reschedule work; source tracking still requires explicit user admission. | Verify reboot/process recovery does not silently restart GPS, while consent/privacy outboxes remain recoverable. Do not describe the final package as containing no boot receiver. |
| `com.google.android.c2dm.permission.RECEIVE` | Firebase/Google messaging transport. | Verify provider restrictions, owner-bound registration and current authorization before display. |
| `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX-generated app signature permission and use. The package suffix differs for the isolated fixture. | Preserve signature protection; inspect the final production package rather than presenting the fixture identity as the app ID. |

The only exported first-party component is the launcher `MainActivity`.
Other exported entries are SDK components with declared permissions:
WorkManager `SystemJobService` (`BIND_JOB_SERVICE`), WorkManager diagnostics
and ProfileInstaller receivers (`DUMP`), Firebase's instance-ID receiver
(`com.google.android.c2dm.permission.SEND`) and Google auth revocation service
(`REVOCATION_NOTIFICATION`). Source declarations do not prove provider
certificate/permission ownership or all platform behavior. Do not treat
`exported=true` alone as a demonstrated vulnerability, or remove SDK components
without validating their purpose.

## Background location and foreground service answers

Draft core-feature description: AnVề lets a member deliberately share location
with individually selected family members and show when a sample was recorded.
The member starts tracking, can stop it and can revoke consent/audience; group
administration cannot silently enable another person's tracking. Background
updates support this advertised feature while the member uses other apps or
locks the screen. Notifications and freshness states show its limits.

This draft must be reconciled with the actual store listing and release video.
[Google's background-location guidance](https://support.google.com/googleplay/android-developer/answer/9799150)
calls for the declaration, a feature demonstration, prominent disclosure and
privacy policy. Record the exact disclosure text shown before the permission
request, its collection/background-sharing explanation and the deliberate
continuation action. Existing Compose screens are source evidence, not a
finished policy review or a device video.

For location FGS, [Android's service-type requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#location)
link the declared type and FGS permission to location runtime prerequisites
and restrictions on background starts. The app's normal start policy is
stricter than merely having a manifest permission. Test the final signed app;
the debug emulator suite does not exercise every minified/provider/device path.

## Announced policy change to recheck at release

The current [sensitive-permissions policy](https://support.google.com/googleplay/android-developer/answer/16558241?hl=en)
announces **2027-01-27** changes for apps targeting Android 17/API 37+:
a fine-location declaration must justify why coarse location or the location
button is insufficient; the location button applies to one-time/infrequent
requests. AnVề currently targets API 37. Prepare that justification and review
the final release date/Console form. This is an announced future requirement,
not a claim that it already took effect on 2026-11-12 or that AnVề has an
approved exception. No location-button UI has been implemented by this phase.

## Submission evidence packet still required

Keep owner-reviewed declaration text, final privacy/deletion URLs, exact AAB
digest/source/app-signing certificate, recorded permission video and a device
matrix for foreground/approximate/background/notification grants, denial,
revocation, screen-off, reboot and OEM restrictions. Include real Google/Maps/
FCM results and actual battery/GPS observations. Recheck the applicable Play
requirements at submission; neither these drafts nor green CI imply approval.
