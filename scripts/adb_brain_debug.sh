#!/usr/bin/env bash
set -euo pipefail

# Debug me.things via adb port-forward.
#
# Usage:
#   scripts/adb_brain_debug.sh [serial]
#
# Env:
#   METHINGS_LOCAL_PORT=18765   Local forwarded port
#   METHINGS_LOCAL_PORT=18765     Back-compat

LOCAL_PORT="${METHINGS_LOCAL_PORT:-${METHINGS_LOCAL_PORT:-18765}}"

pick_serial() {
  local s
  s="${1:-}"
  if [[ -n "$s" ]]; then
    echo "$s"
    return 0
  fi
  # First device in "device" state.
  adb devices | awk 'NR>1 && $2=="device" {print $1; exit}'
}

SERIAL="$(pick_serial "${1:-}")"
if [[ -z "$SERIAL" ]]; then
  echo "error: no adb device in 'device' state detected" >&2
  echo "hint: run 'adb devices -l' and pass the serial as arg 1" >&2
  exit 2
fi

echo "serial=$SERIAL"
echo "forward: tcp:${LOCAL_PORT} -> device tcp:8765"
adb -s "$SERIAL" forward "tcp:${LOCAL_PORT}" tcp:8765

API="http://127.0.0.1:${LOCAL_PORT}"

get_json() {
  local path="$1"
  curl -sS -m 5 -H 'Accept: application/json' "${API}${path}"
}

post_json() {
  local path="$1"
  local body="$2"
  curl -sS -m 8 -H 'Content-Type: application/json' -d "$body" "${API}${path}"
}

py_get() {
  python3 - <<'PY'
import json, sys
obj = json.load(sys.stdin)
for key in sys.argv[1:]:
    if isinstance(obj, dict):
        obj = obj.get(key)
    else:
        obj = None
print("" if obj is None else obj)
PY
}

echo
echo "== /health =="
get_json /health || true

echo
echo "== /ui/version =="
get_json /ui/version || true

echo
echo "== Kotlin /brain/config (settings) =="
CFG_JSON="$(get_json /brain/config || true)"
echo "$CFG_JSON"

HAS_KEY="$(printf '%s' "$CFG_JSON" | python3 -c 'import json,sys; o=json.load(sys.stdin); print("true" if o.get("has_api_key") else "false")' 2>/dev/null || echo "unknown")"

echo
echo "== Worker /brain/status (proxied) =="
STATUS_JSON="$(get_json /brain/status || true)"
echo "$STATUS_JSON"

LAST_ERROR="$(printf '%s' "$STATUS_JSON" | python3 -c 'import json,sys; o=json.load(sys.stdin); print(o.get("last_error",""))' 2>/dev/null || true)"

echo
echo "== /audit/recent?limit=80 =="
get_json "/audit/recent?limit=80" || true

echo
echo "== /brain/sessions?limit=5 =="
SESS_JSON="$(get_json "/brain/sessions?limit=5" || true)"
echo "$SESS_JSON"

LATEST_SID="$(printf '%s' "$SESS_JSON" | python3 -c 'import json,sys; o=json.load(sys.stdin); s=o.get("sessions") or []; print((s[0] or {}).get("session_id","") if s else "")' 2>/dev/null || true)"

if [[ -n "$LATEST_SID" ]]; then
  echo
  echo "== /brain/messages?session_id=${LATEST_SID}&limit=80 =="
  get_json "/brain/messages?session_id=${LATEST_SID}&limit=80" || true
fi

echo
echo "== Summary =="
echo "has_api_key=${HAS_KEY}"
if [[ -n "$LAST_ERROR" ]]; then
  echo "last_error=${LAST_ERROR}"
fi

if [[ "$HAS_KEY" != "true" ]]; then
  echo
  echo "next: set Brain API key (UI: Settings -> Brain) then POST /brain/agent/bootstrap" >&2
fi
