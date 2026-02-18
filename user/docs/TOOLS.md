# me.things Tools (User Root)

This file is a quick reference for the agent's tools, invocation patterns, and chat rendering rules.

For the complete device API reference, see the OpenAPI spec at `$sys/docs/openapi/openapi.yaml`. For agent tool conventions, see `$sys/docs/agent_tools.md`.

## Filesystem Tools (User Root Only)

- `list_dir(path, show_hidden, limit)`
- `read_file(path, max_bytes)`  # text-only
- `read_binary_file(path, offset_bytes=0, size_bytes=262144, encoding="base64")`
- `write_file(path, content, append)`
- `mkdir(path, parents)`
- `move_path(src, dst, overwrite)`
- `delete_path(path, recursive)`

Use these instead of shell commands like `ls`/`cat`.

`read_file` behavior:
- Default mode is text-first.
- Binary/media files are blocked with `binary_file_not_text`.
- For explicit binary analysis, use `read_binary_file`.

`read_binary_file` behavior:
- Supports partial reads: `offset_bytes` + `size_bytes`.
- Returns: `binary=true`, `media_type`, `encoding="base64"`, `body_base64`, `file_size`, `read_offset`, `read_size`, `eof`.

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

The full action map is in the OpenAPI spec at `$sys/docs/openapi/openapi.yaml`.

## Remote Access via SSH Tunnel

If the device's SSH server is running, a remote user can forward the local API/UI port over SSH:

```bash
ssh <user>@<device-ip> -p <ssh-port> -L 33389:127.0.0.1:33389
```

Then `http://127.0.0.1:33389` on the remote machine gives full access to the WebView UI and all local HTTP APIs.

SSH actions through `device_api`:
- `ssh.exec`: one-shot remote command. Payload: `host`, `user`, `port`, `command`.
- `ssh.scp`: upload/download files via SCP. Payload: `direction`, `host`, `user`, `local_path`, `remote_path`.
- `ssh.ws.contract`: websocket contract for interactive SSH (`/ws/ssh/interactive`).

Details and examples: `$sys/docs/openapi/paths/ssh.yaml` and `$sys/docs/openapi/paths/sshd.yaml`

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

## Domain Quickref

For full payload docs and all actions, see the OpenAPI spec at `$sys/docs/openapi/paths/*.yaml`. Read the relevant path file before using a domain for the first time.

### Device Info
- `android.device`: device manufacturer, model, Android version, screen size, locale, etc. No payload needed.

### Camera — `$sys/docs/openapi/paths/camera.yaml`
- `camera.capture`: take a still photo. Key payload: `lens` (back/front), `path`. Returns `rel_path`.
- `camera.preview.start/stop`: JPEG preview stream via `/ws/camera/preview`.
- Do not `pip install` camera bindings; use `device_api`.

### UVC (USB Webcam) — `$sys/docs/openapi/paths/uvc.yaml`
- `uvc.mjpeg.capture`: capture one frame. Key payload: `handle`, `width`, `height`, `fps`, `path`.
- Requires both in-app `device.usb` permission and Android OS USB permission.

### Location
- `location.get`: GPS fix. Key payload: `high_accuracy`, `timeout_ms`.

### Sensors — `$sys/docs/openapi/paths/sensors.yaml`
- `sensor.list`: enumerate available sensors.
- Realtime data via WebSocket `/ws/sensors?sensors=a,g,m&rate_hz=200`.

### Media Playback
- `media.audio.play`: play audio file (`path`) or base64 (`audio_b64` + `ext`).

### Audio Recording & Streaming — `$sys/docs/openapi/paths/audio_record.yaml`
- `audio.record.start/stop`: record to AAC (.m4a). Returns `rel_path`, `duration_ms`, `size_bytes`.
- `audio.stream.start/stop`: live PCM (s16le) via `/ws/audio/pcm`.
- Optional start payload: `path`, `sample_rate`, `channels`, `bitrate`, `max_duration_s`.

### Video Recording & Streaming — `$sys/docs/openapi/paths/video_record.yaml`
- `video.record.start/stop`: record to H.265/H.264 (.mp4). Key payload: `lens`, `resolution` (720p/1080p/4k).
- `video.stream.start/stop`: live JPEG or RGBA frames via `/ws/video/frames`.
- Returns `rel_path`, `duration_ms`, `size_bytes`, `codec`.

### Screen Recording — `$sys/docs/openapi/paths/screen_record.yaml`
- `screenrec.start/stop`: record device screen to .mp4. Requires user consent dialog each time.
- Optional start payload: `resolution` (720p/1080p), `bitrate`, `max_duration_s`.

### Media Decode Streaming — `$sys/docs/openapi/paths/media_stream.yaml`
- `media.stream.audio.start`, `media.stream.video.start`: decode files to PCM/JPEG/RGBA over WebSocket.
- Returns `stream_id` + `ws_path` (`/ws/media/stream/<stream_id>`).

### WebView Browser — `$sys/docs/openapi/paths/webview.yaml`
- `webview.open`: open URL in agent-controlled browser. Key payload: `url`, `timeout_s`.
- `webview.close`: close the browser.
- `webview.status`: current URL, title, dimensions, loading state.
- `webview.screenshot`: capture page as JPEG. Key payload: `path`, `quality`. Returns `rel_path`.
- `webview.js`: execute JavaScript. Key payload: `script`. Returns `result`.
- `webview.tap`: simulate tap. Key payload: `x`, `y`.
- `webview.scroll`: scroll page. Key payload: `dx`, `dy`.
- `webview.back` / `webview.forward`: navigate history.
- `webview.split`: toggle browser split panel visibility. Key payload: `visible` (bool), `fullscreen` (bool, hides chat), `position` (`"start"` = top/left, `"end"` = bottom/right).

### me.me (Device-to-Device) — `$sys/docs/me_me.md`

**Always use `device_api` for me.me. Never use `run_curl` to call me.me endpoints directly.**

Discovery & connection:
- `device_api(action="me.me.status", payload={})` — peer presence snapshot.
- `device_api(action="me.me.scan", payload={})` — scan for nearby devices.
- `device_api(action="me.me.connect", payload={"peer_device_id": "d_xxx"})` — connect.
- `device_api(action="me.me.disconnect", payload={"peer_device_id": "d_xxx"})` — disconnect.

Messaging:
- Request (triggers remote agent): `device_api(action="me.me.message.send", payload={"peer_device_id": "d_xxx", "type": "request", "payload": {"text": "take a photo"}})`
- Send file: `device_api(action="me.me.message.send", payload={"peer_device_id": "d_xxx", "type": "file", "payload": {"rel_path": "captures/photo.jpg"}})`
- Pull messages: `device_api(action="me.me.messages.pull", payload={"peer_device_id": "d_xxx"})`

Important:
- **`type: "request"` is required** when asking a peer to take action. Without it, the remote agent is NOT triggered.
- `peer_device_id` goes in `payload`, never in `detail`.
- Transport (BLE/LAN/relay) is automatic; do not specify it.

### Web UI Customization — `www/index.html`

The app's chat UI lives at `www/index.html` inside your home directory. You can read, modify, and replace it using filesystem tools (`read_file`, `write_file`).

After editing `www/index.html`, reload the WebView so the user sees the change:

```
run_curl(["-X", "POST", "http://127.0.0.1:33389/ui/reload"])
```

To check the current UI version: `read_file("www/.version")`

To revert to the factory UI bundled with the APK:

```
run_curl(["-X", "POST", "http://127.0.0.1:33389/ui/reset"])
```

This re-extracts the original `index.html` from the APK and reloads the WebView automatically.

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

File transfer prefs: see `$sys/docs/openapi/paths/cloud.yaml` (`/file_transfer/prefs`).

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

API endpoint reference is in OpenAPI format under `$sys/docs/openapi/`. Read the relevant path file when working in that domain:
- `$sys/docs/openapi/openapi.yaml` — root spec with all endpoints and tags
- `$sys/docs/openapi/paths/camera.yaml` — CameraX still capture + preview stream
- `$sys/docs/openapi/paths/uvc.yaml` — UVC MJPEG capture + PTZ
- `$sys/docs/openapi/paths/usb.yaml` — USB device enumeration + transfers + streaming
- `$sys/docs/openapi/paths/ble.yaml` — BLE scanning + GATT + events
- `$sys/docs/openapi/paths/tts.yaml` — Android TextToSpeech
- `$sys/docs/openapi/paths/stt.yaml` — Android SpeechRecognizer
- `$sys/docs/openapi/paths/sensors.yaml` — realtime sensor streams via WebSocket
- `$sys/docs/openapi/paths/audio_record.yaml` — audio recording + live PCM streaming
- `$sys/docs/openapi/paths/video_record.yaml` — video recording + live frame streaming
- `$sys/docs/openapi/paths/screen_record.yaml` — screen recording
- `$sys/docs/openapi/paths/media_stream.yaml` — file-based media decode streaming
- `$sys/docs/openapi/paths/webview.yaml` — agent-controllable WebView browser
- `$sys/docs/openapi/paths/vision.yaml` — RGBA8888 + TFLite inference
- `$sys/docs/openapi/paths/me_me.yaml` — device discovery/connection (`me.me`)
- `$sys/docs/openapi/paths/me_sync.yaml` — export/import transfer flow (`me.sync`)
- `$sys/docs/openapi/paths/cloud.yaml` — cloud broker + adapter mode
- `$sys/docs/openapi/paths/files.yaml` — file upload/download/info
- `$sys/docs/openapi/paths/ui.yaml` — viewer control, settings navigation

Conceptual guides (under `$sys/docs/`):
- `$sys/docs/agent_tools.md` — agent tool conventions, Python runtime helpers, chat shortcuts
- `$sys/docs/permissions.md` — permission scopes, identity model, USB special cases
- `$sys/docs/viewer.md` — viewer usage guide, Marp presentations, autonomous presentation examples
- `$sys/docs/me_me.md` — me.me architecture, security model, event forwarding
- `$sys/docs/me_sync.md` — me.sync concepts, modes, transport strategy
- `$sys/docs/me_sync_v3.md` — v3 QR-paired ad-hoc transfer architecture
- `$sys/docs/relay_integrations.md` — Slack/Discord onboarding guide
- `$sys/examples/README.md` — copy/paste golden paths

## Source Code Fallback

me.things is open source. If the docs are insufficient, inspect the repo as the ultimate API reference:
`https://github.com/espresso3389/methings`

## Project Website

https://methings.org/
