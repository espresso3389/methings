# Location API

Device GPS/network location access.

## location.status

Permission: `device.gps`

List location providers and their enabled state.

**Returns:**
- `providers` (string[]): All known location providers (e.g. `[fused, gps, network, passive]`)
- `enabled` (object): Map of provider name to enabled boolean

## location.get

Permission: `device.gps`

Get current device location.

**Params:**
- `high_accuracy` (boolean, optional): Prefer GPS/fused (true) or network/passive (false). Default: true
- `timeout_ms` (integer, optional): Max wait for a location fix (250-120000). Default: 12000

**Returns:**
- `provider` (string): Provider that supplied the fix (e.g. `fused`, `gps`, `network`)
- `latitude` (double): Latitude
- `longitude` (double): Longitude
- `accuracy_m` (float, nullable): Estimated horizontal accuracy in metres
- `altitude_m` (double, nullable): Altitude in metres above WGS 84 ellipsoid
- `bearing_deg` (float, nullable): Bearing in degrees (0-360)
- `speed_mps` (float, nullable): Speed in metres per second
- `time_ms` (int64): UTC time of fix (Unix epoch millis)
- `elapsed_realtime_nanos` (int64): Elapsed realtime nanos since boot
