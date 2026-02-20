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
- Termux for Python runtime and SSH (external app, bootstrapped by methings)
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
  - Termux-managed Python worker (started on-demand via RUN_COMMAND intent)
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

## Agent & System Docs (Source of Truth)
- The on-device agent reads docs from `app/android/app/src/main/assets/system/` and `assets/user_defaults/`, but those are **generated copies** (gitignored).
- **Always edit under `user/`** at the repo root:
  - `user/AGENTS.md`, `user/TOOLS.md` → copied to `assets/user_defaults/`
  - `user/docs/`, `user/examples/`, `user/lib/` → copied to `assets/system/`
- Similarly, `app/android/app/src/main/assets/server/` is an **auto-synced copy** of `server/` at the repo root. **Always edit under `server/`** (e.g., `server/tools/device_api.py`, `server/agents/runtime.py`).
- The Gradle build (`syncSystemAssets` / `syncUserDefaults` / asset sync tasks in `app/android/app/build.gradle.kts`) handles all copies automatically as a preBuild dependency.

## Conventions
- Favor ASCII only in source unless already using Unicode.
- Write all program code and documentation in English unless non-English text is strictly required.
- Add concise comments only when logic is non-obvious.
- Keep security prompts minimal but explicit.
- No silent elevation or background actions.
- API docs policy: the canonical API reference is the OpenAPI 3.1.0 spec under `user/docs/openapi/`. When adding or changing endpoints, update the relevant `paths/*.yaml` file and `openapi.yaml`. Agent-side tool conventions (Python runtime helpers, chat shortcuts) are in `user/docs/agent_tools.md`.
- API scope policy: the OpenAPI spec is agent-facing only. Include user/agent-invokable APIs (for example BLE device-operation APIs), but exclude internal plumbing/debug-only endpoints (for example me.me/me.sync internal transport wiring); document those in `docs/DEBUGGING.md` instead.
- On-device Python tooling: managed by Termux (pkg + pip). Host-side app development must use uv.
- WSL usage is allowed but only when the user explicitly opts in for that session.

## Security & Permissions
- All actions that touch filesystem, network, or shell must pass the permission broker.
- Maintain a per-session audit trail.
- Provide granular toggles (e.g., read-only FS, no network, no shell).
- Permissions and credentials use a plain Room DB; credentials are encrypted with Android Keystore (AES-GCM) and stored as ciphertext in the same DB.
- Credential keys live in Android Keystore and are removed on app uninstall; vault data is not intended to be backed up.

## Background Execution
- Background service runs local HTTP service.
- Python worker runs in Termux, started on-demand via RUN_COMMAND intent, and can be stopped independently.
- SSH (OpenSSH via Termux) is controlled via `/termux/sshd/start` and `/termux/sshd/stop` endpoints.

## Testing
- Unit tests for permission broker and tool router.
- Integration tests for WebView <-> local service <-> Python worker.
- Manual test checklist for Termux + permission prompts.

## Device Provisioning (OAuth Sign-In)
- Users sign in via Google or GitHub through the gateway's server-side OAuth flow (opened in CustomTabs from the app)
- Sign-in binds the device to a user account on the gateway (`user_subject -> device_id` mapping in `user_devices` table)
- Provisioned siblings (devices under the same account) auto-approve each other unconditionally for me.me connections
- Sign-in auto-configures the WebRTC signaling token, enabling P2P DataChannel connections (including TURN relay for NAT traversal) without manual setup
- Provisioned siblings appear in the device list with "Linked" status and are reachable via P2P or relay transport
- The sign-in page is hosted on the gateway (`/provision/start`); provider selection (Google/GitHub) happens server-side, not in the app
- Key files:
  - `app/android/app/src/main/assets/www/index.html` — Account UI section, provision status display, linked device chips
  - `app/android/app/src/main/java/jp/espresso3389/methings/service/LocalHttpServer.kt` — `/me/me/provision/*` endpoints, auto-approve logic, provisioned device list in `/me/me/status`
  - `app/android/app/src/main/java/jp/espresso3389/methings/ui/MainActivity.kt` — `openUrlInBrowserNewTask()` for CustomTabs with `FLAG_ACTIVITY_NEW_TASK`, deep link handling via `onNewIntent()`
  - `app/android/app/src/main/java/jp/espresso3389/methings/ui/WebAppBridge.kt` — `openInBrowser()` JS bridge method

## Current UI (2026-02)
- Minimal control panel in WebView (Termux status, agent worker, Wi-Fi IP, Reset UI).
- UI assets are served from `files/user/www` and can be reset from the UI or via `POST /ui/reset`.
- No chat/terminal/shell UI at the moment (to be reconsidered later).

## Debugging
- Developer notes for debugging agent actions and chat transcripts: `docs/DEBUGGING.md`.

## Repo Notes
- `third_party/libuvc` may appear as persistent local/untracked workspace noise. Ignore it during normal commit/push unless you are intentionally updating that component.
