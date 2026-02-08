# Examples

These scripts show the "golden path" usage patterns for methings tools.

Notes:
- These are intended to be run on-device (Python worker) or via the agent using `run_python`.
- Prefer `device_api` actions for device access; avoid third-party Python native bindings on Android.

Examples:
- `vision_tflite_from_image.py`: load TFLite model, decode image to RGBA, run inference
- `usb_stream_read_one_frame.py`: start a USB bulk stream and read a single framed packet from TCP
- `insta360_ptz_nudge.py`: nudge Insta360 Link gimbal via UVC PTZ control transfers
