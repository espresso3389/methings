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

## Execution Tools

### Built-in (always available, no Termux)

- `run_js(code, timeout_ms?)` — Execute JavaScript via the built-in QuickJS engine with **async/await support**. Default timeout: 30 s (max 120 s). Returns `{status, result, console_output, error}`. Top-level `await` is supported. Full API reference: `$sys/docs/run_js.md`.
- `run_curl(url, method?, headers?, body?, timeout_ms?)` — Make HTTP requests natively. Parameters: `url` (required), `method` (GET/POST/PUT/DELETE/PATCH/HEAD, default GET), `headers` (JSON object), `body` (string), `timeout_ms` (default 30000). Returns `{status, http_status, headers, body}`.

### Shell (works with or without Termux)

- `run_shell(command, cwd?, timeout_ms?, env?)` — Execute a shell command. Uses Termux when available (full bash + packages); falls back to native Android shell (`/system/bin/sh`) otherwise. Returns `{status, exit_code, stdout, stderr, backend}`. Default timeout 60s, max 300s.
- `shell_session(action, session_id?, command?, ...)` — Persistent shell sessions. Termux provides full PTY (ANSI, resize); native mode uses pipe-based sessions (no PTY). Actions: `start`, `exec`, `write`, `read`, `resize`, `kill`, `list`. Maintains state across commands.

### Termux-only

- `termux_fs(action, path, ...)` — Access Termux filesystem (outside app user root). Actions: `read`, `write`, `list`, `stat`, `mkdir`, `delete`. Requires Termux.
- `run_python(args, cwd)` — Run Python locally. Requires Termux.
- `run_pip(args, cwd)` — Run pip locally. Requires Termux.

Full reference for shell tools: `$sys/docs/termux_shell.md`.

Notes:
- Prefer `run_js` over `run_python` — it supports fetch, WebSocket, file I/O, and timers natively.
- Prefer `run_shell` over `run_python` for general commands — it can run any program, not just Python.
- `run_curl` now works natively without Termux. Legacy `run_curl(args, cwd)` form is still supported for backward compatibility.
- `python -` (stdin) is not supported (no interactive stdin). Use `python -c "..."` or write a script file and run it.
- For interactive/stateful workflows (e.g., virtual envs, build systems), use `shell_session` to keep state between commands.

## Media Analysis Tools (Built-in Multimodal)

- `analyze_image(path, data_b64?, mime_type?, prompt?)` — Analyze an image using built-in LLM vision. The image is encoded and sent as multimodal content. Supported by OpenAI, Anthropic, and Gemini.
- `analyze_audio(path, data_b64?, mime_type?, prompt?)` — Analyze an audio file using built-in LLM audio understanding. **Only supported by Gemini.** Other providers return `{"status":"error","error":"media_not_supported"}`.

Parameters:
- `path` (required): user file path (`user://...` or legacy relative path).
- `data_b64`: alternative to `path` — provide raw base64 data directly.
- `mime_type`: override auto-detected MIME type.
- `prompt`: optional question or instruction (e.g. "transcribe this", "what objects are visible?").

Notes:
- Both tools check provider capabilities before encoding. If the current provider does not support the media type, they return early with `error: media_not_supported` and list `supported_types`.
- The system prompt includes `Current provider supports: image, audio` (or similar) so you can check before calling.
- For audio analysis, switch to a Gemini model in Brain settings.
- Prefer these tools over `cloud_request` for media analysis — they handle encoding, size limits, and multimodal formatting automatically.
- When a device tool (e.g. `camera.capture`, `audio.record.stop`) returns media, it is auto-attached to the tool result. You do not need `analyze_*` to see/hear tool-produced media.

## Web Search Tool (Permission-Gated)

- `web_search(query, max_results, provider)`

Notes:
- DuckDuckGo is not a full web-search API here; it is the Instant Answer API and can be weak for non-English queries.
- Prefer `provider="auto"` (default) which uses Brave Search API if configured, else DuckDuckGo.
- If you need better non-English search, use `provider="brave"` and store `brave_search_api_key` in vault.

## Kotlin Control Plane Tool

- `device_api(action, payload, detail)`

Used for allowlisted device control-plane actions. Some actions require user approval and will return `permission_required`.

Only `device_api` HTTP actions are documented in OpenAPI (`$sys/docs/openapi/openapi.yaml`).
Agent tools such as `run_shell`, `run_js`, and `run_curl` are tool-runtime capabilities and are not OpenAPI paths.

## Remote Access via SSH Tunnel

If the device's SSH server is running, a remote user can forward the local API/UI port over SSH:

```bash
ssh <user>@<device-ip> -p <ssh-port> -L 33389:127.0.0.1:33389
```

Then `http://127.0.0.1:33389` on the remote machine gives full access to the WebView UI and all local HTTP APIs.

Direct outbound SSH client actions via `device_api` are deprecated and may be unavailable.
Use `run_shell` for outbound SSH/SCP commands instead.

## App SSH Shell Commands (Outbound)

Use `run_shell` for outbound SSH/SCP operations:

- `run_shell(command="ssh user@host <command>")`
- `run_shell(command="scp <local_file> user@host:<remote_path>")`
- `run_shell(command="scp user@host:<remote_file> <local_path>")`

Notes:
- If Termux is unavailable, `run_shell` falls back to native Android shell.
- If `ssh`/`scp` binaries are missing, call `device_api("termux.show_setup")` and ask the user to complete setup.

### SSHD Management

For on-device SSH server management, use the `/sshd/*` API endpoints (status/config/keys/pin/noauth).
For outbound SSH client access to other machines, use `run_shell` only.

---

## Domain Quickref

For full payload docs and all actions, see the OpenAPI spec at `$sys/docs/openapi/paths/*.yaml`. Read the relevant path file before using a domain for the first time.

### App Info & Updates
- `app.info`: app version, build info, git SHA, repo URL. No payload needed.
- `app.update.check`: check for available updates from release repository.
- `app.update.install`: download and install an app update. Permission-gated.

### Device Info & Permissions
- `android.device`: device manufacturer, model, Android version, screen size, locale, etc. No payload needed.
- `android.permissions`: list all manifest-declared permissions with grant status. No payload needed.
- `android.permissions.request`: request runtime permissions via system dialog. Payload: `{"permissions": ["android.permission.CAMERA", ...]}`. Returns grant results.

### Screen
- `screen.status`: display state (on/off, brightness). No payload needed.
- `screen.keep_on`: keep screen awake. Payload: `{"keep_on": true/false}`.

### Camera — `$sys/docs/openapi/paths/camera.yaml`
- `camera.capture`: take a still photo. Key payload: `lens` (back/front), `path` (`user://...` / `termux://...` / legacy relative). Returns `rel_path`.
- `camera.preview.start/stop`: JPEG preview stream via `/ws/camera/preview`.
- Do not `pip install` camera bindings; use `device_api`.

### UVC (USB Webcam) — `$sys/docs/openapi/paths/uvc.yaml`
- `uvc.mjpeg.capture`: capture one frame. Key payload: `handle`, `width`, `height`, `fps`, `path`.
- Requires both in-app `device.usb` permission and Android OS USB permission.

### Serial (USB Serial) — `$sys/docs/openapi/paths/serial.yaml`
- `serial.open`: open a serial session from a `usb.open` handle (configurable baud/data/stop/parity).
- `serial.list_ports`: list USB serial driver ports available for a `usb.open` handle.
- `serial.read` / `serial.write`: byte I/O using base64 payloads.
- `serial.lines`: set DTR/RTS modem lines.
- `serial.status` / `serial.close`: inspect and close serial sessions.

### MCU (Model-Driven Programming) — `$sys/docs/openapi/paths/mcu.yaml`
- `mcu.models`: list supported programming models and status.
- `mcu.probe`: probe a connected target for the selected model.
- `mcu.flash.plan`: parse `flasher_args.json` into sorted flash segments.
- `mcu.flash`: flash one or multiple segments via the selected model protocol.
- `mcu.reset`: reset target mode (mode set depends on model capabilities).
- `mcu.serial_monitor`: passive serial capture after boot or reset.
- `mcu.serial_lines`: explicit DTR/RTS line control and reset scripts.
- `mcu.diag.serial`: active serial diagnostics (includes sync probe).

Current implementation note:
- `esp32` is the first supported model. API names and workflow are intentionally model-generic so additional MCU families can be added without renaming.

Typical MCU workflow:
- `usb.list` -> `usb.open` to get `handle`.
- Upload binaries to user files (for example `firmware/bootloader.bin`, `firmware/partitions.bin`, `firmware/app.bin`).
- Call `mcu.flash` with model-specific segment layout.
- `mcu.reset` with `mode="reboot"`.
- `mcu.serial_monitor` to confirm runtime logs.

### Location
- `location.status`: current location provider state.
- `location.get`: GPS fix. Key payload: `high_accuracy`, `timeout_ms`.

### Network
- `network.status`: connectivity state (wifi, mobile, type).
- `wifi.status`: Wi-Fi details (SSID, signal, IP).
- `mobile.status`: cellular network info.

### Sensors — `$sys/docs/openapi/paths/sensors.yaml`
- `sensor.list`: enumerate available sensors.
- Realtime data via WebSocket `/ws/sensors?sensors=a,g,m&rate_hz=200`.
- Polling API (no WebSocket needed):
  - `sensor.stream.start`: start sensor polling. Payload: `{"sensors": ["accelerometer"], "rate_hz": 50}`.
  - `sensor.stream.stop`: stop polling.
  - `sensor.stream.status`: current stream state.
  - `sensor.stream.latest`: get latest sensor values.
  - `sensor.stream.batch`: get buffered sensor batch.

### TTS (Text-to-Speech) — `$sys/docs/openapi/paths/tts.yaml`
- `tts.init`: initialize TTS engine. No payload needed (call before first use).
- `tts.voices`: list available voices.
- `tts.speak`: speak text. Payload: `{"text": "hello", "language": "en", "pitch": 1.0, "rate": 1.0}`.
- `tts.stop`: stop speaking.

### STT (Speech-to-Text) — `$sys/docs/openapi/paths/stt.yaml`
- `stt.status`: recognizer state.
- `stt.record`: record and transcribe speech. Returns recognized text (NO INPUT FILE SUPPORT).

### BLE (Bluetooth Low Energy) — `$sys/docs/openapi/paths/ble.yaml`
- `ble.scan.start/stop`: scan for BLE peripherals. Returns discovered devices.
- `ble.connect/disconnect`: connect to a BLE GATT server.
- `ble.gatt.discover`: discover services and characteristics.
- `ble.gatt.read/write`: read or write GATT characteristics.
- Events arrive via `/ws/ble/events` WebSocket.

### Scheduler (Code Execution) — `$sys/docs/openapi/paths/scheduler.yaml`
- `scheduler.status`: engine state (started, running count). No payload needed.
- `scheduler.list`: list all schedules. No payload needed.
- `scheduler.create`: create a schedule. Payload: `name`, `launch_type` (`daemon`/`periodic`/`one_time`), `schedule_pattern` (`minutely`/`hourly`/`daily`/`weekly:Mon`/`monthly:15`/`""`), `runtime` (`run_js`/`run_python`), `code`, optional `args`, `cwd`, `timeout_ms`, `enabled`, `meta`. Permission-gated.
- `scheduler.get`: get schedule by ID. Payload: `{"id": "..."}`.
- `scheduler.update`: update schedule fields. Payload: `{"id": "...", ...fields}`. Permission-gated.
- `scheduler.delete`: delete a schedule and its logs. Payload: `{"id": "..."}`. Permission-gated.
- `scheduler.trigger`: trigger immediate execution. Payload: `{"id": "..."}`. Permission-gated.
- `scheduler.log`: get execution log. Payload: `{"id": "...", "limit": 20}`.

Schedule types:
- `daemon`: runs on service start (restarts automatically).
- `periodic`: fires on pattern (minutely, hourly, daily, weekly:Mon..Sun, monthly:1..28). 1-minute resolution.
- `one_time`: fires once at next tick, then auto-disables.

Runtimes: `run_js` (built-in QuickJS with async/await support, always available), `run_python` (requires Termux).
Limits: max 50 schedules, 200 log entries per schedule, 2000 global log entries.

### Termux
- `termux.status`: worker status, bootstrap phase, setup readiness. No payload needed.
- `termux.restart`: restart the Termux worker. Permission-gated.
- `termux.show_setup`: prompt the user to open the Termux setup wizard in the UI. Use when a shell tool fails because Termux is not installed or not bootstrapped.

### Intent (Android)
- `intent.send`: launch an Android intent. Payload: `{"action": "android.intent.action.VIEW", "data": "https://..."}`.
- `intent.share_app`: share the app via Android share sheet.

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
- `webview.screenshot`: capture page as JPEG. Key payload: `path` (`user://...` / `termux://...` / legacy relative), `quality`. Returns `rel_path`.
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

Receiving files:
- Received files are auto-saved to `me_me_received/<peer_device_id>/<filename>`.
- The pulled message contains `rel_path` pointing to the saved file — use it directly.
- Use `rel_path: <path>` in your chat response to show an inline preview card.

Important:
- **`type: "request"` is required** when asking a peer to take action. Without it, the remote agent is NOT triggered.
- Inbound `file` and `response` messages trigger the local agent automatically.
- `peer_device_id` goes in `payload`, never in `detail`.
- Transport (BLE/LAN/relay) is automatic; do not specify it.

### Web UI Customization — `www/index.html`

The app's chat UI lives at `www/index.html` inside your home directory. You can read, modify, and replace it using filesystem tools (`read_file`, `write_file`).

After editing `www/index.html`, reload the WebView so the user sees the change:

```
run_curl(url="http://127.0.0.1:33389/ui/reload", method="POST")
```

To check the current UI version: `read_file("www/.version")`

To revert to the factory UI bundled with the APK:

```
run_curl(url="http://127.0.0.1:33389/ui/reset", method="POST")
```

This re-extracts the original `index.html` from the APK and reloads the WebView automatically.

---

## Chat Rendering Rules

### Show Media Inline (Required)

The WebView chat UI auto-renders media previews when a message contains:

```
rel_path: captures/latest.jpg
```

Filesystem path convention:
- `user://<relative-path>`: app user files (`/user/*` APIs).
- `termux://<path>`: Termux HOME files (maps to `/data/data/com.termux/files/home`).
- Backward compatibility: bare relative paths are treated as `user://`.

When you create, save, capture, or reference a file, you MUST include `rel_path: <path>` (or `html_path:` for HTML) in your assistant message. Use explicit filesystem prefixes when the file is in Termux. This applies to:
- Captured images/audio/video
- Generated scripts, reports, or data files
- **Listing files you created** — never list bare filenames; always emit a `rel_path:` line for each file so the user gets clickable, previewable cards instead of plain text

User UX notes:
- Tapping an image opens a fullscreen viewer (swipe between images, pinch zoom).
- Media cards include a Share icon.

To fetch the image onto your dev machine (preferred): `GET /user/file/<relative-path>` or `GET /termux/file/<path-under-home>`.
Backward-compatible query form still works: `GET /user/file?path=<path>`.

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
html_path: user://agent_ui/sample.html
```

Rules:
- Prefer `html_path:` (use `open_html:` only for backward compatibility).
- Viewer OPEN supports both `user://...` and `termux://...` paths.
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
- `${file:<path>:base64}`: base64 of a file (`user://...` or `termux://...`; auto-downscale applies to user image files)
- `${file:<path>:base64_raw}`: base64 of original bytes
- `${file:<path>:text}`: UTF-8 decode of file

Body forms:
- `json`: any JSON value
- `body`: raw string body or JSON object/array
- `body_base64`: raw bytes as base64 (use with `content_type`)

Large uploads:
- If total upload bytes exceed ~5MB, `/cloud/request` returns `error=confirm_large_required`.
  Ask the user to confirm, then retry with `confirm_large:true`.

File transfer prefs: see `$sys/docs/openapi/paths/cloud.yaml` (`/file_transfer/prefs`).

Path-style file APIs:
- Read: `/user/file/<path>`, `/termux/file/<path>`
- Info: `/user/file/info/<path>`, `/termux/file/info/<path>`
- List: `/user/list/<dir>`, `/termux/list/<dir>`
- Write: `/user/write/<path>`, `/termux/write/<path>` (POST JSON: `content` or `data_b64`)

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
- `path_outside_user_dir`: path must be under app user root (or use `user://`).
- `path_outside_termux_home`: path must be under Termux HOME (`/data/data/com.termux/files/home`).
- `termux_unavailable`: Termux worker is not reachable.
- `command_not_allowed`: only `python|pip` are permitted via the legacy `run_python`/`run_pip` tools. Use `run_shell` for general shell commands, or `run_js` and `run_curl` for JS/HTTP (they work natively without Termux).
- `worker_unavailable`: Termux worker is not reachable on port 8776. `run_shell` and `shell_session` will fall back to native shell automatically; other Termux tools require Termux to be installed and running.

## Package Name Gotchas

- Do not `pip install uvc` for camera control; use `device_api` USB/UVC actions instead.
- Do not `pip install` camera bindings; use `device_api` `camera.*` actions.

## Docs Index

API endpoint reference is in OpenAPI format under `$sys/docs/openapi/`. Read the relevant path file when working in that domain:
- `$sys/docs/openapi/openapi.yaml` — root spec with all endpoints and tags
- `$sys/docs/openapi/paths/camera.yaml` — CameraX still capture + preview stream
- `$sys/docs/openapi/paths/uvc.yaml` — UVC MJPEG capture + PTZ
- `$sys/docs/openapi/paths/usb.yaml` — USB device enumeration + transfers + streaming
- `$sys/docs/openapi/paths/serial.yaml` — generic USB serial sessions and byte I/O
- `$sys/docs/openapi/paths/mcu.yaml` — model-driven MCU probe, flash, reset, and serial monitor
- `$sys/docs/openapi/paths/ble.yaml` — BLE scanning + GATT + events
- `$sys/docs/openapi/paths/tts.yaml` — Android TextToSpeech
- `$sys/docs/openapi/paths/stt.yaml` — Android SpeechRecognizer
- `$sys/docs/openapi/paths/sensors.yaml` — realtime sensor streams via WebSocket
- `$sys/docs/openapi/paths/audio_record.yaml` — audio recording + live PCM streaming
- `$sys/docs/openapi/paths/video_record.yaml` — video recording + live frame streaming
- `$sys/docs/openapi/paths/screen_record.yaml` — screen recording
- `$sys/docs/openapi/paths/media_stream.yaml` — file-based media decode streaming
- `$sys/docs/openapi/paths/webview.yaml` — agent-controllable WebView browser
- `$sys/docs/openapi/paths/scheduler.yaml` — general-purpose code scheduler
- `$sys/docs/openapi/paths/vision.yaml` — RGBA8888 + TFLite inference
- `$sys/docs/openapi/paths/me_me.yaml` — device discovery/connection (`me.me`)
- `$sys/docs/openapi/paths/me_sync.yaml` — export/import transfer flow (`me.sync`)
- `$sys/docs/openapi/paths/cloud.yaml` — cloud broker + adapter mode
- `$sys/docs/openapi/paths/files.yaml` — file upload/download/info
- `$sys/docs/openapi/paths/ui.yaml` — viewer control, settings navigation

Tool-specific references (under `$sys/docs/`):
- `$sys/docs/run_js.md` — run_js async API: fetch, WebSocket, file I/O, timers, device_api
- `$sys/docs/termux_shell.md` — run_shell, shell_session, termux_fs: Termux shell & file access

Conceptual guides (under `$sys/docs/`):
- `$sys/docs/agent_tools.md` — agent tool conventions, filesystem helpers, chat shortcuts
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
