# Architecture Overview (Current)

## Goals
- Minimal native Android wrapper hosting a WebView-based shell.
- Embedded CPython runtime via Python-for-Android (worker mode).
- Local HTTP service on-device for UI + control APIs.
- Background service for always-up control plane + SSHD.
- Explicit user consent for all sensitive actions.

## High-Level Components
1) Android Shell (Kotlin)
- WebView UI container
- Permission broker (runtime prompts + audit log)
- Background service controller
- SSHD manager

2) Kotlin Local HTTP Service
- HTTP server (localhost) exposing:
  - UI files (`/ui/*`)
  - Python worker control (`/python/*`)
  - Permissions + credential vault
  - SSHD status/config + key management

3) Python Worker (Python)
- Started on-demand for agent execution
- Can crash without taking down the app

4) Web UI (HTML/JS)
- Minimal control panel in WebView
- Native actions via JS bridge
- Served from user data directory (assets copied on first launch)

## Process Topology
- Android app launches:
  - Starts background service (Kotlin control plane)
  - Starts local HTTP service on localhost
  - Python worker starts on demand
- WebView loads local UI via http://127.0.0.1:8765/ui/index.html

## Security Model
- Permission broker mediates access to:
  - Filesystem
  - Network
  - Shell commands
- Consent UI required for any tool with external effects.
- Audit trail for all tool calls and AI actions.
- Permissions/SSH keys are stored in a plain Room DB; credentials are encrypted with Android Keystore (AES-GCM) and stored as ciphertext in the same DB.

## Background Execution
- Android foreground service runs Kotlin control plane and SSHD.
- Python worker is started on demand and can be restarted independently.

## Communication Channels
- Android notifications for permission prompts and SSH no-auth prompts.

## Module Layout (Current)
- app/
  - android/
    - app/src/main/java/.../ui        # WebView host
    - app/src/main/java/.../service   # Foreground service + runtime bootstrap
    - app/src/main/java/.../perm      # Permission broker, audit log
    - app/src/main/assets/www         # Web UI shell
    - app/src/main/assets/server      # Embedded Python worker assets
- server/
  - app.py                            # Local HTTP server entrypoint
  - agents/                           # Agent loop + tool router
  - providers/                        # Cloud provider adapters
  - tools/                            # Filesystem/shell/network tools
  - storage/                          # Session + audit store

## API Sketch (Local HTTP)
- GET /health
- POST /python/start
- POST /python/restart
- POST /python/stop
- POST /permissions/request
- GET /permissions/pending
- POST /permissions/{id}/approve
- POST /permissions/{id}/deny
- GET /ssh/status
- POST /ssh/config

## Build/Bootstrap Plan (Draft)
1) App skeleton (Android Studio, Kotlin, WebView, Service)
2) Integrate Python-for-Android runtime
3) Implement local HTTP server and tool router
4) Build minimal Web UI shell
5) Wire notifications for permission/SSH prompts
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
- Minimal control panel (Python worker, SSHD, PIN auth, Wi-Fi IP, Reset UI)
- Trigger native consent dialogs via JS bridge

4) Security
- Require explicit consent per tool category
- Record audit events with timestamps and user decisions

## Runtime Data Layout
-- Editable (SSH HOME)
  - `files/user/` (user-editable root)
  - `files/user/.ssh/authorized_keys` (SSH public keys)
-- UI content
  - `files/www/` (Web UI content, copied from assets; resettable)
-- Protected (app-only)
  - `files/protected/app.db` (permissions, ssh keys, audit, encrypted credential ciphertext)
  - `files/protected/ssh/` (Dropbear host keys, logs, pid, auth prompt files)
-- Runtime/supporting
  - `files/bin/` (bundled native binaries copied at runtime)
  - `files/pyenv/` (embedded CPython environment)
  - `files/server/` (Python worker assets)
  - `files/profileInstalled` (bootstrap marker)

Note: `files/protected/` is a **policy boundary**, not a kernel-enforced sandbox. Any code running under the app UID (including Python) could access it unless explicitly blocked. Treat it as sensitive and enforce access via app policy and encryption.

## Open Items
- Python-for-Android packaging details and bootstrap sequence
- WebView security constraints and local file access strategy
- Provider auth flows and key storage

## Embedded Features
- ssh server (Dropbear; bundled as native libs)
- Git/GitHub support (gh command?)

## Credential Vault (Service Access)
- Main vault requires explicit permission and biometric unlock (when available).
- Services can register a scoped vault snapshot for silent startup with explicit user approval.
- Each service stores a code hash and token; access is granted only when both match.
- Service vault values are encrypted with per-service Android Keystore keys via the local vault server.
