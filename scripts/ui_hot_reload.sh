#!/usr/bin/env bash
set -euo pipefail

# Hot-reload WebView UI without rebuilding the APK.
#
# Usage:
#   scripts/ui_hot_reload.sh [adb_serial]
#
# What it does:
# - Streams repo `app/android/app/src/main/assets/www/index.html` into the app's private
#   `files/user/www/index.html` via `adb shell run-as`.
# - Keeps `files/user/www/.version` aligned with the APK asset version to prevent the app from
#   auto-resetting the UI on next launch (AssetExtractor resets when versions mismatch).
# - Calls `POST /ui/reload` to force WebView reload.

SERIAL="${1:-}"
ADB=(adb)
if [[ -n "${SERIAL}" ]]; then
  ADB+=( -s "${SERIAL}" )
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_HTML="${ROOT_DIR}/app/android/app/src/main/assets/www/index.html"
SRC_VER="${ROOT_DIR}/app/android/app/src/main/assets/www/.version"
PKG="jp.espresso3389.methings"
DEVICE_PORT="${METHINGS_DEVICE_PORT:-33389}"
HOST_PORT="${METHINGS_LOCAL_PORT:-43389}"

if [[ ! -f "${SRC_HTML}" ]]; then
  echo "Missing ${SRC_HTML}" >&2
  exit 1
fi
if [[ ! -f "${SRC_VER}" ]]; then
  echo "Missing ${SRC_VER}" >&2
  exit 1
fi

# NOTE: On some Android builds, `run-as <pkg> sh -c ...` is blocked by SELinux even for debuggable apps.
# Use only simple executables with `run-as` (cp/cat) and stage files in /data/local/tmp.
TMP_HTML="/data/local/tmp/methings.index.html"
TMP_VER="/data/local/tmp/methings.www.version"

"${ADB[@]}" push "${SRC_HTML}" "${TMP_HTML}" >/dev/null
"${ADB[@]}" push "${SRC_VER}" "${TMP_VER}" >/dev/null

# Copy into app-private files/user/www (relative to app data dir).
"${ADB[@]}" shell run-as "${PKG}" mkdir -p files/user/www >/dev/null 2>&1 || true
"${ADB[@]}" shell run-as "${PKG}" cp "${TMP_HTML}" files/user/www/index.html
"${ADB[@]}" shell run-as "${PKG}" cp "${TMP_VER}" files/user/www/.version

# Ensure port forward exists, then trigger reload.
"${ADB[@]}" forward "tcp:${HOST_PORT}" "tcp:${DEVICE_PORT}" >/dev/null 2>&1 || true

# Ensure the app + local server are up before calling the reload API.
"${ADB[@]}" shell am start -n "${PKG}/.ui.MainActivity" >/dev/null 2>&1 || true
for _ in $(seq 1 30); do
  if curl -fsS --max-time 1 "http://127.0.0.1:${HOST_PORT}/ui/version" >/dev/null 2>&1; then
    break
  fi
  sleep 0.2
done

curl -fsS -X POST "http://127.0.0.1:${HOST_PORT}/ui/reload" -H "Content-Type: application/json" -d '{}' >/dev/null

echo "Hot-reloaded UI: files/user/www/index.html"
