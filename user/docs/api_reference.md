# API Reference (Local Control Plane)

Kugutz exposes a local HTTP control plane on `http://127.0.0.1:8765`.

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
- `ssh.status` -> `GET /ssh/status`
- `ssh.config` -> `POST /ssh/config`
- `ssh.pin.status` -> `GET /ssh/pin/status`
- `ssh.pin.start` -> `POST /ssh/pin/start`
- `ssh.pin.stop` -> `POST /ssh/pin/stop`

### Camera (on-device)
- `camera.list` -> `GET /camera/list`
- `camera.status` -> `GET /camera/status`
- `camera.preview.start` -> `POST /camera/preview/start`
- `camera.preview.stop` -> `POST /camera/preview/stop`
- `camera.capture` -> `POST /camera/capture`

### USB (generic)
- `usb.list` -> `GET /usb/list`
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

## File Endpoints (User Root)

- `POST /user/upload` (multipart)
  - Stores the uploaded file under `files/user/<dir>/...`
  - Returns `path` which is the user-root relative path to reference in chat as `rel_path: <path>`
- `GET /user/file?path=<rel_path>`
  - Serves bytes from user-root.
  - Used by the WebView to render previews (image/video/audio).

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

