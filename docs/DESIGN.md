# Haunt — Design

> Free, open-source mock-location app for Android, built with Kotlin Multiplatform,
> controllable by humans (a polished UI) and by AI agents (a CLI + MCP server over ADB).

| | |
|---|---|
| Repo / brand | **Haunt** |
| Play Store title | **Haunt: Fake GPS Location** (or "Haunt Fake GPS") |
| Launcher label | Haunt |
| CLI | `haunt` |
| Application ID | `io.github.crockalet.haunt` (placeholder — change before first release; it can't change after) |
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
| E1 | Material 3 Expressive, dynamic colour, dark mode (Compose Multiplatform) |
| E2 | MapLibre maps; free default tiles; **user-configurable tile / routing / geocoding endpoints** |
| R1 | Free, GPL-3.0; F-Droid + GitHub Releases + Play Store |

### Backlog (not v1)
A6 QS tile/widget · B5 realism (jitter, drift, acceleration) · B6 random wander · B7 teleport-safe travel ·
B8 timeline scrubbing · C6 scenario files · C7 deep links / Tasker · D4 route library · D5 backup ·
E3 floating overlay joystick · E4 real-vs-fake indicator · E5 tablet layouts · F1 profiles ·
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

**Stack:** Kotlin 2.x · Compose Multiplatform · coroutines/Flow · kotlinx.serialization · Ktor client ·
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
- GPX 1.1 / KML (`<LineString>`, `<Point>`, `gx:Track`) via a small XML pull parser (xmlutil).
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
- `HauntService`: foreground service (type `location`), owns the injector and collects `fixes`.
  Notification shows place name / coordinates, state, Pause/Stop actions.

---

## 6. Agent control over ADB

### 6.1 Socket channel (primary)
- The app listens on `LocalServerSocket("haunt")` (abstract namespace) inside `HauntService` /
  a lightweight `ControlService`.
- **Auth:** on accept, read `LocalSocket.peerCredentials.uid`; allow only `2000` (shell) or `0` (root).
  Other apps on the device are rejected. ADB itself authorises the computer. No tokens.
- If "Allow ADB control" is off → reject with an error explaining where to enable it.
- CLI side: `adb [-s serial] forward tcp:0 localabstract:haunt` (picks a free port) → TCP connect.
- If the app isn't running, the CLI starts it:
  `adb shell am start-foreground-service -n io.github.crockalet.haunt/.ControlService`.

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
adb shell am broadcast -n io.github.crockalet.haunt/.AdbCommandReceiver \
    -a haunt.SET --ed lat 35.6586 --ed lng 139.7454
```
Actions: `haunt.SET`, `haunt.STOP`, `haunt.PAUSE`, `haunt.RESUME`, `haunt.STATUS`.
The result goes back via `setResultData` (JSON), which `am broadcast` prints.
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
| Map style | OpenFreeMap (no key) | any MapLibre style URL or raster XYZ template; satellite = user-provided URL |
| Search | Photon (komoot) | Photon URL, Nominatim URL (1 req/s, no autocomplete) |
| Routing | OSRM public demo (fair use, attribution) | OSRM / Valhalla / GraphHopper URL + optional API key |

All requests send a proper `User-Agent` and attributions are shown on the map. If routing fails,
the app falls back to straight lines and tells the user.

---

## 8. UI (shared/ui)

**Home / Map** (single main screen)
- Full-bleed map, search bar on top (places + coordinate paste).
- Mode switcher: **Pin · Route · Joystick**.
- Bottom sheet: current fake location, state, speed chips, big **Haunt / Stop** button.
- Pin mode: long-press to drop. Route mode: tap to add waypoints, toggle "follow roads", loop mode.
  Joystick mode: thumbstick bottom-left, speed slider.
- Small "agent connected" indicator when an ADB client is attached.

**Library:** Favourites (folders) · History · Imported tracks.
**Settings:** Map & services endpoints · ADB control + activity log · Defaults (accuracy, altitude, update rate, units) · About / licences.
**Onboarding:** Welcome → enable developer options → select mock app (deep link to settings, live check) →
location + notification permissions → done.

Visual direction: Material 3 Expressive, dynamic colour with a ghostly violet fallback palette, dark-first map style.
Mockups to come in the next step.

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
