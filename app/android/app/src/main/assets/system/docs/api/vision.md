# Vision API

On-device TFLite inference with RGBA8888 frame management.

Permission: `device.vision`

Golden path: (1) place `.tflite` under user root, (2) `vision.model.load`, (3) `vision.image.load` to get `frame_id`, (4) `vision.run`, (5) optionally `vision.frame.save`.

See also: [cv.md](../cv.md) for OpenCV image processing in `run_js`.

## vision.model.load

Load a TFLite model for on-device inference.

**Params:**
- `name` (string, required): model name (used as reference in `vision.run`)
- `path` (string, required): user-root relative path to `.tflite` file
- `delegate` (string, optional): hardware acceleration — `none`, `nnapi`, `gpu`. Default: `none`
- `num_threads` (integer, optional): CPU threads for inference. Default: 1

## vision.model.unload

Unload a TFLite model.

**Params:**
- `name` (string, required): model name to unload

## vision.image.load

Decode an image file to an in-memory RGBA frame.

**Params:**
- `path` (string, required): user-root relative path to image (PNG/JPEG)

**Returns:**
- `frame_id` (string): frame ID for subsequent operations
- `width` (integer): pixel width
- `height` (integer): pixel height

## vision.frame.put

Store raw RGBA frame data in memory.

**Params:**
- `width` (integer, required): pixel width
- `height` (integer, required): pixel height
- `data_b64` (string, required): base64-encoded RGBA8888 pixel data
- `frame_id` (string, optional): auto-generated if omitted

**Returns:**
- `frame_id` (string): assigned frame ID

## vision.frame.get

Retrieve RGBA frame metadata.

**Params:**
- `frame_id` (string, required): frame to query

**Returns:**
- `frame_id` (string): frame ID
- `width` (integer): pixel width
- `height` (integer): pixel height

## vision.frame.delete

Delete an in-memory RGBA frame.

**Params:**
- `frame_id` (string, required): frame to delete

## vision.frame.save

Save an RGBA frame as image file.

**Params:**
- `frame_id` (string, required): frame to save
- `path` (string, required): user-root relative output path
- `format` (string, optional): `jpg` or `png`. Default: `jpg`
- `jpeg_quality` (integer, optional): 1-100. Default: 90

**Returns:**
- `rel_path` (string): saved file path

## vision.run

Run TFLite inference on a frame.

**Params:**
- `model` (string, required): model name (from `vision.model.load`)
- `frame_id` (string, required): frame to run inference on
- `normalize` (boolean, optional): normalize input to [0,1]. Default: true
- `mean` (number[], optional): per-channel mean for normalization [R,G,B]
- `std` (number[], optional): per-channel std for normalization [R,G,B]

**Returns:**
- `outputs` (array): model output tensors
- `inference_ms` (number): inference time in milliseconds

**Notes:** If a model expects UINT8 input, set `normalize=false`. Prefer `frame_id` reuse to avoid sending large RGBA buffers repeatedly.
