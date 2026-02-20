# Agent/App Debugging Notes (methings)

This document is a grab-bag of issues we've hit while running the agent on
Android, and the quickest ways to reproduce / debug them.

## Local Control Plane Basics

The Android app exposes a loopback-only HTTP API. The agent runs
in-process (`AgentRuntime`). Built-in tools `run_js` (QuickJS) and
`run_curl` (native HTTP) work without Termux. Shell tools
(`run_python`/`run_pip`) are delegated to Termux when installed.

Useful endpoints (via `adb forward`):

- `GET /health`
- `POST /shell/exec` with `{cmd:"python"|"pip", args:"...", cwd:"..."}` (requires Termux)
- `GET /brain/sessions`, `GET /brain/messages`
- `POST /brain/debug/comment` (local-only) to inject debug/system notes into a session timeline

## Common Shell Errors (Termux)

### `error: [Errno 2] No such file or directory: '/data/.../files/user/-'`

Cause: `python -` (stdin mode) isn't supported by the on-device shell.

Fix: use `python -c "..."` or run a script file under the user dir.

### `error: unexpected character after line continuation character (<string>, line 1)`

Cause: passing code to `python -c` that contains literal backslash sequences
like `\\n` (two characters) in places the interpreter treats as line continuations.

Fix:

- Prefer a single-line `-c` snippet using `;`, or
- Wrap multi-line code using `exec("line1\\nline2\\n...")`, or
- Write a small `.py` file and run it.

## pyusb / libusb on Android

### JNI crash when importing or using `pyusb`

Symptom: `ctypes.util.find_library()` fails (triggered by `pyusb`'s libusb backend).
- `libusb` is packaged under multiple common names (`libusb1.0.so`,
  `libusb-1.0.so`, `libusb.so`) so ecosystem code can find it.

Quick check (device):

`python -c "import ctypes.util; print(ctypes.util.find_library('usb-1.0'))"`

### `usb.backend.libusb1.get_backend()` returns `None` on unrooted Android

Even when the `libusb` shared library loads correctly, `libusb_init()` may fail
with `LIBUSB_ERROR_IO` on unrooted Android because apps cannot enumerate or open
USB devices via `/dev/bus/usb` directly. Android's USB Host API (USBManager)
must be used to obtain permission and file descriptors.

Status:

- `pyusb` is installed and can load the bundled `libusb` library.
- Full USB device access requires bridging from the app (USBManager) to libusb
  (typically via fd handoff / wrap APIs). Until then, `get_backend()` may be
  `None` even though the library exists.

## ABI Scope

As of 2026-02, we intentionally target only `arm64-v8a` to reduce app size and
keep the native dependency story simpler. Build scripts default to arm64 but
can be overridden with `ABIS=...` when needed for development.
