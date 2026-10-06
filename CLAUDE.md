# Haunt — notes for agents

Free, open-source mock-location app (Android first) built with Kotlin Multiplatform, controllable by AI agents over ADB.
Start with `docs/STATUS.md` (current state, next steps, decision log). Design: `docs/DESIGN.md` (architecture, protocol, UI).

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

## Environment gotchas (cloud sessions)
- `.claude/settings.json` runs `scripts/setup-cloud-env.sh` on session start: installs Android SDK pieces into
  `~/android-sdk`, writes `local.properties`, and routes Maven Central through Google's mirror
  (`~/.gradle/init.d/mirror.init.gradle.kts`) because Maven Central returns HTTP 429 to the cloud proxy.
- No KVM in the cloud container, so no emulator; verify with unit tests and the JVM screenshot tests
  (`./gradlew :shared:ui:jvmTest` → `shared/ui/build/screenshots/`, copied to `docs/screenshots/`).
- Pushing `.github/workflows/*` needs the GitHub `workflow` permission (Claude GitHub App).
