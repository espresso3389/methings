# me.things Tools (User Root)

This file is a quick reference for the agent's tools, invocation patterns, and chat rendering rules.

For the complete device API action map, see `$sys/docs/api_reference.md` (read via `read_file("$sys/docs/api_reference.md")`).

## Filesystem Tools (User Root Only)

- `list_dir(path, show_hidden, limit)`
- `read_file(path, max_bytes)`
- `write_file(path, content, append)`
- `mkdir(path, parents)`
- `move_path(src, dst, overwrite)`
- `delete_path(path, recursive)`

Use these instead of shell commands like `ls`/`cat`.

Paths starting with `$sys/` read from **system-protected reference docs** (read-only, always current with app version). Use `list_dir("$sys/docs")` to discover available reference documentation. Writing, deleting, or moving `$sys/` paths returns `system_files_read_only`.

## Execution Tools (Allowlist)

- `run_python(args, cwd)`
- `run_pip(args, cwd)`
- `run_curl(args, cwd)`

Notes:
- `python -` (stdin) is not supported (no interactive stdin). Use `python -c "..."` or write a script file and run it.

## Web Search Tool (Permission-Gated)

- `web_search(query, max_results, provider)`

Notes:
- DuckDuckGo is not a full web-search API here; it is the Instant Answer API and can be weak for non-English queries.
- Prefer `provider="auto"` (default) which uses Brave Search API if configured, else DuckDuckGo.
- If you need better non-English search, use `provider="brave"` and store `brave_search_api_key` in vault.

## Kotlin Control Plane Tool

- `device_api(action, payload, detail)`

Used for allowlisted device control-plane actions. Some actions require user approval and will return `permission_required`.

The full action map (88 actions across 16 domains) is in `$sys/docs/api_reference.md`.

## Remote Access via SSH Tunnel

If the device's SSH server is running, a remote user can forward the local API/UI port over SSH:

```bash
ssh <user>@<device-ip> -p <ssh-port> -L 8765:127.0.0.1:8765
```

Then `http://127.0.0.1:8765` on the remote machine gives full access to the WebView UI and all local HTTP APIs.

SSH actions through `device_api`:
- `ssh.exec`: one-shot remote command.
- `ssh.scp`: upload/download files via SCP.
- `ssh.ws.contract`: websocket contract for interactive SSH (`/ws/ssh/interactive`).

Examples:

```json
{
  "type": "tool_invoke",
  "tool": "device_api",
  "args": {
    "action": "ssh.exec",
    "payload": {
      "host": "192.168.1.20",
      "user": "kawasaki",
      "port": 22,
      "command": "uname -a"
    },
    "detail": "Run uname on remote host"
  }
}
```

```json
{
  "type": "tool_invoke",
  "tool": "device_api",
  "args": {
    "action": "ssh.scp",
    "payload": {
      "direction": "upload",
      "host": "192.168.1.20",
      "user": "kawasaki",
      "local_path": "captures/latest.jpg",
      "remote_path": "/tmp/latest.jpg"
    },
    "detail": "Upload capture to remote host"
  }
}
```

## App SSH Shell Commands (Outbound)

When you are inside the app's SSH shell prompt (it looks like `methings>`), a few outbound SSH helpers are available:

- `ssh user@host <command>`
  - Interactive `ssh user@host` (no command) is not supported (no PTY). Use exec-form only.
- `put <local_file> <user@host:remote_path_or_dir>`
- `get <user@host:remote_file> <local_path>`

Notes:
- `scp` exists but may stall against some OpenSSH-for-Windows targets. Prefer `put/get` when `scp` stalls.
- Node.js may be available as `node` plus JS tools `npm`/`npx` (if the runtime is bundled).
- These outbound commands are available in the interactive SSH shell prompt (`methings>`), not via `device_api`.
- `device_api` action `shell.exec` only allows `python`/`pip`/`curl` and cannot run `ssh`/`put`/`get`.

### SSH Device API Quickstart

For SSHD and authorized key management, use `device_api` actions:

- `ssh.status`, `ssh.config`
- `ssh.keys.list`, `ssh.keys.add`, `ssh.keys.delete`
- `ssh.keys.policy.get`, `ssh.keys.policy.set`
- `ssh.pin.status`, `ssh.pin.start`, `ssh.pin.stop`
- `ssh.noauth.status`, `ssh.noauth.start`, `ssh.noauth.stop`

Delete key tips:
- Prefer `ssh.keys.list` first and delete by returned `fingerprint`.
- `ssh.keys.delete` also accepts `key` in payload when fingerprint is unknown.

---

## Quickstarts

Minimal copy-paste examples for common tasks. For full payload docs and all actions, see `$sys/docs/api_reference.md` and the domain docs linked from there.

### Camera (Take a Picture)

Use `device_api` (do not try to `pip install` camera bindings). Details: `$sys/docs/camera.md`

```json
{
  "type": "tool_invoke",
  "tool": "device_api",
  "args": {
    "action": "camera.capture",
    "payload": { "path": "captures/latest.jpg", "lens": "back" },
    "detail": "Take a picture"
  }
}
```

On success, the response includes `rel_path`. Include `rel_path: <path>` in your message to show inline.

### UVC (USB Webcam)

Capture a single MJPEG frame from a connected UVC camera. Details: `$sys/docs/uvc.md`

```json
{
  "type": "tool_invoke",
  "tool": "device_api",
  "args": {
    "action": "uvc.mjpeg.capture",
    "payload": { "handle": "<usb_handle>", "width": 1280, "height": 720, "fps": 30, "path": "captures/uvc_latest.jpg" },
    "detail": "Capture one MJPEG frame from UVC camera"
  }
}
```

USB permissions: approving `device.usb` in-app is necessary but not sufficient. Android also requires an OS-level USB permission. See `$sys/docs/permissions.md`.

### Location (GPS)

```json
{
  "type": "tool_invoke",
  "tool": "device_api",
  "args": {
    "action": "location.get",
    "payload": { "high_accuracy": true, "timeout_ms": 12000 },
    "detail": "Get current device location"
  }
}
```

### Sensors (Realtime Streams)

1. List available sensors:

```json
{ "type": "tool_invoke", "tool": "device_api",
  "args": { "action": "sensor.list", "payload": {}, "detail": "List available sensors" } }
```

2. Connect to WebSocket `/ws/sensors?sensors=a,g,m&rate_hz=200` for realtime data.

Full protocol (sensor keys, query params, message format): `$sys/docs/sensors.md`.

### Llama.cpp (Local GGUF / MioTTS)

Use `device_api` actions (not raw shell). Details: `$sys/docs/llama.md`

```json
{
  "type": "tool_invoke",
  "tool": "device_api",
  "args": {
    "action": "llama.tts",
    "payload": {
      "model": "MioTTS-0.1B-Q8_0.gguf",
      "text": "Hello from me.things",
      "output_path": "captures/miotts.wav",
      "args": ["-m", "{{model}}", "-p", "{{text}}", "-o", "{{output_path}}"]
    },
    "detail": "Run llama-tts for local speech synthesis"
  }
}
```

Important:
- Use `-p` / `-o` for this runtime. `--text` may fail.
- Avoid `--tts-oute-default` by default; it may depend on remote preset files.
- Prefer explicit local args for MioTTS and pass local `--model-vocoder` when needed.
- `llama.tts` returns `output_wav` metadata and uses a short-output guard by default (`min_output_duration_ms=400`).
- If you intentionally need very short clips, set `min_output_duration_ms: 0`.

After synthesis, include `rel_path: captures/miotts.wav` in your assistant message to render audio inline.

### Media Audio Playback

Play from file:

```json
{ "type": "tool_invoke", "tool": "device_api",
  "args": { "action": "media.audio.play", "payload": { "path": "captures/miotts.wav" }, "detail": "Play audio" } }
```

Play from base64: use `{ "audio_b64": "<base64>", "ext": "wav" }` in payload.

### Audio Recording

Record audio to AAC (.m4a). Details: `$sys/docs/recording.md`

```json
{ "type": "tool_invoke", "tool": "device_api",
  "args": { "action": "audio.record.start", "payload": {}, "detail": "Start audio recording" } }
```

Stop and get the file:

```json
{ "type": "tool_invoke", "tool": "device_api",
  "args": { "action": "audio.record.stop", "payload": {}, "detail": "Stop audio recording" } }
```

Returns `rel_path`, `duration_ms`, `size_bytes`. Optional start payload: `path`, `sample_rate`, `channels`, `bitrate`, `max_duration_s`.

For live PCM streaming: `audio.stream.start` → connect WebSocket `/ws/audio/pcm`.

### Video Recording

Record video to H.265/H.264 (.mp4) using the device camera. Details: `$sys/docs/recording.md`

```json
{ "type": "tool_invoke", "tool": "device_api",
  "args": { "action": "video.record.start", "payload": { "lens": "back" }, "detail": "Start video recording" } }
```

Stop:

```json
{ "type": "tool_invoke", "tool": "device_api",
  "args": { "action": "video.record.stop", "payload": {}, "detail": "Stop video recording" } }
```

Returns `rel_path`, `duration_ms`, `size_bytes`, `codec`. Optional start payload: `lens`, `resolution` (720p/1080p/4k), `max_duration_s`.

For live frame streaming: `video.stream.start` → connect WebSocket `/ws/video/frames`.

### Screen Recording

Record the device screen to H.265/H.264 (.mp4). Requires user consent dialog each time. Details: `$sys/docs/recording.md`

```json
{ "type": "tool_invoke", "tool": "device_api",
  "args": { "action": "screenrec.start", "payload": {}, "detail": "Start screen recording" } }
```

Stop:

```json
{ "type": "tool_invoke", "tool": "device_api",
  "args": { "action": "screenrec.stop", "payload": {}, "detail": "Stop screen recording" } }
```

Optional start payload: `resolution` (720p/1080p), `bitrate`, `max_duration_s`.

### Media Decode Streaming

Decode existing audio/video files to raw data over WebSocket. Details: `$sys/docs/media_stream.md`

```json
{ "type": "tool_invoke", "tool": "device_api",
  "args": { "action": "media.stream.audio.start", "payload": { "source_file": "recordings/audio/rec.m4a" }, "detail": "Decode audio to PCM" } }
```

Returns `stream_id` and `ws_path`. Connect to `/ws/media/stream/<stream_id>` for decoded frames.

---

## Chat Rendering Rules

### Show Media Inline (Required)

The WebView chat UI auto-renders media previews when a message contains:

```
rel_path: captures/latest.jpg
```

When you create, save, capture, or reference a user file, you MUST include `rel_path: <path>` (or `html_path:` for HTML) in your assistant message. This applies to:
- Captured images/audio/video
- Generated scripts, reports, or data files
- **Listing files you created** — never list bare filenames; always emit a `rel_path:` line for each file so the user gets clickable, previewable cards instead of plain text

User UX notes:
- Tapping an image opens a fullscreen viewer (swipe between images, pinch zoom).
- Media cards include a Share icon.

To fetch the image onto your dev machine: `GET /user/file?path=<rel_path>`

#### Marp Slide Navigation (`#page=N`)

For Marp presentation files, append `#page=N` (0-indexed) to navigate to a specific slide:

```
rel_path: presentations/demo.md#page=3
```

- The inline preview thumbnail shows the specified slide instead of the first.
- Tapping the card opens the viewer scrolled to that slide.
- For non-Marp files and plain markdown, the fragment is silently ignored.
- The viewer control API (`/ui/viewer/open`) also supports this fragment.

### Open Agent HTML From Chat

If your reply includes `html_path: ...`, the app will show an OPEN card.

```text
html_path: agent_ui/sample.html
```

Rules:
- Prefer `html_path:` (use `open_html:` only for backward compatibility).
- The path must be user-root relative (no absolute paths, no URL).
- Do not tell the user to manually open a URL or endpoint.

---

## Permission Requests

Permissions are created via `POST /permissions/request`. See `$sys/docs/permissions.md` for the full model.

If a `device_api` action returns `permission_required`, ask the user to approve the prompt and retry.

### Cloud Media Upload Permission

Do not call `/permissions/request` directly for cloud uploads.
Instead, call `POST /cloud/request` and let the broker enforce the permission gate:
- If permission is missing, `/cloud/request` returns `status=permission_required` with `request.id`.
- Ask the user to approve the in-app prompt, then retry with `"permission_id": "<request.id>"`.

## Cloud Request Tool (HTTP Broker)

Use cloud requests when local infrastructure is insufficient (e.g. you need a cloud multimodal model).

Prefer the configured Brain provider (Settings -> Brain):
- Call `device_api` action `brain.config.get` to see `{vendor, base_url, model, has_api_key}` (never returns the key).
- If `has_api_key=false`, ask the user to configure the Brain API key and retry.

Template placeholders (expanded server-side, never echoed back):
- `${vault:<name>}`: credential stored in vault
- `${config:brain.api_key|brain.base_url|brain.model|brain.vendor}`: brain config values
- `${file:<rel_path>:base64}`: base64 of a user-root file (auto-downscale if enabled)
- `${file:<rel_path>:base64_raw}`: base64 of original bytes (no downscale)
- `${file:<rel_path>:text}`: UTF-8 decode of a user-root file

Body forms:
- `json`: any JSON value
- `body`: raw string body or JSON object/array
- `body_base64`: raw bytes as base64 (use with `content_type`)

Large uploads:
- If total upload bytes exceed ~5MB, `/cloud/request` returns `error=confirm_large_required`.
  Ask the user to confirm, then retry with `confirm_large:true`.

Cloud prefs: see `$sys/docs/api_reference.md` -> Cloud Broker.

### Python Helper

```python
import device_permissions as dp
dp.set_identity("my_session_123")
dp.ensure_device("camera2", detail="capture a photo", scope="session")
```

- `dp.ensure_device(...)` is **non-blocking by default**. If permission is pending, it raises `PermissionError` immediately so the agent can ask the user to approve in the UI.
- For human-run scripts that want to wait, pass `wait_for_approval=True`.

## Common Errors

- `permission_required`: user needs to approve on device UI, then retry.
- `path_outside_user_dir`: use paths under the user root only.
- `command_not_allowed`: only `python|pip|curl` are permitted in execution tools.

## Package Name Gotchas

- Do not `pip install uvc` for camera control; use `device_api` USB/UVC actions instead.
- Do not `pip install` camera bindings; use `device_api` `camera.*` actions.

## Docs Index

Read the relevant doc when working in that domain (use `read_file("$sys/docs/<name>")`):
- `$sys/docs/api_reference.md` — **complete action map**, all endpoints, WebSocket protocols
- `$sys/docs/camera.md` — CameraX still capture + preview stream
- `$sys/docs/uvc.md` — UVC MJPEG capture + PTZ
- `$sys/docs/usb.md` — USB device enumeration + transfers + streaming
- `$sys/docs/ble.md` — BLE scanning + GATT + events
- `$sys/docs/tts.md` — Android TextToSpeech
- `$sys/docs/stt.md` — Android SpeechRecognizer
- `$sys/docs/llama.md` — local llama.cpp model execution
- `$sys/docs/sensors.md` — realtime sensor streams via WebSocket
- `$sys/docs/viewer.md` — viewer control API, file info, Marp presentation
- `$sys/docs/vision.md` — RGBA8888 + TFLite inference
- `$sys/docs/permissions.md` — permission scopes and identity
- `$sys/examples/README.md` — copy/paste golden paths

## Source Code Fallback

me.things is open source. If the docs are insufficient, inspect the repo as the ultimate API reference:
`https://github.com/espresso3389/methings`
