#!/usr/bin/env bash
# Idempotent dev-environment setup for Claude Code cloud sessions (and any fresh Linux box).
# Installs the Android SDK pieces Haunt needs, writes local.properties and, in cloud sessions,
# routes Maven Central through Google's mirror (Maven Central rate-limits the cloud proxy).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-16111833_latest.zip"
PACKAGES=("platforms;android-37.0" "build-tools;37.0.0" "platform-tools")

log() { echo "[setup] $*" >&2; }

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  log "Installing Android command-line tools into $SDK"
  mkdir -p "$SDK/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -sSfL "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP" -o "$tmp/tools.zip"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
missing=()
[ -d "$SDK/platforms/android-37.0" ] || missing+=("platforms;android-37.0")
[ -d "$SDK/build-tools/37.0.0" ] || missing+=("build-tools;37.0.0")
[ -x "$SDK/platform-tools/adb" ] || missing+=("platform-tools")
if [ ${#missing[@]} -gt 0 ]; then
  log "Installing SDK packages: ${missing[*]}"
  yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
  "$SDKMANAGER" --sdk_root="$SDK" "${missing[@]}" >/dev/null
fi

echo "sdk.dir=$SDK" > "$REPO_ROOT/local.properties"

if [ "${CLAUDE_CODE_REMOTE:-}" = "true" ]; then
  mkdir -p "$HOME/.gradle/init.d"
  cat > "$HOME/.gradle/init.d/mirror.init.gradle.kts" <<'GRADLE'
// Machine-local: prefer Google's Maven Central mirror (Maven Central rate-limits this environment).
val mirror = "https://maven-central.storage-download.googleapis.com/maven2/"
beforeSettings {
    pluginManagement.repositories { maven(mirror) }
    dependencyResolutionManagement.repositories { maven(mirror) }
}
GRADLE
fi

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export ANDROID_HOME=$SDK" >> "$CLAUDE_ENV_FILE"
fi

log "Done (ANDROID_HOME=$SDK)"
