#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/me_sync_adb_regression.sh \
    --exporter-serial <adb-serial> \
    --importer-serial <adb-serial> \
    [--out-dir <dir>] \
    [--nearby-timeout-ms 120000] \
    [--nearby-max-bytes 2147483648] \
    [--max-bytes 2147483648] \
    [--allow-fallback true|false] \
    [--wipe-existing true|false] \
    [--auto-allow]

What this does:
- Runs real me.sync transfer test in two modes:
  1) wifi_on
  2) wifi_off
- Saves per-case logs and a summary JSON for regression tracking.
USAGE
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing_command: $1" >&2
    exit 1
  }
}

EXPORTER_SERIAL=""
IMPORTER_SERIAL=""
OUT_DIR=""
NEARBY_TIMEOUT_MS="120000"
NEARBY_MAX_BYTES="2147483648"
MAX_BYTES="2147483648"
ALLOW_FALLBACK="true"
WIPE_EXISTING="true"
AUTO_ALLOW="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --exporter-serial) EXPORTER_SERIAL="${2:-}"; shift 2 ;;
    --importer-serial) IMPORTER_SERIAL="${2:-}"; shift 2 ;;
    --out-dir) OUT_DIR="${2:-}"; shift 2 ;;
    --nearby-timeout-ms) NEARBY_TIMEOUT_MS="${2:-}"; shift 2 ;;
    --nearby-max-bytes) NEARBY_MAX_BYTES="${2:-}"; shift 2 ;;
    --max-bytes) MAX_BYTES="${2:-}"; shift 2 ;;
    --allow-fallback) ALLOW_FALLBACK="${2:-}"; shift 2 ;;
    --wipe-existing) WIPE_EXISTING="${2:-}"; shift 2 ;;
    --auto-allow) AUTO_ALLOW="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "unknown_arg: $1" >&2
      usage
      exit 2
      ;;
  esac
done

if [[ -z "$EXPORTER_SERIAL" || -z "$IMPORTER_SERIAL" ]]; then
  echo "--exporter-serial and --importer-serial are required" >&2
  usage
  exit 2
fi

need_cmd adb
need_cmd jq
need_cmd scripts/me_sync_adb_run.sh

if [[ -z "$OUT_DIR" ]]; then
  ts="$(date +%Y%m%d_%H%M%S)"
  OUT_DIR="logs/me_sync_regression_${ts}"
fi
mkdir -p "$OUT_DIR"

set_wifi_mode() {
  local mode="$1"
  local cmd="enable"
  if [[ "$mode" == "off" ]]; then
    cmd="disable"
  fi
  adb -s "$EXPORTER_SERIAL" shell svc wifi "$cmd" >/dev/null
  adb -s "$IMPORTER_SERIAL" shell svc wifi "$cmd" >/dev/null
}

extract_importer_json() {
  local logfile="$1"
  awk '/--- importer result ---/{flag=1; next} /^me\.sync adb run:/{flag=0} flag {print}' "$logfile"
}

extract_ticket_json() {
  local logfile="$1"
  awk '/--- exporter ticket ---/{flag=1; next} /--- importer result ---/{flag=0} flag {print}' "$logfile"
}

run_case() {
  local case_name="$1"
  local wifi_mode="$2"
  local logfile="$OUT_DIR/${case_name}.log"

  echo "=== ${case_name} (wifi ${wifi_mode}) ==="
  set_wifi_mode "$wifi_mode"

  local rc=0
  set +e
  scripts/me_sync_adb_run.sh \
    --exporter-serial "$EXPORTER_SERIAL" \
    --importer-serial "$IMPORTER_SERIAL" \
    --nearby-timeout-ms "$NEARBY_TIMEOUT_MS" \
    --nearby-max-bytes "$NEARBY_MAX_BYTES" \
    --max-bytes "$MAX_BYTES" \
    --allow-fallback "$ALLOW_FALLBACK" \
    --wipe-existing "$WIPE_EXISTING" \
    $([[ "$AUTO_ALLOW" == "true" ]] && echo "--auto-allow") \
    >"$logfile" 2>&1
  rc=$?
  set -e

  local importer_json ticket_json transport bytes imported detail err
  importer_json="$(extract_importer_json "$logfile" | sed '/^$/d' || true)"
  ticket_json="$(extract_ticket_json "$logfile" | sed '/^$/d' || true)"

  transport=""
  bytes="0"
  imported="false"
  detail=""
  err=""

  if [[ -n "$importer_json" ]] && echo "$importer_json" | jq . >/dev/null 2>&1; then
    transport="$(echo "$importer_json" | jq -r '.transport // ""')"
    bytes="$(echo "$importer_json" | jq -r '.nearby.bytes_received // 0')"
    imported="$(echo "$importer_json" | jq -r '.imported // false')"
    detail="$(echo "$importer_json" | jq -r '.detail // ""')"
    err="$(echo "$importer_json" | jq -r '.error // ""')"
  fi

  local ticket_id=""
  if [[ -n "$ticket_json" ]] && echo "$ticket_json" | jq . >/dev/null 2>&1; then
    ticket_id="$(echo "$ticket_json" | jq -r '.ticket_id // ""')"
  fi

  jq -n \
    --arg case_name "$case_name" \
    --arg wifi_mode "$wifi_mode" \
    --argjson exit_code "$rc" \
    --arg ticket_id "$ticket_id" \
    --arg transport "$transport" \
    --argjson nearby_bytes "$bytes" \
    --arg imported "$imported" \
    --arg detail "$detail" \
    --arg error "$err" \
    '{
      case: $case_name,
      wifi_mode: $wifi_mode,
      exit_code: $exit_code,
      ok: ($exit_code == 0),
      ticket_id: $ticket_id,
      imported: ($imported == "true"),
      transport: $transport,
      nearby_bytes: $nearby_bytes,
      detail: (if ($detail | length) > 0 then $detail else null end),
      error: (if ($error | length) > 0 then $error else null end)
    }' > "$OUT_DIR/${case_name}.json"

  if [[ $rc -eq 0 ]]; then
    echo "case ${case_name}: success"
  else
    echo "case ${case_name}: failed (see $logfile)"
  fi
}

run_case "wifi_on" "on"
run_case "wifi_off" "off"

jq -s '{
  generated_at: (now | todate),
  exporter_serial: $exporter,
  importer_serial: $importer,
  cases: .,
  all_passed: (map(.ok) | all)
}' \
  --arg exporter "$EXPORTER_SERIAL" \
  --arg importer "$IMPORTER_SERIAL" \
  "$OUT_DIR/wifi_on.json" "$OUT_DIR/wifi_off.json" > "$OUT_DIR/summary.json"

echo "saved: $OUT_DIR/summary.json"
jq . "$OUT_DIR/summary.json"

if jq -e '.all_passed == true' "$OUT_DIR/summary.json" >/dev/null; then
  exit 0
fi
exit 1
