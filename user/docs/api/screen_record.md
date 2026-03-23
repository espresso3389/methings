# Screen Record API

Screen recording to MP4 (H.265/H.264).

## screenrec.status

Permission: `device.screen`

Get current screen recording state.

**Returns:**
- `recording` (boolean): Whether recording is active
- `recording_state` (string): `idle` | `recording`
- `recording_duration_ms` (integer, nullable): Elapsed recording time
- `recording_codec` (string, nullable): Active codec
- `last_error` (string, nullable): Last error message

## screenrec.start

Permission: `device.screen`

Start screen recording. Requires system MediaProjection consent dialog each time (scope: once).

**Params:**
- `path` (string, optional): User-root relative output path
- `max_duration_s` (integer, optional): Maximum recording duration in seconds
- `resolution` (string, optional): `720p` | `1080p`. Default: `720p`
- `bitrate` (integer, optional): Video bitrate in bps (1000000-20000000). Default: 6000000

**Returns:**
- `state` (string): `recording`
- `rel_path` (string): Output file path under user root
- `resolution` (string): Actual resolution (e.g. `1280x720`)
- `codec` (string): `h265` | `h264`
- `bitrate` (integer): Actual bitrate
- `max_duration_s` (integer): Actual max duration
- `container` (string): `mp4`

## screenrec.stop

Permission: `device.screen`

Stop screen recording.

**Returns:**
- `stopped` (boolean): true
- `state` (string): `idle`
- `rel_path` (string): Output file path
- `duration_ms` (integer): Recording duration
- `size_bytes` (integer): File size
- `codec` (string): Codec used

## screenrec.config.get

Permission: `device.screen`

Get current screen recording configuration.

**Returns:**
- `resolution` (string): Configured resolution
- `codec` (string): Configured codec
- `bitrate` (integer): Configured bitrate

## screenrec.config.set

Permission: `device.screen`

Update screen recording configuration.

**Params:**
- `resolution` (string, optional): Target resolution
- `codec` (string, optional): Target codec
- `bitrate` (integer, optional): Target bitrate
