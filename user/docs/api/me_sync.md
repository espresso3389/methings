# me.sync API

Device-to-device state transfer (export/import). See [me_sync.md](../me_sync.md) for concepts and modes, [me_sync_v3.md](../me_sync_v3.md) for v3 architecture.

Permission: `device.me_sync` (except status/local_state queries)

## me.sync.status

Get active export packages and expiry info.

**Returns:**
- `transfers` (array): objects with `transfer_id` (string) and `expires_at` (integer, epoch ms)

## me.sync.local_state

Check if receiver has existing local data that would be wiped on import.

**Returns:**
- `has_local_data` (boolean): whether local data exists

## me.sync.prepare_export

Build a one-time, time-limited export package for device-to-device transfer.

**Params:**
- `include_user` (boolean, optional): include user files. Default: true
- `include_protected_db` (boolean, optional): include protected database. Default: true
- `include_identity` (boolean, optional): include install identity (`id_dropbear*`). true = migration mode, false = export mode. Default: false
- `mode` (string, optional): `export` or `migration` (alternative to `include_identity`)

**Returns:**
- `transfer_id` (string): stable ID for this export
- `me_sync_uri` (string): compact payload URI (`me.things:me.sync:<base64url>`)
- `qr_data_url` (string): data URL of QR code image
- `download_url` (string): LAN HTTP download URL
- `expires_at` (integer): expiry time (epoch ms)

## me.sync.import

Import data from an export package. Accepts HTTP URL, JSON payload, `me_sync_uri`, or `me.sync.v3` URI.

**Params:**
- `url` (string, optional): HTTP download URL from source
- `payload` (string, optional): JSON payload or `me_sync_uri` string
- `wipe_existing` (boolean, optional): wipe receiver local state before import. Default: true

## me.sync.wipe_all

Wipe all local app data and restart. API-only (no GUI button).

**Params:**
- `restart_app` (boolean, optional): restart app after wiping. Default: true

**Notes:** Dangerous -- destroys all local data.

## me.sync.v3.ticket.create

Create a v3 QR ticket (`me.things:me.sync.v3:<base64url>`) with LAN fallback metadata for Nearby Connections transfer.

**Params:**
- `include_user` (boolean, optional): Default: true
- `include_protected_db` (boolean, optional): Default: true
- `include_identity` (boolean, optional): Default: false

**Returns:**
- `ticket_id` (string): ticket ID
- `ticket_uri` (string): ticket URI (`me.things:me.sync.v3:<base64url>`)

## me.sync.v3.ticket.status

Get v3 ticket status and transfer progress.

**Params (query):**
- `ticket_id` (string, required): ticket ID to check

**Returns:**
- `ticket_status` (string): ticket lifecycle state
- `progress` (object): transfer progress details

## me.sync.v3.ticket.cancel

Cancel a v3 ticket.

**Params:**
- `ticket_id` (string, required): ticket ID to cancel

## me.sync.v3.import.apply

Import using a v3 ticket URI. Attempts Nearby Connections stream first, then falls back to LAN URL.

**Params:**
- `ticket_uri` (string, required): v3 ticket URI (`me.things:me.sync.v3:...`)
- `wipe_existing` (boolean, optional): wipe receiver state before import. Default: true
