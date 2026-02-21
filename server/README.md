# me.things Local API

This document describes the current on-device APIs.

## Ports
- `127.0.0.1:33389`: App local server (entry point for app/UI and adb port-forwarding). Handles all endpoints including `/brain/*`.
- `127.0.0.1:8776`: Termux worker (optional, general-purpose Linux environment for shell tools).

## Core Endpoints (`:33389`)
- `GET /health`
- `GET /ui/version`
- `POST /shell/exec` (requires Termux — general shell commands)
- `POST /shell/session/*` (requires Termux — PTY sessions)
- `POST /shell/fs/*` (requires Termux — file access)
- `GET /brain/status`
- `GET /brain/config`
- `POST /brain/config`
- `POST /brain/start`
- `POST /brain/stop`
- `POST /brain/interrupt`
- `POST /brain/inbox/chat`
- `POST /brain/inbox/event`
- `GET /brain/messages`
- `GET /brain/sessions`
- `GET /brain/events` (SSE)
- `GET /brain/journal/current`
- `POST /brain/journal/current`
- `POST /brain/journal/append`
- `GET /brain/journal/list`
- `GET /brain/memory`
- `POST /brain/memory`
- `POST /brain/agent/bootstrap`
- `POST /brain/session/delete`
- `POST /brain/session/rename`
- `POST /permissions/request`
- `GET /permissions/pending`
- `GET /permissions/{id}`
- `POST /permissions/{id}/approve`
- `POST /permissions/{id}/deny`
- `POST /tools/{tool}/invoke`
- `GET /logs/stream` (SSE)
- `GET /audit/recent`
- `POST /programs/start`
- `GET /programs`
- `POST /programs/{id}/stop`

## Brain API

All `/brain/*` routes are handled by the built-in `AgentRuntime`. No external process proxy.

### `GET /brain/status`
Returns runtime state:
- `running`
- `busy`
- `queue_size`
- `last_error`
- `last_processed_at`
- `model`
- `provider_url`

### `GET /brain/config`
Returns current config.

### `POST /brain/config`
Updates runtime config fields.

Example body:
```json
{
  "vendor": "openai",
  "base_url": "https://api.openai.com/v1",
  "model": "gpt-4.1-mini",
  "api_key": "sk-..."
}
```

### `POST /brain/agent/bootstrap`
Configures and starts the agent in one step. Reads vendor/url/model/key from brain config SharedPreferences. Supports both OpenAI-compatible and Anthropic providers.

### `POST /brain/start`
Starts the background agent loop.

### `POST /brain/stop`
Stops the background agent loop.

### `POST /brain/interrupt`
Interrupts the current agent processing. Optional fields: `item_id`, `session_id`, `clear_queue`.

### `POST /brain/inbox/chat`
Queues a chat input.

Example body:
```json
{
  "text": "create hello.py and run it",
  "meta": {"session_id": "my-session"}
}
```

### `POST /brain/inbox/event`
Queues an external event.

Example body:
```json
{
  "name": "wifi_connected",
  "payload": {
    "ssid": "example"
  }
}
```

### `GET /brain/messages?session_id=...&limit=20`
Returns recent brain messages for a session.

### `GET /brain/sessions?limit=50`
Lists chat sessions with message counts.

### `GET /brain/events`
SSE stream of agent events (tool calls, responses, errors).

## Shell API
All shell endpoints require Termux to be installed. The worker auto-starts when needed.

Note: `run_js` (QuickJS engine) and `run_curl` (native HTTP) are handled in-process by the app and do not use these endpoints or Termux.

### `POST /shell/exec`
Execute a one-shot shell command. Returns separate stdout/stderr.

New format (general command):
```json
{
  "command": "ls -la ~/methings",
  "cwd": "/home",
  "timeout_ms": 60000,
  "env": {"MY_VAR": "value"}
}
```

Legacy format (python/pip/curl only):
```json
{
  "cmd": "python",
  "args": "-c \"print('hello')\"",
  "cwd": "/"
}
```

Response:
```json
{
  "status": "ok",
  "exit_code": 0,
  "stdout": "...",
  "stderr": "..."
}
```

### Session Endpoints
- `POST /shell/session/start` — Create PTY bash session. Body: `{cwd?, rows?, cols?, env?}`. Returns `{session_id, output}`.
- `POST /shell/session/{id}/exec` — Send command. Body: `{command, timeout?}`. Returns `{output, alive}`.
- `POST /shell/session/{id}/write` — Raw stdin write. Body: `{input}`.
- `POST /shell/session/{id}/read` — Read buffered output. Returns `{output, alive}`.
- `POST /shell/session/{id}/resize` — Resize terminal. Body: `{rows, cols}`.
- `POST /shell/session/{id}/kill` — Terminate session.
- `GET /shell/session/{id}/status` — Session status. Returns `{alive, exit_code, idle_seconds}`.
- `GET /shell/session/list` — List active sessions.

### File System Endpoints
All paths validated to be under Termux `$HOME`.

- `POST /shell/fs/read` — Read file. Body: `{path, max_bytes?, offset?}`.
- `POST /shell/fs/write` — Write file. Body: `{path, content, encoding?}`.
- `POST /shell/fs/list` — List directory. Body: `{path, show_hidden?}`.
- `POST /shell/fs/stat` — File metadata. Body: `{path}`.
- `POST /shell/fs/mkdir` — Create directory. Body: `{path, parents?}`.
- `POST /shell/fs/delete` — Delete file/dir. Body: `{path, recursive?}`.
```

## Auth and Permissions
- Sensitive tool usage should go through permission requests.
- Credentials are stored as ciphertext by the app with Android Keystore (AES-GCM).

## SSHD
SSHD is managed by the app's control plane APIs on `:33389` (via Termux OpenSSH).

## Quick Curl Flow
```bash
adb forward tcp:33389 tcp:33389
curl -sS -X POST http://127.0.0.1:33389/brain/config \
  -H 'Content-Type: application/json' \
  -d '{"vendor":"openai","base_url":"https://api.openai.com/v1","model":"gpt-4.1-mini","api_key":"sk-..."}'
curl -sS -X POST http://127.0.0.1:33389/brain/agent/bootstrap
curl -sS -X POST http://127.0.0.1:33389/brain/inbox/chat \
  -H 'Content-Type: application/json' \
  -d '{"text":"hello brain","meta":{"session_id":"debug"}}'
curl -sS 'http://127.0.0.1:33389/brain/messages?session_id=debug&limit=20'
```
