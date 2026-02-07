# Kugutz Tools (User Root)

This file is a quick reference for the agent's tools and permission patterns.

## Filesystem Tools (User Root Only)

- `list_dir(path, show_hidden, limit)`
- `read_file(path, max_bytes)`
- `write_file(path, content, append)`
- `mkdir(path, parents)`
- `move_path(src, dst, overwrite)`
- `delete_path(path, recursive)`

Use these instead of shell commands like `ls`/`cat`.

## Execution Tools (Allowlist)

- `run_python(args, cwd)`
- `run_pip(args, cwd)`
- `run_curl(args, cwd)`

Notes:
- `python -` (stdin) is not supported (no interactive stdin). Use `python -c "..."` or write a script file and run it.

## Web Search Tool (Permission-Gated)

- `web_search(query, max_results, provider)`

Notes:
- DuckDuckGo is not a full web-search API here; it is the Instant Answer API and can be weak for non-English queries.
- Prefer `provider="auto"` (default) which uses Brave Search API if configured, else DuckDuckGo.
- If you need better non-English search, use `provider="brave"` and store `brave_search_api_key` in vault.

## Kotlin Control Plane Tool

- `device_api(action, payload, detail)`

Used for allowlisted device control-plane actions (python/ssh/memory/shell.exec, etc). Some actions require user approval and will return `permission_required`.

## Docs Index

Read the relevant doc when working in that domain:
- `docs/vision.md` (RGBA8888 + TFLite)
- `docs/usb.md` (USB/UVC control + streaming)
- `docs/permissions.md` (permission scopes and identity)
- `examples/README.md` (copy/paste golden paths)

## Permission Requests

Permissions are created via the local HTTP endpoint:

- `POST /permissions/request` with JSON:
  - `tool`: e.g. `device.camera2`, `device.mic`, `device.gps`, `device.ble.scan`, `device.usb`
  - `detail`: short reason shown to the user
  - `scope`: `once` | `program` | `session` | `persistent`
  - `identity`: stable id for the requesting program/session (optional but recommended)
  - `capability`: capability name (optional; derived from `tool` when `tool` starts with `device.`)

After the user approves, the app records a device grant for `(identity, capability)` so subsequent device calls (for the same identity) can succeed without passing a `permission_id`.

### Python Helper

The server provides `device_permissions.py`:

```python
import device_permissions as dp

dp.set_identity("my_session_123")  # optional; uses env KUGUTZ_IDENTITY / KUGUTZ_SESSION_ID if set
dp.ensure_device("camera2", detail="capture a photo", scope="session")
```

## Common Errors

- `permission_required`: user needs to approve on device UI, then retry.
- `path_outside_user_dir`: use paths under the user root only.
- `command_not_allowed`: only `python|pip|curl` are permitted in execution tools.

## Package Name Gotchas (UVC / USB)

When dealing with USB Video Class (UVC) cameras on Android:
- Native library: `libuvc.so` (bundled by the app in `jniLibs/`)
- Use `device_api` USB/UVC actions (e.g. `usb.*`, `uvc.ptz.*`) rather than trying to install Python UVC bindings.

Do not `pip install uvc` for camera control; that name is often unrelated and typically won't work on Android.

## Vision (RGBA8888 + TFLite)

Kugutz provides a minimal on-device vision pipeline:
- Internal image format: `RGBA8888` bytes `[R,G,B,A]` per pixel.
- Inference runs on Android via TFLite. Python is orchestration only.

Use these `device_api` actions:
- `vision.model.load` / `vision.model.unload`
- `vision.image.load` (decode PNG/JPEG from user root into an in-memory RGBA frame)
- `vision.frame.put` / `vision.frame.get` / `vision.frame.delete`
- `vision.frame.save` (save RGBA to JPEG/PNG under user root)
- `vision.run` (run TFLite on a frame; prefer passing `frame_id` to avoid resending pixels)

Typical flow:
1. `vision.model.load` with `{name, path, delegate: "none|nnapi|gpu", num_threads}`
2. `vision.image.load` or `vision.frame.put`
3. `vision.run` with `{model, frame_id}` (plus optional `normalize/mean/std`)
