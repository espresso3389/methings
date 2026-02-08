# UVC (USB Webcam) Controls

This app exposes a small set of UVC helpers via `device_api` for USB webcams (e.g. Insta360 Link).

## Capture One MJPEG Frame

Action: `uvc.mjpeg.capture`

Payload:
- `handle` (string, required): result of `usb.open`.
- `width` (int, optional): desired width (best-effort; closest supported frame is selected).
- `height` (int, optional): desired height.
- `fps` (int, optional): desired FPS (best-effort; closest supported interval is selected).
- `path` (string, optional): output path under user root (default `captures/uvc_<ts>.jpg`).
- `timeout_ms` (int, optional): overall capture timeout (default ~12000).

Response (success):
- `status=ok`
- `rel_path`: saved JPEG path under user root
- Selected stream info: `vs_interface`, `format_index`, `frame_index`, `width`, `height`, `interval_100ns`

Usage notes:
- The stream negotiation uses UVC VS PROBE/COMMIT and then reads UVC payloads until a full JPEG frame is assembled.
- The implementation supports both endpoint types:
  - `transfer_mode=iso`: isochronous IN endpoint (parsed via the KISO bridge)
  - `transfer_mode=bulk`: bulk IN endpoint (read via `bulkTransfer`)
- To show the image inline in chat, include a line: `rel_path: <rel_path>`

## PTZ (Pan/Tilt)

Actions:
- `uvc.ptz.get_abs`
- `uvc.ptz.get_limits`
- `uvc.ptz.set_abs`
- `uvc.ptz.nudge`

These use UVC CameraTerminal controls over `usb.control_transfer` and work best for devices that implement
`CT_PANTILT_ABSOLUTE_CONTROL` (selector `0x0D`).
