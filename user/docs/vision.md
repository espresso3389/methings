# Vision (RGBA8888 + TFLite)

This document explains how to use methings's minimal on-device vision stack.

## Core Idea

- Pixels are **RGBA8888** bytes: `[R,G,B,A]` per pixel.
- Inference runs on Android via **TFLite**.
- The agent should orchestrate using `device_api` actions (not by installing OpenCV).

## device_api Actions

- `vision.model.load` / `vision.model.unload`
- `vision.image.load` (decode PNG/JPEG from user root into an in-memory RGBA frame)
- `vision.frame.put` / `vision.frame.get` / `vision.frame.delete`
- `vision.frame.save` (save RGBA to JPEG/PNG under user root)
- `vision.run` (run TFLite on a frame)

All `vision.*` actions are permission-gated (`device.vision`) and are expected to be approved once per session.

## Golden Path (Recommended)

1. Put a `.tflite` model under the user root (example path: `models/my_model.tflite`).
2. Load the model:
   - action: `vision.model.load`
   - payload:
     - `name`: model name (string)
     - `path`: path under user root (string)
     - `delegate`: `"none" | "nnapi" | "gpu"`
     - `num_threads`: integer
3. Load an image under the user root:
   - action: `vision.image.load`
   - payload: `{path:"images/input.jpg"}`
   - response includes `frame_id`
4. Run inference:
   - action: `vision.run`
   - payload:
     - `model`: model name
     - `frame_id`: from previous step
     - `normalize`: bool (default true)
     - `mean`: optional `[float,float,float]`
     - `std`: optional `[float,float,float]`
5. (Optional) Save the decoded RGBA frame as an image:
   - action: `vision.frame.save`
   - payload: `{frame_id, path:"out/frame.jpg", format:"jpg", jpeg_quality:90}`

## Notes

- Prefer `frame_id` reuse to avoid sending large RGBA buffers repeatedly.
- If a model expects UINT8 input, set `normalize=false` (or use a UINT8 model).
- For camera/USB inputs, use `usb.stream.*` to move bytes efficiently, then convert to RGBA and store a frame (future: direct stream->vision ingestion).
