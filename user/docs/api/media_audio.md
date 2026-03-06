# Media Audio API

Audio playback on the device speaker. Supports wav, mp3, m4a, and other Android-supported formats.

## media.audio.status

`Permission: device.media`

Get audio playback status.

**Returns:**
- `playing` (boolean): Whether audio is currently playing
- `path` (string?): Currently playing file path (null if not playing)

## media.audio.play

`Permission: device.media`

Play audio on the device speaker. Provide either a file path or base64-encoded audio bytes.

**Params:**
- `path` (string, optional): User-root relative audio file path
- `audio_b64` (string, optional): Base64-encoded audio bytes
- `ext` (string, optional): File extension hint when using audio_b64 (e.g. wav, mp3, m4a)

**Notes:** Exactly one of `path` or `audio_b64` must be provided.

## media.audio.stop

`Permission: device.media`

Stop audio playback.
