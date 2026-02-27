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

    def mcu_models(self) -> Dict[str, Any]:
        return self.device_api("mcu.models", {}, detail="MCU model list")

    def mcu_probe(
        self,
        *,
        model: str,
        name: str = "",
        vendor_id: Optional[int] = None,
        product_id: Optional[int] = None,
        permission_timeout_ms: int = 0,
    ) -> Dict[str, Any]:
        payload: Dict[str, Any] = {
            "model": str(model).strip().lower(),
            "permission_timeout_ms": int(permission_timeout_ms),
        }
        if name:
            payload["name"] = name
        if vendor_id is not None:
            payload["vendor_id"] = int(vendor_id)
        if product_id is not None:
            payload["product_id"] = int(product_id)
        return self.device_api("mcu.probe", payload, detail=f"MCU probe ({payload['model']})")

    def mcu_flash_plan(self, *, plan_path: str, model: str = "esp32") -> Dict[str, Any]:
        payload: Dict[str, Any] = {
            "plan_path": str(plan_path).strip(),
            "model": str(model).strip().lower(),
        }
        return self.device_api("mcu.flash.plan", payload, detail=f"MCU flash plan ({payload['model']})")

    def mcu_flash(
        self,
        *,
        model: str,
        handle: str,
        image_path: str = "",
        segments: Optional[list] = None,
        offset: int = 0x10000,
        reboot: bool = True,
        auto_enter_bootloader: bool = True,
        timeout_ms: int = 2000,
        interface_id: Optional[int] = None,
        in_endpoint_address: Optional[int] = None,
        out_endpoint_address: Optional[int] = None,
    ) -> Dict[str, Any]:
        payload: Dict[str, Any] = {
            "model": str(model).strip().lower(),
            "handle": str(handle).strip(),
            "offset": int(offset),
            "reboot": bool(reboot),
            "auto_enter_bootloader": bool(auto_enter_bootloader),
            "timeout_ms": int(timeout_ms),
        }
        p = str(image_path).strip()
        if p:
            payload["image_path"] = p
        if segments is not None:
            payload["segments"] = segments
        if interface_id is not None:
            payload["interface_id"] = int(interface_id)
        if in_endpoint_address is not None:
            payload["in_endpoint_address"] = int(in_endpoint_address)
        if out_endpoint_address is not None:
            payload["out_endpoint_address"] = int(out_endpoint_address)
        return self.device_api("mcu.flash", payload, detail=f"MCU flash ({payload['model']})")

    def mcu_reset(
        self,
        *,
        model: str,
        handle: str,
        mode: str = "reboot",
        sleep_after_ms: int = 120,
        timeout_ms: int = 2000,
    ) -> Dict[str, Any]:
        payload: Dict[str, Any] = {
            "model": str(model).strip().lower(),
            "handle": str(handle).strip(),
            "mode": str(mode).strip().lower(),
            "sleep_after_ms": int(sleep_after_ms),
            "timeout_ms": int(timeout_ms),
        }
        return self.device_api("mcu.reset", payload, detail=f"MCU reset ({payload['model']})")

    def mcu_serial_monitor(
        self,
        *,
        model: str,
        handle: str,
        duration_ms: int = 2000,
        configure_serial: bool = True,
        flush_input: bool = False,
        max_dump_bytes: int = 8192,
        timeout_ms: int = 2000,
    ) -> Dict[str, Any]:
        payload: Dict[str, Any] = {
            "model": str(model).strip().lower(),
            "handle": str(handle).strip(),
            "duration_ms": int(duration_ms),
            "configure_serial": bool(configure_serial),
            "flush_input": bool(flush_input),
            "max_dump_bytes": int(max_dump_bytes),
            "timeout_ms": int(timeout_ms),
        }
        return self.device_api("mcu.serial_monitor", payload, detail=f"MCU serial monitor ({payload['model']})")

    def mcu_micropython_exec(
        self,
        *,
        code: str = "",
        code_b64: str = "",
        model: str = "esp32",
        serial_handle: str = "",
        handle: str = "",
        port_index: int = 0,
        baud_rate: int = 115200,
        timeout_ms: int = 20000,
    ) -> Dict[str, Any]:
        payload: Dict[str, Any] = {
            "model": str(model).strip().lower(),
            "port_index": int(port_index),
            "baud_rate": int(baud_rate),
            "timeout_ms": int(timeout_ms),
        }
        if code:
            payload["code"] = str(code)
        if code_b64:
            payload["code_b64"] = str(code_b64)
        if serial_handle:
            payload["serial_handle"] = str(serial_handle).strip()
        if handle:
            payload["handle"] = str(handle).strip()
        return self.device_api("mcu.micropython.exec", payload, detail="MCU MicroPython exec")

    def mcu_micropython_write_file(
        self,
        *,
        path: str,
        content: str = "",
        content_b64: str = "",
        model: str = "esp32",
        serial_handle: str = "",
        handle: str = "",
        port_index: int = 0,
        baud_rate: int = 115200,
        make_dirs: bool = True,
        chunk_size: int = 768,
        timeout_ms: int = 20000,
    ) -> Dict[str, Any]:
        payload: Dict[str, Any] = {
            "model": str(model).strip().lower(),
            "path": str(path).strip(),
            "port_index": int(port_index),
            "baud_rate": int(baud_rate),
            "make_dirs": bool(make_dirs),
            "chunk_size": int(chunk_size),
            "timeout_ms": int(timeout_ms),
        }
        if content_b64:
            payload["content_b64"] = str(content_b64)
        else:
            payload["content"] = str(content)
        if serial_handle:
            payload["serial_handle"] = str(serial_handle).strip()
        if handle:
            payload["handle"] = str(handle).strip()
        return self.device_api("mcu.micropython.write_file", payload, detail="MCU MicroPython write_file")

    def mcu_micropython_soft_reset(
        self,
        *,
        model: str = "esp32",
        serial_handle: str = "",
        handle: str = "",
        port_index: int = 0,
        baud_rate: int = 115200,
    ) -> Dict[str, Any]:
        payload: Dict[str, Any] = {
            "model": str(model).strip().lower(),
            "port_index": int(port_index),
            "baud_rate": int(baud_rate),
        }
        if serial_handle:
            payload["serial_handle"] = str(serial_handle).strip()
        if handle:
            payload["handle"] = str(handle).strip()
        return self.device_api("mcu.micropython.soft_reset", payload, detail="MCU MicroPython soft reset")

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
