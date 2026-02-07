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
