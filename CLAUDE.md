# Haunt — notes for agents

Free, open-source mock-location app (Android first) built with Kotlin Multiplatform, controllable by AI agents over ADB.
Design: `docs/DESIGN.md` (source of truth for architecture, protocol and UI).

## Layout
- `shared/core` — KMP (android + jvm). Models, geo maths, movement engine, `HauntController`. No platform deps.
- `shared/protocol` — KMP. JSON-RPC types + dispatcher shared by the Android control server and the CLI.
- `shared/ui` — Compose Multiplatform (android + jvm). Glass UI: theme, components, screens. **No Material.**
- `androidApp` — Android app: service, location injection, ADB control server. Flavours `foss` / `play`.
- `cli` — Kotlin/JVM `haunt` command + `haunt mcp` (stdio MCP server).

Package root: `io.github.crockalet.haunt`.

## Build & test
- JDK 21, Gradle wrapper (9.8), AGP 9.4 (KMP modules use `com.android.kotlin.multiplatform.library` with `kotlin { android { } }`).
- Android SDK: set `sdk.dir` in `local.properties` (or `ANDROID_HOME`). compileSdk 37.
- Common commands:
  - `./gradlew :shared:core:jvmTest :shared:protocol:jvmTest :cli:test`
  - `./gradlew :androidApp:assembleFossDebug`
  - `./gradlew :cli:installDist` → `cli/build/install/haunt/bin/haunt`
- Prefer `jvmTest` for shared modules (fast, no emulator).

## Conventions
- Kotlin official style, 4-space indent, no wildcard imports.
- Keep platform code out of `shared/*` commonMain; use `expect`/`actual` only when unavoidable.
- Tests for anything with logic (geo maths, engine, parsers, protocol).
