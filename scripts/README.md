# Scripts

## smoke_test.py
Simple localhost smoke test for the server. Run after starting `server/app.py`.

## ui_hot_reload.sh
Pushes the WebView UI to a connected device and triggers a reload.

Usage:
```bash
scripts/ui_hot_reload.sh <device-serial>
```

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
