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
- `WORK_DIR` (default: /tmp/p4a_build_out)
- `DIST_NAME` (default: kugutz)

## build_p4a.ps1
Windows placeholder that reminds you to build in Linux/WSL and copy the runtime.

## smoke_test.py
Simple localhost smoke test for the Python service. Run after `python server/app.py`.

## Notes
- Python-for-Android is most reliable on Linux; use WSL if on Windows.
- Ensure `pysqlcipher3` is included in requirements for SQLCipher support.
- Python tooling should use uv/venv (avoid system pip).
- `build_p4a.sh` needs internet access to download build dependencies.
- If SQLCipher headers are missing, the build retries without `pysqlcipher3`.
