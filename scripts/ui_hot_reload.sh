#!/usr/bin/env bash
set -euo pipefail

# Hot-reload WebView UI without rebuilding the APK.
#
# Usage:
#   scripts/ui_hot_reload.sh [adb_serial]
#
# What it does:
# - Streams repo `app/android/app/src/main/assets/www/index.html` into the app's private
#   `files/www/index.html` via `adb shell run-as`.
# - Updates `files/www/.version` so the app can detect changes if needed.
# - Calls `POST /ui/reload` to force WebView reload.

SERIAL="${1:-}"
ADB=(adb)
if [[ -n "${SERIAL}" ]]; then
  ADB+=( -s "${SERIAL}" )
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_HTML="${ROOT_DIR}/app/android/app/src/main/assets/www/index.html"
PKG="jp.espresso3389.kugutz"

if [[ ! -f "${SRC_HTML}" ]]; then
  echo "Missing ${SRC_HTML}" >&2
  exit 1
fi

TS="$(date +%s)"

# NOTE: On some Android builds, `run-as <pkg> sh -c ...` is blocked by SELinux even for debuggable apps.
# Use only simple executables with `run-as` (cp/cat) and stage files in /data/local/tmp.
TMP_HTML="/data/local/tmp/kugutz.index.html"
TMP_VER="/data/local/tmp/kugutz.www.version"

"${ADB[@]}" push "${SRC_HTML}" "${TMP_HTML}" >/dev/null
printf '%s\n' "${TS}" > /tmp/kugutz.www.version
"${ADB[@]}" push /tmp/kugutz.www.version "${TMP_VER}" >/dev/null

# Copy into app-private files/www (relative to app data dir).
"${ADB[@]}" shell run-as "${PKG}" mkdir -p files/www >/dev/null 2>&1 || true
"${ADB[@]}" shell run-as "${PKG}" cp "${TMP_HTML}" files/www/index.html
"${ADB[@]}" shell run-as "${PKG}" cp "${TMP_VER}" files/www/.version

# Ensure port forward exists, then trigger reload.
"${ADB[@]}" forward tcp:18765 tcp:8765 >/dev/null 2>&1 || true

# Ensure the app + local server are up before calling the reload API.
"${ADB[@]}" shell am start -n "${PKG}/.ui.MainActivity" >/dev/null 2>&1 || true
for _ in $(seq 1 30); do
  if curl -fsS --max-time 1 "http://127.0.0.1:18765/ui/version" >/dev/null 2>&1; then
    break
  fi
  sleep 0.2
done

curl -fsS -X POST "http://127.0.0.1:18765/ui/reload" -H "Content-Type: application/json" -d '{}' >/dev/null

echo "Hot-reloaded UI: files/www/index.html (version ${TS})"
