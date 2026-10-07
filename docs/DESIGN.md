# Haunt — Design

> Free, open-source mock-location app for Android, built with Kotlin Multiplatform,
> controllable by humans (a polished UI) and by AI agents (a CLI + MCP server over ADB).

| | |
|---|---|
| Repo / brand | **Haunt** |
| Play Store title | **Haunt: Fake GPS Location** (or "Haunt Fake GPS") |
| Launcher label | Haunt |
| CLI | `haunt` |
| Application ID | `io.github.crockalet.haunt` |
| License | GPL-3.0-or-later |
| Min SDK | 26 (Android 8.0) · Target: latest stable |

---

## 1. Scope

### v1
| ID | Feature |
|---|---|
| A1 | Tap / long-press map to set a location; one big start/stop control |
| A2 | Feed GPS + network + fused providers together |
| A3 | Foreground service with notification (current location, pause / stop) |
| A4 | Setup wizard (developer options → "Select mock location app") |
| A5 | Altitude, accuracy, bearing, speed: manual or automatic |
| B1 | On-screen joystick with speed |
| B2 | Routes from waypoints: straight lines or road-following |
| B3 | Speed presets (walk / cycle / drive / custom), loop / ping-pong / reverse |
| B4 | GPX / KML import + export, timed or fixed-speed playback |
| C2 | ADB broadcast commands (no-install fallback) |
| C3 | "Allow ADB control" toggle + agent activity log |
| C5 | `haunt` CLI over ADB, including `haunt mcp` (stdio MCP server) |
| D1 | Place search (Photon by default) |
| D2 | Favourites (folders, colours) + history |
| D3 | Paste coordinates in any format (decimal, DMS, Google Maps URL, plus code) |
| E1 | "Glass" UI (custom, not Material), light + dark themes (Compose Multiplatform) |
| E2 | MapLibre maps; free default tiles; **user-configurable tile / routing / geocoding endpoints** |
| R1 | Free, GPL-3.0; F-Droid + GitHub Releases + Play Store |

### Backlog (not v1)
A6 QS tile/widget · B5 realism (jitter, drift, acceleration) · B6 random wander · B7 teleport-safe travel ·
B8 timeline scrubbing · C6 scenario files · C7 deep links / Tasker · D4 route library · D5 backup ·
E4 real-vs-fake indicator · E5 tablet layouts · F1 profiles ·
F2 locale/timezone hints · F3 fix logger · F4 desktop companion (also drives iOS/emulators) · G* root features.

Dropped: on-device MCP/REST servers (C1, C4). All agent traffic goes over ADB.

---

## 2. Platform facts that drive the design

1. **Mocking is via the official test-provider API.** No root. The user must pick Haunt under
   *Developer options → Select mock location app*. Without it, `addTestProvider` throws
   `SecurityException` — the wizard detects this (also via `AppOpsManager` `OPSTR_MOCK_LOCATION`).
2. **Mocks are detectable.** Since API 31 every `Location` exposes `isMock`. We can't hide this without root (backlog G1).
3. **Fused location.** Most apps use Play Services' `FusedLocationProviderClient`. Feeding
   `LocationManager` test providers usually propagates, but the reliable path is
   `FusedLocationProviderClient.setMockMode(true)` + `setMockLocation()`. That needs Play
   Services, which F-Droid won't accept → **two build flavours**:
   - `foss`: `LocationManager` only (GPS, NETWORK, FUSED on API 31+). Goes to F-Droid.
   - `play`: same + Play Services fused mock. Goes to Play / GitHub.
4. **Apps expect a stream, not a single fix.** We re-emit at ~1 Hz, even when stationary, with fresh
   `time` / `elapsedRealtimeNanos`.
5. **iOS can't mock from an app.** Future iOS support = desktop companion (F4) using shared code.

---

## 3. Architecture

```
┌──────────────────────── Android device ────────────────────────┐
│                                                                │
│  Compose UI (shared/ui) ──┐                                    │
│                           ▼                                    │
│                    HauntController  ◄──── AdbControlServer ◄───┼── localabstract:haunt
│                    (shared/core)          (JSON-RPC, uid check)│        ▲
│                           │          ◄─── AdbCommandReceiver ◄─┼── am broadcast
│                     MovementEngine                             │        │
│                     (tick → Fix)                               │        │
│                           ▼                                    │        │
│                    LocationInjector (androidApp)               │        │
│                    ├ LocationManager test providers            │        │
│                    └ Fused mock (play flavour)                 │        │
└────────────────────────────────────────────────────────────────┘        │
                                                                    adb forward
┌──────────────────────── Dev machine ───────────────────────────┐        │
│  haunt CLI (Kotlin/JVM) ── shared/protocol ────────────────────┼────────┘
│   └ `haunt mcp`  ◄── stdio ── Claude Code / any MCP agent      │
└────────────────────────────────────────────────────────────────┘
```

One `HauntController` owns the state. The UI, the ADB socket and broadcasts are all just clients
of it, so humans and agents always see the same state and can't fight over it.

### Modules

```
haunt/
├─ shared/
│  ├─ core/        KMP common: models, geo maths, MovementEngine, HauntController,
│  │               GPX/KML, coordinate parsing. No platform deps; heavily unit-tested.
│  ├─ protocol/    KMP common: JSON-RPC types (kotlinx.serialization). Shared by app + CLI.
│  ├─ data/        KMP: SQLDelight DB (favourites, history, routes, settings),
│  │               Ktor clients for geocoding + routing behind interfaces.
│  └─ ui/          Compose Multiplatform: screens, theme, map wrapper (maplibre-compose).
├─ androidApp/     Service, LocationInjector, AdbControlServer, broadcast receiver,
│                  onboarding glue, flavours foss/play.
├─ cli/            Kotlin/JVM `haunt` command (Clikt) + MCP server (MCP Kotlin SDK).
└─ desktopApp/     (later) Compose Desktop companion — reuses ui/data/core/protocol.
```

**Stack:** Kotlin 2.x · Compose Multiplatform (Foundation, custom components; no Material) · Haze · coroutines/Flow · kotlinx.serialization · Ktor client ·
SQLDelight · Koin · maplibre-compose · Clikt · MCP Kotlin SDK. Gradle version catalog, convention plugins.

---

## 4. Core (shared/core)

### Models
```kotlin
data class LatLng(val lat: Double, val lng: Double)
data class Fix(                       // what gets injected
    val position: LatLng, val altitude: Double?, val accuracy: Float,
    val bearing: Float?, val speed: Float?, val timeMillis: Long,
)
data class Route(val points: List<LatLng>, val timestamps: List<Long>? /* GPX timing */)
enum class LoopMode { Once, Loop, PingPong }
data class SpeedProfile(val metersPerSecond: Double)  // presets: walk 1.4, cycle 5, drive 13.9
```

### State machine
```
Idle ──start(pos)──► Holding(pos) ──play(route)──► Moving(route, progress)
  ▲                    │   ▲  joystick input              │  ▲
  └─────stop───────────┘   └──────── Joystick ◄───────────┘  │ pause/resume
                                                     Paused ─┘
```
`HauntController` exposes `state: StateFlow<HauntState>` and `fixes: SharedFlow<Fix>`.

### MovementEngine
- Ticks every 1 s (configurable) from a coroutine driven by an injected `Clock`, so tests are deterministic.
- Route playback = distance along a polyline (haversine segment lengths, cumulative index, binary search) →
  interpolated position + bearing of the current segment.
- GPX with timestamps: interpolate by time × playback rate.
- Joystick: integrate `(bearing, magnitude × speed)` into a destination point each tick.

### Parsing
- GPX 1.1 / KML (`<LineString>`, `<Point>`, `gx:Track`) via a small built-in XML reader (`core/Xml.kt`; no xmlutil dependency).
- Coordinates: `35.6586, 139.7454`, `35°39'31"N 139°44'43"E`, Google Maps URLs (`@lat,lng`, `?q=lat,lng`),
  geo: URIs, Open Location Code / plus codes.

---

## 5. Location injection (androidApp)

```kotlin
interface LocationInjector { fun start(); fun push(fix: Fix); fun stop() }
```
- `TestProviderInjector`: `addTestProvider` + `setTestProviderEnabled` for `GPS_PROVIDER`,
  `NETWORK_PROVIDER` and (API 31+) `FUSED_PROVIDER`; `setTestProviderLocation` each tick with full
  accuracy fields (vertical/speed/bearing accuracy on API 26+). `removeTestProvider` on stop.
- `FusedMockInjector` (play flavour only): `setMockMode(true)` / `setMockLocation`.
- `InjectionPipeline` (owned by the process-wide `HauntRuntime`): starts the injector when the controller
  leaves Idle, pushes every fix, removes the providers on Idle. `SecurityException` → mocking stops and an
  `event.error` (MOCK_APP_NOT_SELECTED) goes out. Injection never waits for a service to start.
- `HauntService`: foreground service (type `location`) that runs while not Idle, keeps the process alive and
  shows the notification (place name / coordinates, state, Pause/Resume/Stop). It stops itself on Idle.
  Android 14+ requires a granted location permission for a `location` FGS (also when started via adb), so
  mocking calls fail with UNAVAILABLE + a hint until it is granted (`pm grant … ACCESS_FINE_LOCATION` works).

---

## 6. Agent control over ADB

### 6.1 Socket channel (primary)
- The app listens on `LocalServerSocket("haunt")` (abstract namespace) inside the lightweight
  `ControlService` (`io.github.crockalet.haunt/.android.ControlService`, foreground type `specialUse`,
  exported but guarded by `android.permission.DUMP`). It shares the notification with `HauntService`, stays up
  while clients are connected or mocking is active, and stops itself after 3 idle minutes.
- **Auth:** on accept, read `LocalSocket.peerCredentials.uid`; allow only `2000` (shell) or `0` (root).
  Other apps on the device are rejected. ADB itself authorises the computer. No tokens.
- If "Allow ADB control" is off → the connection is accepted but every call (including `hello`) fails with
  ADB_CONTROL_DISABLED and a hint explaining where to enable it.
- CLI side: `adb [-s serial] forward tcp:0 localabstract:haunt` (picks a free port) → TCP connect.
- If the app isn't running, the CLI starts it:
  `adb shell am start-foreground-service -n io.github.crockalet.haunt/.android.ControlService`
  (`Protocol.CONTROL_SERVICE_COMPONENT`).

**Framing:** newline-delimited JSON-RPC 2.0. First message is `hello` (protocol version, app version).

| Method | Params | Result |
|---|---|---|
| `hello` | `{protocol}` | `{protocol, appVersion, flavour, mockAppSelected}` |
| `status` | – | `HauntState` + last `Fix` |
| `location.set` | `{lat, lng, altitude?, accuracy?}` or `{query}` (geocoded) | `Fix` |
| `location.stop` | – | – |
| `route.play` | `{waypoints[] \| gpx \| kml, speed, followRoads, loop}` | `{routeId, distanceM, etaS}` |
| `move.to` | `{lat, lng \| query, speed, followRoads}` | same as `route.play` |
| `playback.pause` / `.resume` / `.stop` | – | – |
| `playback.setSpeed` | `{metersPerSecond \| multiplier}` | – |
| `places.search` | `{query, near?}` | `Place[]` |
| `favorites.list` / `.save` / `.delete` | … | … |
| `subscribe` | `{events[]}` | – |

**Notifications (server → client):** `event.fix`, `event.state`, `event.routeProgress`,
`event.routeFinished`, `event.error`.

Every call coming from ADB is written to the in-app **Agent activity log** (C3).

### 6.2 Broadcast channel (fallback, no CLI needed)
```
adb shell am broadcast -n io.github.crockalet.haunt/.android.AdbCommandReceiver \
    -a haunt.SET --ed lat 35.6586 --ed lng 139.7454
```
Actions: `haunt.SET`, `haunt.STOP`, `haunt.PAUSE`, `haunt.RESUME`, `haunt.STATUS`.
Extras: `--ed lat/lng/alt`, `--ef acc`, `--es query` (see `Protocol.Broadcast`).
The result goes back via `setResultData` (JSON: the method's result, or `{"error":{code,message,data}}`),
which `am broadcast` prints. Calls honour "Allow ADB control" and appear in the activity log.
Receiver is exported but guarded with `android:permission="android.permission.DUMP"`
(held by shell, not grantable to third-party apps). *To verify on API 26–36.*

### 6.3 CLI
```
haunt devices
haunt status [--json]
haunt set 35.6586 139.7454 [--alt 40 --acc 5]
haunt set "Tokyo Tower"                       # geocoded on device
haunt go "Shibuya Station" --speed walk --roads
haunt play track.gpx [--rate 2x | --speed 30kmh] [--loop]
haunt pause | resume | stop
haunt watch [--json]                          # streams events
haunt fav ls | add <name> | rm <name>
haunt mcp                                     # stdio MCP server
```
Global flags: `-s <serial>`, `--json`. Exit codes are meaningful (0 ok, 2 not connected, 3 mock app not selected, …).

### 6.4 MCP
`haunt mcp` speaks MCP over stdio and relays to the socket. Claude Code config:
```json
{ "mcpServers": { "haunt": { "command": "haunt", "args": ["mcp"] } } }
```
Tools: `set_location`, `move_to`, `play_route`, `pause`, `resume`, `stop`, `set_speed`, `get_status`,
`search_place`, `list_favorites`, `save_favorite`, `list_devices`.
`move_to` / `play_route` return immediately with an ETA, and a `wait_for_arrival` tool blocks
(with a timeout) until `event.routeFinished`. That way agents don't need to poll.

### 6.5 CLI distribution
v1: fat JAR + launcher script (needs Java 17+), published on GitHub Releases, plus Homebrew tap / scoop later.
Revisit a GraalVM native image once the CLI stabilises (removes the Java requirement for agents).

---

## 7. Data & network (shared/data)

SQLDelight tables: `favorite(id, name, lat, lng, folder, color, created)`, `history(...)`,
`route(id, name, encoded_polyline, ...)`, `setting(key, value)`.

Pluggable endpoints (Settings → Map & services):

| Service | Default | Options |
|---|---|---|
| Map style | Haunt's own light / dark style (`HauntMapStyle`, generated from the theme's map colours) over OpenFreeMap tiles (no key) | any MapLibre style URL (used for both themes); satellite = user-provided URL |
| Search | Photon (komoot) | Photon URL, Nominatim URL (1 req/s, no autocomplete) |
| Routing | OSRM public demo for driving, FOSSGIS `routed-foot` / `routed-bike` for walk / cycle speeds (fair use, ≤ 1 req/s, attribution) | OSRM / Valhalla / GraphHopper URL + optional API key |

All requests send a proper `User-Agent`; credits are shown on the map and in Settings → About → Data & licences
(see §8 "Attribution"). If routing fails,
the app falls back to straight lines and tells the user.

---

## 8. UI (shared/ui)

Mockups: [Haunt Screens canvas](https://claude.ai/artifact/BjzUyRME1DM2KfYsU3EkTG). The "Final direction" row is the source of truth.

**Visual direction: "Glass" (not Material).** Built from our own small component set on Compose Foundation,
so it shares across platforms and is not tied to Material.
- Frosted, translucent capsules and cards (backdrop blur) over a quiet, low-saturation map.
  Blur on Android/iOS/desktop via [Haze](https://github.com/chrisbanes/haze), with a solid fallback when blur is unavailable.
- Light and dark themes (follow the system by default). Neutral greys; one calm blue accent
  (`#2F6BFF` light / `#6E9BFF` dark), used only for the location, active state and the main action.
- Type: Plus Jakarta Sans for UI, JetBrains Mono for coordinates and data.
- Map: a custom MapLibre style matching the mockups (soft neutral land, muted parks/water, white roads in light;
  charcoal equivalents in dark).

**Brand: logo 3a "Big Glass Ghost"** (picked 2026-10-07 from the Claude Design logo file; sources in `docs/brand/`).
All three pieces share one ghost path (`M30 48a20 20 0 0 1 40 0v28l-6.67-5-6.66 5-6.67-5-6.67 5-6.66-5L30 76z`)
and take their colours from the theme palette.
- **App icon** (`icon-3a.svg`, 100×100 units, rounded square rx 23): map background with a park top-left, water
  bottom-right and two major roads; a big frosted ghost (glass-fallback fill, divider outline, grey `muted` eyes);
  a dotted accent trail to an accent pin with a major-road centre. Uses: Android adaptive launcher icon (light palette
  only, as launchers can't follow the app theme: `res/drawable/ic_launcher_background.xml` = map art,
  `ic_launcher_foreground.xml` = ghost + trail + pin, with the 100-unit square at 64dp so the pin stays inside the
  66dp safe zone under any mask); Play store icon `icon-512.png` (full square, no rounding; Play masks it);
  in-app `HauntAppIcon` (Compose Canvas, follows light / dark).
- **Wordmark** (`wordmark-3a.svg`, text outlined from Plus Jakarta Sans SemiBold): glass pill holding the ghost
  outline glyph (muted stroke, grey eyes), "haunt" in lowercase (30sp, weight 600, −0.8 letter-spacing) and a 20dp
  accent dot. In-app `HauntWordmark`, under the app icon at the top of every onboarding step.
- **Monochrome glyph**: the solid ghost silhouette, no eyes (viewBox `27 25 46 54`). Uses: notification / status-bar
  icon `ic_stat_haunt.xml` and the Android 13 themed-icon layer `ic_launcher_monochrome.xml`.
- The map's location marker (`GhostMarker`: accent ghost with a white outline) is unchanged; the logo mocks show a
  pin-plus-ghost marker concept that has not been adopted.

**Map screen** (single main screen; map takes the full screen, no bottom nav)
- Top: glass search pill (places + coordinate paste) with Library and Settings buttons, right under the status bar
  (no app title), with a 52 dp glass status dot on its left: accent while faking, `danger` red while not (it lines
  up with the search screen's back button through the pill morph); a status chip below it.
- Bottom, **collapsed by default:** a floating toolbar with **Pin · Route · Joystick**
  (+ pause while a route plays) and an expand arrow, plus a separate round **Start / Stop** button.
- **Starting and stopping are always explicit.** Switching mode, long-pressing the map, picking a search
  result / favourite / history entry, or adding stops only *prepare* (a ring marks the
  picked spot); nothing is faked until **Start**, and only **Stop** ends it. Switching mode leaves the
  running spoof untouched (the status chip keeps saying what is haunted); Start in the new mode replaces it
  without a gap, and a small Stop sits beside Start meanwhile (sized so toolbar + Stop + Start fit 360 dp).
  The stick only steers once the joystick runs.
- Expanding opens a glass details card above the toolbar (place, coordinates, altitude/accuracy/rate;
  route progress, speed presets, follow roads, loop mode; joystick speed).
- Locate button (bottom-right): finds the device's **real** location and moves the camera there; it never
  starts or changes faking. Search results and stops added from search also move the camera to themselves.
  While Haunt is faking, all providers
  return the fake position, so it uses the last real fix seen (≤ 30 min) or asks to stop haunting first.
  Opening Haunt while idle centres the map on the real position.
- Joystick mode: thumbstick over the map, bottom-left by default; drag the grip on its corner to move it
  (position remembered). It is shown as soon as Joystick mode is selected: until Start it waits with a faded
  knob and ignores touches (screenshot `17-map-joystick-ready`). Pad size S / M / L / XL. Optional
  **floating joystick** (E3): "Float over other apps" is a plain app setting (Settings → Joystick and the
  joystick card) that always toggles and persists. The "Display over other apps" permission is separate:
  while the setting is on without it, both places show "Needs “Display over other apps” · Allow" (opens
  the system screen); the first time the setting is turned on without it, Haunt asks once. Permission is
  re-read on resume and whenever the overlay's inputs change, so granting it needs no restart; it never
  flips the setting. With both, the pad is drawn over other apps (`TYPE_APPLICATION_OVERLAY`, hosted by
  `HauntService`) while joystick mode runs and Haunt is in the background; draggable, with an "open Haunt" button.

**Search:** full-screen glass sheet over a blurred map; detects pasted coordinates ("Pick here"), nearby places, recent.
**Library:** Favourites (folders) · History · Tracks; import GPX / KML.
**Settings:** ADB control + activity log · "Connect an AI agent" (`claude mcp add haunt -- haunt mcp`) ·
map & service endpoints · defaults (theme, update rate, accuracy, units) · joystick (pad size, float over other apps) ·
about → **Data & licences**.

**Attribution** (OSMF attribution guideline, FOSSGIS and OpenMapTiles terms):
- Map corner: a solid-glass pill bottom-left, just above the toolbar, with the (i) on its left edge, reading
  "OpenFreeMap © OpenMapTiles © OpenStreetMap", each a link (openfreemap.org, openmaptiles.org,
  openstreetmap.org/copyright). It is spelled out at startup without any interaction and folds into an (i) button on
  the first map gesture (camera pan / zoom / rotate, or long-press); (i) brings it back (`AttributionState`,
  `MapAttribution.kt`). Custom style URLs show their sources' own attribution HTML (parsed into links), falling back
  to "© OpenStreetMap". It replaces maplibre-compose's default overlay (`MapOverlay.AttributionOnly`: a MapLibre logo
  bottom-left plus an expanding attribution box bottom-right, whose text is one horizontally scrolling line that can push
  the OSM credit out of view on narrow phones); the MapLibre logo is dropped (not required by its BSD licence; it crowded the corner)
  and MapLibre is credited under Data & licences. The JVM stand-in map shows the same pill.
- **Data & licences** (Settings → About): map data (© OpenStreetMap contributors / ODbL, "Report a map error" →
  openstreetmap.org/fixthemap, OpenMapTiles CC-BY 4.0, OpenFreeMap), routing (OSRM; FOSSGIS e.V. servers at
  routing.openstreetmap.de for walk / cycle, router.project-osrm.org for driving), search (Photon by komoot), Haunt
  (GPL-3.0-or-later, source link, operator contact e-mail), then open-source licences grouped by licence (tap for the
  library list and full text) and bundled native-code notices. The OFL font licences ship as files in the
  app's resources (all OFL asks for), so they aren't listed.
- Licence data is generated at build time, offline: the AboutLibraries Gradle plugin
  (`com.mikepenz.aboutlibraries.plugin.android`, `offlineMode`) writes `res/raw/aboutlibraries.json` per variant (foss /
  play differ); licence texts live in `androidApp/config/licenses/` and strict mode fails the build when a new licence
  (or a BSD / MIT library from another project, whose copyright line would be missing) appears. A `collect<Variant>Notices`
  task copies MapLibre Native's `META-INF/licenses/` (MapLibre Native, its C/C++ and Rust components; the NDK's libc++
  notice is skipped, as its LLVM exception waives attribution) and Play services' `third_party_licenses` from the AARs into `assets/notices/` — AGP doesn't package them.

**Lifecycle:** backgrounding Haunt keeps faking (and the ADB socket) running; swiping it away from Recents
stops faking, the floating joystick and both services (`onTaskRemoved`). The CLI restarts `ControlService` on its next call.

**Motion:** everything uses three spring presets (`HauntMotion.smooth / snappy / bouncy`, after Morphlet's).
The details card grows out of the toolbar and springs to fit its content; switching mode morphs the card;
screens scale-and-fade; the search pill morphs into the search field (`Modifier.morph(key)`, shared bounds);
buttons and chips have springy press feedback; the joystick knob springs back on release.
**Onboarding:** enable developer options → select mock app (deep link to settings, live check) →
location + notification permissions → done. Each step opens with the app icon above the glass wordmark pill,
then the step's title and text.

---

## 9. Testing

- `shared/core`: unit tests for geo maths, interpolation, state machine, parsers (commonTest, fake clock).
- `shared/protocol`: round-trip serialisation tests; CLI ↔ server contract tests on JVM with an in-memory transport.
- androidApp: instrumented test on an emulator that sets a location via the socket and reads it back via `LocationManager`.
- CI: GitHub Actions — build both flavours, run unit tests, build CLI JAR.

---

## 10. Milestones

| M | Deliverable |
|---|---|
| M0 | Gradle KMP skeleton, CI, theme, empty screens |
| M1 | Static mocking end-to-end: onboarding, map pin, service, injector (A1–A5) |
| M2 | ADB socket + CLI + MCP (C2, C3, C5) — agents can set/stop location |
| M3 | Movement: routes, joystick, speeds, GPX/KML (B1–B4) + routing endpoints |
| M4 | Search, favourites, history, coordinate paste (D1–D3), settings for endpoints |
| M5 | Polish, F-Droid metadata, Play listing, release |

## 11. Open questions
1. Application ID (`io.github.crockalet.haunt` OK, or do you own a domain?).
2. Should M2 (agent control) come before M3 (movement)? Proposed yes. It's the reason the app exists, and it's small once M1 works.
