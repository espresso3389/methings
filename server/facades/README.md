# Python Facade Packages (Android)

This folder holds **pure-Python facades** (and related notes) for Android, where the app already
bundles the real native libraries (`.so`) via `jniLibs/`.

Policy:
- Prefer bundling upstream wheels for pure-Python packages (e.g. `pyusb`) so imports work normally.
- Use facades only when upstream doesn't ship Android wheels but dependency resolution expects the
  distribution to exist (e.g. `opencv-python`).
- Use facades for package names that are not published on PyPI but we still want to support via
  the bundled wheelhouse (e.g. `pyuvc`).

Build wheels:
`python3 scripts/build_facade_wheels.py`

Notes:
- `pyusb` is bundled as an upstream wheel (not a facade).
- OpenCV is shipped as an `opencv-python` facade distribution so normal `pip` deps can resolve.
- This facade does not provide real `cv2` bindings; `import cv2` raises a clear `ImportError`.
- `pyuvc` is shipped as a small facade with `pyuvc.load()` to load the app-bundled `libuvc.so`.
