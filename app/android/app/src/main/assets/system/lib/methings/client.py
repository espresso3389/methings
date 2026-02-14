import json
import os
import urllib.request
import urllib.error
from typing import Any, Dict, Optional


class MethingsClient:
    """
    Small Python-friendly client for the on-device Kotlin control plane (127.0.0.1:33389).

    Intended usage: run_python scripts and local tools can import this from <user_dir>/lib/methings.
    New code can also use <user_dir>/lib/methings (wrapper).
    """

    def __init__(self, base_url: str = "http://127.0.0.1:33389", *, identity: Optional[str] = None):
        self.base_url = base_url.rstrip("/")
        # methings-only.
        self.identity = (
            identity
            or os.environ.get("METHINGS_IDENTITY")
            or os.environ.get("METHINGS_SESSION_ID")
            or ""
        ).strip()

    def request_json(
        self,
        method: str,
        path: str,
        body: Optional[Dict[str, Any]] = None,
        *,
        timeout_s: float = 20.0,
    ) -> Dict[str, Any]:
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        if self.identity:
            # methings-only.
            headers["X-Methings-Identity"] = self.identity
        req = urllib.request.Request(self.base_url + path, data=data, method=method.upper(), headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=float(timeout_s)) as resp:
                raw = resp.read().decode("utf-8", errors="replace")
                return {"ok": True, "status": resp.status, "json": json.loads(raw) if raw else {}}
        except urllib.error.HTTPError as ex:
            raw = ex.read().decode("utf-8", errors="replace")
            try:
                j = json.loads(raw) if raw else {}
            except Exception:
                j = {"raw": raw}
            return {"ok": False, "status": int(ex.code), "json": j}
        except Exception as ex:
            return {"ok": False, "status": 0, "error": str(ex)}

    # -------- device_api convenience --------
    def device_api(self, action: str, payload: Dict[str, Any], *, detail: str = "", timeout_s: Optional[float] = None) -> Dict[str, Any]:
        args: Dict[str, Any] = {"action": action, "payload": payload}
        if detail:
            args["detail"] = detail
        if timeout_s is not None:
            args["timeout_s"] = float(timeout_s)
        return self.request_json("POST", "/tools/device_api/invoke", {"args": args}, timeout_s=60.0)

    # -------- high-level helpers --------
    def camera_capture(self, *, lens: str = "back", path: str = "captures/latest.jpg") -> Dict[str, Any]:
        return self.device_api("camera.capture", {"lens": lens, "path": path}, detail="Camera capture")

    def usb_list(self) -> Dict[str, Any]:
        return self.device_api("usb.list", {}, detail="USB list")

    def usb_status(self) -> Dict[str, Any]:
        return self.device_api("usb.status", {}, detail="USB status")

    def stt_record(self, *, locale: str = "", partial: bool = True, max_results: int = 5) -> Dict[str, Any]:
        payload: Dict[str, Any] = {}
        if locale:
            payload["locale"] = locale
        payload["partial"] = bool(partial)
        payload["max_results"] = int(max_results)
        return self.device_api("stt.record", payload, detail="STT one-shot record")

    def uvc_mjpeg_capture(
        self,
        *,
        handle: str,
        width: int = 1280,
        height: int = 720,
        fps: int = 30,
        path: str = "",
        timeout_ms: int = 12000,
    ) -> Dict[str, Any]:
        payload: Dict[str, Any] = {
            "handle": handle,
            "width": int(width),
            "height": int(height),
            "fps": int(fps),
            "timeout_ms": int(timeout_ms),
        }
        if path:
            payload["path"] = path
        return self.device_api(
            "uvc.mjpeg.capture",
            payload,
            detail="UVC MJPEG capture",
            timeout_s=max(20.0, timeout_ms / 1000.0 + 10.0),
        )
