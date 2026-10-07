# Haunt: Glass design system

Haunt is a mock-location app for Android. Its UI is called "Glass": frosted, translucent pill-shaped surfaces float over a quiet, low-saturation map. It does not use Material. The real components are Compose Multiplatform code, so this project ships **tokens, fonts and CSS recipes only**. There are no React components. Build with plain HTML/JSX and the classes below.

## Setup
- Load `styles.css`. It imports the fonts, the tokens (`tokens/*.css`) and the recipes (`tokens/recipes.css`).
- Put `class="hn-app"` on the root. It sets Plus Jakarta Sans, the text colour and the map background.
- Theme: the system theme is followed by default. Force one with `data-theme="light"` or `data-theme="dark"` on `<html>` or on any container. Always check designs in both themes.
- Design for a phone, about 390 x 844, where the map fills the whole screen. Glass needs something behind it to blur, so put a map (or a map-like backdrop in `--hn-map`, `--hn-park`, `--hn-water` and `--hn-major-road`) under the chrome.

## Styling idiom
- Colours: always use `var(--hn-*)`. Never hard-code hex values. The ones you need most:
  - Text: `--hn-text` and `--hn-muted`.
  - Surfaces: `--hn-glass`, `--hn-glass-border`, `--hn-tile` (neutral fill inside glass), `--hn-hair` (row dividers) and `--hn-scrim`.
  - Accent: `--hn-accent` and `--hn-on-accent`, plus `--hn-selected` and `--hn-selected-content` for selected states.
  - Errors: `--hn-danger`, used only as the dot in an error notice.
- The accent is the only hue in the UI. Use it only for the current location, the active state and the single main action. Everything else stays neutral grey.
- Type: use the classes `.hn-display` (28 bold), `.hn-title` (20 bold), `.hn-title-small`, `.hn-body`, `.hn-body-strong`, `.hn-label` (13 semibold), `.hn-caption`, `.hn-section` and `.hn-small`. Coordinates and numbers use JetBrains Mono: `.hn-coords`, `.hn-mono-value` and `.hn-mono-large`.
- Shape: everything interactive is a pill (`--hn-radius-pill`). Cards use `--hn-radius-card` (30px), sheets `--hn-radius-sheet` (26px), list groups `--hn-radius-list` (24px) and tiles `--hn-radius-tile` (16px).
- Glass: add `.hn-glass` to any floating surface. It applies the blur, tint, border and shadow. Overlay screens (Search, Library, Settings) sit on `.hn-veil`, and glass inside a veil drops its own blur.
- Motion: springs only, using `transition: transform var(--hn-spring-bouncy-duration) var(--hn-spring-bouncy)`. Use `smooth` for layout changes, `snappy` for tints and fades, and `bouncy` for press feedback (`.hn-press`).

## Recipes (`tokens/recipes.css`)
- Buttons: `.hn-btn-primary` (accent, 48px), `.hn-btn-tonal`, `.hn-btn-glass` (combine with `.hn-glass`), `.hn-icon-btn` and `.hn-stop`.
- Selection: `.hn-chip[aria-pressed]`, `.hn-seg > button[aria-selected]`, `.hn-tabs > button[aria-selected]` and `.hn-switch[aria-checked]`.
- Map chrome (combine each with `.hn-glass`): `.hn-search` (52px), `.hn-status` (34px), `.hn-toolbar` (64px) holding `.hn-icon-btn.hn-tool[aria-pressed]`, `.hn-card` and `.hn-notice`.
- Content: `.hn-tiles > .hn-tile` (a label, then a mono value), `.hn-section`, `.hn-list.hn-glass > .hn-row` (`.hn-row-title`, `.hn-row-sub`), `.hn-lead-badge`, `.hn-dot` and `.hn-progress` (`style="--value:.4"`).

## Where the truth lives
- `tokens/colors.css` and `tokens/recipes.css` contain the exact values. Read them before styling anything new.
- `guidelines/glass.md` covers the layout rules for each screen. `guidelines/screens/*.png` are screenshots of every real screen in light and dark. Match them.

## Example
```html
<div class="hn-app" style="position:relative;height:844px;width:390px;overflow:hidden">
  <!-- map underneath -->
  <div class="hn-glass hn-search" style="position:absolute;top:16px;left:16px;right:16px">Search or paste coordinates</div>
  <div style="position:absolute;left:16px;right:16px;bottom:24px;display:flex;gap:10px;align-items:center;justify-content:center">
    <div class="hn-glass hn-toolbar">
      <button class="hn-icon-btn hn-tool" aria-pressed="true">⌖</button>
      <button class="hn-icon-btn hn-tool">⤳</button>
      <button class="hn-icon-btn hn-tool">◎</button>
    </div>
    <button class="hn-stop">■</button>
  </div>
</div>
```
