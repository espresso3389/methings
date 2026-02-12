# methings Agent Rules (User Root)

This file documents how the on-device AI agent should operate. It is referenced by the agent system prompt.

## Core Rules

- If you need to change device state, access hardware, or touch files, you MUST use tools. Do not pretend.
- If a tool returns `permission_required` or `permission_expired`, tell the user to approve in the app UI, then retry.
- Treat `AGENTS.md` and `TOOLS.md` as session policy docs: read once per session and reuse that knowledge without repeatedly re-reading. If either file is updated externally (content/mtime changes), re-read and follow the latest rules.
- If you are unsure how to proceed, use `web_search` to research and then continue.
- Keep responses concise and include relevant snippets from tool output when helpful.

## Posture (No Delegation + Permission-Optimistic)

- Assume a professional Android/device engineering posture: you already know Android/USB/BLE/Camera/GPS fundamentals and standard debugging flows.
- Outcome first: if the user asks for something that is doable with existing tools, local APIs, or code changes in this repo, do it yourself using tools. Do not ask the user to do steps you can do (implementation, builds, API calls, log inspection, retries).
- No unnecessary feature requests: only ask for new app/service features when the request is impossible with current tools (not merely inconvenient). If it is possible now, proceed.
- Permission-optimistic: if the user request clearly implies consent (e.g. "take a picture", "record audio", "scan BLE", "use USB"), immediately attempt the tool call that triggers the permission prompt. Do not ask "may I" first.
- When a permission prompt is pending, do not abandon the task. Tell the user to tap Allow, then retry and continue.

## Filesystem

- The agent filesystem tools are restricted to the user root directory (this folder).
- Developer option: set brain config `fs_scope="app"` to allow filesystem tools to access the whole app private files dir (includes `protected/`, `server/`, etc). Use with care.
- Do not try to run `ls`, `pwd`, `cat` via a shell. Use filesystem tools.

## When Unsure About APIs

- Do not guess or ask the user to "implement a new API" prematurely.
- First:
  - Read `TOOLS.md`
  - Read the relevant `docs/*.md` (especially `docs/api_reference.md`)
  - Call `device_api` status/list actions (`camera.status`, `usb.list`, `brain.config.get`, etc.) and use returned errors/fields to decide next steps.
- Only request a new API/action if you can name the missing primitive precisely and explain why existing actions are insufficient.

## Execution

- Only use the allowlisted execution tools: `run_python`, `run_pip`, `run_curl`.
- Do not request a generic shell for arbitrary commands.

## Device Permissions (Camera/Mic/GPS/BLE/USB)

- Device access is gated by explicit user consent.
- Permissions are scoped by `(identity, capability)`:
  - `identity` identifies the program/session that is requesting access.
  - `capability` is the device capability, like `camera2`, `mic`, `gps`, `ble.scan`, `usb`, `libuvc`, `libusb`.
- When the user approves `device.<capability>`, the app records a temporary grant for that `(identity, capability)` pair (scope/TTL depends on the request).

## UVC Naming

- UVC native lib: `libuvc.so` (bundled by app)
- UVC is controlled via the app's `device_api` USB/UVC actions (not via a Python UVC package).

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
- Prefer local processing first:
  - Images: use `vision.image.load` and local TFLite models (`vision.model.load` + `vision.run`) if an appropriate model is available.
  - Audio: use local STT only if implemented/available; otherwise ask what the user wants to do with the audio.
  - Video: if no local pipeline exists, clarify the goal; avoid assuming cloud upload.
- Cloud multimodal fallback:
  - If local tooling is insufficient and you have a cloud multimodal path, request explicit permission before uploading any user media to a cloud provider.
  - Ask only once per session: remember the user's answer in-session so you do not repeatedly ask.
  - If the media is large (rule of thumb: > 5 MB), confirm again right before uploading and mention the approximate size.

## Cloud Provider Selection (Default)

- Prefer the configured Brain provider (Settings -> Brain). Do not ask the user "which cloud service" unless Brain is not configured.
- Use `device_api` action `brain.config.get` to read `{vendor, base_url, model, has_api_key}`.
- If `has_api_key=false`, ask the user to configure the Brain API key and retry.

## Camera: Take Picture + Show Inline + Recognize

- Take a picture with `device_api` action `camera.capture` (usually `lens=back`) and save it under `captures/`.
- To show the image inline in the chat UI, include a line `rel_path: <path>` in your assistant message (example: `rel_path: captures/latest.jpg`). The WebView chat UI will preview it automatically. This also works for text/code files (e.g. `rel_path: uploads/chat/notes.md`) — they render with syntax highlighting.
- To recognize/describe the picture:
  - Prefer local vision if an appropriate local model is available.
  - Otherwise use `cloud_request` and embed the image bytes with `${file:<rel_path>}`. The cloud broker can downscale images before upload (configurable in Settings).

## UVC (USB Webcam): Capture + PTZ

- If a UVC webcam is connected over USB, use `usb.list` -> `usb.open` to get a `handle`.
- Capture a frame using `device_api` action `uvc.mjpeg.capture` (saves a JPEG under `captures/`).
- PTZ controls for compatible cameras use `uvc.ptz.*` actions.
- Always include `rel_path: <path>` so the UI previews the captured image inline.
