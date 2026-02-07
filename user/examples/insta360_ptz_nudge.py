#!/usr/bin/env python3
import json
import os
import urllib.request


BASE = os.environ.get("KUGUTZ_DEVICE_API", "http://127.0.0.1:8765").rstrip("/")


def post(path: str, payload: dict) -> dict:
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main() -> int:
    # You must supply a permission_id from the app permission broker.
    permission_id = os.environ.get("KUGUTZ_USB_PERMISSION_ID", "").strip()
    if not permission_id:
        raise SystemExit("Set KUGUTZ_USB_PERMISSION_ID (approved device.usb permission)")

    # Insta360 Link VID/PID (from earlier observations).
    vid = int(os.environ.get("KUGUTZ_VID", "0x2e1a"), 0)
    pid = int(os.environ.get("KUGUTZ_PID", "0x4c01"), 0)

    pan = float(os.environ.get("KUGUTZ_PAN", "0.2"))   # -1..+1
    tilt = float(os.environ.get("KUGUTZ_TILT", "0.0")) # -1..+1

    opened = post(
        "/usb/open",
        {"permission_id": permission_id, "vendor_id": vid, "product_id": pid},
    )
    if opened.get("error"):
        print(opened)
        return 2
    handle = opened.get("handle", "")
    if not handle:
        raise SystemExit("usb.open did not return handle")

    try:
        # Call the existing helper that wraps UVC CameraTerminal PanTilt Absolute control.
        # Note: this helper is implemented in the Python device_api client; direct HTTP uses the raw endpoint.
        # Here we call /usb/control_transfer directly using the linux-captured tuple (selector=0x0D).
        # wIndex is usually (entityId<<8 | vcInterface). For Insta360 Link: 0x0100.
        import struct, base64

        # Compute absolute pan/tilt by nudging a fixed step from zero (best-effort for demo).
        pan_abs = int(max(-522000, min(522000, pan * 90000.0)))
        tilt_abs = int(max(-324000, min(360000, tilt * 68400.0)))
        payload = struct.pack("<ii", pan_abs, tilt_abs)

        resp = post(
            "/usb/control_transfer",
            {
                "permission_id": permission_id,
                "handle": handle,
                "request_type": 0x21,
                "request": 0x01,
                "value": 0x0D00,
                "index": 0x0100,
                "data_b64": base64.b64encode(payload).decode("ascii"),
                "timeout_ms": 220,
            },
        )
        print(json.dumps(resp, indent=2))
    finally:
        post("/usb/close", {"permission_id": permission_id, "handle": handle})

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

