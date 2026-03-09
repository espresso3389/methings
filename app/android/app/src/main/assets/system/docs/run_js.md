# run_js Async API

The `run_js` engine is a built-in QuickJS runtime with async/await support, enabling direct access to HTTP, WebSocket, file I/O, and timers from JavaScript. Top-level `await` works — no wrapper function needed.

Tool signature: `run_js(code, timeout_ms?)`. Default timeout: 30 s (max 120 s). Returns `{status, result, console_output, error}`.

## API Reference

| Global | Type | Description |
|--------|------|-------------|
| `console.log/warn/error/info` | sync | Captured in `console_output` |
| `device_api(action, payload)` | sync | Call device API actions |
| `btoa(binaryString)` | sync | Binary string → Base64 string |
| `atob(base64)` | sync | Base64 string → binary string |
| `await fetch(url, options?)` | async | HTTP request → `{status, ok, headers, body}` |
| `await connectWs(url)` | async | WebSocket → handle with `receive()`, `send()`, `close()`, `isOpen` |
| `await connectTcp(host, port, options?)` | async | TCP client → handle with `read()`, `readText()`, `write()`, `close()`, `isOpen` |
| `await listenTcp(host, port, options?)` | async | TCP server → handle with `accept()`, `close()`, `isOpen`, `host`, `port` |
| `await delay(ms)` | async | Sleep for ms milliseconds |
| `setTimeout(fn, ms)` | fire-and-forget | Standard timer |
| `setInterval(fn, ms)` | fire-and-forget | Repeating timer |
| `clearTimeout(id)` / `clearInterval(id)` | sync | Cancel timer |
| `await readFile(path)` | async | Read UTF-8 file, returns string |
| `await readBinaryFile(path)` | async | Read entire binary file, returns `Uint8Array` |
| `await writeFile(path, content)` | async | Write UTF-8 text file |
| `await writeBinaryFile(path, data)` | async | Write binary file from `Uint8Array` |
| `await listDir(path?)` | async | List directory → `[{name, type, size}, ...]` |
| `await mkdir(path)` | async | Create directory (with parents) |
| `await deleteFile(path)` | async | Delete a file |
| `await rmdir(path, recursive?)` | async | Remove directory (recursive=false by default) |
| `await openFile(path, mode?)` | async | Open file handle (see File Handle API below) |
| `cv.*` | sync | OpenCV image processing — full reference: `$sys/docs/cv.md` |

File paths are relative to the user root. `$sys/` prefix reads from system docs (read-only).

## HTTP Fetch

```javascript
const resp = await fetch("http://127.0.0.1:33389/sensors/list");
// resp = {status: 200, ok: true, headers: {...}, body: "..."}
const data = JSON.parse(resp.body);
```

Options (passed as second argument):
```javascript
const resp = await fetch("http://127.0.0.1:33389/camera/capture", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({lens: "back", path: "captures/photo.jpg"}),
    timeout: 45000
});
```

## WebSocket (channel-based)

Use `connectWs(url)` to open a WebSocket. Returns a handle with `receive()`, `send()`, `close()`, and `isOpen`. `receive()` suspends until a message arrives (or the connection closes, returning `null`).

```javascript
const ws = await connectWs("ws://127.0.0.1:33389/ws/sensors?sensors=accelerometer&rate_hz=50");
const samples = [];
const deadline = Date.now() + 3000;
while (Date.now() < deadline) {
    const raw = await ws.receive();
    if (!raw) break;           // connection closed
    samples.push(JSON.parse(raw));
}
await ws.close();
JSON.stringify({count: samples.length, first: samples[0]});
```

STT example:
```javascript
device_api("stt.start", {locale: "en"});
const ws = await connectWs("ws://127.0.0.1:33389/ws/stt/events");
let transcript = "";
while (true) {
    const raw = await ws.receive();
    if (!raw) break;
    const ev = JSON.parse(raw);
    if (ev.event === "final") { transcript = ev.results[0]; break; }
}
await ws.close();
device_api("stt.stop", {});
transcript;
```

## TCP Client / Server

TCP client:
```javascript
const c = await connectTcp("127.0.0.1", 9000, {timeout_ms: 5000});
await c.write("ping\n");
const r = await c.readText(4096, 5000);  // {status: "ok"|"timeout"|"eof", text?, bytes?}
await c.close();
JSON.stringify(r);
```

TCP server:
```javascript
const s = await listenTcp("127.0.0.1", 9100, {backlog: 20});
const cli = await s.accept(10000); // null on timeout
if (cli) {
  const req = await cli.readText(4096, 10000);
  await cli.write("hello\\n");
  await cli.close();
}
await s.close();
```

Notes:
- `read()` returns `{status, data}` where `data` is `Uint8Array` on `status="ok"`.
- `readText()` returns `{status, text, bytes}` on `status="ok"` (UTF-8 decode).
- `accept(timeoutMs)` returns a client handle or `null` on timeout.

## Delay and Timers

```javascript
await delay(1000);   // sleep 1 second

setTimeout(() => console.log("fired"), 500);
const id = setInterval(() => console.log("tick"), 1000);
clearInterval(id);
```

## File I/O

Simple whole-file read/write:

```javascript
// Text files
await writeFile("data/result.json", JSON.stringify({ok: true}));
const content = await readFile("data/result.json");

// Binary files (Uint8Array — no base64 encoding needed)
const bytes = await readBinaryFile("captures/photo.jpg");  // Uint8Array
await writeBinaryFile("copy.jpg", bytes);

// Byte manipulation
const arr = new Uint8Array([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
await writeBinaryFile("hello.bin", arr);

// Directory operations
const items = await listDir("data");        // [{name, type, size}, ...]
await mkdir("data/subdir");
await deleteFile("data/result.json");       // delete a file
await rmdir("data/subdir");                 // remove empty directory
await rmdir("data", true);                  // remove directory recursively
```

## File Handle API

For large files or partial read/write, use `openFile` to get a RandomAccessFile handle with seek support.

`await openFile(path, mode?)` — mode is `"r"` (read, default), `"w"` (write/create), or `"rw"` (read-write).

| Property / Method | Type | Description |
|---|---|---|
| `f.size` | sync property | File size in bytes |
| `f.position` | sync property | Current read/write position |
| `await f.read(length)` | async | Read `length` bytes at current position → `Uint8Array` (empty at EOF) |
| `await f.write(data)` | async | Write `Uint8Array` at current position → bytes written |
| `await f.seek(pos)` | async | Seek to byte position |
| `await f.truncate(size)` | async | Set file length (truncate or extend) |
| `await f.close()` | async | Close the file handle |

Open file handles are automatically closed when the `run_js` execution finishes.

```javascript
// Read first 16 bytes of a file
const f = await openFile("captures/photo.jpg", "r");
const header = await f.read(16);
const fileSize = f.size;
await f.close();
JSON.stringify({header: Array.from(header), size: fileSize});

// Copy a file in chunks
const src = await openFile("large_file.bin", "r");
const dst = await openFile("large_copy.bin", "w");
while (true) {
    const chunk = await src.read(65536);
    if (chunk.length === 0) break;  // EOF
    await dst.write(chunk);
}
await src.close();
await dst.close();

// Modify bytes at a specific offset
const f = await openFile("data.bin", "rw");
await f.seek(100);
await f.write(new Uint8Array([0xFF, 0xFE]));
await f.truncate(200);  // set file size to 200 bytes
await f.close();
```

## Base64 Encoding (btoa / atob)

Standard `btoa`/`atob` for converting between binary strings and Base64. Needed for **writing** binary data via `device_api()` (input fields like `data_b64`), and for **reading** binary data via `fetch()` HTTP endpoints. When **reading** via `device_api()`, prefer the native `data` field (`Uint8Array`) instead — see "Native binary data" above.

```javascript
// Encode binary bytes to base64
const bytes = new Uint8Array([0xC0, 0x00, 0x08, 0x24]);
const b64 = btoa(String.fromCharCode.apply(null, bytes));  // "wAAIJA=="

// Decode base64 to binary bytes
const raw = atob(b64);
const decoded = new Uint8Array(raw.length);
for (let i = 0; i < raw.length; i++) decoded[i] = raw.charCodeAt(i);
```

Helper pattern for serial APIs:
```javascript
function toB64(bytes) {
    return btoa(String.fromCharCode.apply(null, bytes));
}
function fromB64(b64) {
    const s = atob(b64);
    const a = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) a[i] = s.charCodeAt(i);
    return a;
}
```

## device_api (sync)

Same as the `device_api` tool — call device API actions synchronously from JS:
```javascript
const sensors = device_api("sensor.list", {});
```

### Native binary data (Uint8Array)

When called from `run_js`, `device_api()` returns binary fields as native `Uint8Array` — **no base64 decode needed**. USB/Serial read operations return a `data` field containing raw bytes:

```javascript
// Direct Uint8Array — no btoa/atob conversion needed
const r = device_api("serial.read", {serial_handle: sh, max_bytes: 256, timeout_ms: 1000});
const bytes = r.data;         // Uint8Array — native binary
console.log(bytes.length);    // number of bytes read
console.log(bytes[0]);        // first byte value (0-255)
```

Actions returning `data` as `Uint8Array`: `serial.read`, `usb.control_transfer`, `usb.bulk_transfer`, `usb.iso_transfer`. `usb.raw_descriptors` returns `descriptors` as `Uint8Array`.

**Writing binary data:** Input fields (`data_b64`, `send_b64`) still require base64 strings — use `btoa()`:
```javascript
const bytes = new Uint8Array([0xC0, 0x00, 0x08]);
device_api("serial.write", {serial_handle: sh, data_b64: toB64(bytes)});
```

> **Note:** Via `device_api()`, binary fields are native `Uint8Array`. Via HTTP (`fetch()`), the same fields are auto-renamed with a `_b64` suffix and base64-encoded (e.g. `data` → `data_b64`).

## MCU Serial Scripting

`run_js` can implement custom MCU serial protocols entirely in JavaScript, combining `device_api` (for MCU control actions and serial I/O), `fetch` (for serial HTTP endpoints), `delay` (for timing), and native `Uint8Array` for binary data. This allows the agent to support new driver ICs or protocols without app updates.

### Available primitives

| Primitive | API | Description |
|---|---|---|
| USB open/close | `device_api("usb.open", ...)` | Open USB device, get `handle` |
| Serial open/close | `device_api("serial.open", ...)` | Open serial session, get `serial_handle` |
| Serial write | `device_api("serial.write", ...)` | Send raw bytes via `data_b64` |
| Serial read | `device_api("serial.read", ...)` | Read raw bytes, returns `data` (`Uint8Array`) + `data_b64` |
| Serial exchange | `device_api("serial.exchange", ...)` | Send + line-oriented receive in one call |
| DTR/RTS control | `device_api("serial.lines", ...)` | Set modem line states |
| Timing | `await delay(ms)` | Async sleep |
| Binary encoding | `btoa()` / `atob()` | Base64 ↔ binary string (for write inputs) |
| Binary construction | `new Uint8Array(...)` | Native byte arrays |

> **Prefer `device_api()` over `fetch()`** for serial operations — `device_api()` calls the Core API directly (no HTTP roundtrip) and returns binary data as native `Uint8Array`.

### Example: custom bootloader entry

```javascript
function toB64(bytes) {
    return btoa(String.fromCharCode.apply(null, bytes));
}

// Open USB device
const usb = device_api("usb.open", {name: "/dev/bus/usb/001/002"});
const handle = usb.handle;

// Open serial port (device_api — direct Core API call, no HTTP)
const serRes = device_api("serial.open", {handle, baud_rate: 115200});
const sh = serRes.session.serial_handle;

// DTR/RTS bootloader entry sequence (ClassicReset)
function setLines(dtr, rts) {
    device_api("serial.lines", {serial_handle: sh, dtr, rts});
}
setLines(false, true);   // EN low, IO0 high
await delay(100);
setLines(true, false);   // EN high, IO0 low (boot mode)
await delay(50);
setLines(true, true);    // release both

// Send ESP32 SYNC command (SLIP-framed)
const SYNC = new Uint8Array([
    0xC0,                                   // SLIP start
    0x00, 0x08, 0x24, 0x00,                 // command: SYNC, size: 36
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // value + dummy
    0x07, 0x07, 0x12, 0x20,                 // sync pattern
    ...new Array(32).fill(0x55),            // 32x 0x55
    0xC0                                    // SLIP end
]);
device_api("serial.write", {serial_handle: sh, data_b64: toB64(SYNC)});

// Read response — data is native Uint8Array (no base64 decode needed)
await delay(50);
const resp = device_api("serial.read", {serial_handle: sh, max_bytes: 256, timeout_ms: 1000});
const data = resp.data;         // Uint8Array — raw response bytes
console.log("Response bytes:", data.length);
JSON.stringify({sync_sent: true, response_bytes: data.length, first_byte: data[0]});
```

### Example: MicroPython REPL interaction via serial.exchange

```javascript
// Send Ctrl-C + Ctrl-D (soft reset) and capture boot output
const result = device_api("serial.exchange", {
    serial_handle: sh,
    send_b64: btoa("\x03\x04"),  // Ctrl-C, Ctrl-D
    max_lines: 30,
    idle_timeout_ms: 500,
    total_timeout_ms: 10000
});
console.log("Boot output:", JSON.stringify(result.lines));
const hasError = result.lines.some(l =>
    l.includes("Traceback") || l.includes("ImportError") || l.includes("SyntaxError"));
JSON.stringify({boot_ok: !hasError, lines: result.lines});
```

## When to use run_js vs. run_curl

- Use `run_js` when you need to **combine multiple API calls**, **process streaming data** (WebSocket), **do computation on results**, or **orchestrate multi-step workflows** in a single tool call.
- Use `run_curl` for **simple one-shot HTTP requests** where no post-processing is needed.
