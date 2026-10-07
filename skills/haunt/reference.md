# Haunt reference

Details behind [SKILL.md](SKILL.md). Source of truth: `cli/src/main/kotlin/io/github/crockalet/haunt/cli/`
(CLI + `mcp/HauntMcpServer.kt`), `shared/protocol/.../Methods.kt`, `androidApp/.../control/BroadcastCommands.kt`.

## MCP tools (`haunt mcp`)

Errors come back as tool results with `isError: true` and the message plus hint in one sentence.
Numbers sent as strings are accepted.

| Tool | Arguments | Notes |
|---|---|---|
| `list_devices` | – | Serial, state, model; marks the selected one. |
| `select_device` | `serial` (required) | Needed only with several devices; connects right away. |
| `get_status` | – | State (`Idle` / `Holding` / `Moving` / `Joystick`), current or last fix, mock-app warning. |
| `set_location` | `lat`+`lng` or `query`; `altitude`, `accuracy` | Teleport and hold. Replaces any route. |
| `move_to` | `lat`+`lng` or `query`; `speed`, `follow_roads` | From the current fake position. Returns `routeId`, `distanceM`, `etaS` at once. |
| `play_route` | one of `waypoints` (`[{lat,lng}]`, ≥ 2), `gpx`, `kml`, `file` (local path); `speed` or `rate`; `follow_roads`; `loop` (`once` / `loop` / `pingpong`); `name` | `follow_roads` applies to waypoints only. |
| `pause` / `resume` | – | |
| `stop` | `hold` (bool) | `hold: true` keeps the current fake position. |
| `set_speed` | `speed` or `multiplier` | |
| `search_place` | `query` (required); `near_lat`, `near_lng`, `limit` (default 5) | Biased near the current fake location by default. |
| `list_favorites` | – | |
| `save_favorite` | `name` (required); `lat`, `lng`, `folder` | No lat/lng = current fake location. Same name overwrites. |
| `delete_favorite` | `name` (required) | |
| `wait_for_arrival` | `timeout_seconds` (default 120, max 3600), `route_id` | Returns `arrived: true` at once if nothing is moving; `arrived: false` with progress on timeout (call again). |

`speed` strings: `walk`, `cycle`, `drive`, `30kmh`, `5mps`, `20mph` (same parser as the CLI).

Typical flow: `get_status` → `set_location` → `move_to` / `play_route` → `wait_for_arrival` → `stop`.

## CLI exit codes

| Code | Meaning |
|---|---|
| 0 | OK |
| 1 | Error: bad arguments, unreadable file, `INVALID_PARAMS` / `INVALID_STATE` / `NOT_FOUND` / `UNAVAILABLE` from the app |
| 2 | Not connected: adb missing, no / unauthorized / ambiguous device, app not installed or not responding, connection lost, protocol mismatch |
| 3 | Haunt is not the selected mock location app |
| 4 | ADB control is disabled in Haunt |

`haunt devices --json` prints `[]` and exits 2 when nothing is attached.

## Wire protocol

Newline-delimited JSON-RPC 2.0 on the device's abstract socket `haunt`; first call must be `hello`.
Methods: `hello`, `status`, `location.set`, `location.stop`, `route.play`, `move.to`, `playback.pause`,
`playback.resume`, `playback.stop`, `playback.setSpeed`, `places.search`, `favorites.list`, `favorites.save`,
`favorites.delete`, `subscribe`. Events: `event.fix`, `event.state`, `event.routeProgress`,
`event.routeFinished` (`reason`: `Arrived` / `Stopped` / `Replaced`), `event.error`.
Only the shell (uid 2000) or root may connect, which is why everything goes through adb.

## Broadcast fallback (no CLI)

For a quick set/stop when the CLI isn't installed. Needs the same mock-app setup; results are printed by
`am broadcast` as JSON in the `data=` field (or `{"error":{code,message,data}}`).

```sh
R=io.github.crockalet.haunt/.android.AdbCommandReceiver
adb shell am broadcast -n $R -a haunt.SET --ed lat 35.6586 --ed lng 139.7454 [--ed alt 40] [--ef acc 5]
adb shell am broadcast -n $R -a haunt.SET --es query "'Tokyo Tower'"
adb shell am broadcast -n $R -a haunt.STATUS
adb shell am broadcast -n $R -a haunt.PAUSE      # also haunt.RESUME, haunt.STOP
```

There are no route, speed or search actions on this channel; use the CLI or MCP for those.
