# API Reference (Local Control Plane)

me.things exposes a local HTTP control plane on `http://127.0.0.1:8765`.

The agent should use the `device_api(action, payload, detail)` tool instead of calling these endpoints directly.

See also: `TOOLS.md` (in user root) for agent tool usage and quickstart examples.

## Identity + Permissions

- Most device actions require user approval via the permission broker.
- Approvals are remembered per `(identity, capability)` for the configured scope.
- The chat UI sets `identity` to the chat `session_id`, so "approve once per session" works.

Common patterns:
- If a `device_api` action returns `permission_required`, ask the user to approve the prompt and then retry the same action.
- Uploaded files (`/user/upload`) are treated as an explicit read grant for those uploaded files.

See [permissions.md](permissions.md) for scopes, identity model, and USB special cases.

---

## device_api Action Map

`device_api` action names map to Kotlin HTTP endpoints. Actions marked **[no perm]** do not require user approval; all others are permission-gated.

### System / Services

| Action | Method | Endpoint |
|--------|--------|----------|
| `python.status` | GET | `/python/status` **[no perm]** |
| `python.restart` | POST | `/python/restart` |
| `screen.status` | GET | `/screen/status` **[no perm]** |
| `screen.keep_on` | POST | `/screen/keep_on` |
| `shell.exec` | POST | `/shell/exec` |

### SSHD (On-device SSH Server)

| Action | Method | Endpoint |
|--------|--------|----------|
| `sshd.status` | GET | `/sshd/status` **[no perm]** |
| `sshd.config` | POST | `/sshd/config` |
| `sshd.keys.list` | GET | `/sshd/keys` **[no perm]** |
| `sshd.keys.add` | POST | `/sshd/keys/add` |
| `sshd.keys.delete` | POST | `/sshd/keys/delete` |
| `sshd.keys.policy.get` | GET | `/sshd/keys/policy` **[no perm]** |
| `sshd.keys.policy.set` | POST | `/sshd/keys/policy` |
| `sshd.pin.status` | GET | `/sshd/pin/status` **[no perm]** |
| `sshd.pin.start` | POST | `/sshd/pin/start` |
| `sshd.pin.stop` | POST | `/sshd/pin/stop` |
| `sshd.noauth.status` | GET | `/sshd/noauth/status` **[no perm]** |
| `sshd.noauth.start` | POST | `/sshd/noauth/start` |
| `sshd.noauth.stop` | POST | `/sshd/noauth/stop` |

Details: [sshd.md](sshd.md)

### SSH (Remote Host Client)

| Action | Method | Endpoint |
|--------|--------|----------|
| `ssh.exec` | POST | `/ssh/exec` |
| `ssh.scp` | POST | `/ssh/scp` |
| `ssh.ws.contract` | GET | `/ssh/ws/contract` **[no perm]** |

Details: [ssh.md](ssh.md)

### Camera

| Action | Method | Endpoint |
|--------|--------|----------|
| `camera.list` | GET | `/camera/list` |
| `camera.status` | GET | `/camera/status` |
| `camera.capture` | POST | `/camera/capture` |
| `camera.preview.start` | POST | `/camera/preview/start` |
| `camera.preview.stop` | POST | `/camera/preview/stop` |

Preview stream is delivered over WebSocket `/ws/camera/preview` (binary JPEG frames).

Details: [camera.md](camera.md)

### Media Playback

| Action | Method | Endpoint |
|--------|--------|----------|
| `media.audio.status` | GET | `/media/audio/status` |
| `media.audio.play` | POST | `/media/audio/play` |
| `media.audio.stop` | POST | `/media/audio/stop` |

`media.audio.play` accepts either:
- `path`: user-root relative audio file path
- `audio_b64`: base64 audio bytes (+ optional `ext`, e.g. `wav`, `mp3`, `m4a`)

### Audio Recording & PCM Streaming

| Action | Method | Endpoint |
|--------|--------|----------|
| `audio.record.status` | GET | `/audio/record/status` |
| `audio.record.start` | POST | `/audio/record/start` |
| `audio.record.stop` | POST | `/audio/record/stop` |
| `audio.record.config.get` | GET | `/audio/record/config` |
| `audio.record.config.set` | POST | `/audio/record/config` |
| `audio.stream.start` | POST | `/audio/stream/start` |
| `audio.stream.stop` | POST | `/audio/stream/stop` |

Recording produces AAC in .m4a container. PCM streaming delivers signed 16-bit LE samples over WebSocket `/ws/audio/pcm`.

Details: [recording.md](recording.md)

### Video Recording & Frame Streaming

| Action | Method | Endpoint |
|--------|--------|----------|
| `video.record.status` | GET | `/video/record/status` |
| `video.record.start` | POST | `/video/record/start` |
| `video.record.stop` | POST | `/video/record/stop` |
| `video.record.config.get` | GET | `/video/record/config` |
| `video.record.config.set` | POST | `/video/record/config` |
| `video.stream.start` | POST | `/video/stream/start` |
| `video.stream.stop` | POST | `/video/stream/stop` |

Recording produces H.265 (HEVC) or H.264 (fallback) in .mp4 container. Frame streaming delivers JPEG or RGBA over WebSocket `/ws/video/frames`.

Details: [recording.md](recording.md)

### Screen Recording

| Action | Method | Endpoint |
|--------|--------|----------|
| `screenrec.status` | GET | `/screen/record/status` |
| `screenrec.start` | POST | `/screen/record/start` |
| `screenrec.stop` | POST | `/screen/record/stop` |
| `screenrec.config.get` | GET | `/screen/record/config` |
| `screenrec.config.set` | POST | `/screen/record/config` |

Recording produces H.265 (HEVC) or H.264 in .mp4 container. Requires system MediaProjection consent dialog each time (scope: `once`).

Details: [recording.md](recording.md)

### Media Stream (File Decoding)

| Action | Method | Endpoint |
|--------|--------|----------|
| `media.stream.status` | GET | `/media/stream/status` |
| `media.stream.audio.start` | POST | `/media/stream/audio/start` |
| `media.stream.video.start` | POST | `/media/stream/video/start` |
| `media.stream.stop` | POST | `/media/stream/stop` |

Decode existing media files to raw PCM or JPEG/RGBA frames over dynamic WebSocket `/ws/media/stream/<stream_id>`.

Details: [media_stream.md](media_stream.md)

### Android TTS (Text-to-Speech)

| Action | Method | Endpoint |
|--------|--------|----------|
| `tts.init` | POST | `/tts/init` |
| `tts.voices` | GET | `/tts/voices` |
| `tts.speak` | POST | `/tts/speak` |
| `tts.stop` | POST | `/tts/stop` |

This is Android's built-in TextToSpeech. For local llama.cpp TTS (MioTTS etc.), see [Llama.cpp](#llamacpp-local-gguf-models) / [llama.md](llama.md).

Details: [tts.md](tts.md)

### STT (Speech Recognition)

| Action | Method | Endpoint |
|--------|--------|----------|
| `stt.status` | GET | `/stt/status` |
| `stt.record` | POST | `/stt/record` |

Recognition events are delivered over WebSocket `/ws/stt/events`.

Details: [stt.md](stt.md)

### Llama.cpp (Local GGUF Models)

| Action | Method | Endpoint |
|--------|--------|----------|
| `llama.status` | GET | `/llama/status` |
| `llama.models` | GET | `/llama/models` |
| `llama.run` | POST | `/llama/run` |
| `llama.generate` | POST | `/llama/generate` |
| `llama.tts` | POST | `/llama/tts` |
| `llama.tts.plugins.list` | GET | `/llama/tts/plugins` |
| `llama.tts.plugins.upsert` | POST | `/llama/tts/plugins/upsert` |
| `llama.tts.plugins.delete` | POST | `/llama/tts/plugins/delete` |
| `llama.tts.speak` | POST | `/llama/tts/speak` |
| `llama.tts.speak.status` | POST | `/llama/tts/speak/status` |
| `llama.tts.speak.stop` | POST | `/llama/tts/speak/stop` |

Notes:
- `llama.tts` / `llama.tts.speak` support `min_output_duration_ms` (default `400`).
- Set `min_output_duration_ms: 0` to disable short-output validation.

Details: [llama.md](llama.md)

### Sensors

| Action | Method | Endpoint |
|--------|--------|----------|
| `sensor.list` | GET | `/sensor/list` |
| `sensors.list` | GET | `/sensors/list` |
| `sensors.ws.contract` | GET | `/sensors/ws/contract` |

Realtime sensor data is delivered over WebSocket `/ws/sensors`.

Details: [sensors.md](sensors.md)

### Location

| Action | Method | Endpoint |
|--------|--------|----------|
| `location.status` | GET | `/location/status` |
| `location.get` | POST | `/location/get` |

`location.get` payload: `{ "high_accuracy": true, "timeout_ms": 12000 }`

### Network / Radio

| Action | Method | Endpoint |
|--------|--------|----------|
| `network.status` | GET | `/network/status` |
| `wifi.status` | GET | `/wifi/status` |
| `mobile.status` | GET | `/mobile/status` |

### BLE (Bluetooth Low Energy)

| Action | Method | Endpoint |
|--------|--------|----------|
| `ble.status` | GET | `/ble/status` |
| `ble.scan.start` | POST | `/ble/scan/start` |
| `ble.scan.stop` | POST | `/ble/scan/stop` |
| `ble.connect` | POST | `/ble/connect` |
| `ble.disconnect` | POST | `/ble/disconnect` |
| `ble.gatt.services` | POST | `/ble/gatt/services` |
| `ble.gatt.read` | POST | `/ble/gatt/read` |
| `ble.gatt.write` | POST | `/ble/gatt/write` |
| `ble.gatt.notify.start` | POST | `/ble/gatt/notify/start` |
| `ble.gatt.notify.stop` | POST | `/ble/gatt/notify/stop` |

BLE events are delivered over WebSocket `/ws/ble/events`.

Details: [ble.md](ble.md)

### USB (Generic)

| Action | Method | Endpoint |
|--------|--------|----------|
| `usb.list` | GET | `/usb/list` |
| `usb.status` | GET | `/usb/status` |
| `usb.open` | POST | `/usb/open` |
| `usb.close` | POST | `/usb/close` |
| `usb.raw_descriptors` | POST | `/usb/raw_descriptors` |
| `usb.claim_interface` | POST | `/usb/claim_interface` |
| `usb.release_interface` | POST | `/usb/release_interface` |
| `usb.control_transfer` | POST | `/usb/control_transfer` |
| `usb.bulk_transfer` | POST | `/usb/bulk_transfer` |
| `usb.iso_transfer` | POST | `/usb/iso_transfer` |
| `usb.stream.start` | POST | `/usb/stream/start` |
| `usb.stream.stop` | POST | `/usb/stream/stop` |
| `usb.stream.status` | GET | `/usb/stream/status` |

Details: [usb.md](usb.md)

### UVC (USB Webcam)

| Action | Method | Endpoint |
|--------|--------|----------|
| `uvc.mjpeg.capture` | POST | `/uvc/mjpeg/capture` |
| `uvc.diagnose` | POST | `/uvc/diagnose` |
| `uvc.ptz.get_abs` | — | virtual (via `usb.control_transfer`) |
| `uvc.ptz.get_limits` | — | virtual (via `usb.control_transfer`) |
| `uvc.ptz.set_abs` | — | virtual (via `usb.control_transfer`) |
| `uvc.ptz.nudge` | — | virtual (via `usb.control_transfer`) |

Details: [uvc.md](uvc.md)

### Vision (TFLite)

| Action | Method | Endpoint |
|--------|--------|----------|
| `vision.model.load` | POST | `/vision/model/load` |
| `vision.model.unload` | POST | `/vision/model/unload` |
| `vision.image.load` | POST | `/vision/image/load` |
| `vision.frame.put` | POST | `/vision/frame/put` |
| `vision.frame.get` | POST | `/vision/frame/get` |
| `vision.frame.delete` | POST | `/vision/frame/delete` |
| `vision.frame.save` | POST | `/vision/frame/save` |
| `vision.run` | POST | `/vision/run` |

Details: [vision.md](vision.md)

### Brain / Config

| Action | Method | Endpoint |
|--------|--------|----------|
| `brain.config.get` | GET | `/brain/config` **[no perm]** |
| `brain.memory.get` | GET | `/brain/memory` **[no perm]** |
| `brain.memory.set` | POST | `/brain/memory` |
| `cloud.prefs.get` | GET | `/cloud/prefs` **[no perm]** |
| `notifications.prefs.get` | GET | `/notifications/prefs` **[no perm]** |
| `notifications.prefs.set` | POST | `/notifications/prefs` **[no perm]** |
| `me.sync.status` | GET | `/me/sync/status` **[no perm]** |
| `me.sync.local_state` | GET | `/me/sync/local_state` **[no perm]** |
| `me.sync.prepare_export` | POST | `/me/sync/prepare_export` |
| `me.sync.share_nearby` | POST | `/me/sync/share_nearby` |
| `me.sync.import` | POST | `/me/sync/import` |
| `me.sync.wipe_all` | POST | `/me/sync/wipe_all` |

`brain.config.get` returns `{vendor, base_url, model, has_api_key}` (never returns the key itself).

### Background Jobs (App Update Check)

| Action | Method | Endpoint |
|--------|--------|----------|
| `work.app_update_check.status` | GET | `/work/jobs/app_update_check` **[no perm]** |
| `work.app_update_check.schedule` | POST | `/work/jobs/app_update_check/schedule` |
| `work.app_update_check.run_once` | POST | `/work/jobs/app_update_check/run_once` |
| `work.app_update_check.cancel` | POST | `/work/jobs/app_update_check/cancel` |

Notes:
- `status` returns current schedule/tracker snapshot plus WorkManager state for periodic and one-time jobs.
- `schedule` body: `{"interval_minutes":360,"require_charging":false,"require_unmetered":false,"replace":true}`. Interval is clamped to minimum 15 minutes.
- `run_once` body: `{"require_charging":false,"require_unmetered":false}`.
- `cancel` body: `{}` (or empty JSON); clears both periodic and one-time unique works.
- `schedule`, `run_once`, and `cancel` are permission-gated (`device.work` / capability `workmanager`).

### UI (Viewer & Settings) **[no perm]**

| Action | Method | Endpoint |
|--------|--------|----------|
| `viewer.open` | POST | `/ui/viewer/open` |
| `viewer.close` | POST | `/ui/viewer/close` |
| `viewer.immersive` | POST | `/ui/viewer/immersive` |
| `viewer.slideshow` | POST | `/ui/viewer/slideshow` |
| `viewer.goto` | POST | `/ui/viewer/goto` |
| `ui.settings.sections` | GET | `/ui/settings/sections` |
| `ui.settings.navigate` | POST | `/ui/settings/navigate` |
| `ui.me.sync.export.show` | POST | `/ui/me/sync/export/show` |

---

## WebSocket Endpoints

Raw WebSocket connections on the same `127.0.0.1:8765` host.

| Path | Description | Details |
|------|-------------|---------|
| `/ws/sensors` | Realtime sensor data stream | [sensors.md](sensors.md) |
| `/ws/ble/events` | BLE scan results, connect/disconnect, GATT notifications | [ble.md](ble.md) |
| `/ws/stt/events` | Speech recognition partial/final results | [stt.md](stt.md) |
| `/ws/camera/preview` | Camera preview (binary JPEG frames) | [camera.md](camera.md) |
| `/ws/audio/pcm` | Live PCM audio stream (s16le binary frames) | [recording.md](recording.md) |
| `/ws/video/frames` | Live video frames (JPEG or RGBA binary) | [recording.md](recording.md) |
| `/ws/media/stream/<id>` | Decoded media file stream (PCM/JPEG/RGBA) | [media_stream.md](media_stream.md) |

---

## HTTP-Only Endpoints

These endpoints are accessed directly via HTTP, not through `device_api`.

### File Endpoints (User Root)

| Method | Endpoint | Body / Query | Effect |
|--------|----------|--------------|--------|
| `POST` | `/user/upload` | `multipart/form-data` (`file`, optional `dir`) | Store uploaded file under `files/user/<dir>/...`; returns `path` |
| `GET` | `/user/file` | `path=<rel_path>` | Serve bytes from user-root |
| `GET` | `/user/file/info` | `path=<rel_path>` | Return metadata + image/Marp extras |
| `GET` | `/user/list` | `path=<rel_path>` | List files under a user-root directory |

Details: [file_endpoints.md](file_endpoints.md)

### System Reference Docs (Read-Only)

System reference docs (`docs/`, `examples/`, `lib/`) are extracted to `files/system/` and always overwritten on app start to match the current app version. The agent accesses them via the `$sys/` prefix in filesystem tools, which routes through these endpoints.

| Method | Endpoint | Query | Effect |
|--------|----------|-------|--------|
| `GET` | `/sys/list` | `path=<rel_path>` | List directory under `files/system/` |
| `GET` | `/sys/file` | `path=<rel_path>` | Serve file bytes from `files/system/` |

Response format matches `/user/list` and `/user/file` respectively. These endpoints are read-only; there are no write/delete counterparts.

### Viewer Control **[no perm]**

Programmatic control of the WebView fullscreen viewer via 5 POST endpoints (`/ui/viewer/open`, `close`, `immersive`, `slideshow`, `goto`). Supports `#page=N` fragment for Marp slide navigation.

Endpoints:

| Method | Endpoint | Body | Effect |
|--------|----------|------|--------|
| `POST` | `/ui/viewer/open` | `{"path":"rel/path.md"}` | Open user-root file in fullscreen viewer (auto-detect type) |
| `POST` | `/ui/viewer/close` | `{}` | Close viewer |
| `POST` | `/ui/viewer/immersive` | `{"enabled":true}` | Enter/exit immersive mode |
| `POST` | `/ui/viewer/slideshow` | `{"enabled":true}` | Enter/exit Marp slideshow mode |
| `POST` | `/ui/viewer/goto` | `{"page":0}` | Navigate to slide index |

Details: [viewer.md](viewer.md)

### Settings Navigation **[no perm]**

Programmatic navigation to Settings sections in the WebView.

Endpoints:

| Method | Endpoint | Body | Effect |
|--------|----------|------|--------|
| `GET` | `/ui/settings/sections` | — | List section IDs/labels and discovered setting-key mappings |
| `POST` | `/ui/settings/navigate` | `{"section_id":"permissions"}` or `{"setting_key":"remember_approvals"}` | Open Settings and scroll/highlight the target section |

Chat prefix shortcut in the app UI:
- `settings: <section_id_or_setting_key>` (examples: `settings: permissions`, `settings: remember_approvals`)

### me.sync (Export / Import / Migration)

One-time export/import endpoints for device-to-device transfer of chat memory/state.

| Method | Endpoint | Body | Effect |
|--------|----------|------|--------|
| `GET` | `/me/sync/status` | — | List active export packages and expiry |
| `GET` | `/me/sync/local_state` | — | Return whether receiver has existing local data to wipe |
| `POST` | `/me/sync/prepare_export` | `{"include_user":true,"include_protected_db":true,"include_identity":false,"mode":"export"}` | Build one-time export package and return download links + payload |
| `POST` | `/me/sync/share_nearby` | `{"id":"<transfer_id>"}` (optional) | Launch Android share sheet for the prepared `me_sync_uri` (Nearby Share, etc.) |
| `GET` | `/me/sync/download` | `?id=<transfer_id>&token=<token>` | Download prepared ZIP package |
| `POST` | `/me/sync/import` | `{"url":"http://.../me/sync/download?...","wipe_existing":true}` or `{"payload":"...","wipe_existing":true}` | Download package from source, wipe local state, then import |
| `POST` | `/me/sync/wipe_all` | `{"restart_app":true}` | **Dangerous:** wipe all local app data and restart app (best effort) |

Notes:
- `prepare_export` returns `me_sync_uri` (`me.things:me.sync:<base64url>`), `qr_data_url`, and download metadata.
- `share_nearby` may reuse an active export or create one; optional fields: `transfer_id`, `force_refresh`, `include_user`, `include_protected_db`, `include_identity`, `chooser_title`.
- `share_nearby` response includes `share.started` (bool) and returns `nearby_share_unavailable` when no share target is available.
- Current export payload (`version: 2`) prefers encrypted SSH/SCP transport:
  - `transport: "ssh_scp"`
  - `ssh: {host, port, user, remote_path, private_key_b64}`
  - `http_url` remains included as fallback for compatibility.
- Importer tries SSH/SCP first when SSH fields are present, then falls back to HTTP URL if needed.
- `prepare_export` supports both modes:
  - Export mode (default): `include_identity=false` (or `mode:"export"`), excludes `user/.ssh/id_dropbear*`.
  - Migration mode: `include_identity=true` (or `mode:"migration"`), includes `user/.ssh/id_dropbear*`.
- `import` accepts HTTP URL, JSON payload, or `me_sync_uri`.
- `import` defaults to `wipe_existing=true` and wipes receiver local state before applying imported data.
- Imported package then restores `files/user/`, `files/protected/app.db` (if present), re-applies credential/key state, and restarts Python worker.
- App GUI "Export" uses export mode only; migration mode is API-only.
- `wipe_all` is intentionally dangerous and API-only (no GUI button).

Details: [me_sync.md](me_sync.md)

### Brain Journal (Per-Session Notes)

| Method | Endpoint | Body / Query | Effect |
|--------|----------|--------------|--------|
| `GET` | `/brain/journal/config` | — | Return journal limits and root path |
| `GET` | `/brain/journal/current` | `session_id=<sid>` | Return current per-session note |
| `POST` | `/brain/journal/current` | `{"session_id":"<sid>","text":"..."}` | Replace current note |
| `POST` | `/brain/journal/append` | `{"session_id":"<sid>","kind":"milestone","title":"...","text":"...","meta":{...}}` | Append journal entry |
| `GET` | `/brain/journal/list` | `session_id=<sid>&limit=30` | Return recent entries |

Details: [brain_journal.md](brain_journal.md)

### Cloud Broker

| Method | Endpoint | Body | Effect |
|--------|----------|------|--------|
| `POST` | `/cloud/request` | Provider-specific request JSON | Expand placeholders, inject secrets, enforce media-upload permission |
| `GET` | `/cloud/prefs` | — | Read cloud broker preferences |
| `POST` | `/cloud/prefs` | Preferences JSON | Update resize/threshold preferences |

Details: [cloud_broker.md](cloud_broker.md)

### Notifications

| Method | Endpoint | Body | Effect |
|--------|----------|------|--------|
| `GET` | `/notifications/prefs` | — | Read task-completion notification preferences |
| `POST` | `/notifications/prefs` | Partial prefs JSON | Update notification preferences (partial merge) |

Fields: `notify_android` (bool, default `true`), `notify_sound` (bool, default `false`), `notify_webhook_url` (string, default `""`).

### Permissions

| Method | Endpoint | Body | Effect |
|--------|----------|------|--------|
| `POST` | `/permissions/request` | `{"tool","detail","scope","identity","capability"}` | Create permission request |
| `GET` | `/permissions/pending` | — | List pending permission requests |
| `GET` | `/permissions/grants` | — | List currently active (non-expired) approved grants |
| `GET` | `/permissions/{id}` | — | Return request status |
| `POST` | `/permissions/{id}` | `{"approved": true or false}` | Approve or deny request |
| `POST` | `/permissions/clear` | — | Clear grants |
| `GET` | `/permissions/prefs` | — | Read permission preferences |
| `POST` | `/permissions/prefs` | Preferences JSON | Update permission preferences |

Details: [permissions.md](permissions.md)

### Vault

| Method | Endpoint | Body | Effect |
|--------|----------|------|--------|
| `GET` | `/vault/credentials` | — | List stored credential names |
| `POST` | `/vault/credentials/get` | `{"name":"..."}` | Retrieve credential value |
| `POST` | `/vault/credentials/has` | `{"name":"..."}` | Check if credential exists |

Details: [vault.md](vault.md)

### Health

| Method | Endpoint | Effect |
|--------|----------|--------|
| `GET` | `/health` | Server health check |

Details: [health.md](health.md)
