# Video Record API

Permission: `device.camera` (all actions)

## video_record.status

Get current recording and streaming state.

**Returns:** `recording` (bool), `recording_state` (`idle`|`recording`), `recording_duration_ms` (int?), `recording_codec` (string?), `streaming` (bool), `stream_format` (string?), `stream_fps` (int?), `ws_clients` (int), `last_error` (string?)

## video_record.start

Start recording. H.265 (HEVC) or H.264 fallback, .mp4 container.

**Params:**
- `path` (string, optional): User-root relative output path
- `lens` (string, optional): `back` | `front`
- `max_duration_s` (integer, optional): Max duration in seconds
- `resolution` (string, optional): `720p` | `1080p` | `4k`. Default: `720p`

**Returns:** `state`, `rel_path`, `resolution`, `codec` (`h265`|`h264`), `max_duration_s`, `lens`, `container` (`mp4`)

## video_record.stop

Stop recording.

**Returns:** `rel_path` (string), `duration_ms` (int), `size_bytes` (int), `codec` (string)

## video_record.config.get

**Returns:** `resolution`, `codec`, `bitrate`

## video_record.config.set

**Params:** `resolution` (string), `codec` (string), `bitrate` (integer) -- all optional

## video_stream.start

Start live frame streaming over WebSocket.

**Params:**
- `lens` (string): `back` | `front`
- `width`, `height` (integer): Frame dimensions
- `fps` (integer): Target frame rate
- `format` (string): `jpeg` | `rgba`
- `jpeg_quality` (integer): 1-100

**Returns:** `ws_path` (`/ws/video/frames`), `format`, `width`, `height`, `fps`, `lens`

### WebSocket

Connect to `/ws/video/frames`. Server sends text `"hello"`, then binary frames.

- **JPEG**: raw JPEG bytes per message
- **RGBA**: 12-byte header `[width:u32le][height:u32le][ts_ms:u32le]` + raw RGBA bytes

## video_stream.stop

Stop live frame stream.
