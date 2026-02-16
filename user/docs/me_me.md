# me.me

`me.me` is the foundation layer for discovering and connecting `me.things` devices.

Current scope in this phase:
- Device profile + connection policy configuration
- Wi-Fi local discovery via NSD (`_me_things._tcp`)
- BLE local discovery via BLE service-data beacon
- Runtime status + one-shot scan APIs

Endpoints:
- `GET /me/me/status`
- `GET /me/me/config`
- `POST /me/me/config`
- `POST /me/me/scan`
- `POST /me/me/connect`
- `POST /me/me/accept`
- `POST /me/me/connect/confirm`
- `POST /me/me/disconnect`
- `POST /me/me/message/send`
- `POST /me/me/messages/pull`
- `GET /me/me/relay/status`
- `GET /me/me/relay/config`
- `POST /me/me/relay/config`
- `POST /me/me/relay/register`
- `POST /me/me/relay/notify`
- `POST /me/me/relay/events/pull`
- `POST /me/me/relay/pull_gateway`
- `POST /me/me/relay/ingest`

Config fields:
- `device_name`, `device_description`, `device_icon`
- `allow_discovery`
- `connection_timeout`, `max_connections`, `connection_methods`
- `auto_reconnect`, `reconnect_interval`
- `discovery_interval`, `connection_check_interval`
- `allowed_devices`, `blocked_devices`
- `notify_on_connection`, `notify_on_disconnection`

Notes:
- `connection_methods` supports `wifi`, `ble`, `other`.
- `POST /me/me/scan` now returns discovered peers and `warnings` for partial failures (for example, missing BLE runtime permission).
- Automatic nearby discovery runs in background at `discovery_interval` with a short intermittent scan window (low-duty cycle).
- Discovered peers are pruned automatically when they are stale (not seen for a while).
- Connection health checks run in background at `connection_check_interval`.
- If a logical connection becomes unreachable it is marked `disconnected`; when route visibility returns, `auto_reconnect` + `reconnect_interval` can restore it to `connected`.
- `GET /me/me/status` includes:
  - `discovered` / `discovered_count`
  - `pending_requests` / `pending_request_count`
  - `connections` / `connected_count`
  - `advertising.wifi` and `advertising.ble`
  - `last_scan_at`
- `POST /me/me/connect` returns a short-lived `accept_token`.
- `POST /me/me/accept` accepts that token on the target device and creates a logical connection record.
- `POST /me/me/accept` also attempts LAN auto-confirm to the initiator (`/me/me/connect/confirm`) so both sides are connected automatically.
- `POST /me/me/connect/confirm` remains available for manual/fallback confirmation.
- `POST /me/me/disconnect` removes an existing logical connection by `peer_device_id` or `connection_id`.
- `POST /me/me/message/send` delivers encrypted payloads to peer LAN endpoint.
- `POST /me/me/messages/pull` reads/dequeues received messages.
- Data-plane currently uses LAN HTTP (`0.0.0.0:8767`) + per-session AES-GCM.

Relay foundation:
- Purpose: provide a server-mediated notification path for app-to-app coordination (`me.me`, `me.sync`, and future flows).
- Caller-side APIs:
  - `POST /me/me/relay/register`: register current device ID + push token to relay gateway.
  - `POST /me/me/relay/notify`: issue route token and call relay webhook for a target device.
- Receiver-side APIs:
  - `POST /me/me/relay/ingest`: push bridge endpoint (for example FCM adapter -> local server).
  - `POST /me/me/relay/events/pull`: read/dequeue ingested relay events.
  - `POST /me/me/relay/pull_gateway`: pull queued events from relay gateway (`/events/pull`) and ingest locally.
- Runtime:
  - connection-check tick also performs throttled gateway pull when relay is enabled and admin secret is configured.
- Config:
  - `enabled`, `gateway_base_url`, `provider`, `route_token_ttl_sec`, `device_push_token`.
  - `gateway_admin_secret` is accepted on config-set but never returned; it is stored in encrypted credential storage.

Agent alert integration:
- me.me runtime forwards key events to the local agent inbox (`/brain/inbox/event`) with `priority` and `interrupt_policy`.
- Current forwarded events:
  - `me.me.device.discovered` (low, `never`, coalesced per device)
  - `me.me.relay.event.received` (normal, `turn_end`)
  - `me.me.relay.discord.message` (normal, `turn_end`) when relay payload has `normalized.kind=discord.message`
  - `me.me.relay.slack.event` (normal, `turn_end`) when relay payload has `normalized.kind=slack.event`
  - `me.me.message.received` (high, `turn_end`)
- Event payload includes short `summary` text for timeline visibility (provider-specific summary for Slack/Discord relay events).
