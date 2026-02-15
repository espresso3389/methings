#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/me_sync_adb_run.sh \
    --exporter-serial <adb-serial> \
    --importer-serial <adb-serial> \
    [--exporter-port 43389] \
    [--importer-port 53389] \
    [--nearby-timeout-ms 120000] \
    [--nearby-max-bytes 2147483648] \
    [--max-bytes 2147483648] \
    [--allow-fallback true|false] \
    [--wipe-existing true|false] \
    [--auto-allow]

What this does (real me.sync run, no QR scanning):
1. ADB forward both devices' local HTTP server ports
2. Create me.sync v3 ticket on exporter (/me/sync/v3/ticket/create)
3. Pass returned ticket_uri to importer (/me/sync/v3/import/apply)
4. Print import result JSON

Notes:
- This triggers real me.sync behavior; ticket_uri transfer is done via host/ADB.
- If permission dialogs appear, approve them on-device.
- With --auto-allow, helper tapper is started (best-effort) on both devices.
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
EXPORTER_PORT="43389"
IMPORTER_PORT="53389"
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
    --exporter-port) EXPORTER_PORT="${2:-}"; shift 2 ;;
    --importer-port) IMPORTER_PORT="${2:-}"; shift 2 ;;
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
need_cmd curl
need_cmd jq

if [[ "$AUTO_ALLOW" == "true" ]]; then
  need_cmd python3
fi

if ! [[ "$NEARBY_TIMEOUT_MS" =~ ^[0-9]+$ ]]; then
  echo "invalid --nearby-timeout-ms: $NEARBY_TIMEOUT_MS" >&2
  exit 2
fi
if ! [[ "$NEARBY_MAX_BYTES" =~ ^[0-9]+$ ]]; then
  echo "invalid --nearby-max-bytes: $NEARBY_MAX_BYTES" >&2
  exit 2
fi
if ! [[ "$MAX_BYTES" =~ ^[0-9]+$ ]]; then
  echo "invalid --max-bytes: $MAX_BYTES" >&2
  exit 2
fi

if [[ "$ALLOW_FALLBACK" != "true" && "$ALLOW_FALLBACK" != "false" ]]; then
  echo "invalid --allow-fallback: $ALLOW_FALLBACK (use true|false)" >&2
  exit 2
fi

if [[ "$WIPE_EXISTING" != "true" && "$WIPE_EXISTING" != "false" ]]; then
  echo "invalid --wipe-existing: $WIPE_EXISTING (use true|false)" >&2
  exit 2
fi

cleanup() {
  if [[ -n "${AUTO_ALLOW_PID_1:-}" ]]; then
    kill "$AUTO_ALLOW_PID_1" >/dev/null 2>&1 || true
  fi
  if [[ -n "${AUTO_ALLOW_PID_2:-}" ]]; then
    kill "$AUTO_ALLOW_PID_2" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

adb -s "$EXPORTER_SERIAL" forward --remove "tcp:${EXPORTER_PORT}" >/dev/null 2>&1 || true
adb -s "$IMPORTER_SERIAL" forward --remove "tcp:${IMPORTER_PORT}" >/dev/null 2>&1 || true
adb -s "$EXPORTER_SERIAL" forward "tcp:${EXPORTER_PORT}" tcp:33389 >/dev/null
adb -s "$IMPORTER_SERIAL" forward "tcp:${IMPORTER_PORT}" tcp:33389 >/dev/null

# Ensure app process/UI is up (best-effort)
adb -s "$EXPORTER_SERIAL" shell monkey -p jp.espresso3389.methings -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
adb -s "$IMPORTER_SERIAL" shell monkey -p jp.espresso3389.methings -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

if [[ "$AUTO_ALLOW" == "true" ]]; then
  scripts/adb_auto_allow.sh --serial "$EXPORTER_SERIAL" --timeout 180 --poll 0.7 >/tmp/methings_auto_allow_exporter.log 2>&1 &
  AUTO_ALLOW_PID_1=$!
  scripts/adb_auto_allow.sh --serial "$IMPORTER_SERIAL" --timeout 180 --poll 0.7 >/tmp/methings_auto_allow_importer.log 2>&1 &
  AUTO_ALLOW_PID_2=$!
fi

echo "[1/3] exporter health check"
EXP_HEALTH="$(curl -fsS --max-time 8 "http://127.0.0.1:${EXPORTER_PORT}/health")"
echo "$EXP_HEALTH" | jq -e '.status == "ok"' >/dev/null

echo "[2/3] importer health check"
IMP_HEALTH="$(curl -fsS --max-time 8 "http://127.0.0.1:${IMPORTER_PORT}/health")"
echo "$IMP_HEALTH" | jq -e '.status == "ok"' >/dev/null

echo "[3/3] create ticket on exporter"
CREATE_PAYLOAD='{"mode":"export","include_user":true,"include_protected_db":true,"include_identity":false,"force_refresh":true}'
CREATE_RES="$(curl -fsS --max-time 45 -X POST "http://127.0.0.1:${EXPORTER_PORT}/me/sync/v3/ticket/create" -H 'Content-Type: application/json' -d "$CREATE_PAYLOAD")"

if ! echo "$CREATE_RES" | jq -e '.status == "ok"' >/dev/null; then
  echo "ticket_create_failed:" >&2
  echo "$CREATE_RES" | jq . >&2
  exit 1
fi

TICKET_URI="$(echo "$CREATE_RES" | jq -r '.ticket_uri // empty')"
TICKET_ID="$(echo "$CREATE_RES" | jq -r '.ticket_id // empty')"
PAIR_CODE="$(echo "$CREATE_RES" | jq -r '.pair_code // empty')"
if [[ -z "$TICKET_URI" ]]; then
  echo "ticket_uri_missing" >&2
  echo "$CREATE_RES" | jq . >&2
  exit 1
fi

echo "ticket_id=${TICKET_ID} pair_code=${PAIR_CODE}"

echo "run importer /me/sync/v3/import/apply"
IMPORT_PAYLOAD="$(jq -cn \
  --arg ticket_uri "$TICKET_URI" \
  --argjson nearby_timeout_ms "$NEARBY_TIMEOUT_MS" \
  --argjson nearby_max_bytes "$NEARBY_MAX_BYTES" \
  --argjson max_bytes "$MAX_BYTES" \
  --argjson allow_fallback "$ALLOW_FALLBACK" \
  --argjson wipe_existing "$WIPE_EXISTING" \
  '{ticket_uri:$ticket_uri,payload:$ticket_uri,nearby_timeout_ms:$nearby_timeout_ms,nearby_max_bytes:$nearby_max_bytes,max_bytes:$max_bytes,allow_fallback:$allow_fallback,wipe_existing:$wipe_existing}')"

IMPORT_RES="$(curl -sS --max-time 240 -X POST "http://127.0.0.1:${IMPORTER_PORT}/me/sync/v3/import/apply" -H 'Content-Type: application/json' -d "$IMPORT_PAYLOAD")"

echo "--- exporter ticket ---"
echo "$CREATE_RES" | jq '{status,ticket_id,transfer_id,expires_at,pair_code,ticket_uri,fallback_download_url,me_me_offer_delivery}'
echo "--- importer result ---"
echo "$IMPORT_RES" | jq .

if echo "$IMPORT_RES" | jq -e '.status == "ok"' >/dev/null; then
  echo "me.sync adb run: success"
  exit 0
fi

if echo "$IMPORT_RES" | jq -e '.status == "permission_required"' >/dev/null; then
  echo "me.sync adb run: importer permission required (approve on device, then re-run)" >&2
  exit 3
fi

echo "me.sync adb run: failed" >&2
exit 1
