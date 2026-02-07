# Text To Speech (TTS)

Use Android's built-in `TextToSpeech` via `device_api`. This does not require microphone permissions.

## Actions

- `tts.init` (POST): initialize TTS engine (optional).
  - payload: `engine` (string, optional)
- `tts.voices` (GET): list available voices.
- `tts.speak` (POST): speak text on the device speaker.
  - payload:
    - `text` (required)
    - `voice` (optional voice name)
    - `locale` (optional BCP-47 tag, e.g. `en-US`, `ja-JP`)
    - `rate` (optional float, ~0.1..3.0)
    - `pitch` (optional float, ~0.1..3.0)
- `tts.stop` (POST): stop current speech.

