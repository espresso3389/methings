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

Note: STT requires Android runtime permission `RECORD_AUDIO` (the app will prompt).

