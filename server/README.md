# Kugutz Local API

This document describes the current on-device APIs.

## Ports
- `127.0.0.1:8765`: Kotlin local control server (entry point used by app/UI and adb port-forwarding).
- `127.0.0.1:8776`: Python worker server.
- `/brain/*` routes are exposed on `:8765` and proxied to worker `:8776`.

## Core Endpoints (`:8765`)
- `GET /health`
- `GET /ui/version`
- `POST /shell/exec`
- `GET /brain/status`
- `GET /brain/config`
- `POST /brain/config`
- `POST /brain/start`
- `POST /brain/stop`
- `POST /brain/inbox/chat`
- `POST /brain/inbox/event`
- `GET /brain/messages`
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
### `GET /brain/status`
Returns runtime state:
- `running`
- `enabled`
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
  "provider_url": "https://api.openai.com/v1/chat/completions",
  "model": "gpt-4o-mini",
  "api_key_credential": "openai_api_key",
  "auto_start": true
}
```

### `POST /brain/start`
Starts the background brain loop.

### `POST /brain/stop`
Stops the background brain loop.

### `POST /brain/inbox/chat`
Queues a chat input.

Example body:
```json
{
  "text": "create hello.py and run it",
  "meta": {}
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

### `GET /brain/messages?limit=20`
Returns recent brain messages (assistant/tool records).

## Shell Exec API
### `POST /shell/exec`
Allowed `cmd` values:
- `python`
- `pip`
- `curl`

Example body:
```json
{
  "cmd": "python",
  "args": "-c \"print('hello')\"",
  "cwd": "/"
}
```

Response shape:
```json
{
  "status": "ok",
  "code": 0,
  "output": "..."
}
```

## Auth and Permissions
- Sensitive tool usage should go through permission requests.
- Credentials are stored as ciphertext by Kotlin control plane with Android Keystore (AES-GCM).
- Service credential access uses the local vault service at `127.0.0.1:8766`.

## SSHD
SSHD is managed by Kotlin control plane APIs on `:8765` (not by Python worker).

## Quick Curl Flow
```bash
adb forward tcp:8765 tcp:8765
curl -sS -X POST http://127.0.0.1:8765/brain/config \
  -H 'Content-Type: application/json' \
  -d '{"model":"gpt-4o-mini","provider_url":"https://api.openai.com/v1/chat/completions","api_key_credential":"openai_api_key"}'
curl -sS -X POST http://127.0.0.1:8765/brain/start
curl -sS -X POST http://127.0.0.1:8765/brain/inbox/chat \
  -H 'Content-Type: application/json' \
  -d '{"text":"hello brain"}'
curl -sS 'http://127.0.0.1:8765/brain/messages?limit=20'
```
