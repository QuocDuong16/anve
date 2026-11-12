# Native plan decision and formal-tool boundary

The user selected B for avatar and B for email invitation creation, then
answered **tiếp nha** to the concrete native-plan exception request in chat.
The scoped plan uses real Compose text, vectors, fields, buttons and sheets;
Photo Picker/API images are runtime content, never the synthetic botanical
photo bundled into the app. Existing C confirmation and navigation remain.

Both comps are 928×1695; native renders use the repository CI phone viewport,
Material system type, OS insets, 48dp targets, light/dark paired roles and
font-scale reflow. The spec font fingerprint ranked a web face; incumbent
native system Material typography explicitly takes precedence per DESIGN.md
and the approved native plan. No new font, SDK or permission is added.

The avatar spec still diagnoses edge contact around the runtime photo and
requires static authored image plates. The native app intentionally has no
such plate: user-supplied photo pixels are fetched/selected at runtime. No
fake raster, codeDrawn-photo claim, forced gate, receipt or formal pass is
created. The active build gate remains open under the current chat exception;
actual renders and behavior must still be reviewed independently. Email spec
measurement is separately retained; analysis does not imply formal approval.

The implementation preserves B reading order and grouping: avatar title /
audience / preview / choose-remove / privacy / save; invite title / mode pair /
email group+binding / delivery helper / privacy notice / expiry / create.
Expiry opens a native menu for supported durations. Long text can reflow and
body-scroll while the primary footer remains reachable.
