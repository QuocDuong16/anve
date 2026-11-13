# Journey automation and invitation entry points

Source review for ADR 0016 and PR #21, not a security certification or a
claim of real GPS/provider/device validation.

The reviewed privacy candidate changes only MainActivity's external VIEW /
DEFAULT / BROWSABLE entry and its `anve://invite` data-filter digest, plus the
source-manifest hash. Runtime SDK coordinates, permissions, other components,
backup and network XML policies are identical. The source additionally sets
singleTop so a warm link reaches onNewIntent. No new permission or SDK is
introduced. The reviewed candidate is copied to the baseline in the same change.

External URIs are untrusted. The parser rejects noncanonical authority/path,
credentials, ports, encodings, queries and fragments; MainActivity clears URI
data immediately and never copies invitation codes into saved state. A link
restored with saved Activity state is not re-offered. The inbox is process
memory only, binds to the authenticated owner, expires and invalidates older
callbacks. Configuration changes retain that memory; a new process does not.
The custom scheme does not establish verified domain ownership.

Authenticated preview returns only circle ID/name and invite expiry. The
dialog discloses the actual signed-in account and requires explicit joining;
existing membership blocks the action. One-use and membership checks remain
server-authoritative at redemption. Current-account and pending-link guards
run before admission and response publication; uncertain results use a
read-only, account-bound membership query and never auto-repeat redemption.
No invitation action calls the location-consent or GPS start APIs. Sharing
uses the native sharesheet only after an explicit owner action, with a manual
code fallback; choosing a share target is not evidence of delivery.

Journey options are per-create and default OFF. Dismissal drops unapplied
drafts. Server arrival estimates are labeled explicitly; the manual button
remains. Missing-update reminders do not infer an accident. Native screens
and notifications recheck the current stale episode; neither an old signal
nor a location observation starts acquisition. Existing exact-grant,
generation, retention and deletion rules continue to apply.

Validation includes unit tests for admission/account/late-response/unknown
outcomes, PostgreSQL authorization and episode tests, and CI emulator tests
for native controls, cold/warm URI handling, rotation, light/dark and actual
font scale 1.3. The final PR records the exact CI source and artifacts. Real
provider delivery, OEM/GPS/battery behavior, public HTTPS App Links and store
publication remain separate owner/release gates.
