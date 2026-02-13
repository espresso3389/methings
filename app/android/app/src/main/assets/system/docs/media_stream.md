# Media Stream (File-Based Decoding)

Decode existing media files into raw uncompressed data streamed over WebSocket.

Uses `MediaExtractor` + `MediaCodec` to decode audio to PCM (s16le) and video to JPEG or RGBA frames. Multiple concurrent streams are supported, each with a unique `stream_id`.

All actions require permission: `tool="device.media"`, `capability="media_stream"`, `scope="session"`.

---

## Actions

### `media.stream.status` (GET)

Returns all active decode streams.

**Response:**
```json
{
  "status": "ok",
  "count": 1,
  "streams": [
    {
      "stream_id": "adec-a1b2c3d4",
      "type": "audio",
      "source": "recording.m4a",
      "running": true,
      "ws_clients": 1
    }
  ]
}
```

### `media.stream.audio.start` (POST)

Decode an audio file to raw PCM and stream over WebSocket.

**Payload:**
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `source_file` | string | required | User-root relative path to audio file |
| `sample_rate` | int | 44100 | Target sample rate (informational; actual rate matches source) |
| `channels` | int | source | Number of channels (0 = use source) |

**Response:**
```json
{
  "status": "ok",
  "stream_id": "adec-a1b2c3d4",
  "ws_path": "/ws/media/stream/adec-a1b2c3d4",
  "type": "audio",
  "encoding": "pcm_s16le"
}
```

### `media.stream.video.start` (POST)

Decode a video file to frames and stream over WebSocket.

**Payload:**
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `source_file` | string | required | User-root relative path to video file |
| `format` | string | "jpeg" | Output format: `"jpeg"` or `"rgba"` |
| `fps` | int | 10 | Target frames per second (1-60) |
| `jpeg_quality` | int | 70 | JPEG quality (10-95), only for jpeg format |

**Response:**
```json
{
  "status": "ok",
  "stream_id": "vdec-e5f6g7h8",
  "ws_path": "/ws/media/stream/vdec-e5f6g7h8",
  "type": "video",
  "format": "jpeg"
}
```

### `media.stream.stop` (POST)

Stop a decode stream.

**Payload:**
| Field | Type | Description |
|-------|------|-------------|
| `stream_id` | string | The stream ID to stop |

---

## WebSocket: `/ws/media/stream/<stream_id>`

Connect to the stream-specific WebSocket path returned by the start action.

### Wire format

1. **Hello message** (text, JSON) — sent when the stream starts decoding:

   Audio:
   ```json
   {"type": "hello", "sample_rate": 44100, "channels": 2, "encoding": "pcm_s16le"}
   ```

   Video:
   ```json
   {"type": "hello", "format": "jpeg", "width": 1920, "height": 1080, "fps": 10}
   ```

2. **Data frames** (binary):
   - **Audio:** Raw PCM s16le samples.
   - **Video (JPEG):** Raw JPEG bytes per frame.
   - **Video (RGBA):** 12-byte header `[width:u32le][height:u32le][ts_ms:u32le]` + raw RGBA bytes.

3. **End message** (text, JSON) — sent when the file is fully decoded:
   ```json
   {"type": "end"}
   ```

### Notes

- Each stream has its own WebSocket path. Connect after calling the start action.
- The stream automatically ends when the file is fully decoded (sends `{"type":"end"}` then closes).
- Use `media.stream.stop` to abort a stream early.
- Multiple streams can run concurrently (e.g., decode audio and video tracks separately).
- Video frame rate is rate-limited to the requested `fps`; frames are skipped if decoding is faster.
