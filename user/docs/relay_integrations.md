# Relay Integrations (Slack / Discord)

This guide defines how an agent should assist users when they say:
- "Connect Slack"
- "Connect Discord"

The target path is always:
- External platform webhook -> `https://hooks.methings.org/webhook/<route_token>?provider=<provider>`
- Gateway queue -> device runtime delivery
- Local agent event -> `me.me.received`

## Agent-Assisted Onboarding Contract

When a user asks to connect Slack or Discord, the agent should:

1. Confirm target provider (`slack` or `discord`).
2. Confirm target device (`device_id`) from me.me status/config in the app.
3. Ensure gateway linkage is enabled in app settings.
4. Issue route token (`/route_token/issue`) for that target device.
5. Present the exact webhook URL to paste into provider settings.
6. Run one verification event and show the received summary.

## Required Inputs (Minimal)

- Provider: `slack` or `discord`
- Target device ID (from app me.me settings/status)
- Provider-side signing secret (already managed in gateway secrets)

Optional but recommended:
- Routing rule (channel/topic -> device)
- Event allowlist (which events to subscribe)

## Slack Setup

## 1) URL to paste in Slack App

`https://hooks.methings.org/webhook/<route_token>?provider=slack`

`<route_token>` is issued per target device.

## 2) Event Subscriptions

- Enable Event Subscriptions.
- Paste URL above as Request URL.
- URL verification is supported (`type=url_verification`).

Recommended bot events:
- `message.channels`
- optionally: `message.groups`, `message.im`, `message.mpim`

## 3) OAuth scopes (typical)

- `channels:history` (for public channels)
- `groups:history` (private channels, if used)
- `im:history` / `mpim:history` (if used)

After changing scopes/events, reinstall the app to workspace.

## 4) Verify

- Send a Slack message in subscribed channel.
- Confirm the app/agent receives `me.me.received` with `received_origin=external` and `received_provider=slack`.

## Discord Setup

## 1) URL to use

`https://hooks.methings.org/webhook/<route_token>?provider=discord`

## 2) Signature requirement

Gateway expects headers:
- `X-Discord-Request-Timestamp`
- `X-Discord-Signature`

Verification base string:
- `v1:<timestamp>:<raw_body>`
- HMAC-SHA256 with `DISCORD_WEBHOOK_SECRET`

## 3) Verify

- Send a signed test payload.
- Confirm the app/agent receives `me.me.received` with `received_origin=external` and `received_provider=discord`.

## Operational Limits

- Slack Event Subscriptions uses one Request URL per Slack app.
- For multi-device routing:
  - Hub mode: one Slack app URL, then route internally by policy.
  - App split mode: separate Slack app per destination.

Hub mode is usually easier to operate.

For endpoint-level relay debugging details, see `docs/DEBUGGING.md`.
