#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

./gradlew assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK" ]]; then
  echo "Expected APK not found: $APK" >&2
  exit 1
fi

mkdir -p dist
cp "$APK" dist/TrexTrack-release.apk
echo "APK: $(pwd)/dist/TrexTrack-release.apk"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
APKSIGNER="$(find "$SDK/build-tools" -type f -name apksigner -print -quit 2>/dev/null || true)"
if [[ -n "$APKSIGNER" ]]; then
  "$APKSIGNER" verify --verbose dist/TrexTrack-release.apk
else
  echo "WARNING: apksigner not found; independent signature verification skipped."
fi
