# Haunt — project status

_Last updated: 2026-10-07. Read this first in a new session, then `CLAUDE.md` and `docs/DESIGN.md`._

## Where things stand
Milestones M0–M4 from `docs/DESIGN.md` §10 are **implemented, unit-tested and building**, but **nothing has run on a
real device or emulator yet** (the cloud machine that built it has no KVM). The next job is on-device testing.

| Area | State | Notes |
|---|---|---|
| `shared/core` | Done, ~65 tests | Geo maths, `DefaultHauntController` (pin, route, joystick, recorded-track playback, loop/ping-pong, `setPlaybackRate`, `setLoopMode`), coordinate parser (decimal, DMS, DDM, Maps URLs, geo:, plus codes), GPX/KML import + GPX export, built-in XML reader, ISO-8601. |
| `shared/protocol` | Done, 25 tests | JSON-RPC 2.0 over newline-delimited lines, `hello` handshake, `HauntApi`, `RpcServer`, `RpcClient`, events (`fix`, `state`, `routeProgress`, `routeFinished`, `error`), error codes + hints. |
| `cli` | Done, ~33 tests | `haunt devices/status/set/go/play/pause/resume/stop/speed/search/watch/fav/mcp`; ADB forward to `localabstract:haunt`, auto-starts `ControlService`; `haunt mcp` (MCP Kotlin SDK, 15 tools incl. `wait_for_arrival`). Verified with the installed binary (no device). |
| `shared/ui` | Done | Glass design system (Haze), all screens from the mockups, light/dark, screenshot tests → `docs/screenshots/`. Map = maplibre-compose (Android); drawn stand-in on JVM. |
| `androidApp` | Done, ~45 tests | `HauntRuntime` (settings, favourites, history, tracks, activity log), test-provider injection (+ fused mock in `play` flavour), `HauntService` (location FGS + notification), `ControlService` (socket server, peer-uid check), `AdbCommandReceiver` (broadcast fallback), Photon search/reverse, OSRM routing, onboarding, GPX/KML import, launcher icon. |
| CI | Enabled | `.github/workflows/ci.yml` (added 2026-10-06): on push to `main`, PRs and manual dispatch — jvm + androidApp unit tests, CLI dist, foss/play debug APKs (artifact `haunt-debug`; test reports on failure). Cloud sessions can't push changes to workflow files (no `workflow` scope); edit them from a normal checkout or the GitHub web UI. |
| Nightly | Enabled (`.github/workflows/nightly.yml`) | On each push to `main`: rolling `nightly` pre-release with `haunt-foss.apk`, `haunt-play.apk`, `haunt-cli.zip`: R8-minified, non-debuggable release APKs with profileinstaller (versionCode = run number, signed with the shared debug key `androidApp/debug.keystore` until M5). Install link: https://github.com/crockalet/haunt/releases/download/nightly/haunt-foss.apk |

## Next steps (in order)
1. **On-device test pass** (real phone or an emulator with KVM). Checklist:
   - Install `androidApp-foss-debug.apk`; onboarding: Developer options deep link, mock-app selection detected via AppOps, location + notification permission flows, auto-advance.
   - Pin / route / joystick mocking end-to-end; confirm with `adb shell dumpsys location` and Google Maps.
   - Foreground services on API 34+: `HauntService` (type `location`), `ControlService` (type `specialUse`) started from the UI and from `adb shell am start-foreground-service`.
   - Broadcast fallback from a cold process (`adb shell am broadcast -n io.github.crockalet.haunt/.android.AdbCommandReceiver -a haunt.SET --ed lat … --ed lng …`).
   - CLI + MCP against the device: `haunt set`, `go --roads`, `play track.gpx --rate 2x`, `watch --until-finished`, and `claude mcp add haunt -- haunt mcp`.
   - Live Photon search/reverse, OSRM routing, MapLibre long-press, glass blur performance, adaptive icon, targetSdk 37 behaviour.
   - `play` flavour: fused mock mode actually reaching apps that use Play Services location.
   - Floating joystick: permission flow, shows only in the background in joystick mode, steering works over
     another app (e.g. Google Maps), grip drag moves the window smoothly and the spot survives rotation, "open
     Haunt" button. In-app pad grip drag + clamping, all four pad sizes. Morph/spring animations feel right on device.
   - Map style: Haunt light / dark style loads (glyphs + tiles from OpenFreeMap), switches with the theme,
     custom style URL in Settings → Map style still works.
   - Locate button: camera moves to the real fix (no faking started), cached fix or hint while faking, map
     opens on the real position. Swiping Haunt from Recents stops faking, the notification and both services.
2. **Gaps to close**: Fixed 2026-10-07 after the first on-device test: (a) the map screen never saw Settings
   changes made after it opened (`MapStateHolder`'s derived UI state read the constructor's `defaults`, not the
   property), so the joystick card's "Float over other apps" switch, pad size and pad offset were stuck at their
   launch values; (b) Joystick mode showed no pad until Start, now it waits dimmed; (c) on 360 dp phones Start was
   squeezed beside the small Stop; (d) the overlay permission is a separate "Allow" row, never tied to the setting.
   Re-check on the phone: pad in Joystick mode with a pin running → Start → steer; float switch on/off in the card
   and Settings; Allow → grant → back (row disappears, overlay shows when Haunt goes to the background).
   Done 2026-10-07: logo 3a "Big Glass Ghost" (DESIGN §8 "Brand", `docs/brand/`): adaptive launcher icon with a
   themed monochrome layer, ghost-silhouette notification icon, app icon + wordmark on onboarding, no "Haunt" title
   on the map (search pill at the top). Re-check on the phone: launcher icon under the phone's mask, themed icons on,
   status-bar glyph.
   Earlier: FOSSGIS also wants the operator contact e-mail on the website and store listings; the app shows it under Data & licences when built with `HAUNT_CONTACT_EMAIL` set. Done 2026-10-07: map attribution and Settings → About → Data & licences (see DESIGN §8 "Attribution"; screenshots `15-data-licences-*`, `16-licence-text-*`) — map corner credits shown at startup, folding into (i) on the first gesture; OSM / OpenMapTiles / OpenFreeMap / OSRM / FOSSGIS / Photon credits with "Report a map error"; generated open-source licence list (AboutLibraries plugin, offline) plus bundled native notices. Needs an on-device check: credits pill placement above the locate button, links opening the browser, fold on pan / zoom. Done 2026-10-07: with the default routing settings, walk/cycle speeds route on FOSSGIS's `routed-foot` / `routed-bike` OSRM servers (car speeds stay on the OSRM demo); requests are spaced ≥ 1 s apart per their usage policies. Done 2026-10-06: Haunt's own map style (`HauntMapStyle`: light / dark from the theme palette over OpenFreeMap tiles, no icons; previews `docs/screenshots/14-map-style-*.png` rendered with MapLibre GL JS), service endpoint editor (URL + routing profile, validation, reset), full activity-log screen (error details, clear), update rate applies live.
3. **Release prep (M5)**: release signing, F-Droid metadata, Play listing "Haunt: Fake GPS Location" (Play may ask about the `specialUse` FGS), CLI distribution (fat JAR now; consider GraalVM native image / Homebrew).
4. Backlog features: see `docs/DESIGN.md` §1 (QS tile, realism/jitter, scenario files, desktop companion for iOS, …).

## Decision log
| Decision | Why |
|---|---|
| Name **Haunt**; CLI `haunt`; Play title "Haunt: Fake GPS Location" | "Ghostpin" was taken by several location-spoofing projects; "fake GPS" is what people search for. |
| GPL-3.0-or-later | User's choice; forks stay open. |
| Agent control **only over ADB** (CLI + `haunt mcp` on the computer), no network server on the phone | ADB already authenticates the computer; wireless debugging covers Wi-Fi; no tokens or open ports. Socket checks the peer uid (shell/root only). |
| Kotlin Multiplatform; Android first | Future desktop companion (also drives iOS devices/simulators, since iOS can't mock from an app). |
| `foss` / `play` flavours | Fused mock mode needs Play Services; F-Droid build must not include it. |
| Routing/search/tiles default to free public services, **all endpoints user-configurable** | No API keys or bills; public demos have limits. |
| UI: **Glass** (custom components on Compose Foundation + Haze), **not Material**; light + dark; one blue accent; quiet "Simple" map style | Chosen after several rounds of mockups (layouts A–D, Instrument/Glass/Brutal/HUD/Simple). |
| Map controls **collapsed by default** (floating toolbar + stop button, expandable card), no bottom nav | User wanted the map to dominate. |
| Application ID `io.github.crockalet.haunt` is final | Confirmed by the user 2026-10-06; it can't change after publishing. |
| Animations: Compose built-ins (shared bounds, `AnimatedContent`, springs), no library | Morphlet (user's reference) is React Native; Compose has the same primitives. |
| Glass (blurred) surfaces are only faded or moved, never scaled or rotated; Haze runs in `Performance` mode; overlay screens share one veil that only fades | A scaled blur is re-captured and re-blurred every frame. The card's expand animation (scale + fade on a blurred card, plus the status chip scaling out) crashed on a real device (2026-10-06). |
| Floating joystick via `SYSTEM_ALERT_WINDOW` overlay, opt-in | Joystick must work while another app is in front; only asked for when the user turns it on. |
| "Float over other apps" is an app setting; the overlay permission is only shown ("Allow") next to it | User request (2026-10-07): the switch must always toggle, whatever the permission. |
| Map style generated in code (`HauntMapStyle`) from the theme's map colours, not a hosted style | Real map matches the drawn one and the glass UI, follows light / dark, no style server to host; any OpenMapTiles TileJSON works. |
| Fonts: Plus Jakarta Sans (UI), JetBrains Mono (data) | Bundled, OFL. |
| Logo 3a "Big Glass Ghost"; launcher icon in the light palette | User's pick from the Claude Design logo rounds (2026-10-07). Launcher icons can't follow the app theme. |
| UI starts / stops faking only on explicit Start / Stop; mode switches, pins, stops, places only prepare; a running spoof survives mode switches until Start | User request before release (2026-10-07). The ADB API and CLI are unchanged: agents call them on purpose. |

## Design references
- Mockups canvas (user's claude.ai account): https://claude.ai/artifact/BjzUyRME1DM2KfYsU3EkTG — the "Final direction" row is canonical.
- Rendered app screens: `docs/screenshots/`.
- Brand assets (icon, wordmark, 512 px Play icon): `docs/brand/`.
