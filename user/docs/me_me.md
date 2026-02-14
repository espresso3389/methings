# me.me

`me.me` is the foundation layer for discovering and connecting `me.things` devices.

Current scope in this phase:
- Device profile + connection policy configuration
- Runtime status endpoint shape
- Manual scan trigger endpoint

Endpoints:
- `GET /me/me/status`
- `GET /me/me/config`
- `POST /me/me/config`
- `POST /me/me/scan`

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
- This is a compatibility-safe foundation API; transport/data-plane implementation can evolve.
