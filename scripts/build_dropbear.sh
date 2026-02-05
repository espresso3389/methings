#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ASSETS_DIR="$ROOT_DIR/app/android/app/src/main/assets/bin"
JNI_DIR="$ROOT_DIR/app/android/app/src/main/jniLibs"
WORK_DIR="$ROOT_DIR/.dropbear-build"
SRC_DIR="$ROOT_DIR/third_party/dropbear"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT/ndk" ]]; then
    ANDROID_NDK_HOME=$(ls -1d "$ANDROID_SDK_ROOT/ndk"/* 2>/dev/null | sort -V | tail -n 1)
    export ANDROID_NDK_HOME
  fi
fi

if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "$ANDROID_NDK_HOME" ]]; then
  echo "ANDROID_NDK_HOME not set and NDK not found under ANDROID_SDK_ROOT" >&2
  exit 1
fi

API_LEVEL=${DROPBEAR_ANDROID_API:-21}

mkdir -p "$WORK_DIR"

if [[ ! -d "$SRC_DIR/.git" ]]; then
  echo "Missing dropbear submodule at $SRC_DIR" >&2
  echo "Run: git submodule update --init --recursive third_party/dropbear" >&2
  exit 1
fi

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
if [[ ! -d "$TOOLCHAIN" ]]; then
  echo "NDK toolchain not found at $TOOLCHAIN" >&2
  exit 1
fi

write_localoptions() {
  cat > localoptions.h <<'OPT'
#define DROPBEAR_SVR_PASSWORD_AUTH 1
#define DROPBEAR_SVR_PASSWORD_AUTH_PIN_ONLY 1
#define DROPBEAR_SVR_PAM_AUTH 0
#define DROPBEAR_SVR_PUBKEY_AUTH 1
#define DROPBEAR_SVR_MULTIUSER 1
#define DROPBEAR_CLI_PASSWORD_AUTH 0
#define DROPBEAR_SVR_AGENTFWD 0
#define DEBUG_TRACE 1
OPT
}

build_one() {
  local abi="$1"
  local triple="$2"
  local out_dir="$ASSETS_DIR/$abi"
  local jni_out="$JNI_DIR/$abi"
  local build_dir="$WORK_DIR/build-$abi"
  local base_commit=""

  rm -rf "$build_dir"
  mkdir -p "$build_dir"
  base_commit=$(git -C "$SRC_DIR" rev-list --max-parents=0 HEAD | tail -n 1)
  if [[ -n "$base_commit" ]] \
    && git -C "$SRC_DIR" diff --quiet \
    && [[ -z "$(git -C "$SRC_DIR" status --porcelain)" ]]; then
    echo "Using clean submodule source for $abi build"
  else
    echo "Using modified submodule source for $abi build"
  fi

  cp -R "$SRC_DIR"/. "$build_dir/"

  pushd "$build_dir" >/dev/null

  export CC="$TOOLCHAIN/bin/${triple}${API_LEVEL}-clang"
  export AR="$TOOLCHAIN/bin/llvm-ar"
  export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  export STRIP="$TOOLCHAIN/bin/llvm-strip"
  export CFLAGS="-fPIE"
  export LDFLAGS="-pie"

  write_localoptions

  ./configure \
    --host="$triple" \
    --disable-zlib \
    --disable-lastlog \
    --disable-utmp \
    --disable-utmpx \
    --disable-wtmp \
    --disable-wtmpx \
    --disable-pututline \
    --disable-pututxline \
    --disable-loginfunc \
    --disable-syslog

  make PROGRAMS="dropbear dropbearkey"

  local dropbear_bin="dropbear"
  if [[ ! -f "$dropbear_bin" && -f "dropbearmulti" ]]; then
    dropbear_bin="dropbearmulti"
  fi
  if [[ ! -f "$dropbear_bin" ]]; then
    echo "Dropbear binary not found after build" >&2
    exit 1
  fi

  if [[ ! -f "dropbearkey" ]]; then
    echo "dropbearkey binary not found after build" >&2
    exit 1
  fi

  mkdir -p "$out_dir"
  cp -f "$dropbear_bin" "$out_dir/dropbear"
  cp -f "dropbearkey" "$out_dir/dropbearkey"
  "$STRIP" "$out_dir/dropbear" || true
  "$STRIP" "$out_dir/dropbearkey" || true
  chmod 0755 "$out_dir/dropbear" "$out_dir/dropbearkey"

  mkdir -p "$jni_out"
  cp -f "$dropbear_bin" "$jni_out/libdropbear.so"
  cp -f "dropbearkey" "$jni_out/libdropbearkey.so"
  "$STRIP" "$jni_out/libdropbear.so" || true
  "$STRIP" "$jni_out/libdropbearkey.so" || true
  chmod 0755 "$jni_out/libdropbear.so" "$jni_out/libdropbearkey.so"

  popd >/dev/null
}

build_one arm64-v8a aarch64-linux-android
build_one armeabi-v7a armv7a-linux-androideabi
build_one x86 i686-linux-android
build_one x86_64 x86_64-linux-android

echo "Dropbear binaries installed to $ASSETS_DIR and $JNI_DIR"
