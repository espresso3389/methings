#!/usr/bin/env bash
set -euo pipefail

# Build llama.cpp Android binaries from pinned submodule and stage into jniLibs.
#
# Env:
#   LLAMA_ABI=arm64-v8a                 (default: arm64-v8a)
#   LLAMA_ANDROID_PLATFORM=android-26   (default: android-26)
#   LLAMA_BUILD_DIR=.native-build/llama-android/arm64-v8a
#   LLAMA_ENABLE_VULKAN=0|1             (default: 0)

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LLAMA_SRC_DIR="$ROOT_DIR/third_party/llama.cpp"

LLAMA_ABI="${LLAMA_ABI:-arm64-v8a}"
LLAMA_ANDROID_PLATFORM="${LLAMA_ANDROID_PLATFORM:-android-26}"
LLAMA_ENABLE_VULKAN="${LLAMA_ENABLE_VULKAN:-0}"
LLAMA_BUILD_DIR="${LLAMA_BUILD_DIR:-$ROOT_DIR/.native-build/llama-android/$LLAMA_ABI}"

SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
if [[ ! -d "$SDK_DIR" ]]; then
  echo "error: Android SDK not found. Set ANDROID_SDK_ROOT or ANDROID_HOME." >&2
  exit 2
fi

if [[ ! -e "$LLAMA_SRC_DIR/.git" ]]; then
  echo "error: llama.cpp submodule missing at $LLAMA_SRC_DIR" >&2
  echo "hint: git submodule update --init --recursive third_party/llama.cpp" >&2
  exit 2
fi

NDK_DIR="$(ls -d "$SDK_DIR"/ndk/* 2>/dev/null | sort -V | tail -n1 || true)"
if [[ -z "$NDK_DIR" || ! -d "$NDK_DIR" ]]; then
  echo "error: Android NDK not found under $SDK_DIR/ndk" >&2
  exit 2
fi
TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake"
if [[ ! -f "$TOOLCHAIN_FILE" ]]; then
  echo "error: NDK toolchain file missing: $TOOLCHAIN_FILE" >&2
  exit 2
fi

if [[ "$LLAMA_ENABLE_VULKAN" == "1" ]] && ! command -v glslc >/dev/null 2>&1; then
  echo "error: Vulkan build requested but 'glslc' is not installed on host." >&2
  echo "hint: install Vulkan SDK (shader compiler) and ensure glslc is in PATH." >&2
  exit 2
fi

if [[ "$LLAMA_ENABLE_VULKAN" == "1" ]]; then
  NDK_VULKAN_HPP="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/vulkan/vulkan.hpp"
  if [[ ! -f "$NDK_VULKAN_HPP" ]]; then
    echo "error: Vulkan build requested but this Android NDK does not provide C++ Vulkan header (vulkan/vulkan.hpp)." >&2
    echo "hint: use an NDK/toolchain setup with vulkan.hpp support for llama.cpp Vulkan builds." >&2
    exit 2
  fi
fi

mkdir -p "$LLAMA_BUILD_DIR"

echo "[llama] configure ABI=$LLAMA_ABI platform=$LLAMA_ANDROID_PLATFORM vulkan=$LLAMA_ENABLE_VULKAN"
cmake -S "$LLAMA_SRC_DIR" -B "$LLAMA_BUILD_DIR" \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
  -DANDROID_ABI="$LLAMA_ABI" \
  -DANDROID_PLATFORM="$LLAMA_ANDROID_PLATFORM" \
  -DLLAMA_BUILD_SERVER=ON \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_BORINGSSL=ON \
  -DGGML_VULKAN="$LLAMA_ENABLE_VULKAN"

echo "[llama] build"
cmake --build "$LLAMA_BUILD_DIR" --target llama-cli llama-tts -j"$(nproc)"

OUT_DIR="$ROOT_DIR/app/android/app/src/main/jniLibs/$LLAMA_ABI"
mkdir -p "$OUT_DIR"
cp -f "$LLAMA_BUILD_DIR/bin/llama-cli"       "$OUT_DIR/libllama-cli.so"
cp -f "$LLAMA_BUILD_DIR/bin/llama-tts"       "$OUT_DIR/libllama-tts.so"
cp -f "$LLAMA_BUILD_DIR/bin/libllama.so"     "$OUT_DIR/libllama.so"
cp -f "$LLAMA_BUILD_DIR/bin/libggml.so"      "$OUT_DIR/libggml.so"
cp -f "$LLAMA_BUILD_DIR/bin/libggml-cpu.so"  "$OUT_DIR/libggml-cpu.so"
cp -f "$LLAMA_BUILD_DIR/bin/libggml-base.so" "$OUT_DIR/libggml-base.so"
cp -f "$LLAMA_BUILD_DIR/bin/libmtmd.so"      "$OUT_DIR/libmtmd.so"
if [[ "$LLAMA_ENABLE_VULKAN" == "1" && -f "$LLAMA_BUILD_DIR/bin/libggml-vulkan.so" ]]; then
  cp -f "$LLAMA_BUILD_DIR/bin/libggml-vulkan.so" "$OUT_DIR/libggml-vulkan.so"
fi

echo "[llama] staged: $OUT_DIR"
ls -lh "$OUT_DIR" | grep -E 'lib(llama|ggml|mtmd|omp|vulkan)' || true
