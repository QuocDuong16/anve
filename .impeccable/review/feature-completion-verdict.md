# B/C native fix verdict

## verdict

1. **Resolved — GROUND / OWN-WORLD.** Reopened all six corrected C captures at the canonical paths. The light foreground is now incumbent sand (#FBF7F0 sampled in invitation-preview-light.png), and the dark/font-scale 1.3 foreground is the incumbent ink surface. The former lavender dialog field is gone. Loading/offline, unknown, unavailable and already-in-group states retain the corrected ground.
2. **Resolved — C metadata/account landmarks.** The light and dark/font-scale 1.3 previews visibly restore native envelope, calendar and person vectors plus the account divider. Text remains real and wrapping; the long group name, account, privacy notice and actions remain legible in the enlarged capture. The same landmarks appear when preview/account data are available in the unknown/already-member states; unavailable/loading do not invent missing metadata.
3. **Resolved for native evidence/documentation scope — persistence.** docs/implementation/feature-completion.md and build-state notes now distinguish the first failed/superseded test run from the final native evidence, document native phase equivalents and inapplicable web checks, and record the actual tool phases. The formal portal remains pending under the user's explicit chat-review exception. No closed phase, fabricated receipt or passing pixel score is claimed. Native evidence is now attached to source head 4b02f0746237323dcc85f4749268378fa2bffcf1: the manifest records run 43203487546, artifact 13937805348, 43 instrumentation tests with zero failures/errors/skips, and the PR-merge checkout 57b3a275005f4d5a445ad64cae90bad04959022a with the identical source tree. External CI/API verification was supplied by the parent; this review independently checked the local capture manifest, all twelve hashes and rendered files.

Regressions from the fix batch: none observed. Reopened the four unchanged B/journey captures for validity: both options sheets, stale viewer and estimated arrival remain usable, with the 1.3 threshold reflow intact. All twelve canonical captures are valid 842×1870 phone emulator images; hashes match the manifest. The two enlarged captures record actual font scale 1.3; owner-sharing captures record 1.0.

## remaining

Clear for the three scored fixes. This ship verdict covers the scored fixes, not the whole surface. The unchanged B diff still reports 0.5516 / contradicted; the prior documented native reflow adaptation stands, and no numeric pass is invented. Formal portal approval remains pending under the explicit user exception. This verdict does not claim physical-device, OEM, real GPS/battery/provider, third-party delivery, release-build/startup or whole-M8 validation. The two owner-sharing captures receive their first scoped review below.

disposition: ship

---

# Owner invitation-sharing row — first scoped review

disposition: ship

Inputs: incumbent PRODUCT.md/DESIGN.md and Circle controls, current invitation surface contract, invitation-share-light.png and invitation-share-dark.png, capture-manifest.json, CircleScreen.kt sharing/InviteRow source, InvitationSharing.kt and InvitationSharingInstrumentedTest.kt. No new row redesign or row-specific comp/QUALITY BAR card is supplied; this is the native share action extension in the existing invitation card, not a new visual world.

## persistence

Pass at this scope. Both supplied sharing captures exist, were opened, match their names, show the complete invitation row at phone size, and match manifest hashes. They are synthetic CI emulator fixtures from the same final run/source packet as the corrected B/C states. The formal build portal remains pending under the approved chat-review exception, recorded honestly in the shared evidence; this review does not close it.

The UI test source verifies tapping the row calls the share callback with the selected invitation, constructs an ACTION_CHOOSER wrapping text/plain ACTION_SEND, includes the anve invitation link and manual code fallback, omits location data, and removes sharing controls for a member. The supplied final run reports that test passing among 43 tests. CircleScreen.kt:106–114 starts the native chooser after an owner-session check and provides a manual-code recovery message on launch failure. No target-app delivery is inferred from chooser construction or these row screenshots.

## fidelity

| Element | Assessment | Evidence |
| --- | --- | --- |
| Invitation code / expiry | match | Visible above the actions in both themes; native title/supporting typography, with real fixture text. |
| Share action | match | “Chia sẻ link mời” is explicit, readable and inside the selected invitation card. It invokes the share callback independently of revoke. |
| Revoke / create controls | match | Existing destructive revoke and separate create-new action remain distinguishable; no accidental replacement of existing controls. |
| TYPE | match | Incumbent native Material typography; code/expiry/actions maintain hierarchy. FlowRow permits wrapping rather than a rigid fixed-width action strip. Only normal-font sharing captures were supplied; no enlarged-sharing screenshot claim is made. |
| MATERIAL | match | Real Compose Card/TextButton/FlowRow and text, no raster imitation or shipped mockup. |
| GROUND | match to incumbent | Existing Circle card ground retains the default Material container in light/dark, documented by DESIGN.md. The extension adds a text action within that existing card; it does not impose the approved C dialog's foreground composition or redesign the incumbent card. |

Contract: THESIS preserves deliberate sharing and explicit recipient confirmation; OWN-WORLD retains native Material controls; STORY connects an owner invitation to a native chooser with manual fallback; FIRST VIEWPORT keeps code, expiry and the share/revoke decision findable; FORM preserves the current Circle entry and owner-only control boundary. The row adds no automatic membership, GPS toggle or success-delivery claim.

## ceiling

Reached for the scoped native action extension. Familiar text actions, semantic destructive color, spacing and the existing card hierarchy suit Operate mode. Both themes remain legible and the two row actions remain distinct. No page-wide navigation, palette or fixture-system-bar redesign is part of this review. Existing default Material card ground is not recast as a newly introduced C-dialog defect.

## material_fixes

None at the reviewed row scope. Native chooser payload/callback/role evidence is distinct from successfully sending through a chosen external app; real delivery remains unverified.

## keep

Keep the explicit owner share action, visible code/expiry, manual-code fallback, separate revoke control, session guard, native chooser and absence of automatic joining or location-sharing claims.
