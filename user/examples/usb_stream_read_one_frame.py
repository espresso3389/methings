#!/usr/bin/env python3
import base64
import json
import os
import socket
import struct
import urllib.request


BASE = os.environ.get("METHINGS_DEVICE_API", "http://127.0.0.1:8765").rstrip("/")


def post(path: str, payload: dict) -> dict:
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode("utf-8"))


def recv_exact(sock: socket.socket, n: int) -> bytes:
    out = bytearray()
    while len(out) < n:
        chunk = sock.recv(n - len(out))
        if not chunk:
            raise EOFError("socket closed")
        out.extend(chunk)
    return bytes(out)


def main() -> int:
    # You must supply a permission_id from the app permission broker.
    permission_id = os.environ.get("METHINGS_USB_PERMISSION_ID", "").strip()
    if not permission_id:
        raise SystemExit("Set METHINGS_USB_PERMISSION_ID (approved device.usb permission)")

    # Target device selector.
    vid = int(os.environ.get("METHINGS_VID", "0"), 0)
    pid = int(os.environ.get("METHINGS_PID", "0"), 0)
    if vid <= 0 or pid <= 0:
        raise SystemExit("Set METHINGS_VID and METHINGS_PID (e.g. 0x2e1a / 0x4c01)")

    ep_addr = int(os.environ.get("METHINGS_EP", "0x81"), 0)

    print("Opening device...")
    opened = post(
        "/usb/open",
        {
            "permission_id": permission_id,
            "vendor_id": vid,
            "product_id": pid,
        },
    )
    if opened.get("error"):
        print(opened)
        return 2
    handle = opened.get("handle", "")
    if not handle:
        raise SystemExit("usb.open did not return handle")

    print("Starting stream...")
    started = post(
        "/usb/stream/start",
        {
            "permission_id": permission_id,
            "handle": handle,
            "mode": "bulk_in",
            "endpoint_address": ep_addr,
            "chunk_size": 16384,
            "timeout_ms": 200,
        },
    )
    stream_id = started.get("stream_id", "")
    port = int(started.get("tcp_port", 0))
    if not stream_id or port <= 0:
        raise SystemExit(f"usb.stream.start failed: {started}")

    try:
        print(f"Connecting TCP 127.0.0.1:{port} ...")
        s = socket.create_connection(("127.0.0.1", port), timeout=5)
        with s:
            hdr = recv_exact(s, 5)
            t = hdr[0]
            ln = struct.unpack("<I", hdr[1:])[0]
            payload = recv_exact(s, ln) if ln else b""
            print(f"frame type={t} len={ln} head_b64={base64.b64encode(payload[:32]).decode('ascii')}")
    finally:
        print("Stopping stream...")
        post("/usb/stream/stop", {"permission_id": permission_id, "stream_id": stream_id})
        print("Closing device...")
        post("/usb/close", {"permission_id": permission_id, "handle": handle})

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

