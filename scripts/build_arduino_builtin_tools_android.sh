#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${ROOT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/app/android/app/src/main/jniLibs}"
ABI="${ABI:-arm64-v8a}"

if [[ "$ABI" != "arm64-v8a" ]]; then
  echo "build_arduino_builtin_tools_android: unsupported ABI '$ABI' (only arm64-v8a is supported)" >&2
  exit 1
fi

if ! command -v go >/dev/null 2>&1; then
  echo "build_arduino_builtin_tools_android: go command not found" >&2
  exit 1
fi

GOTOOLCHAIN="${GOTOOLCHAIN:-auto}"
SERIAL_DISCOVERY_VERSION="${SERIAL_DISCOVERY_VERSION:-v1.4.3}"
MDNS_DISCOVERY_VERSION="${MDNS_DISCOVERY_VERSION:-v1.0.12}"
SERIAL_MONITOR_VERSION="${SERIAL_MONITOR_VERSION:-v0.15.0}"

mkdir -p "$OUT_DIR/$ABI"

build_tool() {
  local module="$1"
  local version="$2"
  local bin_name="$3"
  local out_name="$4"

  echo "Building $module@$version for Android arm64..." >&2
  GOTOOLCHAIN="$GOTOOLCHAIN" GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
    go install "${module}@${version}"
  local src_bin
  src_bin="$(go env GOPATH)/bin/android_arm64/$bin_name"
  if [[ ! -f "$src_bin" ]]; then
    echo "build_arduino_builtin_tools_android: expected binary not found: $src_bin" >&2
    exit 1
  fi
  cp -f "$src_bin" "$OUT_DIR/$ABI/$out_name"
  chmod 0755 "$OUT_DIR/$ABI/$out_name"
}

SERIAL_DISCOVERY_SUBMODULE="$ROOT_DIR/third_party/serial-discovery"
if [[ ! -f "$SERIAL_DISCOVERY_SUBMODULE/go.mod" ]]; then
  echo "build_arduino_builtin_tools_android: missing submodule at $SERIAL_DISCOVERY_SUBMODULE" >&2
  exit 1
fi
echo "Building patched serial-discovery from submodule ($SERIAL_DISCOVERY_VERSION target) for Android arm64..." >&2
(
  cd "$SERIAL_DISCOVERY_SUBMODULE"
  GOTOOLCHAIN="$GOTOOLCHAIN" GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
    go build -o "$OUT_DIR/$ABI/libserial-discovery.so" .
)
chmod 0755 "$OUT_DIR/$ABI/libserial-discovery.so"
build_tool "github.com/arduino/mdns-discovery" "$MDNS_DISCOVERY_VERSION" "mdns-discovery" "libmdns-discovery.so"
build_tool "github.com/arduino/serial-monitor" "$SERIAL_MONITOR_VERSION" "serial-monitor" "libserial-monitor.so"

echo "Built Arduino builtin discovery tools under $OUT_DIR/$ABI" >&2
