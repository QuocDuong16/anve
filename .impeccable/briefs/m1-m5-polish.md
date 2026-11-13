# M1–M5 UI polish review

Mode: Operate. Native Android Compose. Existing AnVề Material 3 identity.

Scope: M0 splash and M1 sign-in, shared navigation, M2 Circle, M3 consent and
tracking, M4 Live Map, M5 History and Places. SOS is included only where the
shared navigation choice affects its discoverability; no M6 feature redesign.

User request: address every finding from the Impeccable M1–M5 critique. On
2026-11-13 the user approved navigation B, the roof/path logo direction, the
History/Places comp direction, and the generic actionable sign-in error copy.
Repository build path is `comp`; implement the approved direction with native
Compose controls. Existing unrelated working-tree edits and comps must be
preserved.

Critique source: `.impeccable/critique/2026-11-13T08-20-07Z__apps-android-app-src-main-java-com-anve-app-ui.md`.

## Review comps (synthetic data; not app assets)

- `.impeccable/mocks/anve-m1-m5-auth-contrast-comp-v1.png`: splash and sign-in
  in light/dark themes, translated recoverable auth error, optional roof/path
  logo concept.
- `.impeccable/mocks/anve-m1-m5-navigation-options-v1.png`: three navigation
  alternatives. SOS placement and access to less-frequent destinations need
  explicit user selection.
- `.impeccable/mocks/anve-m1-m5-history-places-light-dark-comp-v1.png`:
  History and selected Place detail in light/dark themes. Names, dates, map
  details and events are synthetic.

The comps are references, never production UI or data. The auth copy must stay
truthful across credential, network and provider failures; do not promise a
specific cause. The generated help action in the superseded first comp is not
in scope and must not be implemented.

## Task flow and states to preserve

- Splash: startup branding and version; contrast must remain readable across
  the full background in light and dark themes.
- Sign-in: ready, provider unavailable, loading, user cancellation, credential
  unavailable, network/provider error, retry; errors use concise Vietnamese
  recovery copy and keep technical causes out of the visible message.
- Shared navigation: selected destination, system Back, incoming invitation
  and push routing; SOS remains discoverable and must not be made less safe by
  reducing tab count.
- Circle: loading, empty/create/join, active roster, invite pending/error,
  offline/retry, account changes. Sharing consent remains explicit and OFF by
  default.
- Tracking: consent OFF/pending/conflict, Android permission denied,
  background permission, location disabled, stopped/recovering, no fix,
  offline queue, last update/error, diagnostics entry. Consent and OS permission
  remain separate actions.
- Live Map: loading, unavailable/no grant, stale or disconnected data, map key
  missing, selected member, permission changes and offline recovery. Never
  imply that lack of updates proves danger.
- History: loading, empty, offline/error/retry, member/date/time filters, map
  unavailable, selected point, delayed/backfilled data, delete confirmation,
  retention change and large text.
- Places: loading, empty, offline/error/retry, create/edit/delete, pending or
  uncertain save, member access, local tracking consent/permissions, no
  eligible recipients, delayed/missing events and large text.
- All changed screens: light/dark, font scale 1.3, narrow phone, accessibility
  labels and minimum 48dp touch targets.

## User-approved choices

- **Navigation B:** four primary bottom destinations (Nhóm, Bản đồ, Lịch sử,
  Địa điểm), with SOS as a persistent Floating Action Button. A “Cài đặt chia
  sẻ” card on Nhóm provides routes to Vị trí and Tài khoản. Tapping SOS opens
  the existing SOS screen; it does not send an SOS automatically.
- **Logo:** use the approved roofline connected to a path/doorway direction;
  produce theme-safe artwork with transparent background and use it as a small
  brand mark alongside the existing AnVề wordmark/tagline.
- **History/Places:** use the approved compact History filters/map/timeline and
  the Places list/detail grouping for visibility, self-controlled tracking,
  and events. Preserve all existing features and product copy/invariants.
- **Sign-in error:** use the approved generic retry copy for unclassified
  provider failures; use specific guidance only where the error category is
  reliably known. Never expose raw SDK messages to users.

After the recorded comp approval, implement all critique findings across affected screens,
including semantic light/dark role contrast, 48dp touch target, dense-page
hierarchy, and navigation. Use native Compose controls; no mockup raster ships.
Validate source with repository Make targets, then verify rendered light/dark,
large-text and interactions in GitHub Actions emulator. Report emulator and
physical-device evidence separately.
