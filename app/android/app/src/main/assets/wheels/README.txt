Kugutz wheelhouse (optional)

Place prebuilt Python wheels here to satisfy pip dependency resolution on Android.

Layout:
  wheels/common/*.whl
  wheels/<abi>/*.whl

Example:
  wheels/common/pyusb-1.3.1-py3-none-any.whl
  wheels/common/pyuvc-0.0.0+kugutz1-py3-none-any.whl
  wheels/common/pupil_labs_uvc-0.0.0+kugutz1-py3-none-any.whl
  wheels/common/opencv_python-4.12.0.88+kugutz1-py3-none-any.whl

At runtime the app extracts wheels/<abi>/ to:
  <filesDir>/wheelhouse/<abi>/bundled/
and also extracts wheels/common/ into the same bundled directory.

User-downloaded wheels can be stored under:
  <filesDir>/wheelhouse/<abi>/user/

and sets:
  PIP_FIND_LINKS="<filesDir>/wheelhouse/<abi>/bundled <filesDir>/wheelhouse/<abi>/user"
  KUGUTZ_WHEELHOUSE="<filesDir>/wheelhouse/<abi>/bundled <filesDir>/wheelhouse/<abi>/user"

So "pip install opencv-python" can resolve from the packaged wheel(s).
