# Kugutz Agent Rules (User Root)

This file documents how the on-device AI agent should operate. It is referenced by the agent system prompt.

## Core Rules

- If you need to change device state, access hardware, or touch files, you MUST use tools. Do not pretend.
- If a tool returns `permission_required` or `permission_expired`, tell the user to approve in the app UI, then retry.
- Keep responses concise and include relevant snippets from tool output when helpful.

## Filesystem

- The agent filesystem tools are restricted to the user root directory (this folder).
- Do not try to run `ls`, `pwd`, `cat` via a shell. Use filesystem tools.

## Execution

- Only use the allowlisted execution tools: `run_python`, `run_pip`, `run_uv`, `run_curl`.
- Do not request a generic shell for arbitrary commands.

## Device Permissions (Camera/Mic/GPS/BLE/USB)

- Device access is gated by explicit user consent.
- Permissions are scoped by `(identity, capability)`:
  - `identity` identifies the program/session that is requesting access.
  - `capability` is the device capability, like `camera2`, `mic`, `gps`, `ble.scan`, `usb`, `libuvc`, `libusb`.
- When the user approves `device.<capability>`, the app records a temporary grant for that `(identity, capability)` pair (scope/TTL depends on the request).

Practical flow:

1. A device call fails with `permission_required`.
2. Request permission (see `TOOLS.md`).
3. The phone shows a popup, and Android may also show the OS runtime permission prompt (Camera/Mic/Location/Bluetooth).
4. Retry the device call.

## Identity

- Prefer a runtime-provided identity:
  - `KUGUTZ_IDENTITY` or `KUGUTZ_SESSION_ID` env var.
- If missing, set one once per program/session before requesting device permissions.

## Persistent Memory (`MEMORY.md`)

- Persistent memory is stored in `MEMORY.md` in the user root.
- Only update `MEMORY.md` if the user explicitly asks you to remember/save/store/persist something.
- Use the memory tools to read/write it; do not modify it implicitly.

