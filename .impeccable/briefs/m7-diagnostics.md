# M7-A tracking diagnostics

Mode: Operate. Native Android; existing AnVề identity and Material 3.

Purpose: help a family member explain why location updates are missing and
take the next useful action without reading technical logs. Entry: Vị trí →
Chẩn đoán vị trí; system Back returns to tracking. No new main navigation tab.

Approved comp: `.impeccable/mocks/m7-diagnostics-c.png`. The user selected C,
overview and details. Numbers in the comp are synthetic scenario data, not
runtime evidence. UI text and controls must remain native and accessible.

States: loading, unreadable subsystems, consent OFF/pending/conflict, permission
denial, disabled location, stopped/recovering service, no fix, offline queue,
battery limitations, account changes, unavailable push, export review/failure.

## Direction contract

THESIS: make acquisition and delivery distinguishable; explain missing updates
without making claims about the person's safety.

OWN-WORLD: inherit the existing teal/sand/amber Material color roles and native
type scale from ui/theme. No new palette, decorative images or custom controls.

STORY: read the current explanation; act on the actual blocker; scan acquisition,
sync and permissions; open technical details or deliberately share a safe report.

FIRST VIEWPORT: teal app bar with Back and refresh; status explanation; equal
Tổng quan/Chi tiết tabs; actionable notice; grouped acquisition, synchronization
and device/permission rows; export follows the content and remains scrollable.

FORM: user-selected option C from three compositional comps.
Direction authority: the user-selected comp C and incumbent AnVề Material 3.
The build tool normalized the user-pinned direction into an internal seed alias;
this is not claimed as a concept-seed roll. See `.impeccable/review/direction-provenance.md`.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

Verification: comp approval is design evidence only. Native captures, interaction
checks, dark theme and enlarged text must be obtained from emulator/device;
physical GPS, OEM and battery measurements are separate field evidence.
