# UVC PTZ Notes (Insta360 Link)

This repo targets unrooted Android 14+ devices. For USB devices (like external UVC cameras), we avoid
libusb "device discovery" and instead use Android's USB Host APIs via the local Kotlin control plane.

## Why This Exists

- On Android, `/dev/bus/usb` enumeration is not generally usable for app sandboxes.
- The app already exposes `UsbDeviceConnection.controlTransfer(...)` via `device_api` (`/usb/control_transfer`).
- For UVC PTZ (pan/tilt/zoom), many cameras implement **standard UVC Camera Terminal controls**.

## Insta360 Link (Observed)

From the reference Android project `insta360-link-android-control-app`, the gimbal pan/tilt can be
driven with a UVC `SET_CUR` to selector `0x0D` (PanTilt Absolute):

- `bmRequestType = 0x21` (Host->Device | Class | Interface)
- `bRequest      = 0x01` (SET_CUR)
- `wValue        = 0x0D00` (selector 0x0D in high byte)
- `wIndex        = 0x0100` (`entityId=0x01` << 8 | `vcInterface=0x00`)
- payload        = 8 bytes little-endian: `[pan_i32, tilt_i32]`

Zoom often works with selector `0x0C` (Zoom Relative) with a 3-byte payload.

## How We Use It In methings

We implement virtual `device_api` actions (client-side helper logic in `server/tools/device_api.py`)
that call the Kotlin `/usb/control_transfer` endpoint:

- `uvc.ptz.get_abs`
- `uvc.ptz.get_limits` (best-effort; falls back to known-good clamps)
- `uvc.ptz.set_abs`
- `uvc.ptz.nudge` (reads current, clamps, then writes)

These actions auto-guess:

- `vc_interface`: first UVC VideoControl interface (class `0x0E`, subclass `0x01`)
- `entity_id`: first UVC Camera Terminal ID (Input Terminal subtype `0x02`, `wTerminalType=0x0201`)

The guess is derived by scanning `raw_descriptors` bytes (from `/usb/raw_descriptors`).
