# AGENTS.md

## Project Goal
Build an Android 14+ app that provides an agentic device environment with:
- Chromium/WebView-based GUI for IDE/agentic coding
- Cloud AI providers (Claude, OpenAI, Kimi, etc.) via built-in agent runtime
- Local HTTP service on device
- Explicit, user-granted access to local device resources
- Extensible agent framework

## Target Platform
- Android 14+
- WebView for UI rendering
- Built-in agent runtime (zero-setup, no external dependencies for core functionality)
- Termux optional — provides general-purpose Linux environment for agentic shell tasks and SSH
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

## Architecture
- Android app, minimal native wrapper
  - WebView/Chromium UI (custom shell)
  - Permission broker (runtime prompts + audit log)
- Local service layer (on-device)
  - Local HTTP server for UI + control APIs (`127.0.0.1:33389`)
  - Built-in agent runtime (`AgentRuntime` in `service.agent` package)
  - Background service for agent tasks
- Agent orchestration (built-in)
  - `LlmClient`: SSE streaming to OpenAI Responses API and Anthropic Messages API
  - `ToolExecutor`: tool dispatch (filesystem, device API, journal, memory, JS engine, native HTTP, shell, web search, cloud requests)
  - `JsEngine`: built-in QuickJS JavaScript engine for `run_js` tool (no Termux dependency)
  - `DeviceToolBridge`: calls device handlers via HTTP loopback
  - `AgentStorage`: chat persistence in SQLite (`agent/agent.db`)
  - `JournalStore`: JSONL session journal (`user/journal/`)
- Optional Termux (general-purpose Linux environment for agentic shell tasks)

## File Layout (planned)
- app/                # Android project
- server/             # On-device server code and tools
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
- API docs policy: the canonical API reference is the OpenAPI 3.1.0 spec under `user/docs/openapi/`. When adding or changing endpoints, update the relevant `paths/*.yaml` file and `openapi.yaml`. Agent-side tool conventions (runtime helpers, chat shortcuts) are in `user/docs/agent_tools.md`.
- Adding device APIs: when adding new `device_api` actions, follow the checklist in `docs/adding_device_apis.md` (ACTIONS map, CapabilityMap, route handler, OpenAPI spec).
- API scope policy: the OpenAPI spec is agent-facing only. Include user/agent-invokable APIs (for example BLE device-operation APIs), but exclude internal plumbing/debug-only endpoints (for example me.me/me.sync internal transport wiring); document those in `docs/DEBUGGING.md` instead.
- Built-in execution: `run_js` (QuickJS engine) and `run_curl` (native HTTP) work without Termux.
- On-device shell tooling (optional): `run_python`/`run_pip` require Termux (pkg + pip). Host-side app development must use uv.
- WSL usage is allowed but only when the user explicitly opts in for that session.

## Security & Permissions
- All actions that touch filesystem, network, or shell must pass the permission broker.
- Maintain a per-session audit trail.
- Provide granular toggles (e.g., read-only FS, no network, no shell).
- Permissions and credentials use a plain Room DB; credentials are encrypted with Android Keystore (AES-GCM) and stored as ciphertext in the same DB.
- Credential keys live in Android Keystore and are removed on app uninstall; vault data is not intended to be backed up.

## Background Execution
- Background service runs local HTTP service and built-in agent runtime.
- Agent runs in-process — no Termux required for core agent functionality.
- `run_js` (QuickJS) and `run_curl` (native HTTP) work without Termux.
- Termux is started on-demand when the agent invokes `run_python`/`run_pip` or SSH.
- SSH (OpenSSH via Termux) is controlled via `/termux/sshd/start` and `/termux/sshd/stop` endpoints.
- **Code Scheduler**: in-process scheduler with 1-minute resolution for recurring code execution (daemon/periodic/one_time). Persists to `agent/scheduler.db`. Key files: `SchedulerStore.kt` (SQLite), `SchedulerEngine.kt` (ticker + execution). Agent accesses via `scheduler.*` device_api actions.

## Testing
- Unit tests for permission broker and tool router.
- Integration tests for WebView <-> local service <-> agent runtime.
- Manual test checklist for agent functionality with and without Termux.

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
- Minimal control panel in WebView (agent status, Termux status, Wi-Fi IP, Reset UI).
- UI assets are served from `files/user/www` and can be reset from the UI or via `POST /ui/reset`.
- No chat/terminal/shell UI at the moment (to be reconsidered later).

## Debugging
- Developer notes for debugging agent actions and chat transcripts: `docs/DEBUGGING.md`.

## Repo Notes
- `third_party/libuvc` may appear as persistent local/untracked workspace noise. Ignore it during normal commit/push unless you are intentionally updating that component.
