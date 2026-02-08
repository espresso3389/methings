"""
methings facade for the `opencv-python` distribution.

This exists so `pip` dependency resolution can succeed on Android when the app bundles native
OpenCV libraries via `jniLibs/`.

Important: this build does NOT provide the real Python OpenCV bindings (the upstream `cv2`
extension module). Importing `cv2` fails with a clear error.
"""


raise ImportError(
    "methings: the 'opencv-python' facade package is installed, but real OpenCV Python bindings "
    "('cv2') are not available on this Android build. "
    "If you only needed to satisfy dependencies, this is expected. "
    "If you need the bundled native library, use: `import opencv_android; opencv_android.load()`."
)
