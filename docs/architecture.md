# Architecture Overview

## Goals
- Minimal native Android wrapper hosting a WebView-based IDE.
- Embedded CPython runtime via Python-for-Android.
- Local HTTP service on-device for agent APIs, IDE bridge, and tool routing.
- Background service for long-running agent tasks.
- Multi-channel user communication (in-app chat, terminal/log stream, notifications, Slack/Discord webhooks).
- Explicit user consent for all sensitive actions.

## High-Level Components
1) Android Shell (Kotlin)
- WebView UI container (custom IDE shell)
- Terminal panel (PTY-backed or virtual terminal)
- Permission broker (runtime prompts + audit log)
- Background service controller (start/stop/pause agents)
- Notification + webhook bridge

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
- In-app chat panel
- Terminal/log streaming view
- Agent status + progress
- Native consent trigger via JS bridge

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
- Background tasks must post progress updates via notification and in-app status.

## Communication Channels
- In-app chat UI
- Terminal/log stream in WebView
- Android notifications
- Slack/Discord webhooks (MVP)
 - OAuth via In-App Browser for provider auth (Custom Tabs)

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
- POST /sessions
- GET /sessions/{id}
- POST /sessions/{id}/messages
- POST /tools/{tool_name}/invoke
- POST /permissions/request
- GET /permissions/pending
- POST /permissions/{id}/approve
- POST /permissions/{id}/deny
- GET /logs/stream (SSE)
- GET /webhooks
- POST /webhooks
- POST /webhooks/test

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
- Persist sessions/permissions in SQLite

3) Web UI
- Basic layout: chat panel, terminal/log stream, status bar
- Bridge to local HTTP service for messages and logs
- Trigger native consent dialogs when available

4) Webhooks
- Slack/Discord webhook sender in Python service
- Rate limiting and error reporting

5) Security
- Require explicit consent per tool category
- Record audit events with timestamps and user decisions

## Open Items
- Python-for-Android packaging details and bootstrap sequence
- WebView security constraints and local file access strategy
- Webhook auth, rate limiting, and retry policy
- Provider auth flows and key storage
- In-App Browser OAuth wiring to Python service

## Embedded Features
- HTTP client (by python)
- libusb/libvc for native USB access
- TensorFlow Lite support
- ssh server with busybox
- ssh client
- Git/GitHub support (gh command?)
