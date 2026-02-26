#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/arduino_stage_curated_bundle.sh \
    --bundle-dir /path/to/curated-bundle \
    [--serial <adb-serial>] \
    [--package jp.espresso3389.methings] \
    [--host-port 43389] \
    [--set-additional-urls]

Bundle layout:
  <bundle-dir>/package_methings_index.json
  <bundle-dir>/files/<archive-files...>

What this does:
1. Pushes bundle into app sandbox at files/user/arduino-curated
2. Optionally configures arduino additional URL to:
   http://127.0.0.1:33389/arduino/curated/index.json
3. Verifies server status endpoint via adb forward
USAGE
}

BUNDLE_DIR=""
ADB_SERIAL=""
PACKAGE_NAME="jp.espresso3389.methings"
HOST_PORT="43389"
SET_URLS="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bundle-dir) BUNDLE_DIR="${2:-}"; shift 2 ;;
    --serial) ADB_SERIAL="${2:-}"; shift 2 ;;
    --package) PACKAGE_NAME="${2:-}"; shift 2 ;;
    --host-port) HOST_PORT="${2:-}"; shift 2 ;;
    --set-additional-urls) SET_URLS="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "unknown_arg: $1" >&2
      usage
      exit 2
      ;;
  esac
done

if [[ -z "$BUNDLE_DIR" ]]; then
  echo "--bundle-dir is required" >&2
  usage
  exit 2
fi

command -v adb >/dev/null 2>&1 || { echo "missing_command: adb" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "missing_command: curl" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "missing_command: python3" >&2; exit 1; }

BUNDLE_DIR="$(python3 - "$BUNDLE_DIR" <<'PY'
import os,sys
print(os.path.realpath(sys.argv[1]))
PY
)"

INDEX_FILE="$BUNDLE_DIR/package_methings_index.json"
FILES_DIR="$BUNDLE_DIR/files"

if [[ ! -f "$INDEX_FILE" ]]; then
  echo "missing index file: $INDEX_FILE" >&2
  exit 2
fi

ADB=(adb)
if [[ -n "$ADB_SERIAL" ]]; then
  ADB+=( -s "$ADB_SERIAL" )
fi

echo "[device] ensure app is running"
"${ADB[@]}" shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

echo "[device] staging curated index"
"${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -rf files/user/arduino-curated
"${ADB[@]}" shell run-as "$PACKAGE_NAME" mkdir -p files/user/arduino-curated/files
"${ADB[@]}" push "$INDEX_FILE" /data/local/tmp/methings_package_methings_index.json >/dev/null
"${ADB[@]}" shell run-as "$PACKAGE_NAME" cp /data/local/tmp/methings_package_methings_index.json files/user/arduino-curated/package_methings_index.json

if [[ -d "$FILES_DIR" ]] && find "$FILES_DIR" -type f -print -quit | grep -q .; then
  echo "[device] staging curated files"
  "${ADB[@]}" shell rm -rf /data/local/tmp/methings_arduino_curated_files
  "${ADB[@]}" push "$FILES_DIR" /data/local/tmp/methings_arduino_curated_files >/dev/null
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" mkdir -p files/user/arduino-curated/files
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" cp -R /data/local/tmp/methings_arduino_curated_files/. files/user/arduino-curated/files/
fi

"${ADB[@]}" forward --remove "tcp:${HOST_PORT}" >/dev/null 2>&1 || true
"${ADB[@]}" forward "tcp:${HOST_PORT}" tcp:33389 >/dev/null

STATUS_JSON="$(curl -fsS --max-time 10 "http://127.0.0.1:${HOST_PORT}/arduino/curated/status")"
echo "$STATUS_JSON"

if [[ "$SET_URLS" == "true" ]]; then
  URL="http://127.0.0.1:33389/arduino/curated/index.json"
  echo "[device] configuring additional URLs -> $URL"
  if [[ -n "$ADB_SERIAL" ]]; then
    scripts/arduino_set_additional_urls.sh --url "$URL" --serial "$ADB_SERIAL" --package "$PACKAGE_NAME"
  else
    scripts/arduino_set_additional_urls.sh --url "$URL" --package "$PACKAGE_NAME"
  fi
fi

echo "curated bundle staged."
