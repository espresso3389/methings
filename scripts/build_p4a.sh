#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=${ROOT_DIR:-"$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"}
ASSETS_PATH=${ASSETS_PATH:-"$ROOT_DIR/app/android/app/src/main/assets/pyenv"}
JNI_LIBS_PATH=${JNI_LIBS_PATH:-"$ROOT_DIR/app/android/app/src/main/jniLibs"}
SDK_DIR=${SDK_DIR:-""}
NDK_DIR=${NDK_DIR:-""}
ARCH=${ARCH:-"arm64-v8a"}
WORK_DIR=${WORK_DIR:-"/tmp/p4a_build_out"}
DIST_NAME=${DIST_NAME:-"kugutz"}
REQUIREMENTS=${REQUIREMENTS:-"python3,fastapi==0.99.1,starlette==0.27.0,pydantic==1.10.13,typing-extensions,anyio==3.7.1,sniffio,uvicorn==0.23.2,click,h11,requests,charset-normalizer,idna,urllib3,certifi"}
ANDROID_API=${ANDROID_API:-"34"}

if [[ -z "$SDK_DIR" || -z "$NDK_DIR" ]]; then
  echo "SDK_DIR and NDK_DIR must be set." >&2
  exit 1
fi

export UV_CACHE_DIR="${UV_CACHE_DIR:-$WORK_DIR/.uv-cache}"
VENV_DIR="$WORK_DIR/.venv-p4a"
uv venv --clear "$VENV_DIR"
source "$VENV_DIR/bin/activate"
uv pip install --upgrade pip python-for-android "cython<3"

run_p4a() {
  p4a create \
  --requirements="$1" \
  --arch="$ARCH" \
  --android-api="$ANDROID_API" \
  --sdk-dir="$SDK_DIR" \
  --ndk-dir="$NDK_DIR" \
  --bootstrap=service_only \
  --private "$WORK_DIR" \
  --dist_name "$DIST_NAME" \
  --package jp.espresso3389.kugutz
}

run_p4a "$REQUIREMENTS"

# Try to locate the built python runtime.
P4A_DIST_ROOT=${P4A_DIST_ROOT:-"$HOME/.local/share/python-for-android/dists"}
DIST_ROOT="$P4A_DIST_ROOT/$DIST_NAME"
PY_RUNTIME_DIR="$DIST_ROOT/_python_bundle__${ARCH}/_python_bundle"
DIST_LIBS_DIR="$DIST_ROOT/libs/$ARCH"
if [[ ! -d "$PY_RUNTIME_DIR" ]]; then
  echo "Could not find python bundle at $PY_RUNTIME_DIR" >&2
  echo "Set P4A_DIST_ROOT if your python-for-android dist root differs from $P4A_DIST_ROOT" >&2
  exit 2
fi

rm -rf "$ASSETS_PATH"
mkdir -p "$ASSETS_PATH"
cp -a "$PY_RUNTIME_DIR"/* "$ASSETS_PATH"/

STAMP="${P4A_RUNTIME_STAMP:-$(date -u +%Y%m%d%H%M%S)}"
echo "$STAMP" > "$ASSETS_PATH/.runtime_stamp"

if [[ ! -f "$ASSETS_PATH/stdlib.zip" ]]; then
  echo "Copy failed: stdlib.zip not found in $ASSETS_PATH" >&2
  exit 4
fi

if [[ -d "$DIST_LIBS_DIR" ]]; then
  mkdir -p "$JNI_LIBS_PATH/$ARCH"
  cp -a "$DIST_LIBS_DIR"/* "$JNI_LIBS_PATH/$ARCH"/
fi

echo "Python bundle copied to: $ASSETS_PATH"
