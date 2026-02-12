#!/usr/bin/env bash
set -euo pipefail

# Sync app-private files/user <-> repo user/ explicitly (not during normal build).
#
# Usage:
#   scripts/user_defaults_sync.sh pull [serial]
#   scripts/user_defaults_sync.sh push [serial]
#
# Modes:
# - pull: device files/user -> repo user/
# - push: repo user/ -> device files/user

MODE="${1:-pull}"
SERIAL="${2:-}"

if [[ "${MODE}" != "pull" && "${MODE}" != "push" ]]; then
  echo "Usage: $0 [pull|push] [serial]" >&2
  exit 2
fi

ADB=(adb)
if [[ -n "${SERIAL}" ]]; then
  ADB+=( -s "${SERIAL}" )
fi

PKG="jp.espresso3389.methings"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_USER_DIR="${ROOT_DIR}/user"
TMP_TAR="/data/local/tmp/methings.user.sync.tar"

require_host_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required host tool: $1" >&2
    exit 1
  fi
}

require_host_tool adb
require_host_tool tar

if [[ ! -d "${REPO_USER_DIR}" ]]; then
  echo "Missing repo user dir: ${REPO_USER_DIR}" >&2
  exit 1
fi

if [[ "${MODE}" == "pull" ]]; then
  mkdir -p "${REPO_USER_DIR}"
  # Stream device files/user as tar and replace repo user/ contents.
  "${ADB[@]}" exec-out run-as "${PKG}" tar -C files/user -cf - . | tar -xf - -C "${REPO_USER_DIR}"
  echo "Pulled device files/user -> ${REPO_USER_DIR}"
  exit 0
fi

# push mode
LOCAL_TAR="$(mktemp -t methings-user-sync.XXXXXX.tar)"
trap 'rm -f "${LOCAL_TAR}"' EXIT

tar -C "${REPO_USER_DIR}" -cf "${LOCAL_TAR}" .
"${ADB[@]}" push "${LOCAL_TAR}" "${TMP_TAR}" >/dev/null
"${ADB[@]}" shell run-as "${PKG}" mkdir -p files/user
"${ADB[@]}" shell run-as "${PKG}" tar -C files/user -xf "${TMP_TAR}"
"${ADB[@]}" shell rm -f "${TMP_TAR}" >/dev/null 2>&1 || true

echo "Pushed ${REPO_USER_DIR} -> device files/user"
