#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/esp32_build_and_flash.sh \
    --sketch <path-to-sketch-dir-or-ino> \
    [--serial <adb-serial>] \
    [--fqbn esp32:esp32:m5stack_atom] \
    [--package jp.espresso3389.methings] \
    [--host-port 43389] \
    [--host-cli /tmp/methings-arduino-cli-host] \
    [--arduino-root /tmp/methings-arduino-host] \
    [--target-dir firmware/blink] \
    [--vid 0x0403] \
    [--pid 0x6001] \
    [--usb-name /dev/bus/usb/001/002] \
    [--permission-timeout-ms 120000] \
    [--flash-timeout-ms 2500] \
    [--flash-debug true|false] \
    [--skip-core-update]

What this does:
1. Builds host arduino-cli from third_party/arduino-cli (if missing)
2. Compiles sketch on host Linux for the selected FQBN
3. Pushes firmware segments into app sandbox under files/user/<target-dir>
4. Opens USB device via app local API and flashes via /mcu/flash

Notes:
- This uses host compilation + on-device flashing API (not on-device compile).
- If Android USB permission dialog appears, approve it on-device.
USAGE
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing_command: $1" >&2
    exit 1
  }
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SKETCH_PATH=""
ADB_SERIAL=""
FQBN="esp32:esp32:m5stack_atom"
PACKAGE_NAME="jp.espresso3389.methings"
HOST_PORT="43389"
HOST_CLI="/tmp/methings-arduino-cli-host"
ARDUINO_ROOT="/tmp/methings-arduino-host"
TARGET_DIR="firmware/blink"
USB_NAME=""
USB_VID="0x0403"
USB_PID="0x6001"
PERMISSION_TIMEOUT_MS="120000"
FLASH_TIMEOUT_MS="2500"
FLASH_DEBUG="true"
SKIP_CORE_UPDATE="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sketch) SKETCH_PATH="${2:-}"; shift 2 ;;
    --serial) ADB_SERIAL="${2:-}"; shift 2 ;;
    --fqbn) FQBN="${2:-}"; shift 2 ;;
    --package) PACKAGE_NAME="${2:-}"; shift 2 ;;
    --host-port) HOST_PORT="${2:-}"; shift 2 ;;
    --host-cli) HOST_CLI="${2:-}"; shift 2 ;;
    --arduino-root) ARDUINO_ROOT="${2:-}"; shift 2 ;;
    --target-dir) TARGET_DIR="${2:-}"; shift 2 ;;
    --vid) USB_VID="${2:-}"; shift 2 ;;
    --pid) USB_PID="${2:-}"; shift 2 ;;
    --usb-name) USB_NAME="${2:-}"; shift 2 ;;
    --permission-timeout-ms) PERMISSION_TIMEOUT_MS="${2:-}"; shift 2 ;;
    --flash-timeout-ms) FLASH_TIMEOUT_MS="${2:-}"; shift 2 ;;
    --flash-debug) FLASH_DEBUG="${2:-}"; shift 2 ;;
    --skip-core-update) SKIP_CORE_UPDATE="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "unknown_arg: $1" >&2
      usage
      exit 2
      ;;
  esac
done

if [[ -z "$SKETCH_PATH" ]]; then
  echo "--sketch is required" >&2
  usage
  exit 2
fi

if [[ "$FLASH_DEBUG" != "true" && "$FLASH_DEBUG" != "false" ]]; then
  echo "invalid --flash-debug: $FLASH_DEBUG (use true|false)" >&2
  exit 2
fi

need_cmd adb
need_cmd curl
need_cmd jq
need_cmd python3
need_cmd go

ADB=(adb)
if [[ -n "$ADB_SERIAL" ]]; then
  ADB+=( -s "$ADB_SERIAL" )
fi

if [[ ! -x "$HOST_CLI" ]]; then
  echo "[host] building arduino-cli -> $HOST_CLI"
  (
    cd "$REPO_ROOT/third_party/arduino-cli"
    go build -o "$HOST_CLI" ./
  )
fi

if [[ ! -x "$HOST_CLI" ]]; then
  echo "failed to create host arduino-cli: $HOST_CLI" >&2
  exit 1
fi

SKETCH_CANON="$(python3 - "$SKETCH_PATH" <<'PY'
import os,sys
print(os.path.realpath(sys.argv[1]))
PY
)"
if [[ ! -e "$SKETCH_CANON" ]]; then
  echo "sketch path not found: $SKETCH_CANON" >&2
  exit 2
fi

SKETCH_DIR=""
TMP_SKETCH=""
if [[ -d "$SKETCH_CANON" ]]; then
  SKETCH_DIR="$SKETCH_CANON"
else
  base="$(basename "$SKETCH_CANON")"
  name="${base%.*}"
  TMP_SKETCH="$(mktemp -d)"
  mkdir -p "$TMP_SKETCH/$name"
  cp -f "$SKETCH_CANON" "$TMP_SKETCH/$name/$name.ino"
  SKETCH_DIR="$TMP_SKETCH/$name"
fi

cleanup() {
  if [[ -n "${USB_HANDLE:-}" ]]; then
    curl -sS -H 'Content-Type: application/json' \
      -d "{\"handle\":\"$USB_HANDLE\"}" \
      "http://127.0.0.1:${HOST_PORT}/usb/close" >/dev/null 2>&1 || true
  fi
  "${ADB[@]}" forward --remove "tcp:${HOST_PORT}" >/dev/null 2>&1 || true
  if [[ -n "$TMP_SKETCH" && -d "$TMP_SKETCH" ]]; then
    rm -rf "$TMP_SKETCH"
  fi
}
trap cleanup EXIT

mkdir -p "$ARDUINO_ROOT/data" "$ARDUINO_ROOT/downloads" "$ARDUINO_ROOT/user" "$ARDUINO_ROOT/out"
export ARDUINO_DIRECTORIES_DATA="$ARDUINO_ROOT/data"
export ARDUINO_DIRECTORIES_DOWNLOADS="$ARDUINO_ROOT/downloads"
export ARDUINO_DIRECTORIES_USER="$ARDUINO_ROOT/user"

if [[ "$SKIP_CORE_UPDATE" != "true" ]]; then
  echo "[host] arduino-cli core update-index"
  "$HOST_CLI" core update-index
fi
echo "[host] arduino-cli core install esp32:esp32"
"$HOST_CLI" core install esp32:esp32

OUT_DIR="$ARDUINO_ROOT/out"
rm -f "$OUT_DIR"/*
echo "[host] arduino-cli compile --fqbn $FQBN $SKETCH_DIR"
"$HOST_CLI" compile --fqbn "$FQBN" --output-dir "$OUT_DIR" "$SKETCH_DIR"

BOOTLOADER_BIN="$(ls -1 "$OUT_DIR"/*.bootloader.bin | head -n 1)"
PARTITIONS_BIN="$(ls -1 "$OUT_DIR"/*.partitions.bin | head -n 1)"
APP_BIN="$(ls -1 "$OUT_DIR"/*.bin | grep -Ev '\.bootloader\.bin$|\.partitions\.bin$|\.merged\.bin$' | head -n 1)"
BOOT_APP0_BIN="$(ls -1 "$ARDUINO_ROOT/data/packages/esp32/hardware/esp32"/*/tools/partitions/boot_app0.bin | head -n 1)"

if [[ -z "$BOOTLOADER_BIN" || -z "$PARTITIONS_BIN" || -z "$APP_BIN" || -z "$BOOT_APP0_BIN" ]]; then
  echo "missing expected build artifacts" >&2
  exit 1
fi

echo "[device] staging firmware files in app sandbox"
"${ADB[@]}" push "$BOOTLOADER_BIN" /data/local/tmp/methings_bootloader.bin >/dev/null
"${ADB[@]}" push "$PARTITIONS_BIN" /data/local/tmp/methings_partitions.bin >/dev/null
"${ADB[@]}" push "$BOOT_APP0_BIN" /data/local/tmp/methings_boot_app0.bin >/dev/null
"${ADB[@]}" push "$APP_BIN" /data/local/tmp/methings_app.bin >/dev/null
"${ADB[@]}" shell run-as "$PACKAGE_NAME" mkdir -p "files/user/$TARGET_DIR"
"${ADB[@]}" shell run-as "$PACKAGE_NAME" cp /data/local/tmp/methings_bootloader.bin "files/user/$TARGET_DIR/bootloader.bin"
"${ADB[@]}" shell run-as "$PACKAGE_NAME" cp /data/local/tmp/methings_partitions.bin "files/user/$TARGET_DIR/partitions.bin"
"${ADB[@]}" shell run-as "$PACKAGE_NAME" cp /data/local/tmp/methings_boot_app0.bin "files/user/$TARGET_DIR/boot_app0.bin"
"${ADB[@]}" shell run-as "$PACKAGE_NAME" cp /data/local/tmp/methings_app.bin "files/user/$TARGET_DIR/app.bin"

echo "[device] ensure app is running"
"${ADB[@]}" shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

"${ADB[@]}" forward --remove "tcp:${HOST_PORT}" >/dev/null 2>&1 || true
"${ADB[@]}" forward "tcp:${HOST_PORT}" tcp:33389 >/dev/null

HEALTH="$(curl -fsS --max-time 8 "http://127.0.0.1:${HOST_PORT}/health")"
if ! echo "$HEALTH" | jq -e '.status == "ok"' >/dev/null; then
  echo "local service not healthy: $HEALTH" >&2
  exit 1
fi

echo "[device] opening USB device"
OPEN_PAYLOAD=""
if [[ -n "$USB_NAME" ]]; then
  OPEN_PAYLOAD="$(jq -cn --arg n "$USB_NAME" --argjson t "$PERMISSION_TIMEOUT_MS" '{name:$n, permission_timeout_ms:$t}')"
else
  OPEN_PAYLOAD="$(jq -cn --argjson v "$((USB_VID))" --argjson p "$((USB_PID))" --argjson t "$PERMISSION_TIMEOUT_MS" '{vendor_id:$v, product_id:$p, permission_timeout_ms:$t}')"
fi
OPEN_RES="$(curl -sS -H 'Content-Type: application/json' -d "$OPEN_PAYLOAD" "http://127.0.0.1:${HOST_PORT}/usb/open")"
OPEN_STATUS="$(echo "$OPEN_RES" | jq -r '.status // empty')"
if [[ "$OPEN_STATUS" == "permission_required" ]]; then
  echo "$OPEN_RES" | jq .
  echo "usb permission required; approve Android dialog then rerun" >&2
  exit 3
fi
if [[ "$OPEN_STATUS" != "ok" ]]; then
  echo "usb/open failed:" >&2
  echo "$OPEN_RES" | jq . >&2
  exit 1
fi
USB_HANDLE="$(echo "$OPEN_RES" | jq -r '.handle // empty')"
if [[ -z "$USB_HANDLE" ]]; then
  echo "usb/open returned no handle" >&2
  exit 1
fi

echo "[device] flashing via /mcu/flash (handle=$USB_HANDLE)"
FLASH_PAYLOAD="$(
  jq -cn \
    --arg handle "$USB_HANDLE" \
    --arg td "$TARGET_DIR" \
    --argjson flash_timeout_ms "$FLASH_TIMEOUT_MS" \
    --argjson flash_debug "$FLASH_DEBUG" \
    '{
      model:"esp32",
      handle:$handle,
      segments:[
        {path:($td + "/bootloader.bin"), offset:4096},
        {path:($td + "/partitions.bin"), offset:32768},
        {path:($td + "/boot_app0.bin"), offset:57344},
        {path:($td + "/app.bin"), offset:65536}
      ],
      auto_enter_bootloader:true,
      reboot:true,
      timeout_ms:$flash_timeout_ms,
      debug:$flash_debug
    }'
)"

FLASH_RES="$(curl -sS -H 'Content-Type: application/json' -d "$FLASH_PAYLOAD" "http://127.0.0.1:${HOST_PORT}/mcu/flash")"
FLASH_STATUS="$(echo "$FLASH_RES" | jq -r '.status // empty')"
echo "$FLASH_RES" | jq .
if [[ "$FLASH_STATUS" != "ok" ]]; then
  echo "mcu/flash failed" >&2
  exit 1
fi

echo "esp32 build+flash: success"
