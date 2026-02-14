# Debugging Notes (App + Agent)

This document collects practical debugging tips for me.things app/agent behavior.

## Quick Mental Model

- Kotlin control plane: `127.0.0.1:33389`
- Python worker: `127.0.0.1:8776`
- Many `/brain/*` endpoints are served by Kotlin and proxied to the worker.

The agent loop records:
- user messages
- assistant messages
- tool/action records (including `shell_exec` output)

All of that is persisted in `files/protected/app.db` on-device.

## Port-Forward (From Host)

### Via ADB (USB or Wi-Fi paired)

```bash
adb -s <serial> forward --remove tcp:43389 || true
adb -s <serial> forward tcp:43389 tcp:33389
```

After this, access the APIs at `http://127.0.0.1:43389`.

Important:
- Do not use `adb reverse tcp:33389 tcp:33389` for this app. Reverse binds on the device and can block the app server from starting.

### Via SSH Tunnel (Remote / No ADB)

If the device's SSH server is running, you can forward the GUI and API port over SSH:

```bash
ssh <user>@<device-ip> -p <ssh-port> -L 33389:127.0.0.1:33389
```

Then open `http://127.0.0.1:33389` in a browser on the host to access the full WebView UI and all local HTTP APIs.

## Hot Reload Web UI (No APK Rebuild)

During UI iteration, you can update the on-device WebView assets in-place without rebuilding/installing.

Workflow:
1. Edit the UI in-repo: `app/android/app/src/main/assets/www/index.html`
2. Push it into the app private directory (`files/www`) via `adb shell run-as`
3. Trigger a WebView reload via the local API `POST /ui/reload`

Helper script:

```bash
# Optionally pass a device serial (recommended)
scripts/ui_hot_reload.sh <serial>
# Optional ports:
# METHINGS_LOCAL_PORT=43389 METHINGS_DEVICE_PORT=33389 scripts/ui_hot_reload.sh <serial>
```

Manual equivalent:

```bash
adb -s <serial> push app/android/app/src/main/assets/www/index.html /data/local/tmp/methings.index.html
date +%s > /tmp/methings.www.version && adb -s <serial> push /tmp/methings.www.version /data/local/tmp/methings.www.version
adb -s <serial> shell run-as jp.espresso3389.methings mkdir -p files/www
adb -s <serial> shell run-as jp.espresso3389.methings cp /data/local/tmp/methings.index.html files/www/index.html
adb -s <serial> shell run-as jp.espresso3389.methings cp /data/local/tmp/methings.www.version files/www/.version
adb -s <serial> forward tcp:43389 tcp:33389
curl -X POST http://127.0.0.1:43389/ui/reload -H 'Content-Type: application/json' -d '{}'
```

Notes:
- This changes the *device* UI only. To make changes permanent, also commit them in the repo and later rebuild/install normally.
- `Reset UI` in the app settings overwrites `files/www` from the APK assets.

## Sync user defaults (device <-> repo)

Normal builds do not auto-sync `user/` into APK assets anymore. This prevents surprise git changes.

If you intentionally edit agent docs or examples directly on device (`files/user/...`) and want to keep them,
use explicit sync:

```bash
# Pull device files/user -> repo user/
scripts/user_defaults_sync.sh pull <serial>

# Push repo user/ -> device files/user
scripts/user_defaults_sync.sh push <serial>
```

Build-time opt-in (only when you want APK `assets/user_defaults` refreshed from `repo/user`):

```bash
cd app/android
METHINGS_SYNC_USER_DEFAULTS=1 ./gradlew assembleDebug
# or
./gradlew -Pmethings.syncUserDefaults=1 assembleDebug
# or run only the explicit helper task
./gradlew :app:syncUserDefaultsOnBuild
```

## See The Actual Chat Transcript

`/brain/messages` is session-scoped. First discover which sessions exist:

```bash
curl -sS 'http://127.0.0.1:43389/brain/sessions?limit=20'
```

Then fetch the transcript for a session:

```bash
curl -sS 'http://127.0.0.1:43389/brain/messages?session_id=<session_id>&limit=200'
```

Notes:
- `role` can be `user`, `assistant`, or `tool`.
- Tool outputs (including Python errors) appear as `role=tool` messages.

## Journal (Agent Continuity Notes)

The app also keeps a per-session journal (file-backed under `files/user/journal/<session_id>/...`).

Fetch the current journal note:

```bash
curl -sS 'http://127.0.0.1:43389/brain/journal/current?session_id=<session_id>'
```

List recent journal entries:

```bash
curl -sS 'http://127.0.0.1:43389/brain/journal/list?session_id=<session_id>&limit=30'
```

## Talk To The Agent (UI)

The in-app WebView control panel includes an **Agent Console** section:
- Pick a `session_id` (keeps transcripts and “approve once per session” permissions separate)
- Tap `Start` if the brain loop is not enabled
- Type a message and tap `Send` (or `Ctrl+Enter`)

This UI uses the same local HTTP APIs as the curl examples below.

## Talk To The Agent (HTTP)

Start the brain loop:

```bash
curl -sS -X POST 'http://127.0.0.1:43389/brain/start' -H 'Content-Type: application/json' -d '{}'
```

Send a chat message to a specific session:

```bash
curl -sS -X POST 'http://127.0.0.1:43389/brain/inbox/chat' \
  -H 'Content-Type: application/json' \
  -d '{"text":"hello","meta":{"session_id":"debug"}}'
```

## Filesystem Scope (Reducing Permission Noise)

By default, the built-in filesystem tools are restricted to the user root (`files/user`).

For debugging, you can allow reading/writing anywhere under the app private files dir
(includes `protected/` and `server/`):

```bash
curl -sS -X POST 'http://127.0.0.1:43389/brain/config' \\
  -H 'Content-Type: application/json' \\
  -d '{\"fs_scope\":\"app\"}'
```

To restore the default restriction:

```bash
curl -sS -X POST 'http://127.0.0.1:43389/brain/config' \\
  -H 'Content-Type: application/json' \\
  -d '{\"fs_scope\":\"user\"}'
```

## Logs / Audit Trail

Recent audit events (persisted):

```bash
curl -sS 'http://127.0.0.1:43389/audit/recent?limit=200'
```

Live log stream (SSE):

```bash
curl -N 'http://127.0.0.1:43389/logs/stream'
```

## On-Device Files (Debug Builds)

Android blocks `adb shell ls /data/data/...` without root. For debug builds you can use `run-as`:

```bash
adb -s <serial> shell run-as jp.espresso3389.methings ls -la files
adb -s <serial> shell run-as jp.espresso3389.methings ls -la files/protected
adb -s <serial> shell run-as jp.espresso3389.methings ls -la files/user
```

Important files:
- `files/protected/app.db`: audit + chat history + settings (local debug database)
- `files/user/AGENTS.md`, `files/user/TOOLS.md`: user-root agent docs shipped/reset by the app

## Common Failure Patterns

### Python `shell_exec` errors
- Python errors are returned in the tool call `output` and also stored in chat tool messages.
- `python -` (stdin mode) is not supported in `shell_exec` (no interactive stdin). Use:
  - `python -c "..."`, or
  - write a script file under user root and run it.

### Cloud 401 Unauthorized
- Usually indicates the stored API key is wrong/not saved.
- The runtime surfaces a short error into the chat timeline so the UI is not stuck "processing".

## logcat Grep

```bash
adb -s <serial> logcat -d | rg -n 'BrainRuntime|brain/inbox/chat|/shell/exec|/web/search|permission_required|401'
```
