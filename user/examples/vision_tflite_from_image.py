#!/usr/bin/env python3
import os
import json
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
    # Put these files under the user root on device:
    # - models/model.tflite
    # - images/input.jpg
    model_path = os.environ.get("KUGUTZ_MODEL_PATH", "models/model.tflite")
    image_path = os.environ.get("KUGUTZ_IMAGE_PATH", "images/input.jpg")

    # You must supply a permission_id from the app permission broker.
    # The agent normally gets one automatically via device_api, but direct HTTP needs it explicitly.
    permission_id = os.environ.get("KUGUTZ_VISION_PERMISSION_ID", "").strip()
    if not permission_id:
        raise SystemExit("Set KUGUTZ_VISION_PERMISSION_ID (approved device.vision permission)")

    model_name = os.environ.get("KUGUTZ_MODEL_NAME", "model").strip() or "model"
    delegate = os.environ.get("KUGUTZ_DELEGATE", "nnapi")
    num_threads = int(os.environ.get("KUGUTZ_THREADS", "2"))

    print("Loading model...")
    info = post(
        "/vision/model/load",
        {
            "permission_id": permission_id,
            "name": model_name,
            "path": model_path,
            "delegate": delegate,
            "num_threads": num_threads,
        },
    )
    print(json.dumps(info, indent=2)[:1500])

    print("Loading image...")
    fr = post(
        "/vision/image/load",
        {
            "permission_id": permission_id,
            "path": image_path,
        },
    )
    frame_id = fr.get("frame_id", "")
    print(json.dumps(fr, indent=2))
    if not frame_id:
        raise SystemExit("image.load did not return frame_id")

    print("Running inference...")
    out = post(
        "/vision/run",
        {
            "permission_id": permission_id,
            "model": model_name,
            "frame_id": frame_id,
            "normalize": True,
            "mean": [0.0, 0.0, 0.0],
            "std": [1.0, 1.0, 1.0],
        },
    )
    print(json.dumps(out, indent=2)[:4000])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

