# Scripts

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

## esp32_build_and_flash.sh
Compiles an ESP32 sketch on host Linux and flashes it through the app's on-device
`/mcu/flash` API.

Usage:
```bash
scripts/esp32_build_and_flash.sh \
  --sketch /path/to/BlinkSimple \
  --fqbn esp32:esp32:m5stack_atom \
  --serial <adb-serial>
```

## arduino_set_additional_urls.sh
Sets curated package-index URLs for in-app `arduino-cli`.

Usage:
```bash
scripts/arduino_set_additional_urls.sh \
  --url https://example.com/package_methings_index.json \
  --serial <adb-serial>
```

## arduino_stage_curated_bundle.sh
Stages a curated Arduino package index + archive files into app storage and
optionally points in-app `arduino-cli` to the local loopback index URL.

Usage:
```bash
scripts/arduino_stage_curated_bundle.sh \
  --bundle-dir /path/to/curated-bundle \
  --serial <adb-serial> \
  --set-additional-urls
```

## arduino_generate_curated_bundle.py
Generates a curated bundle (`package_methings_index.json` + `files/`) from a
source package index by selecting one platform version and required tools.

Usage:
```bash
python3 scripts/arduino_generate_curated_bundle.py \
  --source-index /tmp/methings-arduino-host/data/package_index.json \
  --package esp32 \
  --architecture esp32 \
  --platform-version 3.3.7 \
  --out-dir /tmp/arduino-curated-bundle
```

Notes:
- Tar hardlink normalization is enabled by default to avoid Android extraction
  failures on archives containing hardlinks.
- Disable it with `--no-normalize-hardlinks` if you want exact upstream
  archives.
- Normalized archives get a sidecar marker
  (`<archive>.methings-normalized`) so reruns skip upstream checksum checks for
  those rewritten files.

Then stage it:
```bash
scripts/arduino_stage_curated_bundle.sh \
  --bundle-dir /tmp/arduino-curated-bundle \
  --set-additional-urls
```
