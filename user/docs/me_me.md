# me.me

`me.me` is the app-to-app communication foundation for `me.things` devices.

Agent-facing scope:
- Discover peer presence via `GET /me/me/status`
- Send structured content via `POST /me/me/message/send`
- Receive all inbound content via `me.me.received` events

Endpoints:
- `GET /me/me/status`
- `POST /me/me/message/send`

Notes:
- Discovery and route selection are automatic (LAN/BLE/gateway fallback). Agents should not orchestrate low-level transport.
- `GET /me/me/status` is the single snapshot API for peer visibility and runtime state.
- `POST /me/me/message/send` is the single send API. It accepts message metadata plus payload/file attachment fields.
- `POST /me/me/message/send` payload contract:
  - Preferred: `{"peer_device_id":"...","type":"message","payload":{...}}`
  - Text shortcut: `{"peer_device_id":"...","text":"..."}`
  - Backward compatible: `{"peer_device_id":"...","message":"..."}` (normalized to `payload.text`)
  - Object shortcut: `{"peer_device_id":"...","message":{"type":"...","...":...}}` (`message.type` becomes type; remaining fields become payload when `payload` is absent)
  - Empty content is rejected with `400 payload_required`.
- Internal connection/scan/relay endpoints exist but are debug-only and intentionally omitted from agent workflow.
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
