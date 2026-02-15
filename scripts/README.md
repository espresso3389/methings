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

## me_sync_adb_run.sh
Runs a real me.sync v3 export/import flow between two Android devices over ADB
without QR scanning.

It performs:
1. `ticket/create` on exporter
2. passes `ticket_uri` via host script
3. `import/apply` on importer

Usage:

```bash
scripts/me_sync_adb_run.sh \
  --exporter-serial <serial-a> \
  --importer-serial <serial-b>
```

Optional:
- `--auto-allow` to run best-effort permission auto tap on both devices
- `--nearby-timeout-ms <ms>`
- `--nearby-max-bytes <bytes>`
- `--max-bytes <bytes>`
- `--allow-fallback true|false`
- `--wipe-existing true|false`

## me_sync_adb_regression.sh
Runs a two-case regression test for real me.sync transfer over ADB:
1. `wifi_on`
2. `wifi_off`

For each case it executes `me_sync_adb_run.sh`, then saves:
- `<out-dir>/wifi_on.log`, `<out-dir>/wifi_off.log`
- `<out-dir>/wifi_on.json`, `<out-dir>/wifi_off.json`
- `<out-dir>/summary.json`

Usage:

```bash
scripts/me_sync_adb_regression.sh \
  --exporter-serial <serial-a> \
  --importer-serial <serial-b>
```
