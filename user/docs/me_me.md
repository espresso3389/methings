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
- Internal connection/scan/relay endpoints exist but are debug-only and intentionally omitted from agent workflow.

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
