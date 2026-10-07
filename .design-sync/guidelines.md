# Glass layout guidelines

Source: `docs/DESIGN.md` section 8 and the Compose screens in `shared/ui`. Screenshots are in `screens/`, named `NN-<screen>-<light|dark>.png`.

## Principles
- The map is the app. It fills the screen edge to edge. Chrome floats over it on glass and never sits on an opaque bar. There is no bottom navigation.
- Use one calm blue accent, and only for the location, the active state and the main action.
- Keep chrome minimal. The UI is collapsed by default and the user expands what they need.
- Coordinates, speeds, distances and other data use JetBrains Mono. Everything else uses Plus Jakarta Sans.
- Keep 16px screen gutters. Space glass pieces 8–12px apart.

## Map screen
- Top: a glass search pill (`.hn-search`, full width) with Library and Settings glass icon buttons (48px). A glass status chip (`.hn-status`) sits below it with a dot and a short state such as "Haunting · 51.5072, -0.1276".
- Bottom (collapsed): a floating glass toolbar (`.hn-toolbar`, 64px) with Pin, Route and Joystick tool buttons, plus a pause button in Route mode and an expand arrow. A separate round accent Stop button (`.hn-stop`, 64px) sits beside it.
- Expanded: a glass details card (`.hn-card`, radius 30) grows out of the toolbar. It holds the place title (`.hn-title`), coordinates (`.hn-coords`) and a row of stat tiles (altitude, accuracy, rate). In Route mode it shows a progress bar, speed preset chips (Walk, Cycle, Drive), and "follow roads" and "loop" switches. In Joystick mode it shows speed chips.
- Locate button: a glass icon button at bottom right, above the toolbar.
- Joystick mode: a round glass pad with a knob over the map, at bottom left by default.
- Notices: a glass banner (`.hn-notice`) below the status chip, with a dot, a label, an optional muted hint and a dismiss ×.

## Overlay screens (Search, Library, Settings, Activity log)
- These are full-screen `.hn-veil` over the blurred map. Glass inside the veil is tint only.
- Header: a glass back button (48px) and a `.hn-display` title.
- Content: `.hn-section` headers above `.hn-list.hn-glass` groups of `.hn-row`s. Rows are 60px minimum with a leading `.hn-lead-badge` and trailing switches, values or chevrons.
- Library: `.hn-tabs` (Favourites, History, Tracks). Folders use `--hn-folder-blue`, `--hn-folder-green` and `--hn-folder-orange` dots in badges.
- Search: the search pill morphs into a field at the top. When pasted text parses as coordinates, the first result is a primary "Haunt here" row.

## Onboarding
- A step progress row (4px pill segments, accent fill for completed steps), a `.hn-headline`, a `.hn-paragraph` explanation, a primary button for the main action and a tonal or glass button for the secondary one.

## Motion
- Use springs only (`--hn-spring-*`). Cards grow from the toolbar with `smooth`. Selection tints use `snappy`. Press feedback uses `bouncy` and scales the label, never a blurred surface.
