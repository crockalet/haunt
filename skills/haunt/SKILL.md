---
name: haunt
description: Fake or mock the GPS location of an Android phone or emulator over adb with the Haunt app and its `haunt` CLI or `haunt mcp` server. Use when asked to spoof, fake, mock or teleport a device's location, simulate walking/cycling/driving between places, play a GPX/KML track or a route through waypoints, or test location-aware app behaviour (geofences, maps, delivery/ride tracking, region locks) on Android.
---

# Haunt: mock an Android device's location

Haunt is an Android app that feeds fake locations to the system through the official mock-location API.
The `haunt` CLI on the computer talks to it over adb (`adb forward` to the app's `localabstract:haunt`
socket), so there is no network server or token. One device at a time; JSON on request; meaningful exit codes.

## Prerequisites

1. **`haunt` CLI, Java 21+.** There is no stable release yet; the CLI is a JVM app (not a native binary).
   - Nightly build: download `haunt-cli.zip` from
     https://github.com/crockalet/haunt/releases/tag/nightly, unzip it and put `haunt/bin` on `PATH`.
   - From source: `./gradlew :cli:installDist` in a checkout, then use `cli/build/install/haunt/bin/haunt`.
   - It needs Java 21 (`java -version`, or set `JAVA_HOME`); Java 17 fails with `UnsupportedClassVersionError`.
   - It finds adb via `--adb PATH`, `$ANDROID_HOME`, `$ANDROID_SDK_ROOT`, `PATH`, then the usual SDK folders.
2. **Haunt app on the device** (`io.github.crockalet.haunt`). Nightly APK:
   `https://github.com/crockalet/haunt/releases/download/nightly/haunt-foss.apk`, then `adb install -r haunt-foss.apk`.
   Use `haunt-play.apk` instead if the app under test reads location through Google Play Services
   (fused provider); the `foss` build only feeds `LocationManager` providers.
3. **Haunt selected as the mock location app**: Developer options → Select mock location app → Haunt.
   The app's setup wizard walks through it. Over adb this usually works too:
   `adb shell appops set io.github.crockalet.haunt android:mock_location allow`
4. **Location permission** (Android 14+ needs it for Haunt's location service). Open Haunt once, or:
   `adb shell pm grant io.github.crockalet.haunt android.permission.ACCESS_FINE_LOCATION`
5. **Device visible to adb**: `adb devices` lists it as `device` (not `unauthorized` / `offline`).
6. "Allow ADB control" in Haunt → Settings must be on. It is on by default.

## Health check

```sh
haunt devices          # adb devices, with model names; exit 2 if none
haunt status           # connects, shows state, device, app version/flavour; warns if mock app not selected
```

`haunt status` starts Haunt's control service on the device if needed (no need to open the app).
If it prints `Idle (not mocking)` and no warning, you're ready.

## Global options

Put these **before** the subcommand (`haunt -s emulator-5554 status`, not `haunt status -s …`):

- `-s, --serial SERIAL` pick a device; defaults to `$ANDROID_SERIAL`, else the only ready device.
- `--json` machine-readable output on stdout (also accepted after the subcommand). Runtime errors become
  `{"error":{"message","hint","code","exit"}}`.
- `--adb PATH` adb executable.

Coordinates are WGS84 decimal degrees, latitude first. Negative numbers work unquoted: `haunt set -33.8568 151.2153`.

## Common tasks

**Hold a fixed location (teleport)**
```sh
haunt set 35.6586 139.7454                 # lat lng
haunt set "35.6586, 139.7454" --alt 40 --acc 5 --label "Tokyo Tower"
haunt set "Tokyo Tower"                    # place name, geocoded on the phone (needs its internet)
haunt set "8Q7XMP5W+C5"                    # plus code; also DMS, Google Maps URLs, geo: URIs, favourite names
```
Anything that isn't plain `lat lng` is sent as a query and resolved on the device: first as coordinates
in any supported format, then as an exact favourite name, then through the place-search service.
`set` replaces any running route.

**Travel to a place at a speed** (starts from the current fake position, so `set` one first)
```sh
haunt go "Shibuya Station" --speed walk --roads   # --roads follows roads via the phone's routing service
haunt go 35.6595 139.7005 --speed 30kmh
```
Speeds: `walk` (default, 5 km/h), `cycle`/`bike` (18 km/h), `drive`/`car` (50 km/h), or a number with
unit: `30kmh`, `30km/h`, `5mps`, `5m/s`, `20mph`. A bare number is km/h.

**Play a GPX or KML track**
```sh
haunt play track.gpx                     # recorded timestamps at 1x if the track has them, else walking speed
haunt play track.gpx --rate 2x           # recorded timing, twice as fast
haunt play ride.kml --speed 25kmh --loop # fixed speed, restart at the end (--pingpong: back and forth)
haunt play track.gpx --name "Morning run"
```
`--rate` and `--speed` are exclusive, as are `--loop` and `--pingpong`. GPX `trk`/`rte`/`wpt` and KML
`LineString`/`LinearRing`/`Point`/`gx:Track` are read; only the first track in a file plays.
`--roads` is accepted but ignored for GPX/KML (the result carries a warning).

**Route through waypoints.** The CLI has no waypoint command. Either use the MCP `play_route` tool
(`waypoints: [{lat,lng}, …]`, optional `follow_roads`), or write the points to a GPX file and `haunt play` it:
```xml
<gpx version="1.1" creator="agent"><rte>
  <rtept lat="35.6586" lon="139.7454"/><rtept lat="35.6620" lon="139.7310"/><rtept lat="35.6595" lon="139.7005"/>
</rte></gpx>
```

**Control a running route**
```sh
haunt pause
haunt resume
haunt speed 30kmh        # or walk, drive, 5mps …
haunt speed 2x           # multiplies the current speed (or the playback rate of a timed track)
haunt stop --hold        # stop moving, keep holding the current fake position
haunt stop               # stop mocking entirely; the device returns to real GPS
```
`pause` / `resume` fail with "Nothing to pause: not moving" and `speed` with "Nothing is moving" when no route runs.

**Read state and events**
```sh
haunt status --json                        # {device, app, state:{type: Idle|Holding|Moving|Joystick, …}, lastFix, mockAppSelected}
haunt watch                                # stream fix/state/progress/finished/error events until Ctrl-C
haunt watch --json --events fix,routeProgress
haunt go "Shibuya Station" && haunt watch --until-finished   # block until the route finishes (exit 0)
```
`--until-finished` only waits for the next `routeFinished` event; it doesn't check the current state, so
it never returns if nothing is moving. Check `status` first, or use the MCP `wait_for_arrival` tool.
`watch --json` prints one object per line: `{"event":"fix","fix":{…}}`, `{"event":"routeFinished","reason":"Arrived",…}`.

**Search and favourites**
```sh
haunt search ramen --near 35.66,139.70 --limit 5
haunt fav ls
haunt fav add Office 35.6812 139.7671 --folder work
haunt fav add Here       # at the current fake location
haunt fav rm Office
```

**Joystick:** not exposed over adb. The on-screen joystick is UI-only; `status` reports `Joystick` state
when a person is using it, and `pause` / `resume` / `speed` / `stop` work on it.

## Verify the spoof took effect

1. `haunt status` shows `Holding · <lat>, <lng>` (or `Moving …`) and no "not the selected mock location app" warning.
2. `haunt watch --events fix` prints a fix about once a second: Haunt re-emits even when holding still.
3. `adb shell dumpsys location` shows the fake coordinates as the last location of the gps / fused providers.
4. Check in the app under test. Since Android 12 every `Location` has `isMock() == true`; apps that reject mock
   locations will notice, and Haunt cannot hide that without root.

CLI and MCP calls take effect immediately; nothing needs to be pressed in the app.

## Errors and recovery

Exit codes: `0` ok, `1` error (bad arguments, routing/search/state errors), `2` not connected / no device,
`3` mock location app not selected, `4` ADB control disabled. Every error prints `error: …` and usually a
`hint: …` line on stderr; read the hint first.

| Message | Fix |
|---|---|
| `adb not found` | Install platform-tools, set `ANDROID_HOME`, or pass `--adb /path/to/adb`. |
| `No Android device connected` | Plug in / `adb connect <ip>:<port>`, then `haunt devices`. |
| `Device X is unauthorized` / `offline` | Accept the USB debugging prompt on the phone / replug or `adb kill-server`. |
| `Several devices connected: a, b` | `haunt -s <serial> …` or `export ANDROID_SERIAL=<serial>`. |
| `Device X not found` | Serial typo; the hint lists attached serials. |
| `Haunt is not installed on X` | Install the APK (prerequisite 2). |
| `Haunt is not responding on X` | Open Haunt on the phone once (allow its notification), then retry. If Haunt was swiped from Recents, the next call restarts it. |
| `Haunt is not the selected mock location app` (exit 3) | Prerequisite 3. |
| `ADB control is disabled in Haunt` (exit 4) | Haunt → Settings → turn on "Allow ADB control". |
| `Location permission not granted …` | Prerequisite 4 (`pm grant … ACCESS_FINE_LOCATION`). |
| `Haunt isn't mocking a location yet; set one first` | `haunt set …` before `haunt go`. |
| `No place found for "…"` / `Place search failed: …` | Use coordinates, or check the phone's internet / Settings → search service. |
| `warning: Routing failed (…); using straight lines` | Not fatal: the route runs as straight lines. With default settings, walking and cycling speeds route on footpaths / cycle routes and faster speeds on car roads. |
| `Update the haunt CLI and the Haunt app to matching versions` | Protocol mismatch; install matching nightly CLI and APK. |

## When to use `haunt mcp` instead

Use the MCP server when the agent will make many calls in one session, wants structured results, or needs
`wait_for_arrival` (blocks until a route finishes, with a timeout) or waypoint routes. It keeps one connection
open, reconnects if the app restarts, and connects lazily on the first tool call.

```sh
claude mcp add haunt -- haunt mcp
claude mcp add haunt -- haunt -s emulator-5554 mcp     # pin a device
```
Other MCP clients (`mcpServers` JSON; add `"env": {"JAVA_HOME": "…"}` if Java 21 isn't the default):
```json
{ "mcpServers": { "haunt": { "command": "haunt", "args": ["mcp"] } } }
```
Tools: `list_devices`, `select_device`, `get_status`, `set_location`, `move_to`, `play_route`, `pause`, `resume`,
`stop`, `set_speed`, `search_place`, `list_favorites`, `save_favorite`, `delete_favorite`, `wait_for_arrival`.
Arguments and the no-CLI broadcast fallback are in [reference.md](reference.md).
