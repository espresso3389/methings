# STT API

Speech recognition via Android SpeechRecognizer (live mic only).

## stt.status

Get speech recognition status.

`Permission: device.stt`

**Returns:**
- `listening` (boolean): Whether speech recognition is currently active.

## stt.record

Start speech recognition. Events are delivered over WebSocket.

`Permission: device.stt`

**Params:**
- `locale` (string, optional): BCP-47 locale tag (e.g. `en-US`).
- `partial` (boolean, optional): Whether to deliver partial results. Default: true
- `max_results` (integer, optional): Maximum number of result alternatives.

**Returns:**
- `ws_path` (string): WebSocket path for STT events (e.g. `/ws/stt/events`).

### WebSocket

Connect to the returned `ws_path`. Messages are JSON:

- `{"type":"stt","event":"ready"}` — recognizer ready
- `{"type":"stt","event":"partial","results":[...]}` — partial results
- `{"type":"stt","event":"final","results":[...]}` — final results
- `{"type":"stt","event":"error","code":...}` — error

**Notes:** File-based transcription (`stt.transcribe`) is not yet available; Android SpeechRecognizer supports live mic only. Use `/cloud/request` with `${file:<rel_path>:base64}` for cloud STT fallback. Requires Android RECORD_AUDIO runtime permission (the app will prompt).
