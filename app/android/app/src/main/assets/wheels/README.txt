Kugutz wheelhouse (optional)

Place prebuilt Python wheels here to satisfy pip dependency resolution on Android.

Layout:
  wheels/common/*.whl
  wheels/<abi>/*.whl

Example:
  wheels/common/libuvc-0.0.0+kugutz1-py3-none-any.whl
  wheels/arm64-v8a/opencv_python-4.10.0.84-cp311-cp311-android_34_arm64_v8a.whl

At runtime the app extracts wheels/<abi>/ to:
  <filesDir>/wheelhouse/<abi>/bundled/
and also extracts wheels/common/ into the same bundled directory.

User-downloaded wheels can be stored under:
  <filesDir>/wheelhouse/<abi>/user/

and sets:
  PIP_FIND_LINKS="<filesDir>/wheelhouse/<abi>/bundled <filesDir>/wheelhouse/<abi>/user"
  KUGUTZ_WHEELHOUSE="<filesDir>/wheelhouse/<abi>/bundled <filesDir>/wheelhouse/<abi>/user"

So "pip install opencv-python" can resolve from the packaged wheel(s).
