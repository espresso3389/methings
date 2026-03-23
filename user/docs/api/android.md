# Android API

Android device information and runtime permission management. No app-level permission gate required.

## android.device

Get Android device hardware and software details.

**Returns:**
- `manufacturer` (string): e.g. "Google"
- `model` (string): e.g. "Pixel 7"
- `brand` (string): consumer-visible brand
- `device` (string): industrial design name
- `product` (string): product name
- `board` (string): board name
- `hardware` (string): hardware name
- `display` (string): build display ID
- `android_version` (string): e.g. "13"
- `sdk_int` (integer): e.g. 33
- `security_patch` (string): e.g. "2023-01-05"
- `build_id` (string): build ID
- `fingerprint` (string): full build fingerprint
- `supported_abis` (string[]): e.g. ["arm64-v8a", "armeabi-v7a"]
- `screen_density_dpi` (integer): screen density in DPI
- `screen_width_px` (integer): screen width in pixels
- `screen_height_px` (integer): screen height in pixels
- `locale` (string): BCP-47 locale tag, e.g. "en-US"

## android.permissions

List all manifest-declared Android permissions with grant status.

**Returns:**
- `permissions` (array): objects with `name` (string, fully-qualified permission) and `granted` (boolean)

## android.permissions.status

Get Android permission dialog timing status for in-flight and recent requests.

**Returns:**
- `now_ms` (integer): current time
- `pending_permission_requests` (array): objects with `request_id`, `permissions`, `requested_at_ms`, `age_ms`, `timed_out`
- `recent_permission_requests` (array): objects with `request_id`, `permissions`, `requested_at_ms`, `responded`, `results` (map of permission->granted), `completed_at_ms`, `timed_out`
- `usb_pending_permission_requests` (array): objects with `device_name`, `requested_at_ms`, `age_ms`, `timed_out`
- `usb_recent_permission_requests` (array): objects with `device_name`, `requested_at_ms`, `responded`, `granted`, `completed_at_ms`, `timed_out`

## android.permissions.request

Request Android runtime permissions via system dialog. Blocks until user responds or 60s timeout.

Permission: `device.android`

**Params:**
- `permissions` (string[], required): fully-qualified Android permission names (e.g. `["android.permission.BLUETOOTH_SCAN"]`)

**Returns:**
- `results` (object): map of permission name to granted boolean
- `all_granted` (boolean): true if every requested permission is now granted

**Notes:** Only manifest-declared permissions are accepted. Already-granted permissions are skipped (no dialog shown).
