# ADR 0017 — Profile photos and private addressed invitations

Status: Accepted product direction; implementation/verification tracked in
[the slice record](../implementation/profile-and-email-invitations.md).

The owner selected Photo Picker uploads and email-addressed FCM invitations,
with native bottom-sheet compositions B for both. These complete explicit
profile/avatar and invitation-notification requirements without introducing a
new visual world, location permission, Circle model or transport for GPS.

Profile photos are a bounded authenticated resource stored in PostgreSQL,
normalized server-side to remove metadata. Only self/current Circle members
may read the current image. A per-account revision and operation UUID fence
unknown/stale writes, including deletion. Pending images are process-memory
only; no broad media permission, disk image cache, public image URL or
automatic upload/retry is introduced.

Addressed invites bind a single-use code to a normalized email digest.
Creation never exposes whether the email exists, has a device or accepted a
push. An opaque invitation signal points to a private authenticated inbox;
preview and redemption revalidate the binding. Sender-visible records do not
expose receiver-private dismissal or provider outcomes. Quotas and durable
retry/authorization checks bound notification abuse. Opening, previewing or
receiving a notification never joins a Circle or starts sharing.

Existing ordinary codes remain compatible. New native creation uses UUID
fences and owner-bound readback so uncertain mutations do not create extra
invitations. Separate tests cover image validation/metadata stripping, privacy
and session/membership changes, CAS/replay, binding/single use and dispatch
suppression. CI fixtures are not real-provider or physical-device validation.
