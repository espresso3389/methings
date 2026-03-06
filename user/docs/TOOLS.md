# me.things Tools (User Root)

Quick reference for agent tools, invocation patterns, and chat rendering rules.
For the complete device API reference, see `$sys/docs/api/`. For agent tool conventions, see `$sys/docs/agent_tools.md`.

## Filesystem Tools (User Root Only)

- `list_dir(path, show_hidden, limit)`
- `read_file(path, max_bytes)` — text-only; binary files are blocked with `binary_file_not_text`
- `read_binary_file(path, offset_bytes, size_bytes, encoding)` — partial binary reads, returns base64
- `write_file(path, content, append)`
- `mkdir(path, parents)`
- `move_path(src, dst, overwrite)`
- `delete_path(path, recursive)`

Use these instead of shell commands like `ls`/`cat`.

Paths starting with `$sys/` read from **system-protected reference docs** (read-only, always current with app version). Use `list_dir("$sys/docs")` to discover available documentation. Writing to `$sys/` paths returns `system_files_read_only`.

## Execution Tools

### Built-in (always available)

- `run_js(code, timeout_ms?)` — QuickJS engine with **async/await**, `cv.*` OpenCV bindings. Default 30s (max 120s). Full API: `$sys/docs/run_js.md`.
- `run_curl(url, method?, headers?, body?, timeout_ms?)` — Native HTTP requests. Default timeout 30s.

### Shell

- `run_shell(command, cwd?, timeout_ms?, env?)` — One-shot bash command in embedded Linux. Default 60s, max 300s.
- `shell_session(action, session_id?, command?, ...)` — Persistent PTY sessions. Actions: `start`, `exec`, `write`, `read`, `resize`, `kill`, `list`.

### Python

- `run_python(args, cwd)` — Run Python locally.
- `run_pip(args, cwd)` — Run pip locally.

Full reference: `$sys/docs/shell.md`.

Notes:
- Prefer `run_js` over `run_python` — it supports fetch, WebSocket, file I/O, and timers natively.
- Prefer `run_shell` over `run_python` for general commands.
- `python -` (stdin) is not supported. Use `python -c "..."` or write a script file.
- For interactive/stateful workflows, use `shell_session` to keep state between commands.

## Media Analysis Tools (Built-in Multimodal)

- `analyze_image(path, data_b64?, mime_type?, prompt?)` — LLM vision analysis. Supported by OpenAI, Anthropic, Gemini.
- `analyze_audio(path, data_b64?, mime_type?, prompt?)` — LLM audio analysis. **Gemini only.** Others return `media_not_supported`.

Parameters: `path` (required), `data_b64` (alternative to path), `mime_type` (override auto-detect), `prompt` (optional instruction).

When a device tool (e.g. `camera.capture`, `audio.record.stop`) returns media, it is auto-attached — you do not need `analyze_*` to see/hear tool-produced media.

## Web Search Tool (Permission-Gated)

- `web_search(query, max_results, provider)`

Prefer `provider="auto"` (default: Brave Search if configured, else DuckDuckGo). For non-English queries, use `provider="brave"` with `brave_search_api_key` in vault.

## device_api

- `device_api(action, payload, detail)` — Kotlin control-plane actions. Some require user approval (`permission_required`).

Only `device_api` actions are documented in `$sys/docs/api/`. Agent tools (`run_shell`, `run_js`, `run_curl`) are tool-runtime capabilities, not API endpoints.

## Domain Quickref

Read the relevant `$sys/docs/api/<domain>.md` before using a domain for the first time.

- `app.info` / `app.update.*`: app version, updates. → `$sys/docs/api/android.md`
- `android.device` / `android.permissions.*`: device info, runtime permissions. → `$sys/docs/api/android.md`
- `screen.status` / `screen.keep_on`: display state, keep awake. → `$sys/docs/api/screen.md`
- `camera.capture` / `camera.preview.*`: still photo, JPEG preview stream. → `$sys/docs/api/camera.md`
- `uvc.mjpeg.capture` / `uvc.ptz.*`: USB webcam frame capture, PTZ. → `$sys/docs/api/uvc.md`
- `usb.list` / `usb.open` / `usb.close` / `usb.transfer.*`: USB device enumeration + transfers. → `$sys/docs/api/usb.md`
- `mcu.*`: MCU probe, flash, reset, serial monitor, MicroPython. → `$sys/docs/api/mcu.md`
- `ble.scan.*` / `ble.connect` / `ble.gatt.*`: BLE scanning, GATT read/write. → `$sys/docs/api/ble.md`
- `sensor.list` / `sensor.stream.*`: sensor enumeration, realtime data. → `$sys/docs/api/sensors.md`
- `location.get` / `location.status`: GPS fix, provider state. → `$sys/docs/api/location.md`
- `network.status` / `wifi.status` / `mobile.status`: connectivity info. → `$sys/docs/api/network.md`
- `tts.speak` / `tts.voices` / `tts.init` / `tts.stop`: text-to-speech. → `$sys/docs/api/tts.md`
- `stt.record` / `stt.status`: speech-to-text (no input file). → `$sys/docs/api/stt.md`
- `audio.record.*` / `audio.stream.*`: AAC recording, live PCM. → `$sys/docs/api/audio_record.md`
- `video.record.*` / `video.stream.*`: H.265/H.264 recording, live frames. → `$sys/docs/api/video_record.md`
- `screenrec.start` / `screenrec.stop`: screen recording (.mp4). → `$sys/docs/api/screen_record.md`
- `media.audio.play`: audio playback. → `$sys/docs/api/media_audio.md`
- `media.stream.audio.*` / `media.stream.video.*`: file decode to WebSocket. → `$sys/docs/api/media_stream.md`
- `webview.open` / `webview.screenshot` / `webview.js` / `webview.tap` / `webview.scroll` / `webview.split`: agent-controlled browser. → `$sys/docs/api/webview.md`
- `scheduler.*`: daemon/periodic/one_time code execution. → `$sys/docs/api/scheduler.md`
- `me.me.*`: device-to-device discovery, messaging, file transfer. → `$sys/docs/me_me.md`
- `intent.send` / `intent.share_app`: Android intents. → `$sys/docs/api/android.md`
- `vision.*`: RGBA8888 + TFLite inference. → `$sys/docs/api/vision.md`

## Serial (Special: HTTP Endpoints, NOT device_api)

**Serial operations are direct HTTP endpoints.** Use `run_js` with `fetch()` or `run_curl`. Do NOT use `device_api(action="serial.open")` — it will fail with `unknown_action`.

Key endpoints: `POST /serial/open`, `/serial/list_ports`, `/serial/read`, `/serial/write`, `/serial/close`, `GET /serial/status`.
WebSocket async I/O: `GET /serial/ws/contract`, then connect to `/ws/serial/{serial_handle}`.

USB device must be opened first via `device_api(action="usb.open")`. Full details: `$sys/docs/api/serial.md`.

## Chat Rendering Rules

When you create, save, capture, or reference a file, include `rel_path: <path>` in your assistant message for inline preview cards:
```
rel_path: captures/latest.jpg
```

For HTML apps, use `html_path: <path>` to show an OPEN card:
```
html_path: apps/sample.html
```

For Marp slides, append `#page=N` (0-indexed) to navigate to a specific slide: `rel_path: presentations/demo.md#page=3`.

Do not save agent-generated HTML under `www/` unless editing the app UI. Prefer `apps/` or similar.

### Web UI Customization

The chat UI lives at `www/index.html`. After editing, reload with `run_curl(url="http://127.0.0.1:33389/ui/reload", method="POST")`. Revert to factory: `run_curl(url="http://127.0.0.1:33389/ui/reset", method="POST")`.

## Cloud Request Tool (HTTP Broker)

Use when local infrastructure is insufficient (e.g. cloud multimodal model). Prefer the configured Brain provider.

Template placeholders (expanded server-side): `${vault:<name>}`, `${config:brain.*}`, `${file:<path>:base64}`, `${file:<path>:text}`.

Details: `$sys/docs/api/cloud.md`.

## Common Errors

- `permission_required`: user needs to approve on device UI, then retry.
- `path_outside_user_dir`: path must be under app user root (use plain relative path).
- `command_not_allowed`: use `run_shell` for general commands, or `run_js`/`run_curl` for JS/HTTP.

## Package Name Gotchas

- Do not `pip install uvc` for camera control; use `device_api` USB/UVC actions.
- Do not `pip install` camera bindings; use `device_api` `camera.*` actions.

## Source Code Fallback

me.things is open source. If docs are insufficient, inspect the repo: `https://github.com/espresso3389/methings`

## Project Website

https://methings.org/
