# Haunt — project status

_Last updated: 2026-10-06. Read this first in a new session, then `CLAUDE.md` and `docs/DESIGN.md`._

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
| CI | **Not enabled** | Workflow is at `docs/ci/ci.yml`. The original session's GitHub token lacked the `workflow` scope; the user has since installed the Claude GitHub App with workflow write access. In a new session: `git mv docs/ci/ci.yml .github/workflows/ci.yml`, commit, push. |

## Next steps (in order)
1. **Enable CI** (see above).
2. **On-device test pass** (real phone or an emulator with KVM). Checklist:
   - Install `androidApp-foss-debug.apk`; onboarding: Developer options deep link, mock-app selection detected via AppOps, location + notification permission flows, auto-advance.
   - Pin / route / joystick mocking end-to-end; confirm with `adb shell dumpsys location` and Google Maps.
   - Foreground services on API 34+: `HauntService` (type `location`), `ControlService` (type `specialUse`) started from the UI and from `adb shell am start-foreground-service`.
   - Broadcast fallback from a cold process (`adb shell am broadcast -n io.github.crockalet.haunt/.android.AdbCommandReceiver -a haunt.SET --ed lat … --ed lng …`).
   - CLI + MCP against the device: `haunt set`, `go --roads`, `play track.gpx --rate 2x`, `watch --until-finished`, and `claude mcp add haunt -- haunt mcp`.
   - Live Photon search/reverse, OSRM routing, MapLibre long-press, glass blur performance, adaptive icon, targetSdk 37 behaviour.
   - `play` flavour: fused mock mode actually reaching apps that use Play Services location.
3. **Gaps to close**: Settings → service endpoint editor and full activity-log screen (rows exist but do nothing); update rate applies only after process restart; custom muted MapLibre style matching the mockups (currently stock OpenFreeMap positron/dark); OSRM public demo only serves `driving` (walking routes follow car roads — consider a configurable profile/endpoint, e.g. Valhalla).
4. **Release prep (M5)**: decide the final application ID (placeholder `io.github.crockalet.haunt`, can't change after publishing), signing, F-Droid metadata, Play listing "Haunt: Fake GPS Location" (Play may ask about the `specialUse` FGS), CLI distribution (fat JAR now; consider GraalVM native image / Homebrew).
5. Backlog features: see `docs/DESIGN.md` §1 (QS tile, realism/jitter, scenario files, desktop companion for iOS, …).

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
| Fonts: Plus Jakarta Sans (UI), JetBrains Mono (data) | Bundled, OFL. |

## Design references
- Mockups canvas (user's claude.ai account): https://claude.ai/artifact/BjzUyRME1DM2KfYsU3EkTG — the "Final direction" row is canonical.
- Rendered app screens: `docs/screenshots/`.
