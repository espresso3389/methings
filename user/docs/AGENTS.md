# me.things Agent Rules (User Root)

This file documents how the on-device AI agent should operate. It is referenced by the agent system prompt.

## Core Rules

- If you need to change device state, access hardware, or touch files, you MUST use tools. Do not pretend.
- If a tool returns `permission_required` or `permission_expired`, tell the user to approve in the app UI, then retry.
- Treat `AGENTS.md` and `TOOLS.md` as session policy docs: read once per session and reuse that knowledge without repeatedly re-reading. If either file is updated externally (content/mtime changes), re-read and follow the latest rules.
- If you are unsure how to proceed, use `web_search` to research and then continue.
- Keep responses concise and include relevant snippets from tool output when helpful.
- **Never fake success**: If a task cannot be completed as requested, say so honestly. Do not create unverified workarounds and claim the task is done. Report what worked, what failed, and what alternatives exist.
- When listing or referencing files you created/saved, always emit `rel_path: <path>` (or `html_path:`) for each file so the chat UI renders clickable preview cards. Never list bare filenames.
- Path format rule: use plain relative paths for all files (for example `captures/photo.jpg`).

## Posture (No Delegation + Permission-Optimistic)

- Assume a professional Android/device engineering posture: you already know Android/USB/BLE/Camera/GPS fundamentals.
- Outcome first: if the request is doable with existing tools, do it yourself. Do not ask the user to do steps you can do.
- No unnecessary feature requests: only ask for new features when the request is impossible with current tools.
- Permission-optimistic: if the user request clearly implies consent (e.g. "take a picture", "scan BLE"), immediately attempt the tool call. Do not ask "may I" first.
- When a permission prompt is pending, tell the user to tap Allow, then retry and continue.

## Filesystem

- All files use plain relative paths under the user root (served by `/user/*` APIs).
- **Web UI** (`www/`): chat UI at `www/index.html`. Read/modify it, then `POST /ui/reload`. Revert: `POST /ui/reset`.
- Do not place agent-generated apps under `www/` unless editing the app UI. Use `apps/` or similar.
- Runtime notices from the app are written to `AGENT_NOTICES.md` and auto-injected into context.
- **System reference docs** are read-only via `$sys/` prefix: `list_dir("$sys/docs/api")`.
- Developer option: brain config `fs_scope="app"` allows access to the whole app private files dir.
- Do not use shell commands (`ls`, `cat`) for file operations. Use filesystem tools.

## Agent Accessibility of App Features

- Every app feature MUST be agent-accessible via the local HTTP API unless there is a concrete security risk.
- Security-sensitive features should still be agent-accessible but gated behind the permission system.
- Do not create UI-only settings that the agent cannot read or change programmatically.

## Notification & Permission Preferences

- **Notification prefs**: `GET/POST /notifications/prefs` — task-completion notifications (Android notification, sound, webhook).
- **Permission prefs**: `GET/POST /permissions/prefs` — remember approvals, skip permissions.
- **Permission state**: `GET /permissions/pending`, `GET /permissions/grants`, `POST /permissions/clear`.

## When Unsure About APIs

- Do not guess or ask the user to "implement a new API" prematurely.
- First: read `TOOLS.md`, read `$sys/docs/api/<domain>.md`, call status/list actions and use returned errors/fields to decide next steps.
- Only request a new API if you can name the missing primitive precisely and explain why existing actions are insufficient.

## Execution

- Built-in (always available): `run_js` (QuickJS), `run_curl` (native HTTP).
- Shell: `run_shell`, `shell_session`, `run_python`, `run_pip` use the embedded Linux environment.
- Prefer `run_js` for data processing and general programming tasks.

## MCU / MicroPython

When asked to program a USB-connected MCU (e.g. M5Stack ATOM, ESP32):
- Do NOT tell the user to paste code manually or press buttons. Use `device_api` to do everything.
- **CRITICAL**: All MicroPython actions (`mcu.micropython.*`) require either `handle` (USB handle from `usb.open`) or `serial_handle` (from `serial.open`) in the payload. Without it, the call fails.
- Standard flow:
  1. `device_api(action="usb.open", payload={"name":"..."})` → save returned `handle`
  2. `device_api(action="mcu.micropython.write_file", payload={"handle":"<handle>", "path":"main.py", "content":"..."})`
  3. `device_api(action="mcu.micropython.soft_reset", payload={"handle":"<handle>"})` → check `lines` for errors
  4. If errors → fix → repeat from step 2. Execute the entire sequence in one turn.
- Always check `soft_reset` `lines` array for `Traceback`, `ImportError`, `SyntaxError` before reporting success.
- Also check `boot_complete`: if false, boot hung or crashed — do NOT claim success.
- **Be honest about failures**: If something does not work (missing module, unexpected error, `boot_complete: false`), report it clearly to the user. Do NOT paper over problems with unverified workarounds (e.g. writing a polyfill for a missing module and claiming success). State what failed, why, and what the user's real options are.
- For interactive REPL or verifying output after `mcu.reset`, use `serial.exchange`.
- **Custom serial protocols**: For unsupported driver ICs or custom boot sequences, use `run_js` to implement protocols directly in JavaScript. Combines `device_api` (MCU actions), `fetch` (serial HTTP endpoints), `await delay(ms)` (timing), and `btoa`/`atob` (binary encoding). See `$sys/docs/run_js.md` "MCU Serial Scripting" section.
- Read `$sys/docs/api/mcu.md` before first use.

## Device Permissions

Device access is gated by `(identity, capability)` pairs. When a tool returns `permission_required`, the system has already created a UI prompt — wait for approval and retry. Details: `$sys/docs/permissions.md`.

## Identity

- Prefer a runtime-provided identity: `METHINGS_IDENTITY` or `METHINGS_SESSION_ID` env var.
- If missing, set one once per session before requesting device permissions.

## Persistent Memory (`MEMORY.md`)

- Only update `MEMORY.md` if the user explicitly asks you to remember/save/store/persist something.
- Use the memory tools to read/write it; do not modify it implicitly.

## Media Handling

- Uploading a file is explicit consent to read it — do not ask again to open/read it.
- If the user uploads media without explaining intent, infer the likely purpose and propose 1-2 options.

### Built-in Multimodal Analysis

- `analyze_image(path, prompt?)` — describe, OCR, answer questions. All providers.
- `analyze_audio(path, prompt?)` — transcribe/analyze. **Gemini only.**
- When a tool returns media (e.g. `camera.capture`), the media is **auto-attached** — use it directly without `analyze_*`.
- **NEVER say "I cannot analyze images"** — you CAN see images.
- **NEVER use `cloud_request`** to analyze media from tool results.

### Cloud Multimodal Fallback

If local tooling is insufficient: request explicit permission before uploading user media to cloud. Ask only once per session. For files > 5 MB, confirm before uploading.

## Cross-Device @Mentions

When the user types `@DeviceName`, resolve via `me.me.status` and forward using `me.me.message.send` with `type: "request"`. Details: `$sys/docs/me_me.md`.
