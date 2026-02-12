# methings Tools (User Root)

This file is a quick reference for the agent's tools and permission patterns.

## Filesystem Tools (User Root Only)

- `list_dir(path, show_hidden, limit)`
- `read_file(path, max_bytes)`
- `write_file(path, content, append)`
- `mkdir(path, parents)`
- `move_path(src, dst, overwrite)`
- `delete_path(path, recursive)`

Use these instead of shell commands like `ls`/`cat`.

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

Used for allowlisted device control-plane actions (python/ssh/memory/shell.exec, etc). Some actions require user approval and will return `permission_required`.

## Remote Access via SSH Tunnel

If the device's SSH server is running, a remote user can forward the local API/UI port over SSH:

```bash
ssh <user>@<device-ip> -p <ssh-port> -L 8765:127.0.0.1:8765
```

Then `http://127.0.0.1:8765` on the remote machine gives full access to the WebView UI and all local HTTP APIs (brain, permissions, device, cloud, etc.).

## App SSH Shell Commands (Outbound)

When you are inside the app's SSH shell prompt (it looks like `methings>`), a few outbound SSH helpers are available:

- `ssh user@host <command>`
  - Interactive `ssh user@host` (no command) is not supported (no PTY). Use exec-form only.
- `put <local_file> <user@host:remote_path_or_dir>`
- `get <user@host:remote_file> <local_path>`

Notes:
- `scp` exists but may stall against some OpenSSH-for-Windows targets. Prefer `put/get` when `scp` stalls.
- Node.js may be available as `node` plus JS tools `npm`/`npx` (if the runtime is bundled).

### Camera Quickstart (Take A Picture)

Use `device_api` (do not try to `pip install` camera bindings):

Example:

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

On success, the response includes `rel_path` (like `captures/latest.jpg`). Prefer `rel_path` for any filesystem tools and for `vision.image.load`.

### UVC Quickstart (Insta360 Link / USB Webcam)

If a UVC camera is connected over USB (e.g. Insta360 Link), capture a single MJPEG frame with:

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

Then include `rel_path: captures/uvc_latest.jpg` in your assistant message to preview it inline.

USB permissions:

- Approving `device.usb` in-app is necessary but not sufficient.
- Android also requires an OS-level per-device USB permission. If a USB call fails with `error=usb_permission_required`, you must:
  - Bring methings to the foreground and retry.
  - Accept Android's system "Allow access to USB device" dialog.
  - If the OS dialog never appears and it keeps auto-denying, clear defaults/USB associations in app settings, replug, and retry.

### Location Quickstart (GPS)

To query location, use `device_api`:

- `location.status`: provider availability and enabled status.
- `location.get`: best-effort current location via Android `LocationManager.getCurrentLocation`.

Example:

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

### Network / Radio Signal Quickstart

Use these actions to inspect current connectivity and radio conditions:

- `network.status`: active transport summary (wifi/cellular/ethernet/vpn), internet validation, DNS/interface.
- `wifi.status`: Wi-Fi state + SSID/BSSID/RSSI/link speed/frequency.
- `mobile.status`: mobile network/operator + signal summaries when available.
- `ble.status`: Bluetooth availability/enabled state.

Example:

```json
{
  "type": "tool_invoke",
  "tool": "device_api",
  "args": {
    "action": "network.status",
    "payload": {},
    "detail": "Check current connectivity and active transports"
  }
}
```

### Llama.cpp Quickstart (Local GGUF / MioTTS)

Use `device_api` actions (not raw shell) for local `llama.cpp` binaries:

- `llama.status`: detect installed binaries (`llama-cli`, `llama-tts`) and model roots.
- `llama.models`: list local `.gguf` files.
- `llama.run`: run arbitrary `llama.cpp` command-line args.
- `llama.generate`: convenience text generation via `llama-cli`.
- `llama.tts`: convenience wrapper for `llama-tts` with templated args.
- `llama.tts.plugins.list` / `llama.tts.plugins.upsert` / `llama.tts.plugins.delete`: manage reusable TTS templates (codec/vocoder presets).
- `llama.tts.speak`: dedicated fast path that streams generated WAV to the device speaker.
- `llama.tts.speak.status` / `llama.tts.speak.stop`: task control.

Example: status check

```json
{
  "type": "tool_invoke",
  "tool": "device_api",
  "args": { "action": "llama.status", "payload": {}, "detail": "Check llama.cpp runtime" }
}
```

Example: MioTTS-style synthesis (provide args for your `llama-tts` build)

```json
{
  "type": "tool_invoke",
  "tool": "device_api",
  "args": {
    "action": "llama.tts",
    "payload": {
      "model": "MioTTS-0.1B-Q8_0.gguf",
      "text": "Hello from methings",
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

After synthesis, include `rel_path: captures/miotts.wav` in your assistant message to render audio inline.

Example: direct speaker playback (stream while generating)

```json
{
  "type": "tool_invoke",
  "tool": "device_api",
  "args": {
    "action": "llama.tts.speak",
    "payload": {
      "model": "MioTTS-0.1B-Q8_0.gguf",
      "text": "Hello from methings",
      "output_path": "captures/miotts_stream.wav",
      "args": ["-m", "{{model}}", "-p", "{{text}}", "-o", "{{output_path}}"]
    },
    "detail": "Stream local llama-tts output to speaker"
  }
}
```

### Media Audio Playback Quickstart

For immediate playback from file or raw data, use `media.audio.*`:

- `media.audio.status`
- `media.audio.play`
- `media.audio.stop`

Play from file:

```json
{
  "type": "tool_invoke",
  "tool": "device_api",
  "args": {
    "action": "media.audio.play",
    "payload": { "path": "captures/miotts.wav" },
    "detail": "Play audio file on device speaker"
  }
}
```

Play from base64 bytes:

```json
{
  "type": "tool_invoke",
  "tool": "device_api",
  "args": {
    "action": "media.audio.play",
    "payload": { "audio_b64": "<base64-audio>", "ext": "wav" },
    "detail": "Play base64 audio on device speaker"
  }
}
```

### Sensors Quickstart (Realtime Streams)

Use a streaming sensor pipeline (single stream with multiple sensors) so Python code can poll batches with low latency, or attach a WebSocket client.

1) List available sensors (inspect `type`/`string_type`/`name`):

```json
{
  "type": "tool_invoke",
  "tool": "device_api",
  "args": { "action": "sensor.list", "payload": {}, "detail": "List available sensors" }
}
```

2) Get websocket contract:

```json
{
  "type": "tool_invoke",
  "tool": "device_api",
  "args": { "action": "sensors.ws.contract", "payload": {}, "detail": "Get sensors websocket contract" }
}
```

3) Open realtime stream via WebSocket `GET /ws/sensors` (WS-first, breaking change):

- `a`: accelerometer
- `g`: gyroscope
- `m`: magnetic field
- `r`: rotation vector
- `u`: linear acceleration
- `w`: gravity
- `x`: geomagnetic rotation vector
- `y`: game rotation vector
- `z`: proximity
- `l`: light
- `p`: pressure
- `h`: relative humidity
- `t`: ambient temperature
- `s`: step counter
- `q`: step detector

WS query params:
- `sensors`: comma-separated keys (required), e.g. `a,g,m`
- `rate_hz`: `1..1000` (default `200`)
- `latency`: `realtime|normal|ui` (default `realtime`)
- `timestamp`: `mono|unix` (default `mono`)
- `backpressure`: `drop_old|drop_new` (default `drop_old`)
- `max_queue`: `64..50000` (default `4096`)

Server first sends `hello`, then per-sensor `sample` events:

```json
{ "type":"hello", "stream_id":"s1", "sensors":["a","g","m"], "rate_hz":200, "timestamp":"mono" }
```

```json
{ "type":"sample", "stream_id":"s1", "sensor":"a", "t": 123456.789, "v":[0.01, 9.80, 0.12], "seq": 1001 }
```

Notes:
- `t` is seconds (monotonic when `timestamp=mono`, unix epoch when `timestamp=unix`)
- `seq` is a monotonically increasing sequence number per stream
- Old HTTP stream endpoints (`sensor.stream.*`, `sensors.stream.*`) are deprecated and return `gone`.

### Show Media Inline In Chat (Required)

The WebView chat UI auto-renders media previews when a message contains one or more lines like:

```
rel_path: captures/latest.jpg
```

So when you take a picture (or upload/record a file), you MUST include `rel_path: <path>` in your assistant message to show it inline.

User UX notes:
- Tapping an image opens a fullscreen viewer (swipe between images, pinch zoom).
- Media cards include a Share icon.

To fetch the image onto your dev machine, use the local file endpoint (permission-gated under `device.files`):
- `GET /user/file?path=<rel_path>` (example: `/user/file?path=captures/latest.jpg`)

### Open Agent HTML From Chat

If your reply includes `html_path: ...`, the app will show an OPEN card.

When you create an HTML file and want the user to open it, do this in the same assistant reply:

1. Create/write the HTML file under user root.
2. Output exactly one line:

```text
html_path: <relative_path>.html
```

Example:

```text
html_path: agent_ui/sample.html
```

Rules:
- Prefer `html_path:` (use `open_html:` only for backward compatibility).
- The path must be user-root relative (no absolute paths, no URL).
- Do not tell the user to manually open a URL or endpoint.
- Do not replace this with prose like "open this file with your UI."

What happens:
- Chat renders an OPEN card automatically.
- Tap OPEN to launch a separate WebView container.
- A native always-visible `X` closes it and returns to chat.

## Docs Index

Read the relevant doc when working in that domain:
- `docs/api_reference.md` (local HTTP APIs + device_api action map)
- `docs/vision.md` (RGBA8888 + TFLite)
- `docs/usb.md` (USB/UVC control + streaming)
- `docs/uvc.md` (UVC MJPEG capture + PTZ)
- `docs/camera.md` (CameraX still capture + preview stream)
- `docs/ble.md` (BLE scanning + GATT)
- `docs/tts.md` (Android TextToSpeech)
- `docs/llama.md` (local llama.cpp model execution)
- `docs/stt.md` (Android SpeechRecognizer)
- `docs/permissions.md` (permission scopes and identity)
- `examples/README.md` (copy/paste golden paths)

## Source Code Fallback

methings is open source. If the docs are insufficient, inspect the repo as the ultimate API reference:
`https://github.com/espresso3389/methings`

## Permission Requests

Permissions are created via the local HTTP endpoint:

- `POST /permissions/request` with JSON:
  - `tool`: e.g. `device.camera2`, `device.mic`, `device.gps`, `device.ble.scan`, `device.usb`
  - `detail`: short reason shown to the user
  - `scope`: `once` | `program` | `session` | `persistent`
  - `identity`: stable id for the requesting program/session (optional but recommended)
  - `capability`: capability name (optional; derived from `tool` when `tool` starts with `device.`)

After the user approves, the app records a device grant for `(identity, capability)` so subsequent device calls (for the same identity) can succeed without passing a `permission_id`.

### One-Off Session Permission (Cloud Media Upload)

If you need to upload user media (image/audio/video) to a cloud AI provider for analysis, request explicit permission once per session first (and do not repeatedly ask). Use a non-device tool name like `cloud.media_upload`:

Do not call `/permissions/request` directly for cloud uploads.
Instead, call `cloud_request` (or `POST /cloud/request`) and let the broker enforce the permission gate:
- If permission is missing, `/cloud/request` returns `status=permission_required` with `request.id`.
- Ask the user to approve the in-app prompt, then retry the same `/cloud/request` with `"permission_id": "<request.id>"`.

## Cloud Request Tool (HTTP Broker)

Use cloud requests when local infrastructure is insufficient (e.g. you need a cloud multimodal model).
The agent should craft the request template; the Kotlin broker expands placeholders and injects secrets.

Endpoint:
- `POST /cloud/request`

### Which Cloud Service To Use (Default Rule)

Prefer the configured Brain provider (Settings -> Brain):
- Call `device_api` action `brain.config.get` to see `{vendor, base_url, model, has_api_key}` (never returns the key).
- If `has_api_key=false`, ask the user to configure the Brain API key and retry.

Template placeholders (expanded server-side, never echoed back):
- `${vault:<name>}`: credential stored in vault (via `/vault/credentials/<name>`)
- `${config:brain.api_key|brain.base_url|brain.model|brain.vendor}`: brain config values
- `${file:<rel_path>:base64}`: base64 of a user-root file (e.g. `captures/latest.jpg`)
- `${file:<rel_path>:text}`: UTF-8 decode of a user-root file

Body forms:
- `json`: any JSON value (object/array/string/number/bool/null) that will be JSON-encoded
- `body`: either a raw string body, or a JSON object/array (treated like `json`)
- `body_base64`: raw bytes as base64 (use with `content_type`)

File base64 modes:
- `${file:<rel_path>:base64}`: base64, with automatic image downscale (if enabled).
- `${file:<rel_path>:base64_raw}`: base64 of original bytes (no downscale).

### OpenAI Vision Example (Responses API)

This uses the Brain config (model + base_url + api_key) and uploads a local image via a data URL:

Note: `brain.base_url` for OpenAI is typically `https://api.openai.com/v1` (already includes `/v1`), so use:
- `${config:brain.base_url}/responses` (not `${config:brain.base_url}/v1/responses`)

```json
{
  "method": "POST",
  "url": "${config:brain.base_url}/responses",
  "headers": {
    "Authorization": "Bearer ${config:brain.api_key}",
    "Content-Type": "application/json"
  },
  "json": {
    "model": "${config:brain.model}",
    "input": [
      {
        "role": "user",
        "content": [
          { "type": "input_text", "text": "Describe this photo." },
          {
            "type": "input_image",
            "image_url": "data:image/jpeg;base64,${file:captures/latest.jpg:base64}"
          }
        ]
      }
    ]
  },
  "timeout_s": 90
}
```

If this returns `status=permission_required`, retry the same request with:
- `"permission_id": "<request.id>"`

Large uploads:
- If total upload bytes exceed ~5MB, `/cloud/request` returns `error=confirm_large_required`.
  Ask the user to confirm, then retry with `confirm_large:true`.

Cloud prefs:
- `GET /cloud/prefs` and `POST /cloud/prefs`
  - `auto_upload_no_confirm_mb` (default ~1.0): payloads <= this size do not require an extra "confirm_large" step.
  - `min_transfer_kbps` (default 0): if >0, cloud requests abort when average transfer rate drops below this.
  - `image_resize_enabled` (default true): if true, `${file:...:base64}` will downscale common image types before upload.
  - `image_resize_max_dim_px` (default 512): max width/height for resized uploads.
  - `image_resize_jpeg_quality` (default 70): JPEG quality for resized uploads.

### Python Helper

The server provides `device_permissions.py`:

```python
import device_permissions as dp

dp.set_identity("my_session_123")  # optional; uses env METHINGS_IDENTITY / METHINGS_SESSION_ID if set
dp.ensure_device("camera2", detail="capture a photo", scope="session")
```

Notes:
- `dp.ensure_device(...)` is **non-blocking by default**. If permission is pending, it raises `PermissionError` immediately so the agent can ask the user to approve in the UI.
- For human-run scripts that want to wait, pass `wait_for_approval=True` (optionally adjust `timeout_s`).

## Common Errors

- `permission_required`: user needs to approve on device UI, then retry.
- `path_outside_user_dir`: use paths under the user root only.
- `command_not_allowed`: only `python|pip|curl` are permitted in execution tools.

## Package Name Gotchas (UVC / USB)

When dealing with USB Video Class (UVC) cameras on Android:
- Native library: `libuvc.so` (bundled by the app in `jniLibs/`)
- Use `device_api` USB/UVC actions (e.g. `usb.*`, `uvc.ptz.*`) rather than trying to install Python UVC bindings.

Do not `pip install uvc` for camera control; that name is often unrelated and typically won't work on Android.

## Vision (RGBA8888 + TFLite)

methings provides a minimal on-device vision pipeline:
- Internal image format: `RGBA8888` bytes `[R,G,B,A]` per pixel.
- Inference runs on Android via TFLite. Python is orchestration only.

Use these `device_api` actions:
- `vision.model.load` / `vision.model.unload`
- `vision.image.load` (decode PNG/JPEG from user root into an in-memory RGBA frame)
- `vision.frame.put` / `vision.frame.get` / `vision.frame.delete`
- `vision.frame.save` (save RGBA to JPEG/PNG under user root)
- `vision.run` (run TFLite on a frame; prefer passing `frame_id` to avoid resending pixels)

Typical flow:
1. `vision.model.load` with `{name, path, delegate: "none|nnapi|gpu", num_threads}`
2. `vision.image.load` or `vision.frame.put`
3. `vision.run` with `{model, frame_id}` (plus optional `normalize/mean/std`)
