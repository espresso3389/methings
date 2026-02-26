#!/usr/bin/env bash
set -euo pipefail

# Fetch pre-built CLI tools (bash, jq, ripgrep) from the Termux repository
# and stage them into the Android project for methings.
#
# Output (defaults):
#   jniLibs/$ABI/libbash.so      - GNU Bash executable
#   jniLibs/$ABI/libjq-cli.so    - jq executable
#   jniLibs/$ABI/librg.so        - ripgrep (rg) executable
#   assets/termux-tools/lib/     - shared library deps (readline, pcre2, etc.)
#   assets/termux-tools/.version - version marker
#
# These binaries are built by Termux against Android/bionic aarch64.
# Shared lib deps are staged into assets and loaded via LD_LIBRARY_PATH
# set by methings_run.c.

ROOT_DIR=${ROOT_DIR:-"$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"}

TERMUX_REPO_BASE=${TERMUX_REPO_BASE:-"https://termux.librehat.com/apt/termux-main"}
ARCH=${ARCH:-"aarch64"}
ABI=${ABI:-"arm64-v8a"}

OUT_JNI=${OUT_JNI:-"$ROOT_DIR/app/android/app/src/main/jniLibs/$ABI"}
OUT_ASSETS=${OUT_ASSETS:-"$ROOT_DIR/app/android/app/src/main/assets/termux-tools"}
OUT_LIB=${OUT_LIB:-"$OUT_ASSETS/lib"}

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

download_deb() {
  local rel="$1"
  local out="$2"
  if [[ -f "$out" ]]; then return 0; fi
  curl -fsSL "$TERMUX_REPO_BASE/$rel" -o "$out"
}

extract_deb() {
  local deb="$1"
  local outdir="$2"
  rm -rf "$outdir"
  mkdir -p "$outdir"
  (cd "$outdir" && ar x "$deb" && mkdir -p data && tar -C data -xf data.tar.xz)
}

termux_prefix() {
  echo "$1/data/data/data/com.termux/files/usr"
}

# --- Resolve all packages ---

# Tools we want to ship
tools=(bash jq ripgrep)

# Shared library deps (union of all tools' deps)
deps=(readline libandroid-support libiconv ncurses oniguruma pcre2)

echo "Resolving packages:" >&2
declare -A tool_versions
for pkg in "${tools[@]}" "${deps[@]}"; do
  ver="$(pkg_version "$pkg")"
  fn="$(pkg_filename "$pkg")"
  if [[ -z "$ver" || -z "$fn" ]]; then
    echo "  FAILED to resolve: $pkg" >&2
    exit 3
  fi
  echo "  $pkg: $ver ($fn)" >&2
  tool_versions[$pkg]="$ver"
done

# --- Download all debs ---

mkdir -p "$tmp/debs" "$OUT_JNI" "$OUT_ASSETS"
rm -rf "$OUT_LIB"
mkdir -p "$OUT_LIB"

for pkg in "${tools[@]}" "${deps[@]}"; do
  fn="$(pkg_filename "$pkg")"
  download_deb "$fn" "$tmp/debs/$pkg.deb"
done

# --- Stage tool binaries ---

stage_tool_binary() {
  local pkg="$1"
  local bin_name="$2"
  local so_name="$3"

  extract_deb "$tmp/debs/$pkg.deb" "$tmp/$pkg"
  local prefix
  prefix="$(termux_prefix "$tmp/$pkg")"
  local bin="$prefix/bin/$bin_name"
  if [[ ! -f "$bin" ]]; then
    echo "$bin_name binary not found in $pkg package: $bin" >&2
    exit 4
  fi
  echo "Staging $bin_name -> $OUT_JNI/$so_name" >&2
  cp -a "$bin" "$OUT_JNI/$so_name"
  chmod 0755 "$OUT_JNI/$so_name"
}

stage_tool_binary bash bash libbash.so
stage_tool_binary jq jq libjq-cli.so
stage_tool_binary ripgrep rg librg.so

# --- Stage shared lib deps ---

stage_lib_dir() {
  local pkg="$1"
  extract_deb "$tmp/debs/$pkg.deb" "$tmp/$pkg"
  local prefix
  prefix="$(termux_prefix "$tmp/$pkg")"
  if [[ -d "$prefix/lib" ]]; then
    find "$prefix/lib" -maxdepth 1 -type f -name '*.so*' -print0 | while IFS= read -r -d '' f; do
      local base
      base="$(basename "$f")"
      # Skip libc++ to avoid ABI conflicts with the app's own runtime
      if [[ "$base" == "libc++_shared.so" ]]; then continue; fi
      if [[ -f "$OUT_LIB/$base" ]]; then continue; fi
      cp -a "$f" "$OUT_LIB/$base"
    done
  fi
}

for p in "${tools[@]}" "${deps[@]}"; do
  stage_lib_dir "$p"
done

# Create soname aliases (APK packaging strips symlinks)
alias_copy() {
  local want="$1"
  local src_glob="$2"
  if [[ -f "$OUT_LIB/$want" ]]; then return 0; fi
  local src
  src="$(ls -1 "$OUT_LIB"/$src_glob 2>/dev/null | head -n 1 || true)"
  if [[ -z "$src" ]]; then
    echo "warning: could not satisfy $want (missing $src_glob)" >&2
    return 1
  fi
  cp -a "$src" "$OUT_LIB/$want"
}

alias_copy "libreadline.so.8" "libreadline.so.8.*"
alias_copy "libncursesw.so.6" "libncursesw.so.6.*"
alias_copy "libonig.so.5" "libonig.so"

# --- Sanity-check DT_NEEDED for each tool ---

check_needed() {
  local label="$1"
  local binary="$2"
  echo "Sanity-checking DT_NEEDED for $label" >&2
  local missing=0
  while IFS= read -r line; do
    local need
    need=$(echo "$line" | sed -n 's/.*\[\(.*\)\].*/\1/p' | tr -d '[:space:]')
    [[ -z "$need" ]] && continue
    case "$need" in
      libc.so|libm.so|libdl.so|libc++_shared.so|liblog.so) continue;;
    esac
    if [[ ! -f "$OUT_LIB/$need" ]]; then
      echo "  missing: $need" >&2
      missing=1
    fi
  done < <(readelf -d "$binary" | grep -F 'NEEDED')
  if [[ "$missing" -ne 0 ]]; then
    echo "DT_NEEDED sanity-check failed for $label" >&2
    exit 5
  fi
}

check_needed "libbash.so" "$OUT_JNI/libbash.so"
check_needed "libjq-cli.so" "$OUT_JNI/libjq-cli.so"
check_needed "librg.so" "$OUT_JNI/librg.so"

# --- Version marker ---

cat > "$OUT_ASSETS/.version" <<EOF
layout_ver=1
bash_ver=${tool_versions[bash]}
jq_ver=${tool_versions[jq]}
rg_ver=${tool_versions[ripgrep]}
EOF

echo "" >&2
echo "Done. Staged tools:" >&2
echo "  bash:    $OUT_JNI/libbash.so (${tool_versions[bash]})" >&2
echo "  jq:      $OUT_JNI/libjq-cli.so (${tool_versions[jq]})" >&2
echo "  rg:      $OUT_JNI/librg.so (${tool_versions[ripgrep]})" >&2
echo "  Deps:    $OUT_LIB" >&2
