# Camera (CameraX)

methings exposes camera access via the Kotlin control plane (`device_api` tool). Use this instead of trying to `pip install` camera bindings.

## Actions

- `camera.list` (GET): list available cameras.
- `camera.status` (GET): show current preview state.
- `camera.capture` (POST): capture a still image to a file under the user root.
- `camera.preview.start` (POST): start a low-FPS JPEG preview stream over WebSocket.
- `camera.preview.stop` (POST): stop preview.

## Capture Still

`camera.capture` payload:

- `path`: relative path under user root (default `captures/capture_<ts>.jpg`)
- `lens`: `back` (default) or `front`
- `jpeg_quality`: 40..100 (optional, default 95)
- `exposure_compensation`: integer AE steps (optional; clamped to camera range)

Returns:

- `status`: `ok|error`
- `path`: app-private absolute path (debugging/logging)
- `rel_path`: user-root relative path (use this with filesystem tools and `vision.image.load`)

Notes:

- The HTTP call blocks until the file is saved (or a timeout/error), so `rel_path` should exist when `status=ok`.
- To show the image inline in the chat UI, include a line in your message:
  - `rel_path: <rel_path>`
- To download to your dev machine, use the local file endpoints (after approving `device.files` once):
  - `GET /user/file?path=<rel_path>` (example: `/user/file?path=captures/latest.jpg`)

## Preview Stream (JPEG)

`camera.preview.start` payload:

- `lens`: `back|front`
- `width`, `height`: target analysis size (best-effort)
- `fps`: throttled JPEG send rate
- `jpeg_quality`: 10..95

Response includes:

- `ws_path`: `/ws/camera/preview`

WebSocket binary messages are raw JPEG bytes (no framing).
