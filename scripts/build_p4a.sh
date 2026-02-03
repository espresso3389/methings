#!/usr/bin/env bash
set -euo pipefail

ASSETS_PATH=${ASSETS_PATH:-"$(pwd)/app/android/app/src/main/assets/pyenv"}
SDK_DIR=${SDK_DIR:-""}
NDK_DIR=${NDK_DIR:-""}
ARCH=${ARCH:-"arm64-v8a"}
WORK_DIR=${WORK_DIR:-"/tmp/p4a_build_out"}
DIST_NAME=${DIST_NAME:-"androidvivepython"}

if [[ -z "$SDK_DIR" || -z "$NDK_DIR" ]]; then
  echo "SDK_DIR and NDK_DIR must be set." >&2
  exit 1
fi

VENV_DIR="$WORK_DIR/.venv-p4a"
python3 -m venv "$VENV_DIR"
source "$VENV_DIR/bin/activate"
python -m pip install --upgrade pip python-for-android

p4a apk \
  --requirements=python3,fastapi,uvicorn,requests,pysqlcipher3 \
  --arch="$ARCH" \
  --sdk-dir="$SDK_DIR" \
  --ndk-dir="$NDK_DIR" \
  --bootstrap=service_only \
  --private "$WORK_DIR" \
  --dist_name "$DIST_NAME" \
  --package com.example.androidvivepython

# Try to locate the built python runtime.
PY_RUNTIME_DIR=""
if [[ -d "$WORK_DIR" ]]; then
  PY_RUNTIME_DIR=$(find "$WORK_DIR" -type d -path "*/pythoninstalls/*" -name "*" -maxdepth 5 2>/dev/null | head -n 1 || true)
fi

if [[ -z "$PY_RUNTIME_DIR" ]]; then
  echo "Could not auto-locate python runtime under $WORK_DIR." >&2
  echo "Please locate the python runtime (containing bin/python) and copy it to: $ASSETS_PATH" >&2
  exit 2
fi

if [[ ! -f "$PY_RUNTIME_DIR/bin/python" ]]; then
  echo "Located runtime does not contain bin/python: $PY_RUNTIME_DIR" >&2
  exit 3
fi

rm -rf "$ASSETS_PATH"
mkdir -p "$ASSETS_PATH"
cp -a "$PY_RUNTIME_DIR"/* "$ASSETS_PATH"/

if [[ ! -f "$ASSETS_PATH/bin/python" ]]; then
  echo "Copy failed: bin/python not found in $ASSETS_PATH" >&2
  exit 4
fi

echo "Runtime copied to: $ASSETS_PATH"
