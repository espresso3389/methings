# Documentation Index

## API Reference

- **OpenAPI Spec**: `openapi/openapi.yaml` — complete HTTP API reference (OpenAPI 3.1.0).
  - Paths: `openapi/paths/*.yaml`
  - Schemas: `openapi/components/schemas.yaml`
- **Agent Tools**: `agent_tools.md` — device_api mapping, agent filesystem helpers, chat UI shortcuts.
- **Permissions**: `permissions.md` — permission scopes, request flow, USB special cases.

## Architecture & Design

- **me.me**: `me_me.md` — device discovery/connection foundation, security model, event forwarding.
- **me.sync**: `me_sync.md` — export/import concepts, modes, and transfer flow.
- **me.sync v3**: `me_sync_v3.md` — QR-paired ad-hoc transfer architecture (Nearby-first, LAN fallback).
- **Relay Integrations**: `relay_integrations.md` — agent-assisted Slack/Discord onboarding and verification.
- **Viewer Guide**: `viewer.md` — fullscreen viewer usage, Marp presentations, autonomous presentation examples.
