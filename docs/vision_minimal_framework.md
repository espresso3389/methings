# Minimal Vision Framework (No OpenCV)

methings intentionally avoids bundling OpenCV for on-device vision.

Goals:
- Keep the image/signal pipeline small, understandable, and fast.
- Use Android-side primitives for performance (TFLite + small CPU intrinsics).
- Keep Python as orchestration: control plane calls + small result parsing.

## Internal Format

Default internal image format: **RGBA8888** (bytes repeating `[R,G,B,A]`).

Rationale:
- Simple interop with TFLite pre-processing and UI rendering.
- Predictable layout; avoids platform-endian `ARGB` integer confusion.

## RenderScript Toolkit

We use RenderScript Toolkit (intrinsics replacement) for small, fast operations without OpenCV:
- NV21 -> RGBA conversion
- blur/convolve/etc (as needed later)

See: `app/android/app/src/main/java/.../vision/ImageConvert.kt`

Notes:
- Toolkit supports NV21/YV12 YUV formats for yuv->rgb.
- UVC cameras often provide YUYV; if needed at high FPS, we should add a native SIMD converter
  (libyuv/NEON) rather than using scalar Kotlin loops.
