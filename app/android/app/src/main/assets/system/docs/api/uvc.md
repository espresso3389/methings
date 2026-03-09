# UVC API

USB Video Class webcam capture and diagnostics.

## uvc.mjpeg_capture

Capture one MJPEG frame from a USB webcam. Negotiates UVC stream format via VS PROBE/COMMIT, reads payloads until a full JPEG frame is assembled.

Supports both transfer modes: `iso` (isochronous IN via KISO bridge) and `bulk` (bulk IN).

**Params:**
- `handle` (string, required): USB handle from `usb.open`
- `width` (integer, optional): Desired width (best-effort; closest supported frame selected)
- `height` (integer, optional): Desired height
- `fps` (integer, optional): Desired FPS (best-effort)
- `path` (string, optional): Output path under user root. Default: `captures/uvc_<timestamp>.jpg`
- `timeout_ms` (integer, optional): Overall capture timeout. Default: 12000

**Returns:**
- `rel_path` (string): Saved JPEG path under user root
- `vs_interface` (integer): VideoStreaming interface index
- `format_index` (integer): Negotiated format index
- `frame_index` (integer): Negotiated frame index
- `width` (integer): Actual width
- `height` (integer): Actual height
- `interval_100ns` (integer): Frame interval in 100ns units
- `transfer_mode` (string): `iso` | `bulk`
- `jpeg_has_eoi` (boolean): Whether the JPEG EOI marker was present
- `jpeg_eoi_appended` (boolean): Whether an EOI marker was appended as fallback

**Notes:** To show the captured image inline in chat, include `rel_path: <rel_path>` in your message. Some cameras omit the JPEG EOI marker; check `jpeg_has_eoi` and `jpeg_eoi_appended` for diagnostics.

## uvc.diagnose

Run step-by-step UVC diagnostics on a USB webcam. Checks descriptors, VideoControl interface, CameraTerminal IDs, and optional PTZ GET_CUR probes.

**Params:**
- `vendor_id` (integer, optional): Match by USB vendor ID
- `product_id` (integer, optional): Match by USB product ID
- `device_name` (string, optional): Match by device name (`/dev/bus/usb/...`)
- `timeout_ms` (integer, optional): OS permission wait timeout. Default: 60000
- `ptz_get_cur` (boolean, optional): Run PTZ GET_CUR probes. Default: true
- `ptz_selector` (integer, optional): PTZ selector. Default: 13 (0x0D = CT_PANTILT_ABSOLUTE_CONTROL)

**Returns:**
- `steps` (object[]): Ordered step results (use to find where diagnostics failed)
- `vc_interface` (integer, nullable): Detected VideoControl interface ID
- `camera_terminal_ids` (integer[]): Detected CameraTerminal IDs
- `ptz_get_cur` (object[]): PTZ GET_CUR probe results

**Notes:** PTZ uses UVC CameraTerminal controls via `usb.control_transfer`. Includes a known-good `wIndex=0x0100` probe for Insta360 Link.
