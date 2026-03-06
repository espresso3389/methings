# Serial API

USB serial session management. Serial operations use raw HTTP endpoints (NOT `device_api` actions). Call via `run_js` with `fetch()` or `run_curl`.

Permission: `device.usb`

## GET /serial/status

List open USB serial sessions.

**Returns:**
- `count` (integer): number of open sessions
- `items` (array): session objects with `serial_handle`, `usb_handle`, `device_name`, `port_index`, `baud_rate`, `data_bits`, `stop_bits`, `parity`, `opened_at_ms`, `ws_path`, `ws_connected`, `driver`

## POST /serial/list_ports

List available serial ports for a USB device handle.

**Params:**
- `handle` (string, required): USB handle from `usb.open`

**Returns:**
- `handle` (string): USB handle
- `device_name` (string): device name
- `bridge_hint` (string|null): bridge hint
- `driver` (string): driver name
- `port_count` (integer): number of ports
- `ports` (array): objects with `port_index`, `port_number`, `driver_class`

## POST /serial/open

Open a USB serial session from a USB handle.

**Params:**
- `handle` (string, required): USB handle from `usb.open`
- `port_index` (integer, optional): Default: 0
- `baud_rate` (integer, optional): Default: 115200
- `data_bits` (integer, optional): UsbSerialPort DATABITS_* value. Default: 8
- `stop_bits` (integer, optional): UsbSerialPort STOPBITS_* value. Default: 1
- `parity` (integer, optional): UsbSerialPort PARITY_* value. Default: 0
- `dtr` (boolean, optional): DTR line state
- `rts` (boolean, optional): RTS line state

**Returns:**
- `session` (object): opened session details

## POST /serial/close

Close a USB serial session.

**Params:**
- `serial_handle` (string, required): serial session handle

**Returns:**
- `closed` (boolean): whether the session was closed

## POST /serial/read

Read bytes from a USB serial session (polling).

**Params:**
- `serial_handle` (string, required): serial session handle
- `max_bytes` (integer, optional): Default: 4096
- `timeout_ms` (integer, optional): Default: 200

**Returns:**
- `serial_handle` (string): session handle
- `bytes_read` (integer): number of bytes read
- `data_b64` (string): base64-encoded data

## POST /serial/write

Write bytes to a USB serial session (polling).

**Params:**
- `serial_handle` (string, required): serial session handle
- `data_b64` (string, required): base64-encoded data to write
- `timeout_ms` (integer, optional): Default: 2000

**Returns:**
- `serial_handle` (string): session handle
- `bytes_written` (integer): number of bytes written

## POST /serial/lines

Set serial modem line states (DTR/RTS).

**Params:**
- `serial_handle` (string, required): serial session handle
- `dtr` (boolean, optional): DTR state
- `rts` (boolean, optional): RTS state

**Returns:**
- `serial_handle` (string): session handle
- `dtr` (boolean|null): applied DTR state
- `rts` (boolean|null): applied RTS state

## WebSocket

Async serial I/O via WebSocket at `/ws/serial/{serial_handle}`.

**Query params:**
- `read_timeout_ms` (integer): Default: 200
- `max_read_bytes` (integer): Default: 4096
- `write_timeout_ms` (integer): Default: 2000

**Server -> Client:**
- Binary frames: raw serial bytes
- JSON frames: `hello`, `write_ack`, `lines_ack`, `error`, `permission_required`

**Client -> Server:**
- Binary frames: raw bytes to write
- JSON `{"type":"write", "data_b64":"...", "timeout_ms":2000}`: write base64 data
- JSON `{"type":"lines", "dtr":true, "rts":false}`: set modem lines

**Notes:** Get USB handle first via `device_api(action="usb.open")`, then use HTTP endpoints for serial operations. See TOOLS.md for a full usage example.
