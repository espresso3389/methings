import json
import os
import time
import urllib.error
import urllib.request
from typing import Any, Dict


class CloudRequestTool:
    """
    Thin Python wrapper around Kotlin's /cloud/request endpoint.

    The model crafts the request template; Kotlin expands placeholders and injects secrets
    server-side (vault/config/file expansion) and enforces permission prompts.
    """

    def __init__(self, base_url: str = "http://127.0.0.1:33389"):
        self.base_url = base_url.rstrip("/")
        self._identity = (os.environ.get("METHINGS_IDENTITY") or os.environ.get("METHINGS_SESSION_ID") or "").strip() or "default"

    def set_identity(self, identity: str) -> None:
        self._identity = str(identity or "").strip() or "default"

    def _request_json(self, method: str, path: str, body: Dict[str, Any] | None, *, timeout_s: float = 120.0) -> Dict[str, Any]:
        url = self.base_url + path
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if self._identity:
            # Back-compat: accept either header name server-side; send both client-side.
            headers["X-Methings-Identity"] = self._identity
            headers["X-Methings-Identity"] = self._identity
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=float(timeout_s)) as resp:
                raw = resp.read()
                try:
                    parsed = json.loads(raw.decode("utf-8")) if raw else {}
                except Exception:
                    parsed = {"raw": raw.decode("utf-8", errors="replace")}
                return {"status": "ok", "http_status": resp.status, "body": parsed}
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                parsed = json.loads(raw.decode("utf-8")) if raw else {}
            except Exception:
                parsed = {"raw": raw.decode("utf-8", errors="replace")}
            return {"status": "ok", "http_status": e.code, "body": parsed}
        except Exception as e:
            return {"status": "error", "error": "request_failed", "detail": str(e)}

    def run(self, args: Dict[str, Any]) -> Dict[str, Any]:
        identity = str(args.get("identity") or args.get("session_id") or "").strip()
        if identity:
            self.set_identity(identity)

        req = args.get("request")
        payload = req if isinstance(req, dict) else args
        if not isinstance(payload, dict):
            return {"status": "error", "error": "invalid_request"}

        permission_id = str(payload.get("permission_id") or "").strip()
        # Local server call timeout: must exceed the upstream request timeout, otherwise the tool
        # can time out even if the upstream transfer is making progress.
        req_timeout_s = payload.get("timeout_s", 45.0)
        try:
            req_timeout_s = float(req_timeout_s)
        except Exception:
            req_timeout_s = 45.0
        tool_timeout_s = max(60.0, min(300.0, req_timeout_s + 60.0))

        def _with_http_status(body: Any, http_status: int) -> Dict[str, Any]:
            if isinstance(body, dict):
                # Preserve the existing shape (usually {status:'ok', ...}) but surface transport info.
                if "http_status" not in body:
                    body = dict(body)
                    body["http_status"] = int(http_status)
                return body
            return {"status": "error", "error": "invalid_response", "http_status": int(http_status), "body": body}

        def do(pid: str) -> Dict[str, Any]:
            p = dict(payload)
            if self._identity and "identity" not in p:
                p["identity"] = self._identity
            if pid:
                p["permission_id"] = pid
            return self._request_json("POST", "/cloud/request", p, timeout_s=tool_timeout_s)

        r = do(permission_id)
        if r.get("status") != "ok":
            return r
        http_status = int(r.get("http_status") or 0)
        body = r.get("body")

        if http_status == 403 and isinstance(body, dict) and body.get("status") == "permission_required":
            # Default: return immediately so the agent can tell the user to approve and retry.
            # Optional: wait for approval + auto-retry if explicitly requested.
            if not bool(payload.get("wait_for_approval", False)):
                return _with_http_status(body, http_status)

            req = body.get("request") if isinstance(body.get("request"), dict) else {}
            pid = str(req.get("id") or "").strip()
            if not pid:
                return _with_http_status(body, http_status)

            while True:
                st = self._request_json("GET", f"/permissions/{pid}", None, timeout_s=12.0)
                if st.get("status") != "ok":
                    time.sleep(1.0)
                    continue
                perm = st.get("body") if isinstance(st.get("body"), dict) else {}
                status = str(perm.get("status") or "")
                if status == "approved":
                    r2 = do(pid)
                    if r2.get("status") != "ok":
                        return r2
                    b2 = r2.get("body")
                    return _with_http_status(b2, int(r2.get("http_status") or 0))
                if status in {"denied", "expired", "used"}:
                    return {"status": "error", "error": "permission_not_approved", "permission": perm}
                time.sleep(1.0)

        return _with_http_status(body, http_status)
