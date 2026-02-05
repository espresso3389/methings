# Architecture Overview

## Goals
- Minimal native Android wrapper hosting a WebView-based IDE.
- Embedded CPython runtime via Python-for-Android.
- Local HTTP service on-device for agent APIs, IDE bridge, and tool routing.
- Background service for long-running agent tasks.
- Multi-channel user communication (terminal/log stream).
- Explicit user consent for all sensitive actions.

## High-Level Components
1) Android Shell (Kotlin)
- WebView UI container (custom IDE shell)
- Terminal panel (PTY-backed or virtual terminal)
- Permission broker (runtime prompts + audit log)
- Background service controller (start/stop/pause agents)

2) Local Python Service (Python)
- HTTP server (localhost) exposing:
  - IDE bridge endpoints
  - Agent session APIs
  - Tool invocation gateway
  - Permission request endpoints
  - Webhook configuration endpoints
- Tool plugins (filesystem, shell, network) guarded by permissions
- Provider adapters (OpenAI, Claude, Kimi)
- State store (local SQLite)

3) Web UI (HTML/JS)
- IDE shell UI in WebView
- Terminal/log streaming view
- Agent status + progress
- Native consent trigger via JS bridge
 - Served from user data directory (assets copied on first launch)

## Process Topology
- Android app launches:
  - Starts background service
  - Boots Python runtime
  - Starts local HTTP service on localhost
- WebView loads local UI (file:// or http://127.0.0.1)
- UI communicates with Python service over HTTP or WebSocket

## Security Model
- Permission broker mediates access to:
  - Filesystem
  - Network
  - Shell commands
- Consent UI required for any tool with external effects.
- Audit trail for all tool calls and AI actions.

## Background Execution
- Android foreground service runs Python service to keep it alive.
- User can pause/stop agents and terminate background tasks.
- Background tasks must post progress updates via in-app status.
- Host service spawns short-lived worker processes for user programs to avoid restarting the host.

## Communication Channels
 - In-app chat UI
 - Terminal/log stream in WebView

## Module Layout (Proposed)
- app/
  - android/
    - app/src/main/java/.../ui        # WebView host, chat/terminal panels
    - app/src/main/java/.../service   # Foreground service + runtime bootstrap
    - app/src/main/java/.../perm      # Permission broker, audit log
    - app/src/main/assets/www         # Web UI shell
    - app/src/main/assets/server      # Embedded Python server assets
- server/
  - app.py                            # Local HTTP server entrypoint
  - agents/                           # Agent loop + tool router
  - providers/                        # Cloud provider adapters
  - tools/                            # Filesystem/shell/network tools
  - storage/                          # Session + audit store

## API Sketch (Local HTTP)
- GET /health
- POST /programs/start
- POST /programs/{id}/stop
- GET /programs
- POST /tools/{tool_name}/invoke
- POST /permissions/request
- GET /permissions/pending
- POST /permissions/{id}/approve
- POST /permissions/{id}/deny
- GET /logs/stream (SSE)

## Build/Bootstrap Plan (Draft)
1) App skeleton (Android Studio, Kotlin, WebView, Service)
2) Integrate Python-for-Android runtime
3) Implement local HTTP server and tool router
4) Build minimal Web UI shell
5) Wire multi-channel messaging
6) Add permissions UI + audit log

## Implementation Plan (Step-by-Step)
1) Android wrapper
- MainActivity hosts WebView and loads local UI
- Foreground Service starts on app launch
- JS bridge enables native permission dialogs

2) Python service
- Start CPython runtime in background
- Launch local HTTP server on 127.0.0.1
- Provide session and tool endpoints
- Persist permissions in SQLite

3) Web UI
- Basic layout: chat panel, terminal/log stream, status bar
- Bridge to local HTTP service for messages and logs
- Trigger native consent dialogs when available

4) Security
- Require explicit consent per tool category
- Record audit events with timestamps and user decisions

## Runtime Data Layout
- Editable (SSH HOME)
  - `files/user/www` (Web UI content)
  - `files/user/python/apps` (user Python code)
  - `files/user/.ssh/authorized_keys` (SSH public keys)
- Protected (app-only)
  - `files/protected/app.db` (permissions, audit, credential metadata)
  - `files/protected/secrets/` (encrypted credential vault)
  - `files/protected/ssh/` (Dropbear host keys, logs, pid, auth prompt files)

## Open Items
- Python-for-Android packaging details and bootstrap sequence
- WebView security constraints and local file access strategy
- Provider auth flows and key storage

## Embedded Features
- HTTP client (by python)
- libusb/libvc for native USB access
- TensorFlow Lite support
- ssh server (Dropbear)
- ssh client
  - Dropbear binaries are bundled per-ABI under assets/bin/<abi>/dropbear
- Git/GitHub support (gh command?)

## Credential Vault (Service Access)
- Main vault requires explicit permission and biometric unlock (when available).
- Services can register a scoped vault snapshot for silent startup with explicit user approval.
- Each service stores a code hash and token; access is granted only when both match.
- Service vault values are encrypted with per-service Android Keystore keys via the local vault server.
