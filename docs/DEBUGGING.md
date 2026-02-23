# Debugging Notes (App + Agent)

This document collects practical debugging tips for me.things app/agent behavior.

## Quick Mental Model

- App server + built-in agent runtime: `127.0.0.1:33389`
- Termux worker (optional, general-purpose Linux environment for shell tasks): `127.0.0.1:8776`
- All `/brain/*` endpoints are handled by the built-in `AgentRuntime` — no external process proxy.

The agent loop records:
- user messages
- assistant messages
- tool/action records (including `shell_exec` output)

Chat messages are persisted in `files/agent/agent.db` on-device (SQLite). Legacy messages from `files/protected/app.db` (if present) are migrated on first agent startup.

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

## me.sync Real Run Over ADB (No QR Scan)

To run actual me.sync v3 export/import between two devices without scanning QR:

```bash
scripts/me_sync_adb_run.sh \
  --exporter-serial <serial-a> \
  --importer-serial <serial-b>
```

This script:
1. creates a v3 ticket on exporter
2. transfers `ticket_uri` through host
3. calls importer `/me/sync/v3/import/apply` with that ticket

Useful options:

```bash
scripts/me_sync_adb_run.sh \
  --exporter-serial <serial-a> \
  --importer-serial <serial-b> \
  --auto-allow \
  --nearby-timeout-ms 180000 \
  --allow-fallback true \
  --wipe-existing true
```

Notes:
- If permission approval is pending, the script exits and tells you to approve then re-run.
- `--auto-allow` uses `scripts/adb_auto_allow.sh` and is best-effort only.

## me.sync Regression Run (Wi-Fi ON/OFF)

To run a repeatable two-case regression test on two connected devices:

```bash
scripts/me_sync_adb_regression.sh \
  --exporter-serial <serial-a> \
  --importer-serial <serial-b>
```

The script runs:
1. `wifi_on` case
2. `wifi_off` case

and writes logs/results under `logs/me_sync_regression_<timestamp>/`:
- `wifi_on.log`, `wifi_off.log` (raw command output)
- `wifi_on.json`, `wifi_off.json` (parsed per-case summary)
- `summary.json` (combined result with `all_passed`)

If any case fails, the script exits non-zero so it can be used in CI-like local checks.

## me.me Internal Endpoints (Debug Only)

These `me.me` endpoints are internal plumbing and are intentionally excluded from normal
agent-facing documentation. Use them only for debugging/verification:

- `GET /me/me/routes`
- `GET /me/me/config`
- `POST /me/me/config`
- `POST /me/me/scan`
- `POST /me/me/connect`
- `POST /me/me/accept`
- `POST /me/me/disconnect`
- `POST /me/me/messages/pull`
- `GET /me/me/relay/status`
- `GET /me/me/relay/config`
- `POST /me/me/relay/config`
- `POST /me/me/relay/register`
- `POST /me/me/relay/ingest`

Debug intent:
- Transport/route diagnosis (`lan`, `ble`, `gateway`) and fallback reasoning.
- Connection state repair and one-shot scanning during local experiments.
- Relay bridge integration testing (for example FCM adapter -> local ingest).

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
- Tool outputs (including shell errors) appear as `role=tool` messages.

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
- `files/agent/agent.db`: chat history + settings + audit log (agent database)
- `files/agent/scheduler.db`: code scheduler schedules + execution log
- `files/protected/app.db`: legacy chat history + permissions (read-only reference; migrated to `agent.db` on first agent start)
- `files/user/journal/<session_id>/`: per-session journal (CURRENT.md + entries.jsonl)
- `files/system/docs/AGENTS.md`, `files/system/docs/TOOLS.md`: system agent docs (read-only, always current with app version)
- `files/user/AGENTS.md`, `files/user/TOOLS.md`: user-editable agent rules/preferences (seeded on first install, never force-overwritten after v3 migration)

## Common Failure Patterns

### Built-in tools (always available)
- `run_js` (QuickJS engine) and `run_curl` (native HTTP) work without Termux.
- `run_js` errors include `console_output` captured during execution.
- `run_curl` returns `{status, http_status, headers, body}` for successful requests.

### Shell tool errors (requires Termux)
- If Termux is not installed, shell tools (`run_python`, `run_pip`) return `shell_unavailable`.
- The agent can call `device_api("termux.show_setup")` to prompt the user to open the setup wizard in the UI.
- `run_curl` no longer requires Termux — it uses native HTTP. Legacy `run_curl(args, cwd)` form falls back to Termux shell.
- Shell errors are returned in the tool call output and stored in chat messages.
- `python -` (stdin mode) is not supported in `shell_exec` (no interactive stdin). Use:
  - `python -c "..."`, or
  - write a script file under user root and run it.

### Cloud 401 Unauthorized
- Usually indicates the stored API key is wrong/not saved.
- The runtime surfaces a short error into the chat timeline so the UI is not stuck "processing".

### LLM API errors
- `LlmApiException` is logged with HTTP status and response body.
- Check logcat for `LlmClient` tag for SSE parsing errors.

## Scheduler (Code Execution)

The scheduler persists to `files/agent/scheduler.db` (separate from agent.db). It starts automatically with the local HTTP server and ticks every 60 seconds.

List all schedules:

```bash
curl -sS 'http://127.0.0.1:43389/scheduler/schedules'
```

Create a minutely JS schedule:

```bash
curl -sS -X POST 'http://127.0.0.1:43389/scheduler/create' \
  -H 'Content-Type: application/json' \
  -d '{"name":"test","launch_type":"periodic","schedule_pattern":"minutely","runtime":"run_js","code":"Date.now()"}'
```

Check execution log:

```bash
curl -sS -X POST 'http://127.0.0.1:43389/scheduler/log' \
  -H 'Content-Type: application/json' \
  -d '{"id":"<schedule-id>","limit":20}'
```

Engine status (running schedules):

```bash
curl -sS 'http://127.0.0.1:43389/scheduler/status'
```

Trigger a schedule immediately (skip waiting for next tick):

```bash
curl -sS -X POST 'http://127.0.0.1:43389/scheduler/trigger' \
  -H 'Content-Type: application/json' \
  -d '{"id":"<schedule-id>"}'
```

Notes:
- Permission-gated endpoints (create/update/delete/trigger) may return `permission_required` when called via `device_api`.
- Daemon schedules restart automatically on service start.
- One-time schedules auto-disable after firing.
- `run_python` schedules require Termux installed.
- Max 50 schedules, 200 log entries per schedule, 2000 global log entries.
- Stale `running` log entries are marked `interrupted` on engine restart.

## logcat Grep

```bash
adb -s <serial> logcat -d | rg -n 'AgentRuntime|LlmClient|ToolExecutor|SchedulerEngine|brain/inbox/chat|/shell/exec|/web/search|permission_required|401'
```
