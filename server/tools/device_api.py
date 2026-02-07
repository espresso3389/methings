import json
import os
import time
import urllib.error
import urllib.request
from typing import Any, Dict, Optional


class DeviceApiTool:
    _ACTIONS: Dict[str, Dict[str, Any]] = {
        "python.status": {"method": "GET", "path": "/python/status", "permission": False},
        "python.restart": {"method": "POST", "path": "/python/restart", "permission": True},
        "ssh.status": {"method": "GET", "path": "/ssh/status", "permission": False},
        "ssh.config": {"method": "POST", "path": "/ssh/config", "permission": True},
        "ssh.pin.status": {"method": "GET", "path": "/ssh/pin/status", "permission": False},
        "ssh.pin.start": {"method": "POST", "path": "/ssh/pin/start", "permission": True},
        "ssh.pin.stop": {"method": "POST", "path": "/ssh/pin/stop", "permission": True},
        "usb.list": {"method": "GET", "path": "/usb/list", "permission": True},
        "usb.open": {"method": "POST", "path": "/usb/open", "permission": True},
        "usb.close": {"method": "POST", "path": "/usb/close", "permission": True},
        "usb.control_transfer": {"method": "POST", "path": "/usb/control_transfer", "permission": True},
        "usb.raw_descriptors": {"method": "POST", "path": "/usb/raw_descriptors", "permission": True},
        "usb.claim_interface": {"method": "POST", "path": "/usb/claim_interface", "permission": True},
        "usb.release_interface": {"method": "POST", "path": "/usb/release_interface", "permission": True},
        "usb.bulk_transfer": {"method": "POST", "path": "/usb/bulk_transfer", "permission": True},
        "shell.exec": {"method": "POST", "path": "/shell/exec", "permission": True},
        "brain.memory.get": {"method": "GET", "path": "/brain/memory", "permission": False},
        "brain.memory.set": {"method": "POST", "path": "/brain/memory", "permission": True},
    }

    def __init__(self, base_url: str = "http://127.0.0.1:8765"):
        self.base_url = base_url.rstrip("/")
        self._identity = (os.environ.get("KUGUTZ_IDENTITY") or os.environ.get("KUGUTZ_SESSION_ID") or "").strip() or "default"
        # Cache approvals (in-memory). Kotlin also reuses approvals server-side by identity/capability.
        self._permission_ids: Dict[str, str] = {}

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
        # Prefer per-chat identity if provided so approvals can be remembered for the session.
        # Fallback is install identity from env (shared across sessions).
        identity = str(args.get("identity") or args.get("session_id") or "").strip()
        if identity:
            self.set_identity(identity)

        action = str(args.get("action") or "").strip()
        if not action:
            return {"status": "error", "error": "missing_action"}
        spec = self._ACTIONS.get(action)
        if not spec:
            return {"status": "error", "error": "unknown_action"}

        payload = args.get("payload")
        if payload is None:
            payload = {}
        if not isinstance(payload, dict):
            return {"status": "error", "error": "invalid_payload"}

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

        body = payload if spec["method"] == "POST" else None
        return self._request_json(spec["method"], spec["path"], body)

    def _permission_profile_for_action(self, action: str) -> tuple[str, str, str]:
        a = (action or "").strip()
        if a.startswith("ssh.pin."):
            return "ssh_pin", "ssh.pin", "session"
        if a.startswith("usb."):
            return "device.usb", "usb", "session"
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
            # Wait briefly for approval to avoid "permission hell" during agent loops.
            status = self._wait_for_permission(pid, timeout_s=float(os.environ.get("KUGUTZ_PERMISSION_TIMEOUT_S", "45") or "45"))
            if status == "approved":
                return pid, {"id": pid, "status": "approved"}
            if status == "denied":
                return "", {"id": pid, "status": "denied", "tool": tool, "detail": detail, "scope": scope, "capability": capability}
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

    def _request_json(self, method: str, path: str, body: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if self._identity:
            headers["X-Kugutz-Identity"] = self._identity
        req = urllib.request.Request(self.base_url + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=12) as resp:
                raw = resp.read().decode("utf-8", errors="replace")
                parsed = json.loads(raw) if raw else {}
                return {"status": "ok", "http_status": int(resp.status), "body": parsed}
        except urllib.error.HTTPError as ex:
            raw = ex.read().decode("utf-8", errors="replace")
            try:
                parsed = json.loads(raw) if raw else {}
            except Exception:
                parsed = {"raw": raw}
            return {"status": "http_error", "http_status": int(ex.code), "body": parsed}
        except Exception as ex:
            return {"status": "error", "error": str(ex)}
