# BLE (Bluetooth Low Energy)

Kugutz exposes BLE via the Kotlin control plane (`device_api` tool).

## Events (WebSocket)

BLE is event-driven. Start scanning or connect to a device, then listen on:

- `ws_path`: `/ws/ble/events`

Messages are JSON strings like:

- `{"type":"ble","event":"scan_result", ...}`
- `{"type":"ble","event":"connected", "address":"...", ...}`
- `{"type":"ble","event":"services", "services":[...], ...}`
- `{"type":"ble","event":"char_notify", "value_b64":"..."}`

## Actions

- `ble.status` (GET)
- `ble.scan.start` (POST) -> returns `ws_path`
  - payload: `low_latency` (bool)
- `ble.scan.stop` (POST)
- `ble.connect` (POST) -> returns `ws_path`
  - payload: `address` (MAC), `auto_connect` (bool)
- `ble.disconnect` (POST)
  - payload: `address`
- `ble.gatt.services` (POST)
  - payload: `address`
- `ble.gatt.read` (POST)
  - payload: `address`, `service_uuid`, `char_uuid`
- `ble.gatt.write` (POST)
  - payload: `address`, `service_uuid`, `char_uuid`, `value_b64`, `with_response` (bool)
- `ble.gatt.notify.start` / `ble.gatt.notify.stop` (POST)
  - payload: `address`, `service_uuid`, `char_uuid`

