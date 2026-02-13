# Audio Recording & PCM Streaming

Record audio to compressed files (AAC/.m4a) or stream live PCM data over WebSocket.

All actions require permission: `tool="device.mic"`, `capability="recording"`, `scope="session"`.

---

## Actions

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

---

## WebSocket: `/ws/audio/pcm`

Connect to receive live PCM audio frames.

### Wire format

1. **Hello message** (text, JSON) — sent when streaming starts or when a new client connects to an active stream:
   ```json
   {"type": "hello", "sample_rate": 44100, "channels": 1, "encoding": "pcm_s16le"}
   ```

2. **Audio frames** (binary) — raw signed 16-bit little-endian PCM samples. Frame size depends on the platform audio buffer; typically a few kilobytes per frame.

### Notes

- Connect to the WebSocket before or after calling `audio.stream.start`. Late-joining clients receive a hello message on connect.
- The WebSocket is server-to-client only; client messages are ignored.
- If no clients are connected, PCM data is silently discarded.
- Recording and streaming can run simultaneously on most devices, but some OEMs may block concurrent mic access.

---

## Recorded files

Audio recordings are saved under `recordings/audio/` in the user root. Retrieve them via:
- `GET /user/file?path=recordings/audio/<filename>`
- Or use the filesystem tool with `rel_path` from the stop response.
