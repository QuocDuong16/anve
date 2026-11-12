# Profile photos and addressed invitations

Follow-up to PR #21 on `codex/profile-and-email-invitations`. The owner asked
for a requirements-to-source audit and implementation of remaining features.
The audit confirmed that renaming exists, but profile pictures are initials
only; notification signals cover SOS/Places/Journeys, but not invitations.

The owner selected Android Photo Picker uploads and email-addressed FCM
invitations, then selected **B bottom sheets for both flows**. Comps and exact
prompts are in `.impeccable/mocks/profile-and-email-invites/`. Background menus
are illustrative; existing navigation is preserved. No mockup is an app asset.

## Scope and evidence

| Requirement | Source before this slice | Work in this slice |
| --- | --- | --- |
| Display name | PATCH /me and native RenameDialog implemented | Preserve |
| Profile image | Initials only; Google avatar URL not rendered | Photo Picker, preview, upload/remove, authorized roster images |
| Invitation notification | Manual link/code and explicit preview/join implemented | Addressed invitation, private inbox, opaque FCM signal, explicit existing confirmation |
| Invitation creator context | Existing preview has group identity/expiry | Show inviter name in received-invitation context |
| Automatic arrival/stale reminder | Implemented in PR #21; 43 emulator tests/CI green at 4b02f07 | Preserve; field thresholds remain unverified |
| Maps, history, Places, SOS, diagnostics, consent and sessions | Implemented; feature-specific automated evidence exists | Preserve, distinguish from provider/physical-device gates |
| Product journey/progress text | Contains obsolete splash-only/Planned statements and superseded privacy examples | Reconcile with source and ADRs without relabeling field evidence |
| Multiple Circles, numeric confidence, server-push transport | Proposed | Do not silently add to MVP |

The listed features now have source implementations on this branch. Local
`make quality-check` and `make android-test-build` passed at `617c17f`, with
328 JVM tests and zero failures/errors/skips. PostgreSQL race integration
passed after migrations 0012/0013. Hosted checks and native renders/review are
tracked by [PR #22](https://github.com/QuocDuong16/anve/pull/22), stacked on
unmerged PR #21. Later refinements require exact-head revalidation; no provider
or physical-device result is implied.

## Profile photo boundary

Use the system single-image picker without broad media/storage permission.
Only the selected image is decoded, cropped and reduced in memory. Review
before upload; dismiss discards an unsubmitted selection. No camera capture,
external avatar URL fetching, durable image queue or image disk cache.

The API validates bounded JPEG/PNG input and decodes/re-encodes to a small,
opaque JPEG, removing EXIF/metadata. PostgreSQL stores one current image per
account; replacing/removing deletes the previous bytes in the transaction,
and account deletion cascades. A separate metadata row retains the revision
and latest operation ID after removal, preventing stale CAS writes from
restoring a deleted image. Current owner/session checks precede mutation;
reads require self or current shared-Circle membership and the current image
ID. Responses are no-store. Image IDs are not authorization.

Client writes use an owner-bound operation UUID plus expected revision.
Uncertain responses reconcile through metadata reads, never automatic replay.
Late responses, account changes, and process recreation do not restore draft
image bytes or automatically upload. Image content/email never enters audit
or HTTP telemetry; audit records one metadata-only event per committed change.

## Addressed invitation boundary

A new idempotent creation path supports ordinary link/code mode or an email
binding. Email mode stores a normalized recipient-email digest, not a
discoverable account-lookup result. The create response never reports account
existence, device/token presence, push acceptance, dismissal or delivery.
Existing registered recipients can receive an opaque CIRCLE_INVITE + UUID
signal; notification payloads contain no email, code, names or coordinates.
The private inbox also works without push, and requires the authenticated
email to match the invitation binding. Future registration can see a matching
live invitation in the inbox; creation does not promise retroactive FCM.

Preview and redemption enforce the email binding, single use, expiry and
current group-owner authority. Joining remains a separate explicit action and
never enables location sharing. Closing confirmation defers; hiding a received
invitation is receiver-private and suppresses further notifications without
revealing a recipient outcome to the sender.

Creation UUID fences survive group removal until owner-account deletion, so
late requests cannot recreate retired work. A bounded per-owner creation
budget and receiver-digest notification budget limit abuse without making the
recipient budget an account-enumeration response. Durable outbox/device work
uses bounded retry and current authorization/session/device checks at dispatch.
Revocation, expiry, redemption, dismissal and account deletion suppress work.

## UI states and verification

Both B sheets use real native text, controls, scrolling, insets and Material
light/dark pairs. Keep the existing C join confirmation; add received-invite
context in the Circle flow without a new main tab. Include no-avatar,
processing/cancel, offline, validation, conflict, unknown result/readback,
deleted/revoked/expired invitation, already-in-group and account-switch states.

Use Make checks, PostgreSQL integration and GitHub Actions emulator captures
for actual controls, image normalization, permissions, authorization, CAS,
single-submit/read-only recovery and both themes/OS font scale 1.3. Fresh
Impeccable finish review and documenter are required. Formal tool gates are
recorded truthfully; old portal exceptions are not fabricated as receipts.
Real Photo Picker/provider interoperability, FCM delivery, OEM/GPS/battery,
public websites and Play distribution retain separate evidence boundaries.


## Current automated evidence

`make quality-check` and `make android-test-build` passed locally after the
contrast/image-retry refinement (`3245b57`); 328 JVM tests have zero failures,
errors or skips. PostgreSQL race integration passed for both new migrations,
avatar ownership/CAS/current membership and invitation binding/replay/private
hide/quotas/current dispatch. All three Go CI jobs passed at this head in
[run 43281016118](https://github.com/QuocDuong16/anve/actions/runs/43281016118).
Android CI is still pending: the first app job at this head failed resolving
many existing Maven dependencies before tests; artifacts and provider/device
behavior are not inferred from local success. Final CI/native results replace
this paragraph after inspection. Photo processing is additionally exercised
at minSdk 26 and API 35 in Actions.


Private photo/invitation HTTP calls share the existing authenticated session
refresher but disable transport recovery/redirects and mark mutation bodies
one-shot, including empty DELETE. This blocks automatic 503 Retry-After:0
follow-ups as well as connection replay. The existing explicit owner-bound
401 refresh is safe after refused admission. MockWebServer tests verify one
request for PUT/POST/DELETE under 503 and no forwarding on a 307 redirect.
