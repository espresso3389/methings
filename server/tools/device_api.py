import json
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
        "shell.exec": {"method": "POST", "path": "/shell/exec", "permission": True},
        "brain.memory.get": {"method": "GET", "path": "/brain/memory", "permission": False},
        "brain.memory.set": {"method": "POST", "path": "/brain/memory", "permission": True},
    }

    def __init__(self, base_url: str = "http://127.0.0.1:8765"):
        self.base_url = base_url.rstrip("/")

    def run(self, args: Dict[str, Any]) -> Dict[str, Any]:
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
            if cmd not in {"python", "pip", "uv", "curl"}:
                return {"status": "error", "error": "command_not_allowed"}

        if spec["permission"]:
            detail = str(args.get("detail") or "").strip()
            if not detail:
                detail = f"{action}: {json.dumps(payload, ensure_ascii=True)[:240]}"
            # Don't block the agent/tool loop waiting for UI approval. Create a request and
            # return it; the runtime/UI will tell the user to approve and retry.
            req = self._request_permission(detail)
            return {"status": "permission_required", "request": req}

        body = payload if spec["method"] == "POST" else None
        return self._request_json(spec["method"], spec["path"], body)

    def _request_permission(self, detail: str) -> Dict[str, Any]:
        resp = self._request_json(
            "POST",
            "/permissions/request",
            {"tool": "device_api", "detail": detail, "scope": "once"},
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
