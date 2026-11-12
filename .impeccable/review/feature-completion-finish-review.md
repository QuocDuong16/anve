disposition: fix

Inputs not supplied: a scoped QUALITY BAR card, completed build-phase evidence, final cold-link CI result, and owner invitation-sharing captures. This review covers the rendered B options sheet, C recipient dialog and supplied journey status states only; owner sharing remains pending/unreviewed and is not classified as a UI defect.

## persistence

Pass: PRODUCT.md, incumbent DESIGN.md, the two surface contracts, approved B/C comps with approved/synthetic sidecars, build state/spec, and feature-completion diff exist. The user explicitly selected B/C and approved native controls through “Duyệt nha bạn code tiếp đi”. The recorded exception authorizes native implementation while the formal component portal remains pending; it is not a portal pass or runtime-test waiver.

Evidence: opened all ten 842×1870 phone emulator captures in `.impeccable/review/feature-completion-native/`: automation-options-light, automation-options-dark-font130, automation-stale-viewer-light, automation-arrival-estimate-light, invitation-preview-light, invitation-preview-dark-font130, invitation-loading-offline, invitation-unknown, invitation-unavailable and invitation-existing-circle. All match their foreground state, have plausible device dimensions and contain usable content. Plain dialog-fixture backdrops are outside the approved foreground scope. Sidecars record actual font scale 1.3 for the two dark captures and 1.0 for the others. The packet attributes these captures and 8/8 UI tests to commit 33d7020, Android run 43200857561; that run's separate cold-link test failed. The packet identifies a test correction in f0df797 with replacement CI pending. Captures are emulator evidence, not physical-device, GPS, OEM, battery or real-provider validation.

Fail: `.impeccable/build/state.json` still has `plates` open and hero/sections/motion/responsive/review pending. `plates.gate` says no plates owed, but this does not close subsequent phases. The approved chat exception must remain explicit. Record completed native review/render evidence and truthful native-equivalent or explicitly skipped phases; retain the formal portal as pending rather than fabricating a receipt or score.

Source sampling: JourneyOptionsSheet.kt, InvitationDialog.kt, InvitationHost.kt and InvitationSharing.kt. Serena Kotlin initialization failed, so these named files were read directly under the repository fallback rule. ViewModel/session/network internals, CI logs and instrumentation source were not independently audited in this design review.

## fidelity

Comp inventory: B has a handled bottom sheet, title/helper, separate outlined arrival/reminder groups, icon/title/switch rows, descriptive copy, four threshold chips and a full-width Apply action over creation context. C has a protected compact dialog, decision title, emphasized group name, one-use/expiry icon rows, amber privacy notice, separated account icon row and defer/filled-join actions. Its synthetic background navigation is not the app navigation specification.

The measured B diff reports overall 0.5516, contradicted. Opened the full comparison and the paired crops for every missing/contradicted region. Fixed-coordinate crops compare different semantic elements after the sheet grows upward; they do not establish that the visible controls disappeared. The surface brief explicitly prioritizes Material roles, real 48dp controls, wrapping, scrolling and OS font scale, and explicitly expands the arrival helper to disclose automatic ending/notification. These are the authority for the following adaptations. Do not alter the comp/spec to conceal the numeric result.

| Element / measured region | Assessment | Rendered evidence / authority |
| --- | --- | --- |
| app-bar | adaptation | Native insets/type; existing Back and refresh remain. |
| destination | adaptation | Existing native creation field is visible behind the scrim. |
| duration | adaptation | Diff says contradicted; paired crop lands on the preceding destination field because native creation rows are taller. Native sizing is expressly retained. |
| audience | adaptation | Diff says missing; crop lands on the sheet title because the sheet covers more of the background. No change to audience-selection topology is established by this crop. |
| options-entry | adaptation | Native creation summary remains the entry; covered background is not an omitted sheet control. |
| sheet-surface | adaptation | Diff says contradicted; native sheet begins higher but retains both outlined groups, threshold controls and footer. Explicit typography/touch-target adaptation. |
| drag-handle | adaptation | Diff says missing; handle is visible at the new sheet top. |
| sheet-title | adaptation | Diff says missing; full capture visibly leads with “Cho hành trình này”. Native headline role replaces generated bold lettering. |
| sheet-helper | match | Diff says missing; correct helper is directly below the relocated title. |
| arrival-frame | adaptation | Taller outlined native group accommodates truthful expanded copy. |
| arrival-icon | adaptation | Diff says contradicted; crop samples the relocated reminder bell, while the actual arrival row has a native location vector. |
| arrival-label | match | Correct label and independent switch are present. |
| arrival-body | adaptation | Diff says missing; text is visible above its old coordinate, and explains ending/notifying plus manual confirmation. Explicit surface-brief change. |
| arrival-switch | adaptation | Native Material Switch, visibly off; generated switch geometry is not an Android control spec. |
| stale-frame | adaptation | Diff says contradicted; group is present at a higher y position with larger native spacing. |
| stale-icon | adaptation | Diff says missing; native bell is visible at the relocated group heading. |
| stale-label | match | Correct reminder label is present. |
| stale-body | match | Diff says missing; “Gửi cho bạn và người xem đã chọn.” is visible above its old crop. |
| stale-switch | adaptation | Diff says missing; actual native Switch is visible and on in the comparison fixture, as permitted by the surface brief. Product defaults are not inferred from this fixture. |
| stale-threshold-label | match | Diff says missing; label is visible above the chips at its new coordinate. |
| stale-thresholds | adaptation | Diff says contradicted; all four values and selected 10 minutes are visible. At font scale 1.3, 30 minutes wraps to the next line without clipping. |
| apply | adaptation | Diff says missing; full-width filled native Apply remains visible above the system gesture inset. |
| C protected dialog / reading order | match | Decision title → group → one-use/expiry → privacy → account → actions retained. |
| C metadata/account landmarks | missing | Envelope, calendar and person vectors plus account separator from approved C are absent; InvitationDialog.kt:47–50 and :58 render plain Text. No recorded native constraint requires their removal. |
| C privacy notice / actions | match | Amber notice, explicit account, defer and filled join controls remain. Unknown uses read-only “Kiểm tra kết quả”; unavailable/already-member states offer defer without joining. |
| TYPE | adaptation | Native Material typography and sp scaling are explicit contract priorities. Legible Vietnamese copy wraps in the dark 1.3 captures. Group name retains headline hierarchy; exact generated weight/size is not a fixed-sp requirement. |
| MATERIAL | match | This Operate surface promises native Compose, vectors and real text, not photographic or physical plates. No raster asset is owed or shipped by these surfaces. |
| GROUND — B | adaptation | Native sample #FBF7F0 versus comp #F8F3EC: the native surface follows the incumbent sand role; no lavender field is introduced here. |
| GROUND — C | contradicted | Comp foreground sample (440,730) is #FAF5F0; native light sample (440,680) is #ECE6F0. The large lavender field replaces approved sand. InvitationDialog.kt:39 leaves the AlertDialog container default; DESIGN.md documents additional container roles inheriting default Material colors, but that does not authorize changing the approved C foreground ground. |

Contract promises: THESIS survives opt-in controls and explicit invitation confirmation; OWN-WORLD fails C ground; STORY retains manual-versus-estimated arrival and no automatic invitation membership/sharing; FIRST VIEWPORT preserves B/C order and privacy notice with the C landmark omissions above; FORM retains the approved sheet/dialog and enlarged-text reflow. The extension is a user-selected incumbent-world refinement, not a newly rolled visual world; the prior journey contract preserves its seed reference. No new concept-roll claim is made.

## ceiling

Incumbent Material 3 is the quality authority; no scoped QUALITY BAR card was supplied. Native switches, chips, protected dialog, semantic notice, full-width action, system typography and wrapping reach the restrained Operate ceiling except for the two C fidelity findings. Static captures cannot verify Back, Remove animations, focus order, sharing chooser behavior or network mutation outcomes. The reported 8 UI tests cover the supplied test run; cold-link correction and owner sharing require their own exact-commit evidence. Existing seven-tab navigation is outside this scoped extension and is not a redesign finding.

## material_fixes

1. GROUND / OWN-WORLD: set the scoped C dialog container to the incumbent semantic sand/ink surface pair, retaining native AlertDialog behavior and contrast; recapture light and dark/font-scale 1.3 states (InvitationDialog.kt:39).
2. Fidelity / C inventory: restore the native envelope/calendar metadata rows and separated person/account row from approved C, keeping text real, icons decorative to accessibility, and the body scrollable (InvitationDialog.kt:47–50, :58); recapture normal and enlarged/long-content states.
3. Persistence: finish the evidence record truthfully for completed native phases and explicitly skipped inapplicable phases; preserve pending formal portal status and failed/superseded versus passing CI distinctions. Do not assert whole-feature completion until replacement CI and owner-sharing capture review are complete.

## keep

Keep native real controls, Material typography/insets, explicit Apply/draft boundary, truthful GPS uncertainty, the invitation privacy notice and real account, uncertain-outcome readback, approved B/C topology, and existing navigation.
