# Media Stream API

Decode audio/video files to real-time PCM or frame streams over WebSocket.

## media_stream.status

Permission: `device.media`

Get active media decode streams.

**Returns:**
- `streams` (object[]): Each stream contains:
  - `stream_id` (string): Stream identifier
  - `type` (string): `audio` | `video`
  - `source_file` (string): Source file path

## media_stream.audio.start

Permission: `device.media`

Start decoding an audio file to raw PCM samples.

**Params:**
- `source_file` (string, required): User-root relative audio file path
- `sample_rate` (integer, optional): Target sample rate (resampled if different from source)
- `channels` (integer, optional): Target channel count

**Returns:**
- `stream_id` (string): Stream ID for management and WebSocket path
- `ws_path` (string): WebSocket path for PCM data

### WebSocket

Connect to `ws_path` (e.g. `/ws/media/stream/<stream_id>`) to receive raw PCM sample data.

## media_stream.video.start

Permission: `device.media`

Start decoding a video file to JPEG or RGBA frames.

**Params:**
- `source_file` (string, required): User-root relative video file path
- `format` (string, optional): Frame output format: `jpeg` | `rgba`
- `fps` (integer, optional): Target frame rate
- `jpeg_quality` (integer, optional): JPEG quality 1-100 (when format=jpeg)

**Returns:**
- `stream_id` (string): Stream ID
- `ws_path` (string): WebSocket path for frame data

### WebSocket

Connect to `ws_path` (e.g. `/ws/media/stream/<stream_id>`) to receive decoded frames.

## media_stream.stop

Permission: `device.media`

Stop a media decode stream.

**Params:**
- `stream_id` (string, optional): Stream ID to stop. Stops all streams if omitted.
