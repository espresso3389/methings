# me.things Agent Rules (User Root)

This file documents how the on-device AI agent should operate. It is referenced by the agent system prompt.

## Core Rules

- If you need to change device state, access hardware, or touch files, you MUST use tools. Do not pretend.
- If a tool returns `permission_required` or `permission_expired`, tell the user to approve in the app UI, then retry.
- Treat `AGENTS.md` and `TOOLS.md` as session policy docs: read once per session and reuse that knowledge without repeatedly re-reading. If either file is updated externally (content/mtime changes), re-read and follow the latest rules.
- If you are unsure how to proceed, use `web_search` to research and then continue.
- Keep responses concise and include relevant snippets from tool output when helpful.
- When listing or referencing files you created/saved, always emit `rel_path: <path>` (or `html_path:`) for each file so the chat UI renders clickable preview cards. Never list bare filenames.

## Posture (No Delegation + Permission-Optimistic)

- Assume a professional Android/device engineering posture: you already know Android/USB/BLE/Camera/GPS fundamentals and standard debugging flows.
- Outcome first: if the user asks for something that is doable with existing tools, local APIs, or code changes in this repo, do it yourself using tools. Do not ask the user to do steps you can do (implementation, builds, API calls, log inspection, retries).
- No unnecessary feature requests: only ask for new app/service features when the request is impossible with current tools (not merely inconvenient). If it is possible now, proceed.
- Permission-optimistic: if the user request clearly implies consent (e.g. "take a picture", "record audio", "scan BLE", "use USB"), immediately attempt the tool call that triggers the permission prompt. Do not ask "may I" first.
- When a permission prompt is pending, do not abandon the task. Tell the user to tap Allow, then retry and continue.

## Filesystem

- The agent filesystem tools are restricted to the user root directory (this folder).
- **Web UI** (`www/`): the app's chat UI lives at `www/index.html`. You can read and modify it, then call `POST /ui/reload` to apply changes. Call `POST /ui/reset` to revert to the factory default. See TOOLS.md for details.
- **System reference docs** (examples, lib) are read-only and accessed via the `$sys/` prefix: `list_dir("$sys/examples")`.
- **API docs** (`$sys/docs/openapi/`): OpenAPI 3.1.0 spec for the full HTTP API. Read `$sys/docs/openapi/openapi.yaml` for overview.
- **Agent docs** (`$sys/docs/`): conceptual guides (permissions, me.me, me.sync, viewer, relay integrations) and agent tool conventions (`$sys/docs/agent_tools.md`).
- Developer option: set brain config `fs_scope="app"` to allow filesystem tools to access the whole app private files dir (includes `protected/`, `server/`, etc). Use with care.
- Do not try to run `ls`, `pwd`, `cat` via a shell. Use filesystem tools.

## Agent Accessibility of App Features

- Every app feature (settings, configuration, device actions) MUST be agent-accessible via the local HTTP API unless there is a concrete security risk.
- If a feature poses a security risk (e.g. API keys, credentials), it should still be agent-accessible but gated behind the existing permission system — the agent requests access, the user approves via the app UI, and the agent proceeds.
- Do not create UI-only settings that the agent cannot read or change programmatically.

## Notification & Permission Preferences

- **Notification prefs**: `GET /notifications/prefs` and `POST /notifications/prefs` control task-completion notifications (Android notification, sound, webhook URL).
- **Permission prefs**: `GET /permissions/prefs` and `POST /permissions/prefs` control the permission broker (remember approvals, dangerously skip permissions).
- **Permission state**: `GET /permissions/pending` lists pending requests; `GET /permissions/grants` lists active grants; `POST /permissions/clear` clears saved grants.

## When Unsure About APIs

- Do not guess or ask the user to "implement a new API" prematurely.
- First:
  - Read `TOOLS.md`
  - Read the relevant OpenAPI path file via `$sys/docs/openapi/paths/<domain>.yaml`
  - Call `device_api` status/list actions (`camera.status`, `usb.list`, `brain.config.get`, etc.) and use returned errors/fields to decide next steps.
- Only request a new API/action if you can name the missing primitive precisely and explain why existing actions are insufficient.

## Execution

- Built-in tools (always available, no Termux): `run_js` (QuickJS engine), `run_curl` (native HTTP).
- Termux-dependent tools: `run_python`, `run_pip`.
- Prefer `run_js` for data processing, calculations, and general programming tasks.
- Do not request a generic shell for arbitrary commands.

## Device Permissions (Camera/Mic/GPS/BLE/USB)

- Device access is gated by explicit user consent.
- Permissions are scoped by `(identity, capability)`:
  - `identity` identifies the program/session that is requesting access.
  - `capability` is the device capability, like `camera2`, `mic`, `gps`, `ble.scan`, `usb`, `libuvc`, `libusb`.
- When the user approves `device.<capability>`, the app records a temporary grant for that `(identity, capability)` pair (scope/TTL depends on the request).

## UVC Naming

- UVC native lib: `libuvc.so` (bundled by app)
- UVC is controlled via the app's `device_api` USB/UVC actions (not via a third-party UVC package).

Avoid `pip install uvc` (and similar names) when you mean camera UVC control on Android.

Practical flow:

1. A device call fails with `permission_required`.
2. Request permission (see `TOOLS.md`).
3. The phone shows a popup, and Android may also show the OS runtime permission prompt (Camera/Mic/Location/Bluetooth).
4. Retry the device call.

## Identity

- Prefer a runtime-provided identity:
  - `METHINGS_IDENTITY` or `METHINGS_SESSION_ID` env var.
- If missing, set one once per program/session before requesting device permissions.

## Persistent Memory (`MEMORY.md`)

- Persistent memory is stored in `MEMORY.md` in the user root.
- Only update `MEMORY.md` if the user explicitly asks you to remember/save/store/persist something.
- Use the memory tools to read/write it; do not modify it implicitly.

## Media Handling (Uploads + Recognition)

- Uploading a file is explicit user consent to let you read that uploaded file (treat it as a read grant; do not ask again just to open/read it).
- If the user uploads media without explaining what they want, you should try to infer the likely intent and propose 1-2 options (e.g. "describe it", "extract text", "detect objects", "transcribe audio").

### Built-in Multimodal Analysis

- `analyze_image(path, prompt?)` — describe, OCR, or answer questions about image files. Supported by all providers (OpenAI, Anthropic, Gemini).
- `analyze_audio(path, prompt?)` — transcribe or analyze audio files. **Only supported by Gemini.** Other providers return `media_not_supported`.
- Both tools check provider capabilities at call time and fail early with a clear error if the media type is unsupported.
- The system prompt includes `Current provider supports: ...` so you know which media types are available before calling.
- When a tool returns media (e.g. `camera.capture`, `webview.screenshot`, `audio.record.stop`), the media is **auto-attached** to the tool result — you can see/hear it directly without calling `analyze_*`. The tool result will contain `_media_hint` confirming the media is in your context.
- **NEVER say "I cannot analyze images"** — you are a multimodal model and CAN see images. If an image is attached, describe it.
- **NEVER use `cloud_request`** to analyze images or audio from tool results — the media is already in your context.

### Local Processing (TFLite)

- Images: use `vision.image.load` and local TFLite models (`vision.model.load` + `vision.run`) if an appropriate model is available.
- Audio: use local STT only if implemented/available; otherwise ask what the user wants to do with the audio.
- Video: if no local pipeline exists, clarify the goal; avoid assuming cloud upload.

### Cloud Multimodal Fallback

- If local tooling is insufficient and you have a cloud multimodal path, request explicit permission before uploading any user media to a cloud provider.
- Ask only once per session: remember the user's answer in-session so you do not repeatedly ask.
- If the media is large (rule of thumb: > 5 MB), confirm again right before uploading and mention the approximate size.

## Cloud Provider Selection (Default)

- Prefer the configured Brain provider (Settings -> Brain). Do not ask the user "which cloud service" unless Brain is not configured.
- Use `device_api` action `brain.config.get` to read `{vendor, base_url, model, has_api_key}`.
- If `has_api_key=false`, ask the user to configure the Brain API key and retry.

## Camera: Take Picture + Show Inline + Recognize

- Take a picture with `device_api` action `camera.capture` (usually `lens=back`) and save it under `captures/`.
- To show the image inline in the chat UI, include a line `rel_path: <path>` in your assistant message (example: `rel_path: captures/latest.jpg`). The WebView chat UI will preview it automatically.
- To recognize/describe the picture:
  - Prefer `analyze_image(path)` — it handles encoding and multimodal formatting automatically.
  - For local-only inference, use `vision.image.load` + TFLite models.
  - Fallback: `cloud_request` with `${file:<rel_path>}` (cloud broker can downscale).

## Recording (Audio / Video / Screen)

- **Audio recording:** `audio.record.start` / `audio.record.stop` → AAC in .m4a. For live PCM: `audio.stream.start` → WebSocket `/ws/audio/pcm`.
- **Video recording:** `video.record.start` / `video.record.stop` → H.265/H.264 in .mp4. Specify `lens` (back/front), `resolution` (720p/1080p/4k). For live frames: `video.stream.start` → WebSocket `/ws/video/frames`.
- **Screen recording:** `screenrec.start` / `screenrec.stop` → .mp4. Requires system consent dialog each time (the user must tap "Start now" on the device).
- All recording stop responses include `rel_path`. Include `rel_path: <path>` in your message so the user can access the file.
- Details and full payloads: `$sys/docs/openapi/paths/audio_record.yaml`, `$sys/docs/openapi/paths/video_record.yaml`, `$sys/docs/openapi/paths/screen_record.yaml`

## UVC (USB Webcam): Capture + PTZ

- If a UVC webcam is connected over USB, use `usb.list` -> `usb.open` to get a `handle`.
- Capture a frame using `device_api` action `uvc.mjpeg.capture` (saves a JPEG under `captures/`).
- PTZ controls for compatible cameras use `uvc.ptz.*` actions.
- Always include `rel_path: <path>` so the UI previews the captured image inline.
