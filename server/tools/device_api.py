import json
import os
import time
import base64
import struct
import urllib.error
import urllib.request
from typing import Any, Dict, Optional


class DeviceApiTool:
    _ACTIONS: Dict[str, Dict[str, Any]] = {
        "python.status": {"method": "GET", "path": "/python/status", "permission": False},
        "python.restart": {"method": "POST", "path": "/python/restart", "permission": True},
        "screen.status": {"method": "GET", "path": "/screen/status", "permission": False},
        "screen.keep_on": {"method": "POST", "path": "/screen/keep_on", "permission": True},
        "sshd.status": {"method": "GET", "path": "/sshd/status", "permission": False},
        "sshd.config": {"method": "POST", "path": "/sshd/config", "permission": True},
        "ssh.exec": {"method": "POST", "path": "/ssh/exec", "permission": True},
        "ssh.scp": {"method": "POST", "path": "/ssh/scp", "permission": True},
        "ssh.ws.contract": {"method": "GET", "path": "/ssh/ws/contract", "permission": False},
        "sshd.keys.list": {"method": "GET", "path": "/sshd/keys", "permission": False},
        "sshd.keys.add": {"method": "POST", "path": "/sshd/keys/add", "permission": True},
        "sshd.keys.delete": {"method": "POST", "path": "/sshd/keys/delete", "permission": True},
        "sshd.keys.policy.get": {"method": "GET", "path": "/sshd/keys/policy", "permission": False},
        "sshd.keys.policy.set": {"method": "POST", "path": "/sshd/keys/policy", "permission": True},
        "sshd.pin.status": {"method": "GET", "path": "/sshd/pin/status", "permission": False},
        "sshd.pin.start": {"method": "POST", "path": "/sshd/pin/start", "permission": True},
        "sshd.pin.stop": {"method": "POST", "path": "/sshd/pin/stop", "permission": True},
        "sshd.noauth.status": {"method": "GET", "path": "/sshd/noauth/status", "permission": False},
        "sshd.noauth.start": {"method": "POST", "path": "/sshd/noauth/start", "permission": True},
        "sshd.noauth.stop": {"method": "POST", "path": "/sshd/noauth/stop", "permission": True},
        "camera.list": {"method": "GET", "path": "/camera/list", "permission": True},
        "camera.status": {"method": "GET", "path": "/camera/status", "permission": True},
        "camera.preview.start": {"method": "POST", "path": "/camera/preview/start", "permission": True},
        "camera.preview.stop": {"method": "POST", "path": "/camera/preview/stop", "permission": True},
        "camera.capture": {"method": "POST", "path": "/camera/capture", "permission": True},
        "ble.status": {"method": "GET", "path": "/ble/status", "permission": True},
        "ble.scan.start": {"method": "POST", "path": "/ble/scan/start", "permission": True},
        "ble.scan.stop": {"method": "POST", "path": "/ble/scan/stop", "permission": True},
        "ble.connect": {"method": "POST", "path": "/ble/connect", "permission": True},
        "ble.disconnect": {"method": "POST", "path": "/ble/disconnect", "permission": True},
        "ble.gatt.services": {"method": "POST", "path": "/ble/gatt/services", "permission": True},
        "ble.gatt.read": {"method": "POST", "path": "/ble/gatt/read", "permission": True},
        "ble.gatt.write": {"method": "POST", "path": "/ble/gatt/write", "permission": True},
        "ble.gatt.notify.start": {"method": "POST", "path": "/ble/gatt/notify/start", "permission": True},
        "ble.gatt.notify.stop": {"method": "POST", "path": "/ble/gatt/notify/stop", "permission": True},
        "tts.init": {"method": "POST", "path": "/tts/init", "permission": True},
        "tts.voices": {"method": "GET", "path": "/tts/voices", "permission": True},
        "tts.speak": {"method": "POST", "path": "/tts/speak", "permission": True},
        "tts.stop": {"method": "POST", "path": "/tts/stop", "permission": True},
        "media.audio.status": {"method": "GET", "path": "/media/audio/status", "permission": True},
        "media.audio.play": {"method": "POST", "path": "/media/audio/play", "permission": True},
        "media.audio.stop": {"method": "POST", "path": "/media/audio/stop", "permission": True},
        "llama.status": {"method": "GET", "path": "/llama/status", "permission": True},
        "llama.models": {"method": "GET", "path": "/llama/models", "permission": True},
        "llama.run": {"method": "POST", "path": "/llama/run", "permission": True},
        "llama.generate": {"method": "POST", "path": "/llama/generate", "permission": True},
        "llama.tts": {"method": "POST", "path": "/llama/tts", "permission": True},
        "llama.tts.plugins.list": {"method": "GET", "path": "/llama/tts/plugins", "permission": True},
        "llama.tts.plugins.upsert": {"method": "POST", "path": "/llama/tts/plugins/upsert", "permission": True},
        "llama.tts.plugins.delete": {"method": "POST", "path": "/llama/tts/plugins/delete", "permission": True},
        "llama.tts.speak": {"method": "POST", "path": "/llama/tts/speak", "permission": True},
        "llama.tts.speak.status": {"method": "POST", "path": "/llama/tts/speak/status", "permission": True},
        "llama.tts.speak.stop": {"method": "POST", "path": "/llama/tts/speak/stop", "permission": True},
        "stt.status": {"method": "GET", "path": "/stt/status", "permission": True},
        "stt.record": {"method": "POST", "path": "/stt/record", "permission": True},
        "location.status": {"method": "GET", "path": "/location/status", "permission": True},
        "location.get": {"method": "POST", "path": "/location/get", "permission": True},
        "network.status": {"method": "GET", "path": "/network/status", "permission": True},
        "wifi.status": {"method": "GET", "path": "/wifi/status", "permission": True},
        "mobile.status": {"method": "GET", "path": "/mobile/status", "permission": True},
        "sensors.list": {"method": "GET", "path": "/sensors/list", "permission": True},
        "sensor.list": {"method": "GET", "path": "/sensor/list", "permission": True},
        "sensors.ws.contract": {"method": "GET", "path": "/sensors/ws/contract", "permission": True},
        "usb.list": {"method": "GET", "path": "/usb/list", "permission": True},
        "usb.status": {"method": "GET", "path": "/usb/status", "permission": True},
        "usb.open": {"method": "POST", "path": "/usb/open", "permission": True},
        "usb.close": {"method": "POST", "path": "/usb/close", "permission": True},
        "usb.control_transfer": {"method": "POST", "path": "/usb/control_transfer", "permission": True},
        "usb.raw_descriptors": {"method": "POST", "path": "/usb/raw_descriptors", "permission": True},
        "usb.claim_interface": {"method": "POST", "path": "/usb/claim_interface", "permission": True},
        "usb.release_interface": {"method": "POST", "path": "/usb/release_interface", "permission": True},
        "usb.bulk_transfer": {"method": "POST", "path": "/usb/bulk_transfer", "permission": True},
        "usb.iso_transfer": {"method": "POST", "path": "/usb/iso_transfer", "permission": True},
        "usb.stream.start": {"method": "POST", "path": "/usb/stream/start", "permission": True},
        "usb.stream.stop": {"method": "POST", "path": "/usb/stream/stop", "permission": True},
        "usb.stream.status": {"method": "GET", "path": "/usb/stream/status", "permission": True},
        "uvc.mjpeg.capture": {"method": "POST", "path": "/uvc/mjpeg/capture", "permission": True},
        "uvc.diagnose": {"method": "POST", "path": "/uvc/diagnose", "permission": True},
        "vision.model.load": {"method": "POST", "path": "/vision/model/load", "permission": True},
        "vision.model.unload": {"method": "POST", "path": "/vision/model/unload", "permission": True},
        "vision.frame.put": {"method": "POST", "path": "/vision/frame/put", "permission": True},
        "vision.frame.get": {"method": "POST", "path": "/vision/frame/get", "permission": True},
        "vision.frame.delete": {"method": "POST", "path": "/vision/frame/delete", "permission": True},
        "vision.frame.save": {"method": "POST", "path": "/vision/frame/save", "permission": True},
        "vision.image.load": {"method": "POST", "path": "/vision/image/load", "permission": True},
        "vision.run": {"method": "POST", "path": "/vision/run", "permission": True},
        "shell.exec": {"method": "POST", "path": "/shell/exec", "permission": True},
        "brain.memory.get": {"method": "GET", "path": "/brain/memory", "permission": False},
        "brain.memory.set": {"method": "POST", "path": "/brain/memory", "permission": True},
        "viewer.open": {"method": "POST", "path": "/ui/viewer/open", "permission": False},
        "viewer.close": {"method": "POST", "path": "/ui/viewer/close", "permission": False},
        "viewer.immersive": {"method": "POST", "path": "/ui/viewer/immersive", "permission": False},
        "viewer.slideshow": {"method": "POST", "path": "/ui/viewer/slideshow", "permission": False},
        "viewer.goto": {"method": "POST", "path": "/ui/viewer/goto", "permission": False},
        # Non-sensitive configuration helpers (do not return secrets).
        "brain.config.get": {"method": "GET", "path": "/brain/config", "permission": False},
        "cloud.prefs.get": {"method": "GET", "path": "/cloud/prefs", "permission": False},
    }

    def __init__(self, base_url: str = "http://127.0.0.1:8765"):
        self.base_url = base_url.rstrip("/")
        self._identity = (os.environ.get("METHINGS_IDENTITY") or os.environ.get("METHINGS_SESSION_ID") or "").strip() or "default"
        # Cache approvals (in-memory). Kotlin also reuses approvals server-side by identity/capability.
        self._permission_ids: Dict[str, str] = {}
        # Conservative defaults: a few device actions can legitimately take longer than the tool runner
        # or urllib's default timeout.
        self._action_timeout_s: Dict[str, float] = {
            "camera.capture": 45.0,
            "camera.preview.start": 25.0,
            "camera.preview.stop": 25.0,
            "ssh.exec": 300.0,
            "ssh.scp": 600.0,
            "vision.run": 75.0,
            "usb.open": 60.0,
            "usb.stream.start": 25.0,
            "usb.stream.stop": 25.0,
            "uvc.mjpeg.capture": 45.0,
            "screen.keep_on": 12.0,
            "llama.run": 300.0,
            "llama.generate": 300.0,
            "llama.tts": 420.0,
            "llama.tts.plugins.list": 20.0,
            "llama.tts.plugins.upsert": 20.0,
            "llama.tts.plugins.delete": 20.0,
            "llama.tts.speak": 120.0,
            "llama.tts.speak.status": 20.0,
            "llama.tts.speak.stop": 20.0,
            "media.audio.play": 120.0,
            "media.audio.status": 20.0,
            "media.audio.stop": 20.0,
        }

    def set_identity(self, identity: str) -> None:
        self._identity = str(identity or "").strip() or "default"

    def _wait_for_permission(self, permission_id: str, *, timeout_s: float = 45.0, poll_s: float = 0.8) -> str:
        pid = str(permission_id or "").strip()
        if not pid:
            return "invalid"
        deadline = time.time() + max(1.0, float(timeout_s or 45.0))
        poll_s = max(0.2, min(float(poll_s or 0.8), 5.0))
        while time.time() < deadline:
            resp = self._request_json("GET", f"/permissions/{pid}", None)
            body = resp.get("body") if isinstance(resp, dict) else None
            if isinstance(body, dict):
                status = str(body.get("status") or "").strip()
                if status in {"approved", "denied", "used"}:
                    return status
            time.sleep(poll_s)
        return "timeout"

    def run(self, args: Dict[str, Any]) -> Dict[str, Any]:
        try:
            return self._run(args)
        except Exception as ex:
            # Never let exceptions bubble into the agent loop; surface as a normal tool error.
            return {"status": "error", "error": "device_api_exception", "detail": str(ex)}

    def _run(self, args: Dict[str, Any]) -> Dict[str, Any]:
        # Prefer per-chat identity if provided so approvals can be remembered for the session.
        # Fallback is install identity from env (shared across sessions).
        identity = str(args.get("identity") or args.get("session_id") or "").strip()
        if identity:
            self.set_identity(identity)

        action = str(args.get("action") or "").strip()
        payload = args.get("payload")
        if payload is None:
            payload = {}
        if not isinstance(payload, dict):
            return {"status": "error", "error": "invalid_payload"}

        # Compatibility shim: some plans accidentally nest a device_api call as:
        #   {"action":"device_api","payload":{"action":"llama.status","payload":{...}}}
        # Unwrap this shape so execution still succeeds.
        if action == "device_api":
            nested_action = str(payload.get("action") or "").strip()
            nested_payload = payload.get("payload", {})
            if not isinstance(nested_payload, dict):
                return {"status": "error", "error": "invalid_payload"}
            if nested_action:
                action = nested_action
                payload = nested_payload

        if not action:
            return {"status": "error", "error": "missing_action"}
        spec = self._ACTIONS.get(action)
        if not spec:
            # Virtual actions implemented client-side (still executed via the Kotlin control plane).
            if action.startswith("uvc."):
                return self._run_uvc_action(action, args)
            return {"status": "error", "error": "unknown_action"}

        if action == "shell.exec":
            cmd = str(payload.get("cmd") or "")
            if cmd not in {"python", "pip", "curl"}:
                return {"status": "error", "error": "command_not_allowed"}

        if spec["permission"]:
            detail = str(args.get("detail") or "").strip()
            if not detail:
                detail = f"{action}: {json.dumps(payload, ensure_ascii=True)[:240]}"
            # Request permission (Kotlin will prompt). Block briefly to allow a one-tap approval flow.
            perm_tool, perm_capability, perm_scope = self._permission_profile_for_action(action)
            pid, req = self._get_or_request_permission(perm_tool, perm_capability, perm_scope, detail)
            if not pid:
                return {"status": "permission_required", "request": req}
            # Pass through permission_id so Kotlin endpoints can enforce if they choose to.
            if spec["method"] == "POST" and isinstance(payload, dict) and "permission_id" not in payload:
                payload["permission_id"] = pid

        # Allow callers to override timeout per action.
        timeout_s = args.get("timeout_s", None)
        if timeout_s is None and isinstance(payload, dict):
            timeout_s = payload.get("timeout_s", None)
        if timeout_s is None:
            timeout_s = self._action_timeout_s.get(action, 12.0)
        try:
            timeout_s = float(timeout_s)
        except Exception:
            timeout_s = 12.0
        timeout_s = max(3.0, min(timeout_s, 900.0))

        body = payload if spec["method"] == "POST" else None
        return self._request_json(spec["method"], spec["path"], body, timeout_s=timeout_s)

    def _run_uvc_action(self, action: str, args: Dict[str, Any]) -> Dict[str, Any]:
        """
        UVC helpers implemented as "virtual" device_api actions.

        Motivation: UVC CameraTerminal PTZ controls for devices like Insta360 Link can be driven via
        UsbDeviceConnection.controlTransfer without requiring libusb device discovery on Android.
        """
        payload = args.get("payload") or {}
        if not isinstance(payload, dict):
            return {"status": "error", "error": "invalid_payload"}

        # All UVC actions are USB actions -> use the same session-scoped permission bucket.
        detail = str(args.get("detail") or "").strip() or f"{action}: {json.dumps(payload, ensure_ascii=True)[:240]}"
        pid, req = self._get_or_request_permission("device.usb", "usb", "session", detail)
        if not pid:
            return {"status": "permission_required", "request": req}

        # Convenience defaults for UVC PTZ.
        selector = int(payload.get("selector") or 0x0D)  # CT_PANTILT_ABSOLUTE_CONTROL
        handle = str(payload.get("handle") or "").strip()
        timeout_ms = int(payload.get("timeout_ms") or 220)
        device_name = str(payload.get("device_name") or payload.get("name") or "").strip()

        def _uvc_pick_device_name() -> str:
            # Best-effort: pick the first UVC VideoControl interface device.
            resp = self._request_json("GET", "/usb/list", None, timeout_s=8.0)
            body = resp.get("body") if isinstance(resp, dict) else None
            if not isinstance(body, dict):
                return ""
            devices = body.get("devices")
            if not isinstance(devices, list):
                return ""
            for d in devices:
                if not isinstance(d, dict):
                    continue
                name = str(d.get("name") or "").strip()
                if not name:
                    continue
                interfaces = d.get("interfaces")
                if not isinstance(interfaces, list):
                    continue
                for it in interfaces:
                    if not isinstance(it, dict):
                        continue
                    if int(it.get("interface_class") or -1) == 0x0E and int(it.get("interface_subclass") or -1) == 0x01:
                        return name
            return ""

        def _usb_open(name: str) -> Dict[str, Any]:
            return self._request_json("POST", "/usb/open", {"permission_id": pid, "name": name}, timeout_s=60.0)

        def _usb_close(h: str) -> None:
            h = str(h or "").strip()
            if not h:
                return
            try:
                self._request_json("POST", "/usb/close", {"permission_id": pid, "handle": h}, timeout_s=8.0)
            except Exception:
                pass

        def _ensure_handle() -> str:
            nonlocal device_name
            nonlocal handle
            if handle:
                return handle
            if not device_name:
                device_name = _uvc_pick_device_name()
            if not device_name:
                return ""
            opened = _usb_open(device_name)
            b = opened.get("body") if isinstance(opened, dict) else None
            if isinstance(b, dict) and b.get("status") == "ok":
                handle = str(b.get("handle") or "").strip()
            return handle

        if not _ensure_handle():
            return {"status": "error", "error": "missing_handle", "detail": "No handle provided and no UVC device could be selected."}

        # Resolve vc_interface + entity_id (camera terminal id) if absent.
        vc_interface = payload.get("vc_interface")
        entity_id = payload.get("entity_id")
        if vc_interface is None or entity_id is None:
            guess = self._uvc_guess_vc_and_entity(handle, pid)
            if vc_interface is None:
                vc_interface = guess.get("vc_interface")
            if entity_id is None:
                entity_id = guess.get("entity_id")
        if vc_interface is None:
            vc_interface = 0
        if entity_id is None:
            entity_id = 1  # Common for UVC Camera Terminal; also matches Insta360 Link observed behavior.

        vc_interface = int(vc_interface)
        entity_id = int(entity_id)
        w_index = ((entity_id & 0xFF) << 8) | (vc_interface & 0xFF)
        w_value = (selector & 0xFF) << 8

        # UVC class-specific interface request constants.
        req_type_in = 0xA1   # IN | Class | Interface
        req_type_out = 0x21  # OUT | Class | Interface
        set_cur = 0x01
        get_cur = 0x81
        get_min = 0x82
        get_max = 0x83

        def usb_control_transfer(request_type: int, request: int, value: int, index: int, data_b64: str) -> Dict[str, Any]:
            body: Dict[str, Any] = {
                "permission_id": pid,
                "handle": handle,
                "request_type": int(request_type),
                "request": int(request),
                "value": int(value),
                "index": int(index),
                "timeout_ms": int(timeout_ms),
            }
            if (int(request_type) & 0x80) != 0:
                # IN transfer: provide explicit length. Some devices reject the default 256 bytes.
                body["length"] = int(payload.get("length") or 8)
            else:
                body["data_b64"] = data_b64
            return self._request_json("POST", "/usb/control_transfer", body)

        def ctrl_in(request: int, length: int) -> Dict[str, Any]:
            # Explicitly set length for IN transfers. Do not send data_b64 (ignored by Kotlin for IN).
            payload["length"] = int(length)
            return usb_control_transfer(req_type_in, request, w_value, w_index, "")

        def ctrl_out(request: int, data: bytes) -> Dict[str, Any]:
            payload.pop("length", None)
            return usb_control_transfer(req_type_out, request, w_value, w_index, base64.b64encode(data).decode("ascii"))

        def ctrl_in_with_retry(request: int, length: int) -> Dict[str, Any]:
            nonlocal handle
            r = ctrl_in(request, length)
            if r.get("status") != "http_error" or int(r.get("http_status") or 0) != 500:
                return r
            body = r.get("body") if isinstance(r, dict) else None
            if not (isinstance(body, dict) and body.get("error") == "control_transfer_failed"):
                return r
            # Transient/stale handle: reopen once and retry.
            if not device_name:
                dn = _uvc_pick_device_name()
            else:
                dn = device_name
            if not dn:
                return r
            _usb_close(handle)
            opened = _usb_open(dn)
            ob = opened.get("body") if isinstance(opened, dict) else None
            new_handle = str(ob.get("handle") or "").strip() if isinstance(ob, dict) else ""
            if not new_handle:
                return r
            handle = new_handle
            # Try to claim the VC interface (best-effort).
            try:
                self._request_json(
                    "POST",
                    "/usb/claim_interface",
                    {"permission_id": pid, "handle": handle, "interface_id": int(vc_interface), "force": True},
                    timeout_s=8.0,
                )
            except Exception:
                pass
            return ctrl_in(request, length)

        if action == "uvc.ptz.get_abs":
            resp = ctrl_in_with_retry(get_cur, 8)
            raw = self._extract_b64(resp)
            if raw is None or len(raw) < 8:
                return {"status": "error", "error": "short_read", "detail": resp}
            pan_abs, tilt_abs = struct.unpack_from("<ii", raw, 0)
            return {"status": "ok", "pan_abs": int(pan_abs), "tilt_abs": int(tilt_abs), "detail": resp}

        if action == "uvc.ptz.get_limits":
            rmin = ctrl_in_with_retry(get_min, 8)
            rmax = ctrl_in_with_retry(get_max, 8)
            min_raw = self._extract_b64(rmin)
            max_raw = self._extract_b64(rmax)
            if min_raw is None or max_raw is None or len(min_raw) < 8 or len(max_raw) < 8:
                return {"status": "error", "error": "limits_unavailable", "detail": {"min": rmin, "max": rmax}}
            pan_min, tilt_min = struct.unpack_from("<ii", min_raw, 0)
            pan_max, tilt_max = struct.unpack_from("<ii", max_raw, 0)
            return {
                "status": "ok",
                "pan_min": int(pan_min),
                "pan_max": int(pan_max),
                "tilt_min": int(tilt_min),
                "tilt_max": int(tilt_max),
            }

        if action == "uvc.ptz.set_abs":
            if "pan_abs" not in payload or payload.get("pan_abs") is None:
                return {"status": "error", "error": "missing_pan_abs"}
            if "tilt_abs" not in payload or payload.get("tilt_abs") is None:
                return {"status": "error", "error": "missing_tilt_abs"}
            try:
                pan_abs = int(payload.get("pan_abs"))
                tilt_abs = int(payload.get("tilt_abs"))
            except Exception:
                return {"status": "error", "error": "invalid_pan_tilt"}
            data = struct.pack("<ii", pan_abs, tilt_abs)
            resp = ctrl_out(set_cur, data)
            return {"status": "ok", "rc": self._extract_rc(resp), "detail": resp}

        if action == "uvc.ptz.nudge":
            pan = float(payload.get("pan") or 0.0)
            tilt = float(payload.get("tilt") or 0.0)
            step_pan = int(round(max(-1.0, min(1.0, pan)) * float(payload.get("step_pan") or 90000.0)))
            step_tilt = int(round(max(-1.0, min(1.0, tilt)) * float(payload.get("step_tilt") or 68400.0)))

            cur = self._run_uvc_action("uvc.ptz.get_abs", {"payload": {"handle": handle, "selector": selector, "vc_interface": vc_interface, "entity_id": entity_id}, "detail": "UVC PTZ get abs"})
            if cur.get("status") != "ok":
                return {"status": "error", "error": "get_abs_failed", "detail": cur}

            # Prefer queried limits; fallback to known-good Insta360 Link clamp if unavailable.
            limits = self._run_uvc_action("uvc.ptz.get_limits", {"payload": {"handle": handle, "selector": selector, "vc_interface": vc_interface, "entity_id": entity_id}, "detail": "UVC PTZ get limits"})
            if limits.get("status") == "ok":
                pan_min = int(limits["pan_min"])
                pan_max = int(limits["pan_max"])
                tilt_min = int(limits["tilt_min"])
                tilt_max = int(limits["tilt_max"])
            else:
                pan_min, pan_max = -522000, 522000
                tilt_min, tilt_max = -324000, 360000

            pan_abs = int(max(pan_min, min(pan_max, int(cur["pan_abs"]) + step_pan)))
            tilt_abs = int(max(tilt_min, min(tilt_max, int(cur["tilt_abs"]) + step_tilt)))
            return self._run_uvc_action(
                "uvc.ptz.set_abs",
                {
                    "payload": {
                        "handle": handle,
                        "selector": selector,
                        "vc_interface": vc_interface,
                        "entity_id": entity_id,
                        "pan_abs": pan_abs,
                        "tilt_abs": tilt_abs,
                        "timeout_ms": timeout_ms,
                    },
                    "detail": f"UVC PTZ nudge pan={pan} tilt={tilt} -> abs({pan_abs},{tilt_abs})",
                },
            )

        return {"status": "error", "error": "unknown_uvc_action"}

    def _extract_rc(self, resp: Dict[str, Any]) -> Optional[int]:
        body = resp.get("body") if isinstance(resp, dict) else None
        if isinstance(body, dict) and "rc" in body:
            try:
                return int(body.get("rc"))
            except Exception:
                return None
        return None

    def _extract_b64(self, resp: Dict[str, Any]) -> Optional[bytes]:
        body = resp.get("body") if isinstance(resp, dict) else None
        if not isinstance(body, dict):
            return None
        data_b64 = body.get("data_b64")
        if not isinstance(data_b64, str) or not data_b64:
            return None
        try:
            return base64.b64decode(data_b64.encode("ascii"), validate=False)
        except Exception:
            return None

    def _uvc_guess_vc_and_entity(self, handle: str, permission_id: str) -> Dict[str, Optional[int]]:
        """
        Best-effort guess for:
        - vc_interface: VideoControl interface number (UVC subclass 1)
        - entity_id: Camera Terminal ID (wTerminalType 0x0201)
        Derived by scanning raw USB descriptors.
        """
        resp = self._request_json(
            "POST",
            "/usb/raw_descriptors",
            {"permission_id": permission_id, "handle": handle},
        )
        raw = self._extract_b64(resp)
        if not raw:
            return {"vc_interface": None, "entity_id": None}

        vc_interface: Optional[int] = None
        entity_id: Optional[int] = None

        i = 0
        n = len(raw)
        while i + 2 < n:
            dlen = raw[i]
            if dlen <= 0:
                break
            if i + dlen > n:
                break
            dtype = raw[i + 1]
            if dtype == 0x04 and dlen >= 9:
                # Interface descriptor: bInterfaceNumber at +2, class at +5, subclass at +6.
                b_interface_number = raw[i + 2]
                b_interface_class = raw[i + 5]
                b_interface_subclass = raw[i + 6]
                if b_interface_class == 0x0E and b_interface_subclass == 0x01:
                    vc_interface = int(b_interface_number)
            elif dtype == 0x24 and dlen >= 8:
                subtype = raw[i + 2]
                if subtype == 0x02 and dlen >= 8:
                    # VC_INPUT_TERMINAL: bTerminalID at +3, wTerminalType at +4..+5
                    terminal_id = raw[i + 3]
                    w_terminal_type = int(raw[i + 4]) | (int(raw[i + 5]) << 8)
                    if w_terminal_type == 0x0201 and entity_id is None:
                        entity_id = int(terminal_id)
            i += dlen

        return {"vc_interface": vc_interface, "entity_id": entity_id}

    def _permission_profile_for_action(self, action: str) -> tuple[str, str, str]:
        a = (action or "").strip()
        if a.startswith("screen."):
            return "device.screen", "screen", "session"
        if a.startswith("sshd.keys."):
            # Kotlin forces scope to once for ssh_keys; still group by session identity.
            return "ssh_keys", "ssh_keys", "once"
        if a.startswith("sshd.pin."):
            return "ssh_pin", "sshd.pin", "session"
        if a.startswith("sshd."):
            return "device.sshd", "sshd", "session"
        if a.startswith("ssh."):
            return "device.ssh", "ssh", "session"
        if a.startswith("camera."):
            return "device.camera", "camera", "session"
        if a.startswith("ble."):
            return "device.ble", "ble", "session"
        if a.startswith("tts."):
            return "device.tts", "tts", "session"
        if a.startswith("media.audio."):
            return "device.media", "media", "session"
        if a.startswith("llama."):
            return "device.llama", "llama", "session"
        if a.startswith("stt."):
            return "device.mic", "stt", "session"
        if a.startswith("location."):
            return "device.gps", "location", "session"
        if a.startswith("network.") or a.startswith("wifi.") or a.startswith("mobile."):
            return "device.network", "network", "session"
        if a.startswith("sensors."):
            return "device.sensors", "sensors", "session"
        if a.startswith("sensor."):
            return "device.sensors", "sensors", "session"
        if a.startswith("usb."):
            return "device.usb", "usb", "session"
        if a.startswith("vision."):
            return "device.vision", "vision", "session"
        # Default to session scope: approve once per chat session, then no repeated prompts.
        return "device_api", "device_api", "session"

    def _get_or_request_permission(self, tool: str, capability: str, scope: str, detail: str) -> tuple[str, Dict[str, Any]]:
        # If we already have an approved permission id, keep using it.
        cache_key = f"{tool}::{capability}::{scope}"
        cached = self._permission_ids.get(cache_key, "")
        if cached and self._is_approved(cached):
            return cached, {"id": cached, "status": "approved"}

        # Ask Kotlin to create/reuse a session-scoped permission.
        req = self._request_permission(tool=tool, capability=capability, scope=scope, detail=detail)
        pid = str(req.get("id") or "").strip()
        if pid:
            self._permission_ids[cache_key] = pid
            if self._is_approved(pid):
                return pid, req
            # Don't block waiting for user approval inside the tool call; return immediately so the
            # agent can ask the user to approve and then retry.
            st = str(req.get("status") or "").strip() or "pending"
            return "", {"id": pid, "status": st, "tool": tool, "detail": detail, "scope": scope, "capability": capability}
        return "", req

    def _is_approved(self, permission_id: str) -> bool:
        pid = str(permission_id or "").strip()
        if not pid:
            return False
        resp = self._request_json("GET", f"/permissions/{pid}", None)
        body = resp.get("body") if isinstance(resp, dict) else None
        if isinstance(body, dict):
            return str(body.get("status") or "") == "approved"
        return False

    def _request_permission(self, *, tool: str, capability: str, scope: str, detail: str) -> Dict[str, Any]:
        resp = self._request_json(
            "POST",
            "/permissions/request",
            {
                "tool": tool,
                "detail": detail,
                # "once" makes agent usage unbearable; keep a short-lived approval.
                "scope": scope,
                "identity": self._identity,
                "capability": capability,
            },
        )
        if resp.get("status") != "ok":
            return {"status": "error", "error": "permission_request_failed", "detail": resp}
        body = resp.get("body")
        return body if isinstance(body, dict) else {"status": "error", "error": "invalid_permission_response"}

    def _request_json(self, method: str, path: str, body: Optional[Dict[str, Any]] = None, *, timeout_s: float = 12.0) -> Dict[str, Any]:
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if self._identity:
            # Back-compat: accept either header name server-side; send both client-side.
            headers["X-Methings-Identity"] = self._identity
            headers["X-Methings-Identity"] = self._identity
        req = urllib.request.Request(self.base_url + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=float(timeout_s)) as resp:
                raw = resp.read().decode("utf-8", errors="replace")
                parsed = json.loads(raw) if raw else {}
                return {"status": "ok", "http_status": int(resp.status), "body": parsed}
        except urllib.error.HTTPError as ex:
            raw = ex.read().decode("utf-8", errors="replace")
            try:
                parsed = json.loads(raw) if raw else {}
            except Exception:
                parsed = {"raw": raw}
            # Provide a clear hint for OS-level USB permission failures. This is not the app's
            # tool-permission broker; the user must accept Android's USB dialog (and the request
            # may be auto-denied if the app isn't in the foreground).
            if isinstance(parsed, dict) and parsed.get("error") == "usb_permission_required":
                parsed.setdefault(
                    "hint",
                    "USB permission is required. Bring me.things to the foreground and accept the system USB access dialog, then retry. "
                    "If Android auto-denies without showing a dialog, clear the app's defaults (App info -> Open by default -> Clear defaults), replug the device, and retry.",
                )
            return {"status": "http_error", "http_status": int(ex.code), "body": parsed}
        except Exception as ex:
            return {"status": "error", "error": str(ex)}
