# Scripts

## build_p4a.sh
Builds a Python-for-Android runtime (Linux/WSL) and copies it into:
`app/android/app/src/main/assets/pyenv`

### Required env vars
- `SDK_DIR` (Android SDK path)
- `NDK_DIR` (Android NDK path)

### Optional env vars
- `ARCH` (default: arm64-v8a)
- `ASSETS_PATH` (default: repo/app/android/app/src/main/assets/pyenv)
- `JNI_LIBS_PATH` (default: repo/app/android/app/src/main/jniLibs)
- `WORK_DIR` (default: /tmp/p4a_build_out)
- `DIST_NAME` (default: androidvivepython)

## smoke_test.py
Simple localhost smoke test for the Python service. Run after `python server/app.py`.

## Notes
- Python-for-Android is most reliable on Linux; use WSL if on Windows.
- Python tooling for on-device runtime should use venv + pip (avoid system pip).
- Host-side app development uses uv.
- `build_p4a.sh` needs internet access to download build dependencies.
- Native libs from the dist are copied into `app/android/app/src/main/jniLibs/<arch>/`.

## sync_p4a_dist.sh
Copies (syncs) an already-built python-for-android dist into the repo without rebuilding.

Use this if `jniLibs/` got out of sync (e.g. after a clean) and the app fails at runtime with:
`dlopen failed: library "libpython3.11.so" not found`.

The script copies:
- `.../dists/<DIST_NAME>/_python_bundle__<ARCH>/_python_bundle/*` -> `app/android/app/src/main/assets/pyenv/`
- `.../dists/<DIST_NAME>/libs/<ARCH>/*` -> `app/android/app/src/main/jniLibs/<ARCH>/`

## build_llama_android.sh
Builds `llama.cpp` Android binaries from the pinned submodule and stages them into:
`app/android/app/src/main/jniLibs/<abi>/`.

Defaults:
- Source: `third_party/llama.cpp` (submodule, fork branch `methings-miotts`)
- ABI: `arm64-v8a`

Important env vars:
- `LLAMA_ENABLE_VULKAN=1` to request Vulkan backend build (`0` by default)
- `LLAMA_BUILD_DIR=...` to override build output directory

Notes:
- Vulkan build requires `glslc` on host PATH (Vulkan SDK shader compiler).
- Vulkan build requires NDK/toolchain support for C++ Vulkan header (`vulkan/vulkan.hpp`).
- Script uses Android SDK/NDK from `ANDROID_SDK_ROOT` (or `ANDROID_HOME`, fallback `$HOME/Android/Sdk`).
