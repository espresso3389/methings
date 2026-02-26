#!/usr/bin/env bash
set -euo pipefail

# Fetch a Node.js (Android/bionic) runtime from the Termux repository and stage it into the
# Android project for methings.
#
# Why Termux:
# - Provides Android/bionic aarch64 builds.
# - Node + npm are packaged, self-contained, and reasonably up to date.
#
# Output (defaults):
# - Native binary + shared libs: app/android/app/src/main/jniLibs/arm64-v8a/
#   - libnode.so (Node executable, packaged like other shell tools)
#   - libssl.so.3, libcrypto.so.3, libc++_shared.so, libz.so.1, libcares.so, libicu*.so.78
# - JS tooling (npm + corepack): app/android/app/src/main/assets/node/usr/lib/node_modules/
# - Version marker: app/android/app/src/main/assets/node/.version
#
# Opt-in:
# - This script performs network I/O and increases APK size. Run explicitly.

ROOT_DIR=${ROOT_DIR:-"$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"}

TERMUX_REPO_BASE=${TERMUX_REPO_BASE:-"https://termux.librehat.com/apt/termux-main"}
ARCH=${ARCH:-"aarch64"}
ABI=${ABI:-"arm64-v8a"}
NODE_PKG=${NODE_PKG:-"nodejs-lts"}   # or "nodejs"

OUT_JNI=${OUT_JNI:-"$ROOT_DIR/app/android/app/src/main/jniLibs/$ABI"}
OUT_ASSETS=${OUT_ASSETS:-"$ROOT_DIR/app/android/app/src/main/assets/node"}
OUT_NODELIB=${OUT_NODELIB:-"$OUT_ASSETS/lib"}

PACKAGES_URL="$TERMUX_REPO_BASE/dists/stable/main/binary-${ARCH}/Packages.gz"

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "missing required command: $1" >&2; exit 2; }
}

need_cmd curl
need_cmd gzip
need_cmd ar
need_cmd tar
need_cmd python3
need_cmd readelf
need_cmd grep

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "Downloading Termux package index: $PACKAGES_URL" >&2
curl -fsSL "$PACKAGES_URL" -o "$tmp/Packages.gz"
gzip -dc "$tmp/Packages.gz" > "$tmp/Packages"

pkg_field() {
  local pkg="$1"
  local field="$2"
  python3 - "$tmp/Packages" "$pkg" "$field" <<'PY'
import re,sys
path, pkg, field = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(path, "r", encoding="utf-8", errors="replace").read()
for st in text.split("\n\n"):
    if st.startswith(f"Package: {pkg}\n"):
        m = re.search(rf"^{re.escape(field)}: (.+)$", st, re.M)
        if m:
            print(m.group(1))
        break
PY
}

pkg_filename() { pkg_field "$1" "Filename"; }
pkg_version() { pkg_field "$1" "Version"; }

node_ver="$(pkg_version "$NODE_PKG")"
node_fn="$(pkg_filename "$NODE_PKG")"
if [[ -z "$node_ver" || -z "$node_fn" ]]; then
  echo "could not resolve package $NODE_PKG in Termux index" >&2
  exit 3
fi

npm_ver="$(pkg_version "npm")"
npm_fn="$(pkg_filename "npm")"
if [[ -z "$npm_ver" || -z "$npm_fn" ]]; then
  echo "could not resolve package npm in Termux index" >&2
  exit 3
fi

deps=(openssl c-ares libicu zlib libc++ libsqlite)
# NOTE: We stage Termux's libsqlite3 into assets/node/lib (not jniLibs) so Node can
# load a compatible sqlite without interfering with the app's embedded Python runtime.

echo "Resolved:" >&2
echo "  $NODE_PKG: $node_ver ($node_fn)" >&2
echo "  npm: $npm_ver ($npm_fn)" >&2

download_deb() {
  local rel="$1"
  local out="$2"
  if [[ -f "$out" ]]; then
    return 0
  fi
  curl -fsSL "$TERMUX_REPO_BASE/$rel" -o "$out"
}

extract_deb() {
  local deb="$1"
  local outdir="$2"
  rm -rf "$outdir"
  mkdir -p "$outdir"
  (cd "$outdir" && ar x "$deb" && mkdir -p data && tar -C data -xf data.tar.xz)
}

mkdir -p "$tmp/debs"
mkdir -p "$OUT_JNI"
mkdir -p "$OUT_ASSETS"
rm -rf "$OUT_NODELIB"
mkdir -p "$OUT_NODELIB"

download_deb "$node_fn" "$tmp/debs/node.deb"
download_deb "$npm_fn" "$tmp/debs/npm.deb"

for d in "${deps[@]}"; do
  fn="$(pkg_filename "$d")"
  ver="$(pkg_version "$d")"
  if [[ -z "$fn" || -z "$ver" ]]; then
    echo "could not resolve dep package $d" >&2
    exit 3
  fi
  echo "  dep $d: $ver ($fn)" >&2
  download_deb "$fn" "$tmp/debs/$d.deb"
done

extract_deb "$tmp/debs/node.deb" "$tmp/node"
extract_deb "$tmp/debs/npm.deb" "$tmp/npm"

prefix_node="$tmp/node/data/data/data/com.termux/files/usr"
prefix_npm="$tmp/npm/data/data/data/com.termux/files/usr"

node_bin="$prefix_node/bin/node"
if [[ ! -f "$node_bin" ]]; then
  echo "node binary not found in package: $node_bin" >&2
  exit 4
fi

echo "Staging node binary -> $OUT_JNI/libnode.so" >&2
cp -a "$node_bin" "$OUT_JNI/libnode.so"
chmod 0755 "$OUT_JNI/libnode.so"

# Stage corepack (shipped with Node) and npm (separate package) into assets.
dst_mod="$OUT_ASSETS/usr/lib/node_modules"
rm -rf "$dst_mod"
mkdir -p "$dst_mod"

if [[ -d "$prefix_node/lib/node_modules/corepack" ]]; then
  echo "Staging corepack module -> assets/node/usr/lib/node_modules/corepack" >&2
  cp -a "$prefix_node/lib/node_modules/corepack" "$dst_mod/"
fi
if [[ -d "$prefix_npm/lib/node_modules/npm" ]]; then
  echo "Staging npm module -> assets/node/usr/lib/node_modules/npm" >&2
  cp -a "$prefix_npm/lib/node_modules/npm" "$dst_mod/"
else
  echo "npm module not found in npm package" >&2
  exit 4
fi

stage_lib_dir() {
  local pkg="$1"
  extract_deb "$tmp/debs/$pkg.deb" "$tmp/$pkg"
  local pref="$tmp/$pkg/data/data/data/com.termux/files/usr"
  if [[ -d "$pref/lib" ]]; then
    find "$pref/lib" -maxdepth 1 -type f -name '*.so*' -print0 | while IFS= read -r -d '' f; do
      local base
      base="$(basename "$f")"
      # Prefer the app's existing C++ runtime to avoid surprising ABI differences across native deps.
      if [[ "$base" == "libc++_shared.so" ]]; then
        continue
      fi
      # Do not overwrite existing libs unless we are explicitly staging a newer file into an empty slot.
      if [[ -f "$OUT_NODELIB/$base" ]]; then
        continue
      fi
      cp -a "$f" "$OUT_NODELIB/$base"
    done
  fi
}

for p in "${deps[@]}"; do
  stage_lib_dir "$p"
done

# Create "soname" aliases by copying the versioned file to the exact DT_NEEDED name.
# APK packaging does not preserve symlinks, and most DT_NEEDED names here are versioned
# (e.g. libz.so.1). We stage these under assets/node/lib and add that dir to LD_LIBRARY_PATH
# in the app shell wrappers.
alias_copy() {
  local want="$1"
  local src_glob="$2"
  local src
  if [[ -f "$OUT_NODELIB/$want" ]]; then
    return 0
  fi
  src="$(ls -1 "$OUT_NODELIB"/$src_glob 2>/dev/null | head -n 1 || true)"
  if [[ -z "$src" ]]; then
    echo "warning: could not satisfy $want (missing $src_glob)" >&2
    return 1
  fi
  cp -a "$src" "$OUT_NODELIB/$want"
}

alias_copy "libz.so.1" "libz.so.1.*"
alias_copy "libsqlite3.so" "libsqlite3.so.*" || alias_copy "libsqlite3.so" "libsqlite3.*.so"
alias_copy "libicudata.so.78" "libicudata.so.78.*"
alias_copy "libicui18n.so.78" "libicui18n.so.78.*"
alias_copy "libicuuc.so.78" "libicuuc.so.78.*"

# Basic sanity check: confirm node's DT_NEEDED libs exist (except libc/libm/libdl which are system).
echo "Sanity-checking DT_NEEDED for libnode.so" >&2
missing=0
while read -r line; do
  # readelf output can include extra spaces; trim result.
  need=$(echo "$line" | sed -n 's/.*\\[\\(.*\\)\\].*/\\1/p' | tr -d '[:space:]')
  if [[ -z "$need" ]]; then
    continue
  fi
  case "$need" in
    libc.so|libm.so|libdl.so|libc++_shared.so) continue;;
  esac
  if [[ ! -f "$OUT_NODELIB/$need" ]]; then
    echo "  missing: $need" >&2
    missing=1
  fi
done < <(readelf -d "$OUT_JNI/libnode.so" | grep -F 'NEEDED')
if [[ "$missing" -ne 0 ]]; then
  echo "DT_NEEDED sanity-check failed; try adding missing dependency packages." >&2
  exit 5
fi

cat > "$OUT_ASSETS/.version" <<EOF
layout_ver=4
node_pkg=$NODE_PKG
node_ver=$node_ver
npm_ver=$npm_ver
EOF

echo "Done. Staged Node runtime:" >&2
echo "  Native: $OUT_JNI/libnode.so" >&2
echo "  Assets: $OUT_ASSETS/usr/lib/node_modules" >&2
echo "  Deps:   $OUT_NODELIB" >&2
