#!/usr/bin/env bash
set -euo pipefail

NDK_DIR="${NDK_DIR:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK_DIR" ]]; then
  # Try to auto-detect from SDK
  SDK_ROOT="${ANDROID_HOME:-${HOME}/Android/Sdk}"
  if [[ -d "$SDK_ROOT/ndk" ]]; then
    NDK_DIR="$(ls -d "$SDK_ROOT/ndk"/*/ 2>/dev/null | sort -V | tail -1)"
    NDK_DIR="${NDK_DIR%/}"
  fi
  if [[ -z "$NDK_DIR" ]]; then
    echo "NDK_DIR or ANDROID_NDK_ROOT must be set." >&2
    exit 1
  fi
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT_DIR/scripts/methingspy.c"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/app/android/app/src/main/jniLibs}"
API="${ANDROID_API:-21}"
# Keep the app lean: for now we only support modern Android devices (arm64).
# Override with ABIS=... if you explicitly need other architectures.
ABIS="${ABIS:-arm64-v8a}"

TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
if [[ ! -d "$TOOLCHAIN" ]]; then
  echo "NDK toolchain not found at $TOOLCHAIN" >&2
  exit 1
fi

if [[ ! -f "$SRC" ]]; then
  echo "Missing source at $SRC" >&2
  exit 1
fi

abi_to_triple() {
  case "$1" in
    arm64-v8a) echo "aarch64-linux-android";;
    armeabi-v7a) echo "armv7a-linux-androideabi";;
    x86) echo "i686-linux-android";;
    x86_64) echo "x86_64-linux-android";;
    *) return 1;;
  esac
}

for ABI in $ABIS; do
  triple="$(abi_to_triple "$ABI")"
  out="$OUT_DIR/$ABI/libmethingspy.so"
  mkdir -p "$OUT_DIR/$ABI"
  "$TOOLCHAIN/bin/${triple}${API}-clang" \
    -fPIE -pie -O2 -s \
    -ldl \
    "$SRC" \
    -o "$out"
  chmod 0755 "$out"
  echo "Built $out"
done
