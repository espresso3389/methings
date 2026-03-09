# Audio Record API

Audio recording to AAC (.m4a) and live PCM streaming via WebSocket.

Permission: `device.mic`

## audio.record.status

Get current audio recording state.

**Returns:**
- `recording` (boolean): whether recording is active
- `recording_state` (string): `idle` or `recording`
- `recording_duration_ms` (integer|null): current duration
- `streaming` (boolean): whether PCM stream is active
- `stream_sample_rate` (integer|null): stream sample rate
- `stream_channels` (integer|null): stream channel count
- `ws_clients` (integer): connected WebSocket clients
- `last_error` (string|null): last error message

## audio.record.start

Start recording AAC audio to .m4a file.

**Params:**
- `path` (string, optional): user-root relative output path
- `max_duration_s` (integer, optional): auto-stop after N seconds (5-3600). Default: 300
- `sample_rate` (integer, optional): 8000-48000 Hz. Default: 44100
- `channels` (integer, optional): 1 (mono) or 2 (stereo). Default: 1
- `bitrate` (integer, optional): AAC bitrate in bps (32000-320000). Default: 128000

**Returns:**
- `state` (string): `recording`
- `rel_path` (string): output file path (relative to `recordings/audio/`)
- `sample_rate` (integer): applied sample rate
- `channels` (integer): applied channels
- `bitrate` (integer): applied bitrate
- `max_duration_s` (integer): applied max duration
- `format` (string): `aac`
- `container` (string): `m4a`

## audio.record.stop

Stop audio recording.

**Returns:**
- `stopped` (boolean): true
- `state` (string): `idle`
- `rel_path` (string): saved recording path
- `duration_ms` (integer): recording duration in milliseconds
- `size_bytes` (integer): file size in bytes

## audio.record.config.get

Get current recording configuration defaults.

**Returns:**
- `sample_rate` (integer): current sample rate
- `channels` (integer): current channels
- `bitrate` (integer): current bitrate
- `max_duration_s` (integer): current max duration

## audio.record.config.set

Set recording configuration defaults.

**Params:**
- `sample_rate` (integer, optional): sample rate
- `channels` (integer, optional): channel count
- `bitrate` (integer, optional): bitrate

## audio.stream.start

Start live PCM audio stream. Connect to WebSocket for signed 16-bit LE samples.

**Params:**
- `sample_rate` (integer, optional): PCM sample rate in Hz
- `channels` (integer, optional): number of channels

**Returns:**
- `streaming` (boolean): true
- `ws_path` (string): WebSocket path (`/ws/audio/pcm`)
- `sample_rate` (integer): applied sample rate
- `channels` (integer): applied channels
- `encoding` (string): `pcm_s16le`

### WebSocket

Connect to `/ws/audio/pcm` after starting the stream.

- Server sends text `hello` message, then binary frames of signed 16-bit LE PCM samples.

## audio.stream.stop

Stop live PCM audio stream.
