# me.sync

`me.sync` is the app's device-to-device transfer flow for moving local state between installations.

For the planned ad-hoc/nearby redesign, see [me_sync_v3.md](me_sync_v3.md).

## What It Transfers

Depending on mode and options, export can include:

- `files/user/` content
- `files/protected/app.db`
- Room-backed state snapshot (credentials, ssh keys, prefs)
- Optional install identity files (`user/.ssh/id_dropbear*`) in migration mode

## Core Concepts

- **Export package**: A time-limited package generated on the source device.
- **Transfer ID**: Stable ID for one prepared export (`transfer_id`).
- **Expiry**: Prepared exports expire; expired transfers must be regenerated.
- **me_sync_uri**: A compact payload (`me.things:me.sync:<base64url>`) used by QR/deep-link/share.
- **Import wipe**: Import defaults to wiping receiver local state before applying transferred data.

## Modes

- **Export mode** (`include_identity=false`, default): Excludes `user/.ssh/id_dropbear*`. Intended for normal cross-device export/import.

- **Migration mode** (`include_identity=true`): Includes install identity files. Intended for full migration scenarios.

## Transport Strategy

Prepared exports provide metadata for transfer. Current payload supports:

- Preferred encrypted SSH/SCP metadata (`transport: "ssh_scp"`)
- HTTP download fallback URL for compatibility

Importer attempts SSH/SCP first when metadata is present, then falls back to HTTP download.

## v3 Preview APIs

Current implementation includes v3 ticket endpoints:

- `POST /me/sync/v3/ticket/create`
- `GET /me/sync/v3/ticket/status?ticket_id=...`
- `POST /me/sync/v3/ticket/cancel`
- `POST /me/sync/v3/import/apply`

`v3` tickets use URI format `me.things:me.sync.v3:<base64url>`.
Current v3 import first attempts Nearby Connections stream transfer, then falls back to LAN metadata if enabled.

## Security Notes

- Export packages are temporary and time-bounded.
- Sensitive mutations remain permission-gated by the local permission broker.
- `wipe_all` is intentionally dangerous and should only be used with explicit user intent.

## Typical Flow

1. Source calls `prepare_export`
2. Source shares `me_sync_uri` (QR/deep-link)
3. Target resolves payload and calls `import`
4. Target wipes (default) and restores transferred state
5. Runtime restarts and UI reloads to apply imported state
