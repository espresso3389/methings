# Relay Integrations (Slack / Discord)

This guide defines how an agent should assist users when they say:
- "Connect Slack"
- "Connect Discord"

The target path is always:
- External platform webhook -> `https://hooks.methings.org/webhook/<route_token>?provider=<provider>`
- Gateway queue -> device `POST /me/me/relay/pull_gateway`
- Local consume -> `POST /me/me/relay/events/pull`

## Agent-Assisted Onboarding Contract

When a user asks to connect Slack or Discord, the agent should:

1. Confirm target provider (`slack` or `discord`).
2. Confirm target device (`device_id`) and check relay status.
3. Ensure relay config on device:
   - `enabled=true`
   - `gateway_base_url=https://hooks.methings.org`
   - `gateway_admin_secret` configured
4. Ensure device is registered at gateway (`/me/me/relay/register`).
5. Issue route token (`/route_token/issue`) for that target device.
6. Present the exact webhook URL to paste into provider settings.
7. Run one verification event and show the received summary.

## Required Inputs (Minimal)

- Provider: `slack` or `discord`
- Target device ID (usually from `GET /me/me/config`)
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
- On device, run `POST /me/me/relay/pull_gateway`.
- Confirm `POST /me/me/relay/events/pull` includes:
  - `provider=slack`
  - `kind=slack.event`
  - meaningful `summary`

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
- Pull from device via `POST /me/me/relay/pull_gateway`.
- Confirm latest event contains:
  - `provider=discord`
  - `kind=discord.message`
  - summary generated from author/content.

## Operational Limits

- Slack Event Subscriptions uses one Request URL per Slack app.
- For multi-device routing:
  - Hub mode: one Slack app URL, then route internally by policy.
  - App split mode: separate Slack app per destination.

Hub mode is usually easier to operate.
