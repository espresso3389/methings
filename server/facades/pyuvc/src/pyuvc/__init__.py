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
    Load the app-bundled libuvc shared library.

    This is a minimal facade. It does NOT implement a full Python UVC camera API.
    """
    probes = ["libuvc.so"]
    nlib = os.environ.get("KUGUTZ_NATIVELIB")
    if nlib:
        probes.append(os.path.join(nlib, "libuvc.so"))
    return _try_cdll(probes)  # type: ignore[return-value]


__all__ = ["load"]

