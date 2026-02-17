# AGENTS.md

## Project Goal
Build an Android 14+ app that provides a Python development environment with:
- Chromium/WebView-based GUI for IDE/agentic coding
- Cloud AI providers (Claude, OpenAI, Kimi, etc.)
- Local HTTP service on device
- Explicit, user-granted access to local device resources
- Extensible agent framework

## Target Platform
- Android 14+
- WebView for UI rendering
- Embedded CPython runtime (Python-for-Android)
- Background service required

## Core Tenets
- User consent is required for any device/resource access.
- Clear separation: UI, local services, agent orchestration, provider SDKs.
- All sensitive actions must be audit-logged.
- Everything should run offline except explicit cloud calls.

## Agent Behavior (Product Goal)
- Outcome first: the agent should deliver the requested artifact/state change (e.g., a photo, a file, a running service), not a tutorial.
- Tool-driven: if the user asks to do something, the agent should use tools and/or write+run code to actually do it (no pretending).
- Minimal questions: only ask the user when consent is required or when a requested capability is not available yet.

## Suggested Architecture
- Android app (Kotlin), minimal native wrapper
  - WebView/Chromium UI (custom shell)
  - Permission broker (runtime prompts + audit log)
- Local service layer (on-device)
  - Local HTTP server for UI + control APIs
  - Embedded CPython runtime (worker, started on-demand)
  - Background service for agent tasks
- Agent orchestration
  - Cloud provider adapters (OpenAI/Claude/Kimi/etc.)
  - Tool invocation router (with gating + allowlist)
  - Session context and state storage

## File Layout (planned)
- app/                # Android project
- server/             # Local Python service + agent router
- docs/               # Specs and design docs
- scripts/            # Build and bootstrap utilities
- AGENTS.md           # This file

## Conventions
- Favor ASCII only in source unless already using Unicode.
- Write all program code and documentation in English unless non-English text is strictly required.
- Add concise comments only when logic is non-obvious.
- Keep security prompts minimal but explicit.
- No silent elevation or background actions.
- API docs policy: the canonical API reference is the OpenAPI 3.1.0 spec under `user/docs/openapi/`. When adding or changing endpoints, update the relevant `paths/*.yaml` file and `openapi.yaml`. Agent-side tool conventions (Python runtime helpers, chat shortcuts) are in `user/docs/agent_tools.md`.
- API scope policy: the OpenAPI spec is agent-facing only. Include user/agent-invokable APIs (for example BLE device-operation APIs), but exclude internal plumbing/debug-only endpoints (for example me.me/me.sync internal transport wiring); document those in `docs/DEBUGGING.md` instead.
- On-device Python tooling: use venv + pip (avoid system pip). Host-side app development must use uv.
- WSL usage is allowed but only when the user explicitly opts in for that session.

## Security & Permissions
- All actions that touch filesystem, network, or shell must pass the permission broker.
- Maintain a per-session audit trail.
- Provide granular toggles (e.g., read-only FS, no network, no shell).
- Permissions/SSH keys use a plain Room DB; credentials are encrypted with Android Keystore (AES-GCM) and stored as ciphertext in the same DB.
- Credential keys live in Android Keystore and are removed on app uninstall; vault data is not intended to be backed up.

## Background Execution
- Background service runs local HTTP service and SSHD.
- Python worker is started on-demand and can be stopped independently.

## Testing
- Unit tests for permission broker and tool router.
- Integration tests for WebView <-> local service <-> Python worker.
- Manual test checklist for SSHD + permission prompts.

## Current UI (2026-02)
- Minimal control panel in WebView (Python worker, SSHD, PIN auth, Wi-Fi IP, Reset UI).
- UI assets are served from `files/www` and can be reset from the UI.
- No chat/terminal/shell UI at the moment (to be reconsidered later).

## Debugging
- Developer notes for debugging agent actions and chat transcripts: `docs/DEBUGGING.md`.

## Repo Notes
- `third_party/libuvc` may appear as persistent local/untracked workspace noise. Ignore it during normal commit/push unless you are intentionally updating that component.
