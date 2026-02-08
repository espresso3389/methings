import json
import os
import time
import urllib.error
import urllib.request
from typing import Any, Dict, Optional


BASE_URL = "http://127.0.0.1:8765"
_IDENTITY = (os.environ.get("METHINGS_IDENTITY") or os.environ.get("METHINGS_SESSION_ID") or "").strip() or "default"


def set_identity(identity: str) -> None:
    global _IDENTITY
    _IDENTITY = str(identity or "").strip() or "default"


def _request_json(
    method: str,
    path: str,
    body: Optional[Dict[str, Any]] = None,
    identity: str = "",
) -> Dict[str, Any]:
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if identity:
        # Back-compat: accept either header name server-side; send both client-side.
        headers["X-Methings-Identity"] = identity
        headers["X-Methings-Identity"] = identity
    req = urllib.request.Request(BASE_URL + path, data=data, method=method, headers=headers)
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


def request(tool: str, detail: str = "", scope: str = "session", identity: str = "") -> Dict[str, Any]:
    """
    Create a permission request in the Kotlin control plane.

    For device resources, use tool names like:
      - device.camera2
      - device.mic
      - device.gps
      - device.ble.scan
      - device.usb

    This triggers an on-device popup (and OS runtime permission prompt if needed).
    """
    tool = str(tool or "").strip()
    if not tool:
        raise ValueError("tool is required")
    ident = (identity or _IDENTITY).strip()
    resp = _request_json(
        "POST",
        "/permissions/request",
        {"tool": tool, "detail": str(detail or ""), "scope": str(scope or "once"), "identity": ident},
        identity=ident,
    )
    if resp.get("status") != "ok":
        raise RuntimeError(f"permission request failed: {resp}")
    body = resp.get("body")
    if not isinstance(body, dict) or not body.get("id"):
        raise RuntimeError(f"invalid permission response: {resp}")
    return body


def get(permission_id: str) -> Dict[str, Any]:
    permission_id = str(permission_id or "").strip()
    if not permission_id:
        raise ValueError("permission_id is required")
    resp = _request_json("GET", f"/permissions/{permission_id}")
    if resp.get("status") != "ok":
        raise RuntimeError(f"permission get failed: {resp}")
    body = resp.get("body")
    if not isinstance(body, dict):
        raise RuntimeError(f"invalid permission get response: {resp}")
    return body


def wait(permission_id: str, timeout_s: float = 60.0, poll_s: float = 0.5) -> Dict[str, Any]:
    """
    Block waiting for the user to approve/deny the permission request.
    """
    deadline = time.monotonic() + float(timeout_s)
    last: Dict[str, Any] = {}
    while time.monotonic() < deadline:
        current = get(permission_id)
        last = current
        status = str(current.get("status") or "")
        if status in {"approved", "denied", "expired", "used"}:
            return current
        time.sleep(float(poll_s))
    return {"id": permission_id, "status": "timeout", "last": last}


def ensure_device(
    capability: str,
    detail: str = "",
    scope: str = "persistent",
    timeout_s: float = 60.0,
    wait_for_approval: bool = False,
    identity: str = "",
) -> str:
    """
    Convenience wrapper for requesting a device permission.

    Default behavior is *non-blocking* to avoid agent deadlocks/timeouts while waiting for the user.
    - If already approved, returns the permission_id.
    - If pending/denied/etc, raises PermissionError immediately.

    If you want to block (e.g. in a human-run script), pass wait_for_approval=True.
    """
    cap = str(capability or "").strip()
    if not cap:
        raise ValueError("capability is required")
    tool = f"device.{cap}"
    req = request(tool=tool, detail=detail, scope=scope, identity=(identity or _IDENTITY).strip())
    pid = str(req.get("id"))
    if wait_for_approval:
        final = wait(pid, timeout_s=timeout_s)
    else:
        final = get(pid)
    if final.get("status") != "approved":
        raise PermissionError(f"{tool} not approved: {final}")
    return pid
