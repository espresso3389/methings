# Debugging Notes (App + Agent)

This document collects practical debugging tips for Kugutz app/agent behavior.

## Quick Mental Model

- Kotlin control plane: `127.0.0.1:8765`
- Python worker: `127.0.0.1:8776`
- Many `/brain/*` endpoints are served by Kotlin and proxied to the worker.

The agent loop records:
- user messages
- assistant messages
- tool/action records (including `shell_exec` output)

All of that is persisted in `files/protected/app.db` on-device.

## Port-Forward (From Host)

```bash
adb -s <serial> forward tcp:18765 tcp:8765
```

After this, access the APIs at `http://127.0.0.1:18765`.

## See The Actual Chat Transcript

`/brain/messages` is session-scoped. First discover which sessions exist:

```bash
curl -sS 'http://127.0.0.1:18765/brain/sessions?limit=20'
```

Then fetch the transcript for a session:

```bash
curl -sS 'http://127.0.0.1:18765/brain/messages?session_id=<session_id>&limit=200'
```

Notes:
- `role` can be `user`, `assistant`, or `tool`.
- Tool outputs (including Python errors) appear as `role=tool` messages.

## Filesystem Scope (Reducing Permission Noise)

By default, the built-in filesystem tools are restricted to the user root (`files/user`).

For debugging, you can allow reading/writing anywhere under the app private files dir
(includes `protected/` and `server/`):

```bash
curl -sS -X POST 'http://127.0.0.1:18765/brain/config' \\
  -H 'Content-Type: application/json' \\
  -d '{\"fs_scope\":\"app\"}'
```

To restore the default restriction:

```bash
curl -sS -X POST 'http://127.0.0.1:18765/brain/config' \\
  -H 'Content-Type: application/json' \\
  -d '{\"fs_scope\":\"user\"}'
```

## Logs / Audit Trail

Recent audit events (persisted):

```bash
curl -sS 'http://127.0.0.1:18765/audit/recent?limit=200'
```

Live log stream (SSE):

```bash
curl -N 'http://127.0.0.1:18765/logs/stream'
```

## On-Device Files (Debug Builds)

Android blocks `adb shell ls /data/data/...` without root. For debug builds you can use `run-as`:

```bash
adb -s <serial> shell run-as jp.espresso3389.kugutz ls -la files
adb -s <serial> shell run-as jp.espresso3389.kugutz ls -la files/protected
adb -s <serial> shell run-as jp.espresso3389.kugutz ls -la files/user
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
