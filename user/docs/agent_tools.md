# Agent Tools Reference

This document covers agent-side tool conventions and Python runtime helpers
that are **not** part of the HTTP API (see `openapi/` for the full API spec).

## device_api Tool

The agent uses `device_api(action, payload, detail)` to call the local HTTP
control plane on `http://127.0.0.1:33389`. Action names map to Kotlin HTTP
endpoints (e.g. `camera.capture` → `POST /camera/capture`).

See `openapi/openapi.yaml` for the complete endpoint reference.

## Identity + Permissions

- Most device actions require user approval via the permission broker.
- Approvals are remembered per `(identity, capability)` for the configured scope.
- The chat UI sets `identity` to the chat `session_id`, so "approve once per session" works.
- If a `device_api` action returns `permission_required`, ask the user to approve the prompt and retry.
- Uploaded files (`/user/upload`) are treated as an explicit read grant for those uploaded files.

See [permissions.md](permissions.md) for scopes, identity model, and USB special cases.

## Agent Filesystem Tools (Python Runtime)

These are Python runtime functions, not HTTP endpoints.

### read_file

Parameters:
- `path` (string): user-root relative path (or `$sys/...` for system docs).
- `max_bytes` (integer): max bytes to read.

Response:
- Text: `{status, path, content, truncated, binary:false, file_size, read_offset, read_size}`.
- Binary/media: `{status:"error", error:"binary_file_not_text", ...}`.

### read_binary_file

Parameters:
- `path` (string): user-root relative path.
- `offset_bytes` (integer, optional, default `0`): byte offset from file start.
- `size_bytes` (integer, optional, default `262144`): bytes to read.
- `encoding` (string, optional): currently only `"base64"` is supported.

Response:
- `{status, path, binary:true, media_type, encoding:"base64", body_base64, file_size, read_offset, read_size, eof}`.

### list_dir

Lists files in a user-root directory.

## System Reference Docs ($sys/ prefix)

System reference docs (`docs/`, `examples/`, `lib/`) are extracted to `files/system/`
and always overwritten on app start to match the current app version. The agent
accesses them via the `$sys/` prefix in filesystem tools, which routes through
`GET /sys/file` and `GET /sys/list`.

## Chat UI Shortcuts

### Inline Preview Cards

Include `rel_path: <path>` in chat messages to trigger inline preview cards
in the app UI. For Marp presentations, append `#page=N` to show a specific slide.

### Settings Navigation

Type `settings: <section_id_or_setting_key>` in the chat to navigate to a
Settings section (e.g. `settings: permissions`, `settings: remember_approvals`).
