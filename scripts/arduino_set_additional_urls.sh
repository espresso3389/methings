#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/arduino_set_additional_urls.sh \
    --url <package-index-url>[,<package-index-url>...] \
    [--serial <adb-serial>] \
    [--package jp.espresso3389.methings]

Writes the URL list to:
  $HOME/.arduino15/additional_urls.txt (inside app sandbox)

`arduino-cli` launched through methings runtime will read this file and set
`ARDUINO_BOARD_MANAGER_ADDITIONAL_URLS` automatically when not otherwise set.
USAGE
}

URLS=""
ADB_SERIAL=""
PACKAGE_NAME="jp.espresso3389.methings"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --url) URLS="${2:-}"; shift 2 ;;
    --serial) ADB_SERIAL="${2:-}"; shift 2 ;;
    --package) PACKAGE_NAME="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "unknown_arg: $1" >&2
      usage
      exit 2
      ;;
  esac
done

if [[ -z "$URLS" ]]; then
  echo "--url is required" >&2
  usage
  exit 2
fi

command -v adb >/dev/null 2>&1 || { echo "missing_command: adb" >&2; exit 1; }

ADB=(adb)
if [[ -n "$ADB_SERIAL" ]]; then
  ADB+=( -s "$ADB_SERIAL" )
fi

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
printf "%s\n" "$URLS" > "$tmp"

"${ADB[@]}" push "$tmp" /data/local/tmp/methings_additional_urls.txt >/dev/null
"${ADB[@]}" shell run-as "$PACKAGE_NAME" mkdir -p .arduino15
"${ADB[@]}" shell run-as "$PACKAGE_NAME" cp /data/local/tmp/methings_additional_urls.txt .arduino15/additional_urls.txt
"${ADB[@]}" shell run-as "$PACKAGE_NAME" cat .arduino15/additional_urls.txt

echo "additional URLs configured."
