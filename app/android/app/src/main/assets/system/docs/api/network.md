# Network API

Device network connectivity status. See [permissions.md](../permissions.md) for permission model.

## network.status

`Permission: device.network`

Get network connectivity status.

**Returns:**
- `connected` (boolean): Whether the device has network connectivity
- `type` (string): Network type (wifi, cellular, etc.)
- `wifi` (object): WiFi details when connected
- `cellular` (object): Cellular details when connected

## wifi.status

`Permission: device.network`

Get WiFi status.

**Returns:**
- `wifi_enabled` (boolean): Whether WiFi is enabled
- `ssid` (string?): Connected SSID (null if not connected)
- `ip_address` (string?): IP address on the WiFi network

## mobile.status

`Permission: device.network`

Get mobile / cellular status.

**Returns:**
- `mobile_data_enabled` (boolean): Whether mobile data is enabled
- `network_type` (string?): Mobile network type (LTE, 5G, etc.)
- `carrier` (string?): Carrier name
