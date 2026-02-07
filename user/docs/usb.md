# USB / UVC

This document describes the USB control/data plane exposed to the agent.

## Enumerate + Open

- `device_api` action `usb.list`
- `device_api` action `usb.open` (by `{name}` or `{vendor_id, product_id}`) -> returns `handle`
- `device_api` action `usb.close`

## Transfers (Control Plane)

- `usb.control_transfer` (class/vendor control messages)
- `usb.bulk_transfer` (bulk/interrupt endpoints)
- `usb.iso_transfer` (iso IN via native usbfs workaround; low-level)
- `usb.raw_descriptors` (base64 raw descriptor bytes)
- `usb.claim_interface` / `usb.release_interface`

## Streaming Data Plane

For high-rate payloads, prefer streaming rather than JSON base64.

Start/stop:
- `usb.stream.start` -> returns `tcp_port` and `ws_path`
- `usb.stream.stop`
- `usb.stream.status`

TCP framing:
- `[u8 type][u32le length][payload]`
- `type=1`: bulk IN payload bytes
- `type=2`: iso IN "KISO" blob (native URB result)

WebSocket framing:
- Binary message `[u8 type] + payload`

## UVC PTZ (Insta360 Link)

Use `uvc.ptz.*` helpers:
- `uvc.ptz.nudge` (recommended)
- `uvc.ptz.get_abs`, `uvc.ptz.set_abs`

These are implemented by calling `usb.control_transfer` with standard UVC CameraTerminal selectors.

