# API Reference (Local Control Plane)

methings exposes a local HTTP control plane on `http://127.0.0.1:8765`.

The agent should use the `device_api(action, payload, detail)` tool instead of calling these endpoints directly.

## Identity + Permissions (High Level)

- Most device actions require user approval via the permission broker.
- Approvals are remembered per `(identity, capability)` for the configured scope.
- The chat UI sets `identity` to the chat `session_id`, so "approve once per session" works.

Common patterns:
- If a `device_api` action returns `permission_required`, ask the user to approve the prompt and then retry the same action.
- Uploaded files (`/user/upload`) are treated as an explicit read grant for those uploaded files.

## device_api Action Map

`device_api` action names map to Kotlin endpoints:

### System / Services
- `python.status` -> `GET /python/status`
- `python.restart` -> `POST /python/restart`
- `screen.status` -> `GET /screen/status`
- `screen.keep_on` -> `POST /screen/keep_on`
- `ssh.status` -> `GET /ssh/status`
- `ssh.config` -> `POST /ssh/config`
- `ssh.pin.status` -> `GET /ssh/pin/status`
- `ssh.pin.start` -> `POST /ssh/pin/start`
- `ssh.pin.stop` -> `POST /ssh/pin/stop`

### Media Playback
- `media.audio.status` -> `GET /media/audio/status`
- `media.audio.play` -> `POST /media/audio/play`
- `media.audio.stop` -> `POST /media/audio/stop`
  - `media.audio.play` accepts either:
    - `path`: user-root relative audio file path
    - `audio_b64`: base64 audio bytes (+ optional `ext`, e.g. `wav`, `mp3`, `m4a`)

### Camera (on-device)
- `camera.list` -> `GET /camera/list`
- `camera.status` -> `GET /camera/status`
- `camera.preview.start` -> `POST /camera/preview/start`
- `camera.preview.stop` -> `POST /camera/preview/stop`
- `camera.capture` -> `POST /camera/capture`

### USB (generic)
- `usb.list` -> `GET /usb/list`
- `usb.status` -> `GET /usb/status`
- `usb.open` -> `POST /usb/open`
- `usb.close` -> `POST /usb/close`
- `usb.raw_descriptors` -> `POST /usb/raw_descriptors`
- `usb.claim_interface` -> `POST /usb/claim_interface`
- `usb.release_interface` -> `POST /usb/release_interface`
- `usb.control_transfer` -> `POST /usb/control_transfer`
- `usb.bulk_transfer` -> `POST /usb/bulk_transfer`
- `usb.iso_transfer` -> `POST /usb/iso_transfer`
- `usb.stream.start` -> `POST /usb/stream/start`
- `usb.stream.stop` -> `POST /usb/stream/stop`
- `usb.stream.status` -> `GET /usb/stream/status`

### UVC (USB Webcam helpers)
- `uvc.mjpeg.capture` -> `POST /uvc/mjpeg/capture`
  - Captures one MJPEG frame from a connected UVC webcam and saves a JPEG under `captures/`.
  - Supports both `transfer_mode=iso` and `transfer_mode=bulk` depending on the camera endpoints.
- `uvc.diagnose` -> `POST /uvc/diagnose`
  - Runs a step-by-step USB/UVC diagnostic and returns a structured `steps[]` report:
    - USB device listing + matching
    - OS-level USB permission
    - Open device
    - Read raw descriptors + parse VC interface / camera terminal IDs / extension units
    - PTZ GET_CUR probes (including a known-good `wIndex=0x0100` probe for Insta360 Link)
- `uvc.ptz.*` -> implemented client-side via `usb.control_transfer` (CameraTerminal PTZ selectors)

### Vision (local TFLite pipeline)
- `vision.model.load` -> `POST /vision/model/load`
- `vision.model.unload` -> `POST /vision/model/unload`
- `vision.image.load` -> `POST /vision/image/load`
- `vision.frame.put` -> `POST /vision/frame/put`
- `vision.frame.get` -> `POST /vision/frame/get`
- `vision.frame.delete` -> `POST /vision/frame/delete`
- `vision.frame.save` -> `POST /vision/frame/save`
- `vision.run` -> `POST /vision/run`

### Llama.cpp (local GGUF models)
- `llama.status` -> `GET /llama/status`
- `llama.models` -> `GET /llama/models`
- `llama.run` -> `POST /llama/run`
- `llama.generate` -> `POST /llama/generate`
- `llama.tts` -> `POST /llama/tts`
- `llama.tts.plugins.list` -> `GET /llama/tts/plugins`
- `llama.tts.plugins.upsert` -> `POST /llama/tts/plugins/upsert`
- `llama.tts.plugins.delete` -> `POST /llama/tts/plugins/delete`
- `llama.tts.speak` -> `POST /llama/tts/speak`
- `llama.tts.speak.status` -> `POST /llama/tts/speak/status`
- `llama.tts.speak.stop` -> `POST /llama/tts/speak/stop`

### Network / Radio status
- `network.status` -> `GET /network/status`
- `wifi.status` -> `GET /wifi/status`
- `mobile.status` -> `GET /mobile/status`
- `ble.status` -> `GET /ble/status`

## File Endpoints (User Root)

| Method | Endpoint | Body / Query | Effect |
|--------|----------|--------------|--------|
| `POST` | `/user/upload` | `multipart/form-data` (`file`, optional `dir`) | Store uploaded file under `files/user/<dir>/...`; returns `path` |
| `GET` | `/user/file` | `path=<rel_path>` | Serve bytes from user-root for preview/render/open |
| `GET` | `/user/file/info` | `path=<rel_path>` | Return metadata + image/Marp extras |

Details: [file_endpoints.md](file_endpoints.md)

## Viewer Control

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

## Brain Journal (Per-Session Notes)

| Method | Endpoint | Body / Query | Effect |
|--------|----------|--------------|--------|
| `GET` | `/brain/journal/config` | — | Return journal limits and root path |
| `GET` | `/brain/journal/current` | `session_id=<sid>` | Return current per-session note |
| `POST` | `/brain/journal/current` | `{"session_id":"<sid>","text":"..."}` | Replace current note |
| `POST` | `/brain/journal/append` | `{"session_id":"<sid>","kind":"milestone","title":"...","text":"...","meta":{...}}` | Append journal entry |
| `GET` | `/brain/journal/list` | `session_id=<sid>&limit=30` | Return recent entries |

Details: [brain_journal.md](brain_journal.md)

## Cloud Broker (Placeholder Expansion + Secrets)

| Method | Endpoint | Body | Effect |
|--------|----------|------|--------|
| `POST` | `/cloud/request` | Provider-specific request JSON | Expand placeholders, inject secrets, enforce media-upload permission |
| `GET` | `/cloud/prefs` | — | Read cloud broker preferences |
| `POST` | `/cloud/prefs` | Preferences JSON | Update resize/threshold preferences |

Details: [cloud_broker.md](cloud_broker.md)

## Notification Preferences

| Method | Endpoint | Body | Effect |
|--------|----------|------|--------|
| `GET` | `/notifications/prefs` | — | Read task-completion notification settings |
| `POST` | `/notifications/prefs` | `{"notify_android":bool,"notify_sound":bool,"notify_webhook_url":"..."}` | Update notification settings (partial updates OK) |

Response fields:
- `notify_android` — show Android notification when agent finishes while backgrounded (default `true`)
- `notify_sound` — play a sound with the notification (default `false`)
- `notify_webhook_url` — optional webhook URL called on task completion (default `""`)

## Permission Preferences

| Method | Endpoint | Body | Effect |
|--------|----------|------|--------|
| `GET` | `/permissions/prefs` | — | Read permission broker preferences |
| `POST` | `/permissions/prefs` | `{"remember_approvals":bool,"dangerously_skip_permissions":bool}` | Update permission preferences |
| `GET` | `/permissions/pending` | — | List pending permission requests |
| `GET` | `/permissions/grants` | — | List active approved permission grants |
| `POST` | `/permissions/clear` | `{}` | Clear all saved approval grants |

Preference fields:
- `remember_approvals` — remember user approvals for the configured scope (default `true`)
- `dangerously_skip_permissions` — auto-approve all permission requests without prompting (default `false`)

## Maintenance (UI-only)

The following operations are available only from the WebView settings UI (via `AndroidBridge`). They do not have HTTP API endpoints yet.

- **Reset UI** — reverts `index.html` to the APK-bundled default
- **Reset Agent Docs** — reverts `AGENTS.md`, `TOOLS.md`, and `docs/` to APK-bundled defaults
