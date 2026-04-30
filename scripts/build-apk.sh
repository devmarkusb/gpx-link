#!/usr/bin/env bash
# Build a debug APK for the GPX Link Android app (macOS + Linux).
# Prerequisites: JDK 17+ on PATH and an Android SDK (Android Studio installs one).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
GRADLEW="$ANDROID_DIR/gradlew"
OUT_APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"

die() {
  echo "build-apk: error: $*" >&2
  exit 1
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing command '$1' — install it and retry"
}

ensure_java_17_plus() {
  need_cmd java
  # Parses first version line like 'openjdk version "21.0.2"' or 'java version "17.0.10"'
  local ver
  ver="$(java -version 2>&1 | head -n1 | sed -n 's/.* version "\([0-9][0-9]*\).*/\1/p')"
  [[ -n "$ver" ]] || die "could not detect Java version — need JDK 17+"
  [[ "$ver" -ge 17 ]] || die "need JDK 17 or newer (found major version $ver). Install Temurin 17+ or use Android Studio's bundled JDK."
}

ensure_android_sdk() {
  local props="$ANDROID_DIR/local.properties"
  if [[ -f "$props" ]] && grep -q '^sdk.dir=' "$props"; then
    return 0
  fi

  local sdk=""
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    sdk="$ANDROID_SDK_ROOT"
  elif [[ -n "${ANDROID_HOME:-}" ]]; then
    sdk="$ANDROID_HOME"
  elif [[ -d "$HOME/Library/Android/sdk" ]]; then
    sdk="$HOME/Library/Android/sdk"
  elif [[ -d "$HOME/Android/Sdk" ]]; then
    sdk="$HOME/Android/Sdk"
  fi

  [[ -n "$sdk" ]] || die "Android SDK not found. Install Android Studio (or cmdline-tools), then either:
  • export ANDROID_SDK_ROOT to your SDK path, or
  • create $props with one line: sdk.dir=/path/to/Android/sdk"

  [[ -d "$sdk" ]] || die "SDK directory does not exist: $sdk"

  printf 'sdk.dir=%s\n' "$sdk" >"$props"
  echo "build-apk: wrote $props (sdk.dir=$sdk)"
}

[[ -x "$GRADLEW" ]] || die "missing executable $GRADLEW — open the android/ folder from a checkout"

ensure_java_17_plus
ensure_android_sdk

echo "build-apk: running Gradle (first run may download dependencies; Chaquopy may take several minutes)..."
(cd "$ANDROID_DIR" && ./gradlew --no-daemon assembleDebug)

[[ -f "$OUT_APK" ]] || die "expected APK not found at $OUT_APK"

echo ""
echo "Done. Debug APK:"
echo "  $OUT_APK"
echo ""
echo "Install on a device (USB debugging on):"
echo "  adb install -r \"$OUT_APK\""
