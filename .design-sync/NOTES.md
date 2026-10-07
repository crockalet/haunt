# design-sync notes

- Haunt's UI is Compose Multiplatform, not React, so the standard converter (package-build.mjs) can't run.
  The user chose a tokens-only sync (2026-10-07). Nothing here is a reimplementation of components to build with.
- Build: `node .design-sync/build.mjs` writes `ds-bundle/`. Colours, type, radii and springs are parsed from
  `shared/ui/.../theme/*.kt`, so theme changes flow through by rebuilding. The parser throws on any colour form it doesn't know.
- Hand-maintained (these drift silently): `recipes.css` mirrors component sizes and colours in `shared/ui/.../components`,
  and `cards/*.html` and `guidelines.md` are authored. When components change, update recipes.css first.
- Type classes (`.hn-<style>`) are generated from HauntTypography names. Recipe class names must not collide
  with them (`.hn-badge` is a type style, so the leading badge recipe is `.hn-lead-badge`).
- No `_ds_bundle.js` and no `_ds_sync.json`: there is no component bundle to anchor, so every sync re-uploads everything (about 40 files).
- Verify cards by screenshotting with the cached Playwright Chromium (`~/.cache/ms-playwright/chromium-*/chrome-linux64/chrome --headless --screenshot`);
  no npm playwright module is installed in this repo.
