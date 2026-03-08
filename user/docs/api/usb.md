# USB API

Low-level USB device access: enumeration, handles, transfers, and streaming.

All actions require `Permission: device.usb`.

When called via `device_api()` from `run_js`, transfer operations return binary data as native `Uint8Array` (e.g. `data`). HTTP responses auto-rename binary fields with a `_b64` suffix and base64-encode them (e.g. `data` → `data_b64`).

## usb.list

List connected USB devices.

**Returns:**
- `devices` (array): Each: `{name, vendor_id, product_id, product_name, manufacturer_name, has_permission}`

## usb.status

Get USB subsystem status.

**Returns:**
- `has_permission` (boolean): Whether USB permission is granted
- `open_handles` (array of string): Currently open device handles

## usb.open

Open a USB device and get a handle.

**Params:**
- `name` (string, optional): Device name from `usb.list`
- `vendor_id` (integer, optional): Match by vendor ID
- `product_id` (integer, optional): Match by product ID

**Returns:**
- `handle` (string): Opaque handle for subsequent USB operations

## usb.close

Close a USB device handle.

**Params:**
- `handle` (string, required): Handle from `usb.open`

## usb.raw_descriptors

Get raw USB descriptors.

**Params:**
- `handle` (string, required): USB handle

**Returns:**
- `descriptors` (Uint8Array): Raw USB descriptors (`run_js` `device_api()` only)
- `descriptors_b64` (string): Base64-encoded raw USB descriptors (HTTP)

## usb.claim_interface

Claim a USB interface.

**Params:**
- `handle` (string, required): USB handle
- `interface_id` (integer, required): Interface number to claim
- `force` (boolean, optional): Force claim (detach kernel driver). Default: false

## usb.release_interface

Release a USB interface.

**Params:**
- `handle` (string, required): USB handle
- `interface_id` (integer, required): Interface number

## usb.control_transfer

Perform a USB control transfer.

**Params:**
- `handle` (string, required): USB handle
- `request_type` (integer, required): bmRequestType byte
- `request` (integer, required): bRequest byte
- `value` (integer, required): wValue
- `index` (integer, required): wIndex
- `data_b64` (string, optional): Base64-encoded data for OUT transfers
- `length` (integer, optional): Expected response length for IN transfers
- `timeout_ms` (integer, optional): Default: 5000

**Returns:**
- `transferred` (integer): Number of bytes transferred
- `data` (Uint8Array / `data_b64` string via HTTP): Response data (IN transfers)

## usb.bulk_transfer

Perform a USB bulk transfer.

**Params:**
- `handle` (string, required): USB handle
- `endpoint` (integer, required): Endpoint address
- `data_b64` (string, optional): Base64-encoded data for OUT transfers
- `length` (integer, optional): Expected read length for IN transfers
- `timeout_ms` (integer, optional): Default: 5000

**Returns:**
- `transferred` (integer): Number of bytes transferred
- `data` (Uint8Array / `data_b64` string via HTTP): Response data (IN transfers)

## usb.iso_transfer

Perform a USB isochronous transfer (low-level, native usbfs workaround).

**Params:**
- `handle` (string, required): USB handle
- `endpoint` (integer, required): Endpoint address
- `packet_count` (integer, optional): Number of packets per URB
- `packet_size` (integer, optional): Max packet size
- `timeout_ms` (integer, optional): Default: 5000

**Returns:**
- `data` (Uint8Array / `data_b64` string via HTTP): KISO blob

## usb.stream_start

Start high-rate USB streaming for bulk or isochronous endpoints. Prefer streaming over JSON base64 for high-rate payloads.

**Params:**
- `handle` (string, required): USB handle
- `endpoint` (integer, required): Endpoint address
- `transfer_type` (string, optional): `bulk` or `iso`

**Returns:**
- `tcp_port` (integer): TCP port for raw streaming data
- `ws_path` (string): WebSocket path for streaming data

### Framing

**TCP:** `[u8 type][u32le length][payload]` -- type=1: bulk IN, type=2: iso IN KISO blob

**WebSocket:** binary message `[u8 type] + payload`

## usb.stream_stop

Stop USB data streaming.

**Params:**
- `handle` (string, required): USB handle

## usb.stream_status

Get USB stream status.

**Returns:**
- `streaming` (boolean): Whether streaming is active
