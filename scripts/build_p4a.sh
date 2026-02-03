#!/usr/bin/env bash
set -euo pipefail

ASSETS_PATH=${ASSETS_PATH:-"$(pwd)/app/android/app/src/main/assets/pyenv"}
JNI_LIBS_PATH=${JNI_LIBS_PATH:-"$(pwd)/app/android/app/src/main/jniLibs"}
SDK_DIR=${SDK_DIR:-""}
NDK_DIR=${NDK_DIR:-""}
ARCH=${ARCH:-"arm64-v8a"}
WORK_DIR=${WORK_DIR:-"/tmp/p4a_build_out"}
DIST_NAME=${DIST_NAME:-"kugutz"}
REQUIREMENTS=${REQUIREMENTS:-"python3,fastapi==0.99.1,starlette==0.27.0,pydantic==1.10.13,typing-extensions,anyio==3.7.1,sniffio,uvicorn==0.23.2,click,h11,requests,charset-normalizer,idna,urllib3,certifi,sqlcipher,pysqlcipher3"}
TCLSH_CMD=${TCLSH_CMD:-""}

if [[ -z "$SDK_DIR" || -z "$NDK_DIR" ]]; then
  echo "SDK_DIR and NDK_DIR must be set." >&2
  exit 1
fi

export UV_CACHE_DIR="${UV_CACHE_DIR:-$WORK_DIR/.uv-cache}"
VENV_DIR="$WORK_DIR/.venv-p4a"

if [[ -z "$TCLSH_CMD" ]]; then
  if command -v tclsh >/dev/null 2>&1; then
    TCLSH_CMD="$(command -v tclsh)"
  elif command -v tclsh8.6 >/dev/null 2>&1; then
    TCLSH_CMD="$(command -v tclsh8.6)"
  fi
fi

if [[ -z "$TCLSH_CMD" ]]; then
  TCL_PREFIX="$WORK_DIR/tcl"
  TCLSH_CMD="$TCL_PREFIX/bin/tclsh8.6"
  if [[ ! -x "$TCLSH_CMD" ]]; then
    echo "tclsh not found; building a local Tcl in $TCL_PREFIX" >&2
    TCL_TARBALL="$WORK_DIR/tcl8.6.14-src.tar.gz"
    curl -L -o "$TCL_TARBALL" https://prdownloads.sourceforge.net/tcl/tcl8.6.14-src.tar.gz
    tar -xf "$TCL_TARBALL" -C "$WORK_DIR"
    pushd "$WORK_DIR/tcl8.6.14/unix" >/dev/null
    ./configure --prefix="$TCL_PREFIX"
    make -j"$(nproc)"
    make install
    popd >/dev/null
  fi
fi

export TCLSH_CMD
uv venv --clear "$VENV_DIR"
source "$VENV_DIR/bin/activate"
uv pip install --upgrade pip python-for-android "cython<3"

run_p4a() {
  p4a create \
  --requirements="$1" \
  --arch="$ARCH" \
  --local-recipes "$(pwd)/scripts/recipes" \
  --sdk-dir="$SDK_DIR" \
  --ndk-dir="$NDK_DIR" \
  --bootstrap=service_only \
  --private "$WORK_DIR" \
  --dist_name "$DIST_NAME" \
  --package jp.espresso3389.kugutz
}

P4A_STATUS=0
set +e
run_p4a "$REQUIREMENTS"
P4A_STATUS=$?
if [[ $P4A_STATUS -ne 0 ]]; then
  echo "Primary build failed. Retrying without pysqlcipher3..." >&2
  FALLBACK_REQS=$(echo "$REQUIREMENTS" | sed 's/,pysqlcipher3//g' | sed 's/,sqlcipher//g')
  run_p4a "$FALLBACK_REQS"
  P4A_STATUS=$?
fi
set -e

if [[ $P4A_STATUS -ne 0 ]]; then
  exit "$P4A_STATUS"
fi

# Try to locate the built python runtime.
PY_RUNTIME_DIR="/home/kawasaki/.local/share/python-for-android/dists/$DIST_NAME/_python_bundle__${ARCH}/_python_bundle"
DIST_LIBS_DIR="/home/kawasaki/.local/share/python-for-android/dists/$DIST_NAME/libs/$ARCH"
if [[ ! -d "$PY_RUNTIME_DIR" ]]; then
  echo "Could not find python bundle at $PY_RUNTIME_DIR" >&2
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
