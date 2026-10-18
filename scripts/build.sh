#!/usr/bin/env bash
#
# Builds a signed release APK in a low-memory environment.
#
# Exists because the settings that make this project buildable inside a 2 GB container are not obvious
# from the outside and are easy to undo by accident: the Kotlin compiler must run in-process (a second
# JVM does not fit), the daemon heap has to be large enough for dexing yet small enough to leave room
# for the native allocations, and R8 must stay off.
#
# Usage:
#   scripts/build.sh          # run tests, then build the release APK
#   scripts/build.sh --no-test
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# ---- Toolchain ------------------------------------------------------------------------------------
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"

# Anything set here would override gradle.properties, and the tuned values are the point of this script.
unset GRADLE_OPTS || true

if [ ! -d "$ANDROID_HOME" ]; then
  echo "error: ANDROID_HOME ($ANDROID_HOME) does not exist" >&2
  exit 1
fi

GRADLE="${GRADLE_BIN:-gradle}"
if ! command -v "$GRADLE" >/dev/null 2>&1; then
  if [ -x /opt/gradle/bin/gradle ]; then
    GRADLE=/opt/gradle/bin/gradle
  else
    echo "error: gradle not found; set GRADLE_BIN" >&2
    exit 1
  fi
fi

# The SDK path lives outside the repo, so it must not be committed; regenerate it every run.
echo "sdk.dir=$ANDROID_HOME" > local.properties

AVG_BEFORE=$(awk '/MemAvailable/{print $2}' /proc/meminfo)
echo "==> Available memory before build: $((AVG_BEFORE / 1024)) MB"

# ---- Tests --------------------------------------------------------------------------------------
if [ "${1:-}" != "--no-test" ]; then
  echo "==> Running the test suite"
  "$GRADLE" :core:test :app:testDebugUnitTest --console=plain --no-daemon
fi

# ---- Release ------------------------------------------------------------------------------------
echo "==> Assembling the release APK"
"$GRADLE" :app:assembleRelease --console=plain --no-daemon

APK="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK" ]; then
  echo "error: the build reported success but produced no APK" >&2
  echo "       check /sys/fs/cgroup/memory.events for oom_kill" >&2
  exit 1
fi

mkdir -p dist
cp "$APK" dist/QuickShare-Release.apk

# ---- Verify -------------------------------------------------------------------------------------
BT="$ANDROID_HOME/build-tools/34.0.0"
if [ -x "$BT/apksigner" ]; then
  echo "==> Verifying the signature"
  "$BT/apksigner" verify --verbose --print-certs dist/QuickShare-Release.apk | head -12
  "$BT/aapt2" dump badging dist/QuickShare-Release.apk | grep -E "^package:|^sdkVersion|^targetSdkVersion|application-label" || true
fi

echo
ls -l dist/QuickShare-Release.apk
sha256sum dist/QuickShare-Release.apk
