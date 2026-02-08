from methings import MethingsClient


def main():
    k = MethingsClient()

    # 1) List USB devices (look for your webcam, then open it via device_api usb.open).
    # Note: this example assumes you already have a usb handle. Replace this.
    usb_handle = ""
    if not usb_handle:
        print("error: set usb_handle from usb.open response")
        return 1

    # 2) Capture one MJPEG frame to user-root captures/.
    r = k.uvc_mjpeg_capture(
        handle=usb_handle,
        width=1280,
        height=720,
        fps=30,
        path="captures/uvc_latest.jpg",
        timeout_ms=15000,
    )
    print(r)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
