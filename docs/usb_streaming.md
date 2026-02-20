# USB Streaming Data Plane (TCP + WebSocket)

HTTP+JSON (base64) is fine for control messages but adds latency/CPU for high-rate data.
methings provides a binary streaming data plane for USB reads:

- Local TCP stream for agent consumption
- Local WebSocket stream for WebView/UI preview

## Start/Stop

Control plane endpoints:

- `POST /usb/stream/start`
- `POST /usb/stream/stop`
- `GET  /usb/stream/status`

`/usb/stream/start` request fields:

- `handle`: string (from `/usb/open`)
- `mode`: `"bulk_in"` or `"iso_in"`
- `endpoint_address`: int (e.g. `0x81`)
- `timeout_ms`: int (transfer timeout)
- `chunk_size`: int (bulk buffer size)
- `interval_ms`: int (optional sleep per loop)
- `packet_size`: int (iso only)
- `num_packets`: int (iso only)

Response includes:

- `tcp_host=127.0.0.1`, `tcp_port`
- `ws_path` (e.g. `/ws/usb/stream/<stream_id>`)

## Frame Format

### TCP

Each frame is:

- `u8 type` (`1=bulk_in`, `2=iso_in`)
- `u32le length`
- `length` bytes payload

For `iso_in`, the payload is a raw **KISO** blob produced by the native usbfs URB path (see `docs/usb_iso_transfer.md`).

### WebSocket

Each WS binary message is:

- `u8 type` (`1` or `2`)
- payload bytes

## Notes

- This is intentionally low-level. Protocol parsing (CDC serial framing, UVC payload headers, etc.) is done in agent code.
- Streams are localhost-only and tied to a previously opened USB handle.
