# Vision API (Minimal, RGBA8888 + TFLite)

This is the initial "small and intuitive" vision layer for methings. It avoids OpenCV.

## Internal Image Format

- `RGBA8888` bytes: `[R,G,B,A]` per pixel.

## Permissions

Vision endpoints require `permission_id` from a `device.vision` request (session-scoped by default via `device_api`).

## Endpoints (Local Control Plane)

All endpoints are on `http://127.0.0.1:8765`.

### Frame Store

- `POST /vision/frame/put`
  - `{permission_id, width, height, rgba_b64}`
  - returns `{frame_id, stats}`

- `POST /vision/frame/get`
  - `{permission_id, frame_id}`
  - returns `{width, height, rgba_b64}`

- `POST /vision/frame/delete`
  - `{permission_id, frame_id}`

- `POST /vision/frame/save`
  - `{permission_id, frame_id, path, format:"jpg|png", jpeg_quality?}`
  - `path` is relative to `<filesDir>/user/`

### Image Load

- `POST /vision/image/load`
  - `{permission_id, path}`
  - returns `{frame_id, width, height}`

### Model Management

- `POST /vision/model/load`
  - `{permission_id, name, path, delegate:"none|nnapi|gpu", num_threads?}`
  - `path` is relative to `<filesDir>/user/`

- `POST /vision/model/unload`
  - `{permission_id, name}`

### Inference

- `POST /vision/run`
  - `{permission_id, model, frame_id, normalize?, mean?, std?}`
  - or `{permission_id, model, width, height, rgba_b64, ...}`

Returns JSON-friendly tensor outputs (lists). This is intended for small outputs (detections, landmarks).

## device_api Actions

Python agent tool `device_api` supports:

- `vision.model.load`, `vision.model.unload`
- `vision.frame.put`, `vision.frame.get`, `vision.frame.delete`, `vision.frame.save`
- `vision.image.load`
- `vision.run`
