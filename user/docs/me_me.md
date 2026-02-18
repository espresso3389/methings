# me.me

`me.me` is the app-to-app communication foundation for `me.things` devices.

Agent-facing actions (all via `device_api`):
- `me.me.status`: peer presence and connection snapshot
- `me.me.scan`: discover nearby devices
- `me.me.connect`: connect to a peer (`peer_device_id` in payload)
- `me.me.disconnect`: disconnect from a peer
- `me.me.message.send`: send content to a connected peer
- `me.me.messages.pull`: pull received messages from a peer
- Receive inbound content via `me.me.received` events (pushed to brain)

**Critical**: All parameters go in `payload`, not `detail`. Every action targeting a peer needs `peer_device_id` in `payload`.

### Sending messages

Use `me.me.message.send` for all sends. Put `peer_device_id` in `payload`.

Payload contract:
- Request (triggers remote agent): `{"peer_device_id":"...","type":"request","payload":{"text":"..."}}`
- Text shortcut (informational only): `{"peer_device_id":"...","text":"..."}`
- File send: `{"peer_device_id":"...","type":"file","payload":{"rel_path":"captures/photo.jpg"}}`
- Backward compatible: `{"peer_device_id":"...","message":"..."}` (normalized to `payload.text`)
- Empty content is rejected with `400 payload_required`.

Message `type` determines how the remote device handles the message:
- `request` / `agent_request` / `task` / `command` / `agent_task`: **triggers the remote agent** to process and respond. Use this when you want the peer device to take action.
- `message`: informational only — stored on the remote device but does **not** trigger agent processing (unless priority is `high` or `urgent`).
- `file`: send a file — the server reads and embeds the file content automatically from `payload.rel_path`.
- **Important**: When asking a remote device to do something (take a photo, run a command, etc.), always use `type: "request"`. Using `type: "message"` or the text shortcut will NOT trigger the remote agent.

### Pulling messages

Use `me.me.messages.pull`: `{"peer_device_id":"d_xxx"}`.

Notes:
- Discovery and route selection are automatic (LAN/BLE/gateway fallback). Agents should not orchestrate low-level transport.
- There is no `me.me.message.send_file` action. Use `me.me.message.send` with `payload.payload.rel_path`.
- Connection handshake security:
  - Offer token is signed with source identity key:
    - preferred: `Ed25519`
    - fallback: `ECDSA P-256`
  - Session key is derived from ephemeral key exchange + `HKDF-SHA256`:
    - preferred: `X25519`
    - fallback: `ECDH P-256`
  - Message payload is encrypted with `AES-GCM` using the derived session key.

Agent alert integration:
- me.me runtime forwards key events to the local agent inbox (`/brain/inbox/event`) with `priority` and `interrupt_policy`.
- Current forwarded event for inbound content: `me.me.received` (normal/high, `turn_end`).
- `me.me.received` payload carries routing/type details in fields:
  - `received_origin`: `peer` or `external`
  - `received_transport`: `lan`, `ble`, or `gateway`
  - `received_provider`: `direct`, `generic`, or provider name
  - `received_kind`: message/event kind (for example `message`, `audio`, `discord.message`, `slack.event`)
- Event payload includes short `summary` text for agent handling.

Integration onboarding:
- For agent-assisted provider onboarding (Slack/Discord), see `relay_integrations.md`.
- For low-level endpoint behavior and troubleshooting, see `docs/DEBUGGING.md`.
