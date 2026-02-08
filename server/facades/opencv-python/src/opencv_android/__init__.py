import ctypes
import os
from typing import Optional


def _try_cdll(names: list[str]) -> Optional[ctypes.CDLL]:
    last: Optional[BaseException] = None
    for name in names:
        try:
            return ctypes.CDLL(name, mode=getattr(ctypes, "RTLD_GLOBAL", 0))
        except BaseException as ex:  # noqa: BLE001
            last = ex
    if last is not None:
        raise OSError(f"Failed to load any of: {names}") from last
    return None


def load() -> ctypes.CDLL:
    """
    Load the app-bundled OpenCV shared library (Android SDK style).

    This is a facade helper; it does not provide Python `cv2` bindings.
    """
    probes = ["libopencv_java4.so"]
    nlib = os.environ.get("METHINGS_NATIVELIB")
    if nlib:
        probes.append(os.path.join(nlib, "libopencv_java4.so"))
    return _try_cdll(probes)  # type: ignore[return-value]


__all__ = ["load"]

