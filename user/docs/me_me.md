# me.me

`me.me` is the app-to-app communication foundation for `me.things` devices.

**Always use the `device_api` tool for me.me operations. Never use `run_curl` to call me.me endpoints directly.**

Agent-facing actions (all via `device_api` tool):
- `me.me.status`: peer presence and connection snapshot
- `me.me.scan`: discover nearby devices
- `me.me.connect`: connect to a peer
- `me.me.disconnect`: disconnect from a peer
- `me.me.message.send`: send content to a connected peer
- `me.me.messages.pull`: pull received messages from a peer
- Receive inbound content via `me.me.received` events (pushed to brain)

All parameters go in `payload`, not `detail`. Every action targeting a peer needs `peer_device_id` in `payload`.

### Sending messages

Always use the `device_api` tool:

```
device_api(action="me.me.message.send", payload={
  "peer_device_id": "d_xxx",
  "type": "request",
  "payload": {"text": "take a photo and send it back"}
})
```

Message `type` is critical:
- **`request`**: triggers the remote agent to take action. **Always use this when asking a peer to do something.**
- `message`: informational only — does NOT trigger the remote agent.
- `file`: send a file — `payload.payload.rel_path` is auto-embedded by the server.

Examples:

Request (triggers remote agent):
```
device_api(action="me.me.message.send", payload={
  "peer_device_id": "d_xxx", "type": "request",
  "payload": {"text": "take a photo and send it back"}
})
```

Send a file:
```
device_api(action="me.me.message.send", payload={
  "peer_device_id": "d_xxx", "type": "file",
  "payload": {"rel_path": "captures/photo.jpg"}
})
```

**Warning**: `"text": "..."` shortcut (without `"type": "request"`) is informational only and will NOT trigger the remote agent.

### Pulling messages

```
device_api(action="me.me.messages.pull", payload={"peer_device_id": "d_xxx"})
```

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
