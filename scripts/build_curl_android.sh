#!/usr/bin/env bash
set -euo pipefail

# Build curl CLI for Android using mbedTLS as TLS backend.
# Output: jniLibs/$ABI/libcurl-cli.so (PIE executable in .so disguise)
#
# Pattern follows build_dropbear.sh for NDK auto-detection.

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
JNI_DIR="$ROOT_DIR/app/android/app/src/main/jniLibs"
THIRD_PARTY="$ROOT_DIR/third_party"
WORK_DIR="$ROOT_DIR/.curl-build"

MBEDTLS_VERSION="${MBEDTLS_VERSION:-3.6.2}"
CURL_VERSION="${CURL_VERSION:-8.11.1}"

MBEDTLS_TARBALL="mbedtls-${MBEDTLS_VERSION}.tar.bz2"
CURL_TARBALL="curl-${CURL_VERSION}.tar.gz"

# --- NDK auto-detection (same as build_dropbear.sh) ---
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  if [[ -n "${NDK_DIR:-}" && -d "${NDK_DIR}" ]]; then
    ANDROID_NDK_HOME="${NDK_DIR}"
  fi
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  if [[ -n "${ANDROID_NDK_ROOT:-}" && -d "${ANDROID_NDK_ROOT}" ]]; then
    ANDROID_NDK_HOME="${ANDROID_NDK_ROOT}"
  fi
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -n "$SDK_ROOT" && -d "$SDK_ROOT/ndk" ]]; then
    ANDROID_NDK_HOME=$(ls -1d "$SDK_ROOT/ndk"/* 2>/dev/null | sort -V | tail -n 1)
  fi
fi

if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "$ANDROID_NDK_HOME" ]]; then
  echo "ANDROID_NDK_HOME not set and NDK not found under ANDROID_SDK_ROOT" >&2
  exit 1
fi

API_LEVEL="${ANDROID_API:-21}"

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
if [[ ! -d "$TOOLCHAIN" ]]; then
  echo "NDK toolchain not found at $TOOLCHAIN" >&2
  exit 1
fi

mkdir -p "$THIRD_PARTY" "$WORK_DIR"

# --- Download sources ---
download_mbedtls() {
  local dst="$THIRD_PARTY/$MBEDTLS_TARBALL"
  if [[ -f "$dst" ]]; then
    echo "mbedTLS tarball already cached"
    return
  fi
  echo "Downloading mbedTLS ${MBEDTLS_VERSION}..."
  curl -fSL "https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-${MBEDTLS_VERSION}/${MBEDTLS_TARBALL}" -o "$dst.tmp"
  mv "$dst.tmp" "$dst"
}

download_curl() {
  local dst="$THIRD_PARTY/$CURL_TARBALL"
  if [[ -f "$dst" ]]; then
    echo "curl tarball already cached"
    return
  fi
  echo "Downloading curl ${CURL_VERSION}..."
  curl -fSL "https://curl.se/download/${CURL_TARBALL}" -o "$dst.tmp"
  mv "$dst.tmp" "$dst"
}

download_mbedtls
download_curl

# --- ABI mapping ---
abi_to_triple() {
  case "$1" in
    arm64-v8a)   echo "aarch64-linux-android";;
    armeabi-v7a) echo "armv7a-linux-androideabi";;
    x86)         echo "i686-linux-android";;
    x86_64)      echo "x86_64-linux-android";;
    *) echo "Unsupported ABI: $1" >&2; return 1;;
  esac
}

abi_to_cmake_arch() {
  case "$1" in
    arm64-v8a)   echo "aarch64";;
    armeabi-v7a) echo "arm";;
    x86)         echo "x86";;
    x86_64)      echo "x86_64";;
    *) return 1;;
  esac
}

# --- Build one ABI ---
build_one() {
  local abi="$1"
  local triple
  triple="$(abi_to_triple "$abi")"
  local build_dir="$WORK_DIR/build-$abi"
  local prefix="$WORK_DIR/prefix-$abi"
  local jni_out="$JNI_DIR/$abi"

  rm -rf "$build_dir" "$prefix"
  mkdir -p "$build_dir/mbedtls" "$build_dir/curl" "$prefix"

  local CC="$TOOLCHAIN/bin/${triple}${API_LEVEL}-clang"
  local AR="$TOOLCHAIN/bin/llvm-ar"
  local RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  local STRIP="$TOOLCHAIN/bin/llvm-strip"

  # --- Build mbedTLS ---
  echo "=== Building mbedTLS for $abi ==="
  tar xf "$THIRD_PARTY/$MBEDTLS_TARBALL" -C "$build_dir/mbedtls" --strip-components=1

  cmake -S "$build_dir/mbedtls" -B "$build_dir/mbedtls-build" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_NATIVE_API_LEVEL="$API_LEVEL" \
    -DCMAKE_INSTALL_PREFIX="$prefix" \
    -DENABLE_PROGRAMS=OFF \
    -DENABLE_TESTING=OFF \
    -DUSE_SHARED_MBEDTLS_LIBRARY=OFF \
    -DUSE_STATIC_MBEDTLS_LIBRARY=ON \
    -DCMAKE_C_FLAGS="-fPIE" \
    -DCMAKE_BUILD_TYPE=Release

  cmake --build "$build_dir/mbedtls-build" -j"$(nproc)"
  cmake --install "$build_dir/mbedtls-build"

  # --- Build curl ---
  echo "=== Building curl for $abi ==="
  tar xf "$THIRD_PARTY/$CURL_TARBALL" -C "$build_dir/curl" --strip-components=1

  pushd "$build_dir/curl" >/dev/null

  export CC AR RANLIB STRIP
  export CFLAGS="-fPIE -I${prefix}/include"
  export LDFLAGS="-pie -L${prefix}/lib"
  export LIBS="-lmbedtls -lmbedx509 -lmbedcrypto"
  export PKG_CONFIG_PATH="${prefix}/lib/pkgconfig"

  ./configure \
    --host="$triple" \
    --prefix="$prefix" \
    --with-mbedtls="$prefix" \
    --without-openssl \
    --without-gnutls \
    --without-wolfssl \
    --without-bearssl \
    --without-rustls \
    --without-libpsl \
    --without-brotli \
    --without-zstd \
    --without-nghttp2 \
    --without-libidn2 \
    --without-librtmp \
    --without-libssh2 \
    --disable-shared \
    --enable-static \
    --disable-ldap \
    --disable-ldaps \
    --disable-rtsp \
    --disable-dict \
    --disable-telnet \
    --disable-tftp \
    --disable-pop3 \
    --disable-imap \
    --disable-smb \
    --disable-smtp \
    --disable-gopher \
    --disable-mqtt \
    --disable-manual \
    --disable-docs \
    --enable-ipv6 \
    --enable-unix-sockets

  make -j"$(nproc)" V=1

  popd >/dev/null

  # --- Install ---
  local curl_bin="$build_dir/curl/src/curl"
  if [[ ! -f "$curl_bin" ]]; then
    echo "curl binary not found after build" >&2
    exit 1
  fi

  mkdir -p "$jni_out"
  cp -f "$curl_bin" "$jni_out/libcurl-cli.so"
  "$STRIP" "$jni_out/libcurl-cli.so"
  chmod 0755 "$jni_out/libcurl-cli.so"

  echo "=== Installed $jni_out/libcurl-cli.so ==="
}

# --- Main ---
ABIS="${ABIS:-arm64-v8a}"
for abi in $ABIS; do
  build_one "$abi"
done

echo "curl CLI built successfully for: $ABIS"
