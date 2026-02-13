# Speech Recognizer (STT)

Use Android's built-in `SpeechRecognizer` via `device_api`.

## Events (WebSocket)

Recognition results are delivered on:

- `ws_path`: `/ws/stt/events`

Messages are JSON strings like:

- `{"type":"stt","event":"ready", ...}`
- `{"type":"stt","event":"partial","results":[...]}`
- `{"type":"stt","event":"final","results":[...]}`
- `{"type":"stt","event":"error","code":...}`

## Actions

- `stt.status` (GET)
- `stt.start` (POST) -> returns `ws_path`
  - payload:
    - `locale` (optional BCP-47 tag)
    - `partial` (bool, default true)
    - `max_results` (int)
- `stt.stop` (POST)
- `stt.transcribe` (POST) -> file/data to text (not implemented yet)
  - payload:
    - `rel_path` (preferred): audio file under `user/` root (e.g. `uploads/chat/foo.webm`)
    - OR `audio_b64`: base64-encoded bytes
  - current behavior:
    - returns `error=stt_transcribe_unavailable` (Android SpeechRecognizer supports live mic only)
    - use `/cloud/request` with `${file:...:base64}` for cloud STT fallback

Note: STT requires Android runtime permission `RECORD_AUDIO` (the app will prompt).
