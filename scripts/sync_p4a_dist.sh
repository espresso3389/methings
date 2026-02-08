#!/usr/bin/env bash
set -euo pipefail

# Sync an existing python-for-android dist into the Android project.
#
# This does NOT build p4a. It only copies files from:
#   ~/.local/share/python-for-android/dists/<DIST_NAME>
#
# If the dist doesn't exist, run scripts/build_p4a.sh first.

ROOT_DIR=${ROOT_DIR:-"$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"}
ARCH=${ARCH:-"arm64-v8a"}
DIST_NAME=${DIST_NAME:-"methings"}
P4A_DIST_ROOT=${P4A_DIST_ROOT:-"$HOME/.local/share/python-for-android/dists"}

ASSETS_PATH=${ASSETS_PATH:-"$ROOT_DIR/app/android/app/src/main/assets/pyenv"}
JNI_LIBS_PATH=${JNI_LIBS_PATH:-"$ROOT_DIR/app/android/app/src/main/jniLibs"}

DIST_ROOT="$P4A_DIST_ROOT/$DIST_NAME"
PY_RUNTIME_DIR="$DIST_ROOT/_python_bundle__${ARCH}/_python_bundle"
DIST_LIBS_DIR="$DIST_ROOT/libs/$ARCH"

if [[ ! -d "$DIST_ROOT" ]]; then
  echo "sync_p4a_dist: dist not found: $DIST_ROOT" >&2
  echo "Run: SDK_DIR=... NDK_DIR=... ./scripts/build_p4a.sh (DIST_NAME=$DIST_NAME ARCH=$ARCH)" >&2
  exit 2
fi

if [[ ! -d "$PY_RUNTIME_DIR" ]]; then
  echo "sync_p4a_dist: python bundle not found: $PY_RUNTIME_DIR" >&2
  echo "This dist may be incomplete; re-run scripts/build_p4a.sh." >&2
  exit 3
fi

rm -rf "$ASSETS_PATH"
mkdir -p "$ASSETS_PATH"
cp -a "$PY_RUNTIME_DIR"/* "$ASSETS_PATH"/

if [[ ! -f "$ASSETS_PATH/stdlib.zip" ]]; then
  echo "sync_p4a_dist: copy failed: stdlib.zip not found in $ASSETS_PATH" >&2
  exit 4
fi

if [[ -d "$DIST_LIBS_DIR" ]]; then
  mkdir -p "$JNI_LIBS_PATH/$ARCH"
  cp -a "$DIST_LIBS_DIR"/* "$JNI_LIBS_PATH/$ARCH"/
else
  echo "sync_p4a_dist: warning: libs dir not found: $DIST_LIBS_DIR" >&2
fi

echo "Synced python runtime assets to: $ASSETS_PATH"
echo "Synced native libs to: $JNI_LIBS_PATH/$ARCH"

