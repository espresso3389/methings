# Camera API

Permission: `device.camera` (all actions)

Still image capture and preview streaming. For video recording, see [video_record.md](video_record.md). For OpenCV processing on camera frames, see [cv.md](../cv.md).

## camera.list

List available cameras.

**Returns:** `cameras` array with `id` (string), `lens` (`back`|`front`|`external`)

## camera.status

Get camera preview state.

**Returns:** `preview_active` (bool), `lens` (string)

## camera.capture

Capture a still image. Blocks until file is saved.

**Params:**
- `path` (string, optional): Output path. Default: `captures/capture_<timestamp>.jpg`
- `lens` (string, optional): `back` | `front`. Default: `back`
- `jpeg_quality` (integer, optional): 40-100. Default: 95
- `exposure_compensation` (integer, optional): AE compensation steps

**Returns:** `rel_path` (string, user-root relative), `path` (string, absolute)

**Notes:** Include `rel_path: <path>` in chat to show inline preview. Download via `GET /user/file/<rel_path>`.

## camera.preview.start

Start low-FPS JPEG preview stream.

**Params:**
- `lens` (string): `back`|`front`. Default: `back`
- `width` (int): Default: 640
- `height` (int): Default: 480
- `fps` (int): Default: 5
- `jpeg_quality` (int): 10-95. Default: 70

**Returns:** `ws_path` (`/ws/camera/preview`)

### WebSocket

Connect to `/ws/camera/preview`. Binary messages are raw JPEG bytes.

## camera.preview.stop

Stop preview stream.
