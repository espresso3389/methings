#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${ROOT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
ARDUINO_CLI_DIR="${ARDUINO_CLI_DIR:-$ROOT_DIR/third_party/arduino-cli}"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/app/android/app/src/main/jniLibs}"
ABI="${ABI:-arm64-v8a}"
OUT_FILE="$OUT_DIR/$ABI/libarduino-cli.so"

if [[ "$ABI" != "arm64-v8a" ]]; then
  echo "build_arduino_cli_android: unsupported ABI '$ABI' (only arm64-v8a is supported)" >&2
  exit 1
fi

if [[ ! -d "$ARDUINO_CLI_DIR" ]]; then
  echo "build_arduino_cli_android: missing submodule at $ARDUINO_CLI_DIR" >&2
  echo "Run: git submodule update --init --recursive third_party/arduino-cli" >&2
  exit 1
fi

if ! command -v go >/dev/null 2>&1; then
  echo "build_arduino_cli_android: go command not found" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT_FILE")"

echo "Building arduino-cli for Android arm64..." >&2
(
  cd "$ARDUINO_CLI_DIR"
  CGO_ENABLED=0 GOOS=android GOARCH=arm64 \
    go build -trimpath -ldflags="-s -w" -o "$OUT_FILE" .
)

chmod 0755 "$OUT_FILE"
echo "Built $OUT_FILE" >&2
