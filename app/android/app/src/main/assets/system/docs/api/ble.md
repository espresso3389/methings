# BLE API

Bluetooth Low Energy scanning, GATT connection, and characteristic read/write/notify.

All actions require `Permission: device.ble`.

## ble.status

Get BLE adapter and connection state.

**Returns:**
- `bluetooth_enabled` (boolean): Whether Bluetooth is on
- `scanning` (boolean): Whether a scan is active
- `connected_devices` (array of string): Connected device addresses

## ble.scan_start

Start BLE scan. Results are delivered over WebSocket.

**Params:**
- `low_latency` (boolean, optional): Use low-latency scan mode. Default: true

**Returns:**
- `ws_path` (string): `/ws/ble/events`

## ble.scan_stop

Stop BLE scan.

## ble.connect

Connect to a BLE device. Connection events are delivered over WebSocket.

**Params:**
- `address` (string, required): BLE MAC address
- `auto_connect` (boolean, optional): Use autoConnect mode. Default: false

**Returns:**
- `ws_path` (string): `/ws/ble/events`

## ble.disconnect

Disconnect a BLE device.

**Params:**
- `address` (string, required): BLE MAC address

## ble.gatt_services

List GATT services of a connected device.

**Params:**
- `address` (string, required): BLE MAC address

**Returns:**
- `services` (array): Each: `{uuid, characteristics: [{uuid, properties: [string]}]}`

## ble.gatt_read

Read a GATT characteristic.

**Params:**
- `address` (string, required): BLE MAC address
- `service_uuid` (string, required): GATT service UUID
- `char_uuid` (string, required): Characteristic UUID

**Returns:**
- `value_b64` (string): Base64-encoded characteristic value

## ble.gatt_write

Write a GATT characteristic.

**Params:**
- `address` (string, required): BLE MAC address
- `service_uuid` (string, required): GATT service UUID
- `char_uuid` (string, required): Characteristic UUID
- `value_b64` (string, required): Base64-encoded value to write
- `with_response` (boolean, optional): Use write-with-response. Default: true

## ble.gatt_notify_start

Subscribe to GATT notifications. Values are delivered over WebSocket as `char_notify` events.

**Params:**
- `address` (string, required): BLE MAC address
- `service_uuid` (string, required): GATT service UUID
- `char_uuid` (string, required): Characteristic UUID

## ble.gatt_notify_stop

Unsubscribe from GATT notifications.

**Params:**
- `address` (string, required): BLE MAC address
- `service_uuid` (string, required): GATT service UUID
- `char_uuid` (string, required): Characteristic UUID

### WebSocket

All BLE events are delivered on `/ws/ble/events` as JSON messages:

- `{"type":"ble","event":"scan_result", ...}` -- discovered device
- `{"type":"ble","event":"connected","address":"..."}` -- GATT connected
- `{"type":"ble","event":"services","services":[...]}` -- service discovery complete
- `{"type":"ble","event":"char_notify","value_b64":"..."}` -- characteristic notification
