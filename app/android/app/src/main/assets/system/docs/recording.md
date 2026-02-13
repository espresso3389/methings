# Recording & Live Streaming

Record audio/video to compressed files or stream live uncompressed data over WebSocket.

---

## Audio Recording & PCM Streaming

All audio actions require permission: `tool="device.mic"`, `capability="recording"`, `scope="session"`.

### `audio.record.status` (GET)

Returns current recording and streaming state.

**Response:**
```json
{
  "status": "ok",
  "recording": false,
  "recording_state": "idle",
  "recording_duration_ms": null,
  "streaming": false,
  "stream_sample_rate": null,
  "stream_channels": null,
  "ws_clients": 0,
  "last_error": null
}
```

### `audio.record.start` (POST)

Start recording audio to an AAC file in .m4a container.

**Payload:**
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `path` | string | auto-generated | Output filename (relative to `recordings/audio/`) |
| `sample_rate` | int | config (44100) | Sample rate in Hz (8000-48000) |
| `channels` | int | config (1) | 1 = mono, 2 = stereo |
| `bitrate` | int | config (128000) | AAC bitrate in bps (32000-320000) |
| `max_duration_s` | int | config (300) | Auto-stop after N seconds (5-3600) |

**Response:**
```json
{
  "status": "ok",
  "state": "recording",
  "rel_path": "recordings/audio/rec_1700000000000.m4a",
  "sample_rate": 44100,
  "channels": 1,
  "bitrate": 128000,
  "max_duration_s": 300,
  "format": "aac",
  "container": "m4a"
}
```

### `audio.record.stop` (POST)

Stop an active recording.

**Response:**
```json
{
  "status": "ok",
  "stopped": true,
  "state": "idle",
  "rel_path": "recordings/audio/rec_1700000000000.m4a",
  "duration_ms": 5230,
  "size_bytes": 42800
}
```

### `audio.record.config.get` (GET)

Returns the default recording configuration stored in SharedPreferences.

**Response:**
```json
{
  "status": "ok",
  "sample_rate": 44100,
  "channels": 1,
  "bitrate": 128000,
  "max_duration_s": 300
}
```

### `audio.record.config.set` (POST)

Update default recording configuration. Only provided fields are changed.

**Payload:** Same fields as config.get response (all optional).

### `audio.stream.start` (POST)

Start live PCM audio capture and stream over WebSocket.

**Payload:**
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `sample_rate` | int | config (44100) | Sample rate in Hz |
| `channels` | int | config (1) | 1 = mono, 2 = stereo |

**Response:**
```json
{
  "status": "ok",
  "streaming": true,
  "ws_path": "/ws/audio/pcm",
  "sample_rate": 44100,
  "channels": 1,
  "encoding": "pcm_s16le"
}
```

### `audio.stream.stop` (POST)

Stop the live PCM stream.

### WebSocket: `/ws/audio/pcm`

Connect to receive live PCM audio frames.

**Wire format:**
1. **Hello message** (text, JSON) — sent when streaming starts or when a new client connects to an active stream:
   ```json
   {"type": "hello", "sample_rate": 44100, "channels": 1, "encoding": "pcm_s16le"}
   ```

2. **Audio frames** (binary) — raw signed 16-bit little-endian PCM samples. Frame size depends on the platform audio buffer; typically a few kilobytes per frame.

**Notes:**
- Connect before or after `audio.stream.start`. Late-joining clients receive a hello on connect.
- Server-to-client only; client messages are ignored.
- If no clients are connected, PCM data is silently discarded.
- Recording and streaming can run simultaneously on most devices, but some OEMs may block concurrent mic access.

---

## Video Recording & Frame Streaming

All video actions require permission: `tool="device.camera"`, `capability="recording"`, `scope="session"`.

### `video.record.status` (GET)

Returns current video recording and streaming state.

**Response:**
```json
{
  "status": "ok",
  "recording": false,
  "recording_state": "idle",
  "recording_duration_ms": null,
  "recording_codec": null,
  "streaming": false,
  "stream_format": null,
  "stream_fps": null,
  "ws_clients": 0,
  "last_error": null
}
```

### `video.record.start` (POST)

Start recording video to an H.265 (or H.264 fallback) .mp4 file with audio.

**Payload:**
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `path` | string | auto-generated | Output filename (relative to `recordings/video/`) |
| `lens` | string | "back" | Camera lens: `"back"` or `"front"` |
| `resolution` | string | config ("720p") | `"720p"`, `"1080p"`, or `"4k"` |
| `max_duration_s` | int | config (300) | Auto-stop after N seconds (5-3600) |

**Response:**
```json
{
  "status": "ok",
  "state": "recording",
  "rel_path": "recordings/video/vid_1700000000000.mp4",
  "resolution": "720p",
  "codec": "h265",
  "max_duration_s": 300,
  "lens": "back",
  "container": "mp4"
}
```

The `codec` field reports the actual codec used. If the device lacks an HEVC encoder, it falls back to `"h264"`.

### `video.record.stop` (POST)

Stop an active video recording.

**Response:**
```json
{
  "status": "ok",
  "stopped": true,
  "state": "idle",
  "rel_path": "recordings/video/vid_1700000000000.mp4",
  "duration_ms": 12340,
  "size_bytes": 2048000,
  "codec": "h265"
}
```

### `video.record.config.get` (GET)

Returns default video recording configuration.

**Response:**
```json
{
  "status": "ok",
  "resolution": "720p",
  "codec": "h265",
  "max_duration_s": 300
}
```

### `video.record.config.set` (POST)

Update default configuration. Only provided fields are changed.

**Payload:** `resolution` (`"720p"`, `"1080p"`, `"4k"`), `codec` (`"h265"`, `"h264"`), `max_duration_s` (5-3600).

### `video.stream.start` (POST)

Start live camera frame streaming over WebSocket.

**Payload:**
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `lens` | string | "back" | Camera lens: `"back"` or `"front"` |
| `width` | int | 640 | Frame width (160-1920) |
| `height` | int | 480 | Frame height (120-1080) |
| `fps` | int | 5 | Target frames per second (1-30) |
| `format` | string | "jpeg" | `"jpeg"` or `"rgba"` |
| `jpeg_quality` | int | 70 | JPEG quality (10-95), only for jpeg format |

**Response:**
```json
{
  "status": "ok",
  "streaming": true,
  "ws_path": "/ws/video/frames",
  "format": "jpeg",
  "width": 640,
  "height": 480,
  "fps": 5,
  "lens": "back"
}
```

### `video.stream.stop` (POST)

Stop the live video frame stream.

### WebSocket: `/ws/video/frames`

Connect to receive live camera frames.

**Wire format:**

1. **Hello message** (text, JSON):
   ```json
   {"type": "hello", "format": "jpeg", "width": 640, "height": 480, "fps": 5}
   ```

2. **Frames** (binary):
   - **JPEG format:** Raw JPEG bytes per frame.
   - **RGBA format:** 12-byte header `[width:u32le][height:u32le][ts_ms:u32le]` followed by raw RGBA pixel bytes (width * height * 4 bytes).

**Notes:**
- Late-joining clients receive a hello on connect if the stream is active.
- Server-to-client only.
- Frames are dropped when clients are slow (backpressure: keep-only-latest).
- Video recording and frame streaming use separate CameraX bindings and can conflict. Stop camera preview before starting video recording or frame streaming.

---

## Screen Recording

Record the device screen to H.265 (or H.264 fallback) .mp4 files.

All screen recording actions require permission: `tool="device.screen"`, `capability="screen_recording"`, `scope="once"`.

**Note:** Screen recording requires the Android system's MediaProjection consent dialog each time. The user must approve via the system dialog that appears on the device. The `screenrec.start` action has a 45-second timeout to allow for this interaction.

**Note:** `device_api` action names use the `screenrec.` prefix (not `screen.`) to avoid collision with existing `screen.status` / `screen.keep_on` actions.

### `screenrec.status` (GET)

Returns current screen recording state.

**Response:**
```json
{
  "status": "ok",
  "recording": false,
  "recording_state": "idle",
  "recording_duration_ms": null,
  "recording_codec": null,
  "last_error": null
}
```

### `screenrec.start` (POST)

Start screen recording. Launches the system consent dialog on the device.

**Payload:**
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `path` | string | auto-generated | Output filename (relative to `recordings/screen/`) |
| `resolution` | string | config ("720p") | `"720p"` or `"1080p"` |
| `bitrate` | int | config (6000000) | Video bitrate in bps (1M-20M) |
| `max_duration_s` | int | config (300) | Auto-stop after N seconds (5-3600) |

**Response:**
```json
{
  "status": "ok",
  "state": "recording",
  "rel_path": "recordings/screen/screen_1700000000000.mp4",
  "resolution": "1280x720",
  "codec": "h265",
  "bitrate": 6000000,
  "max_duration_s": 300,
  "container": "mp4"
}
```

If the user denies the consent dialog: `{"status": "error", "error": "projection_denied"}`.

### `screenrec.stop` (POST)

Stop the active screen recording.

**Response:**
```json
{
  "status": "ok",
  "stopped": true,
  "state": "idle",
  "rel_path": "recordings/screen/screen_1700000000000.mp4",
  "duration_ms": 15200,
  "size_bytes": 5120000,
  "codec": "h265"
}
```

### `screenrec.config.get` (GET)

Returns default screen recording configuration.

**Response:**
```json
{
  "status": "ok",
  "resolution": "720p",
  "bitrate": 6000000,
  "max_duration_s": 300
}
```

### `screenrec.config.set` (POST)

Update defaults. Only provided fields are changed.

**Payload:** `resolution` (`"720p"`, `"1080p"`), `bitrate` (1000000-20000000), `max_duration_s` (5-3600).

---

## Recorded files

- Audio: `recordings/audio/` — AAC in .m4a container
- Video: `recordings/video/` — H.265/H.264 in .mp4 container
- Screen: `recordings/screen/` — H.265/H.264 in .mp4 container

Retrieve via `GET /user/file?path=recordings/...` or use the filesystem tool with `rel_path` from the stop response.
