# Camera (CameraX)

Kugutz exposes camera access via the Kotlin control plane (`device_api` tool). Use this instead of trying to `pip install` camera bindings.

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

Returns `{status, path}` (path is app-private absolute path).

## Preview Stream (JPEG)

`camera.preview.start` payload:

- `lens`: `back|front`
- `width`, `height`: target analysis size (best-effort)
- `fps`: throttled JPEG send rate
- `jpeg_quality`: 10..95

Response includes:

- `ws_path`: `/ws/camera/preview`

WebSocket binary messages are raw JPEG bytes (no framing).

