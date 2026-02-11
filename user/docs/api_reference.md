# API Reference (Local Control Plane)

methings exposes a local HTTP control plane on `http://127.0.0.1:8765`.

The agent should use the `device_api(action, payload, detail)` tool instead of calling these endpoints directly.

## Identity + Permissions (High Level)

- Most device actions require user approval via the permission broker.
- Approvals are remembered per `(identity, capability)` for the configured scope.
- The chat UI sets `identity` to the chat `session_id`, so "approve once per session" works.

Common patterns:
- If a `device_api` action returns `permission_required`, ask the user to approve the prompt and then retry the same action.
- Uploaded files (`/user/upload`) are treated as an explicit read grant for those uploaded files.

## device_api Action Map

`device_api` action names map to Kotlin endpoints:

### System / Services
- `python.status` -> `GET /python/status`
- `python.restart` -> `POST /python/restart`
- `screen.status` -> `GET /screen/status`
- `screen.keep_on` -> `POST /screen/keep_on`
- `ssh.status` -> `GET /ssh/status`
- `ssh.config` -> `POST /ssh/config`
- `ssh.pin.status` -> `GET /ssh/pin/status`
- `ssh.pin.start` -> `POST /ssh/pin/start`
- `ssh.pin.stop` -> `POST /ssh/pin/stop`

### Media Playback
- `media.audio.status` -> `GET /media/audio/status`
- `media.audio.play` -> `POST /media/audio/play`
- `media.audio.stop` -> `POST /media/audio/stop`
  - `media.audio.play` accepts either:
    - `path`: user-root relative audio file path
    - `audio_b64`: base64 audio bytes (+ optional `ext`, e.g. `wav`, `mp3`, `m4a`)

### Camera (on-device)
- `camera.list` -> `GET /camera/list`
- `camera.status` -> `GET /camera/status`
- `camera.preview.start` -> `POST /camera/preview/start`
- `camera.preview.stop` -> `POST /camera/preview/stop`
- `camera.capture` -> `POST /camera/capture`

### USB (generic)
- `usb.list` -> `GET /usb/list`
- `usb.status` -> `GET /usb/status`
- `usb.open` -> `POST /usb/open`
- `usb.close` -> `POST /usb/close`
- `usb.raw_descriptors` -> `POST /usb/raw_descriptors`
- `usb.claim_interface` -> `POST /usb/claim_interface`
- `usb.release_interface` -> `POST /usb/release_interface`
- `usb.control_transfer` -> `POST /usb/control_transfer`
- `usb.bulk_transfer` -> `POST /usb/bulk_transfer`
- `usb.iso_transfer` -> `POST /usb/iso_transfer`
- `usb.stream.start` -> `POST /usb/stream/start`
- `usb.stream.stop` -> `POST /usb/stream/stop`
- `usb.stream.status` -> `GET /usb/stream/status`

### UVC (USB Webcam helpers)
- `uvc.mjpeg.capture` -> `POST /uvc/mjpeg/capture`
  - Captures one MJPEG frame from a connected UVC webcam and saves a JPEG under `captures/`.
  - Supports both `transfer_mode=iso` and `transfer_mode=bulk` depending on the camera endpoints.
- `uvc.diagnose` -> `POST /uvc/diagnose`
  - Runs a step-by-step USB/UVC diagnostic and returns a structured `steps[]` report:
    - USB device listing + matching
    - OS-level USB permission
    - Open device
    - Read raw descriptors + parse VC interface / camera terminal IDs / extension units
    - PTZ GET_CUR probes (including a known-good `wIndex=0x0100` probe for Insta360 Link)
- `uvc.ptz.*` -> implemented client-side via `usb.control_transfer` (CameraTerminal PTZ selectors)

### Vision (local TFLite pipeline)
- `vision.model.load` -> `POST /vision/model/load`
- `vision.model.unload` -> `POST /vision/model/unload`
- `vision.image.load` -> `POST /vision/image/load`
- `vision.frame.put` -> `POST /vision/frame/put`
- `vision.frame.get` -> `POST /vision/frame/get`
- `vision.frame.delete` -> `POST /vision/frame/delete`
- `vision.frame.save` -> `POST /vision/frame/save`
- `vision.run` -> `POST /vision/run`

### Llama.cpp (local GGUF models)
- `llama.status` -> `GET /llama/status`
- `llama.models` -> `GET /llama/models`
- `llama.run` -> `POST /llama/run`
- `llama.generate` -> `POST /llama/generate`
- `llama.tts` -> `POST /llama/tts`
- `llama.tts.speak` -> `POST /llama/tts/speak`
- `llama.tts.speak.status` -> `POST /llama/tts/speak/status`
- `llama.tts.speak.stop` -> `POST /llama/tts/speak/stop`

## File Endpoints (User Root)

- `POST /user/upload` (multipart)
  - Stores the uploaded file under `files/user/<dir>/...`
  - Returns `path` which is the user-root relative path to reference in chat as `rel_path: <path>`
- `GET /user/file?path=<rel_path>`
  - Serves bytes from user-root.
  - Used by the WebView to render previews (image/video/audio).

## Brain Journal (Per-Session Notes)

These endpoints store small, file-backed notes under `files/user/journal/<session_id>/...`.

- `GET /brain/journal/config`
  - Returns journal size limits and the root path.
- `GET /brain/journal/current?session_id=<sid>`
  - Returns the current per-session journal note (`CURRENT.md`).
- `POST /brain/journal/current`
  - Body: `{ "session_id": "<sid>", "text": "..." }`
  - Replaces `CURRENT.md` (auto-rotates to `CURRENT.<ts>.md` if too large).
- `POST /brain/journal/append`
  - Body: `{ "session_id": "<sid>", "kind": "milestone", "title": "...", "text": "...", "meta": {...} }`
  - Appends to `entries.jsonl` (auto-rotates to `entries.<ts>.jsonl` if too large).
  - If entry text is too large, it is stored as `entry.<ts>.<title>.md` and `stored_path` is set.
- `GET /brain/journal/list?session_id=<sid>&limit=30`
  - Returns recent journal entries for the session.

## Cloud Broker (Placeholder Expansion + Secrets)

- `POST /cloud/request`
  - Expands placeholders like `${config:brain.api_key}` and `${file:captures/latest.jpg:base64}`.
  - Injects secrets from the secure vault (do not embed API keys in messages).
  - Enforces `cloud.media_upload` permission for requests that include file placeholders.
- `GET /cloud/prefs`, `POST /cloud/prefs`
  - Includes image downscale config for `${file:...:base64}` uploads:
    - `image_resize_enabled`, `image_resize_max_dim_px`, `image_resize_jpeg_quality`
  - Large payload confirm thresholds:
    - `auto_upload_no_confirm_mb` (alias: `allow_auto_upload_payload_size_less_than_mb`)
