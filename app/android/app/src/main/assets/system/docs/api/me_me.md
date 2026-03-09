# me.me API

Device-to-device communication. See [me_me.md](../me_me.md) for concepts and usage patterns.

## me.me.status

Get self profile, peer presence, and discovery state.

**Returns:** `self` (object), `peers` (array), `discovery` (object)

**Notes:** Route selection is automatic (LAN/BLE/gateway fallback). Do not orchestrate transport.

## me.me.provision.status

Get provisioning status.

**Returns:** `provisioned` (bool), `user_subject` (string?), `device_id`, `device_name`, `devices` (array of `{device_id, device_name}`), `claimed_at` (epoch ms?), `p2p_enabled` (bool), `signaling_token_configured` (bool)

## me.me.provision.refresh

Refresh device list from gateway. Also auto-refreshed via push when siblings change.

**Returns:** `user_subject`, `devices` (updated array)

## me.me.message.send

Permission: `device.me_me`

Send encrypted content to a peer via automatic route selection.

**Params:**
- `peer_device_id` (string, required): Target device ID
- `type` (string, optional): `request` (triggers remote agent), `message` (informational), `file`, `agent_request`, `task`, `command`, `agent_task`
- `payload` (object, optional): Structured payload (preferred)
- `text` (string, optional): Text shortcut
- `message` (string|object, optional): Backward-compatible field

**Returns:** `route` (`lan`|`ble`|`gateway`)

**Notes:** Always use `type: "request"` when asking a peer to take action. `type: "message"` does NOT trigger the remote agent. For files, use `type: "file"` with `payload: {"rel_path": "..."}`. Inbound content arrives as `me.me.received` events.
