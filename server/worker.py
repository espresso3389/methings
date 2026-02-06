import contextlib
import io
import json
import re
import runpy
import shlex
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Dict, Optional
from urllib.parse import parse_qs, urlparse

from agents.runtime import BrainRuntime
from storage.db import Storage
from tools.router import ToolRouter


BASE_DIR = Path(__file__).resolve().parent.parent
USER_DIR = BASE_DIR / "user"
USER_DIR.mkdir(parents=True, exist_ok=True)
DATA_DIR = BASE_DIR / "protected"
DATA_DIR.mkdir(parents=True, exist_ok=True)

STORAGE = Storage(DATA_DIR / "app.db")
TOOL_ROUTER = ToolRouter(DATA_DIR)


def _now_ms() -> int:
    return int(time.time() * 1000)


def _emit_log(event: str, data: Dict):
    try:
        STORAGE.add_audit(event, json.dumps(data))
    except Exception:
        pass


def _resolve_cwd(cwd: str) -> Path:
    if not cwd:
        return USER_DIR
    candidate = (USER_DIR / str(cwd).lstrip("/")).resolve()
    user_root = USER_DIR.resolve()
    if str(candidate).startswith(str(user_root)):
        return candidate
    return user_root


def _shell_exec_impl(cmd: str, raw_args: str, cwd: str) -> Dict:
    if cmd not in {"python", "pip", "uv"}:
        return {"status": "error", "error": "command_not_allowed"}

    workdir = _resolve_cwd(cwd)
    args = shlex.split(raw_args or "")
    output = io.StringIO()
    code = 0

    with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
        try:
            if cmd == "pip":
                try:
                    from pip._internal.cli.main import main as pip_main
                except Exception:
                    try:
                        import ensurepip

                        ensurepip.bootstrap(upgrade=True)
                        from pip._internal.cli.main import main as pip_main
                    except Exception:
                        from pip._internal import main as pip_main  # type: ignore
                code = int(pip_main(args) or 0)
            elif cmd == "uv":
                uv_cmd = [sys.executable, "-m", "uv", *args]
                uv_proc = subprocess.run(uv_cmd, cwd=str(workdir), capture_output=True, text=True)
                uv_output = (uv_proc.stdout or "") + (uv_proc.stderr or "")
                if uv_proc.returncode != 0 and re.search(r"No module named ['\"]?uv['\"]?", uv_output):
                    install_proc = subprocess.run(
                        [sys.executable, "-m", "pip", "install", "uv"],
                        cwd=str(workdir),
                        capture_output=True,
                        text=True,
                    )
                    if install_proc.returncode == 0:
                        uv_proc = subprocess.run(uv_cmd, cwd=str(workdir), capture_output=True, text=True)
                    else:
                        if install_proc.stdout:
                            print(install_proc.stdout, end="")
                        if install_proc.stderr:
                            print(install_proc.stderr, end="")
                if uv_proc.stdout:
                    print(uv_proc.stdout, end="")
                if uv_proc.stderr:
                    print(uv_proc.stderr, end="")
                code = int(uv_proc.returncode)
            else:
                if not args:
                    raise RuntimeError("interactive_not_supported")
                if args[0] in {"-V", "--version"}:
                    print(sys.version)
                elif args[0] == "-c":
                    if len(args) < 2:
                        raise RuntimeError("missing_code")
                    exec_globals = {"__name__": "__main__"}
                    exec(args[1], exec_globals, exec_globals)
                else:
                    script_path = Path(args[0])
                    if not script_path.is_absolute():
                        script_path = (workdir / script_path).resolve()
                    runpy.run_path(str(script_path), run_name="__main__")
        except SystemExit as exc:
            code = int(getattr(exc, "code", 0) or 0)
        except Exception as exc:
            code = 1
            print(f"error: {exc}")

    return {"status": "ok", "code": code, "output": output.getvalue()}


def _create_permission_request_sync(
    tool: str,
    detail: str = "",
    scope: str = "once",
    duration_min: int = 0,
) -> Dict:
    expires_at = None
    if scope == "session" and duration_min > 0:
        expires_at = _now_ms() + duration_min * 60 * 1000
    request_id = STORAGE.create_permission_request(
        tool=tool,
        detail=detail,
        scope=scope,
        expires_at=expires_at,
    )
    request = STORAGE.get_permission_request(request_id)
    _emit_log("permission_requested", request or {})
    return request or {"id": request_id, "status": "pending"}


def _tool_invoke_impl(
    tool_name: str,
    args: Dict,
    request_id: Optional[str] = None,
    detail: str = "",
) -> Dict:
    if not request_id:
        request = _create_permission_request_sync(tool_name, detail=detail)
        return {"status": "permission_required", "request": request}

    request = STORAGE.get_permission_request(request_id)
    if not request or request.get("status") != "approved":
        return {"status": "permission_required", "request": request}

    expires_at = request.get("expires_at")
    if expires_at and _now_ms() > int(expires_at):
        STORAGE.update_permission_status(request_id, "expired")
        return {"status": "permission_expired", "request": request}

    result = TOOL_ROUTER.invoke(tool_name, args)
    _emit_log("tool_invoked", {"tool": tool_name, "result": result})

    if request.get("scope") == "once":
        STORAGE.mark_permission_used(request_id)
    return result


BRAIN_RUNTIME = BrainRuntime(
    user_dir=USER_DIR,
    storage=STORAGE,
    emit_log=_emit_log,
    shell_exec=_shell_exec_impl,
    tool_invoke=_tool_invoke_impl,
)


class WorkerHandler(BaseHTTPRequestHandler):
    server_version = "KugutzWorker/0.2"

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            self._send_json({"status": "ok"})
            return
        if parsed.path == "/brain/status":
            self._send_json(BRAIN_RUNTIME.status())
            return
        if parsed.path == "/brain/config":
            self._send_json(BRAIN_RUNTIME.get_config())
            return
        if parsed.path == "/brain/messages":
            query = parse_qs(parsed.query or "")
            try:
                limit = int((query.get("limit") or ["50"])[0])
            except Exception:
                limit = 50
            self._send_json({"messages": BRAIN_RUNTIME.list_messages(limit=limit)})
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        parsed = urlparse(self.path)
        payload = self._read_json_body()
        if payload is None:
            self._send_json({"error": "invalid_json"}, status=400)
            return

        if parsed.path == "/shutdown":
            self._send_json({"status": "stopping"})
            threading.Thread(target=self.server.shutdown, daemon=True).start()
            return
        if parsed.path == "/shell/exec":
            self._handle_shell_exec(payload)
            return
        if parsed.path == "/brain/config":
            self._send_json(BRAIN_RUNTIME.update_config(payload or {}))
            return
        if parsed.path == "/brain/start":
            self._send_json(BRAIN_RUNTIME.start())
            return
        if parsed.path == "/brain/stop":
            self._send_json(BRAIN_RUNTIME.stop())
            return
        if parsed.path == "/brain/inbox/chat":
            text = str((payload or {}).get("text") or "")
            meta = (payload or {}).get("meta")
            if not isinstance(meta, dict):
                meta = {}
            if not text.strip():
                self._send_json({"error": "missing_text"}, status=400)
                return
            self._send_json(BRAIN_RUNTIME.enqueue_chat(text, meta=meta))
            return
        if parsed.path == "/brain/inbox/event":
            name = str((payload or {}).get("name") or "")
            body = (payload or {}).get("payload")
            if not isinstance(body, dict):
                body = {}
            if not name.strip():
                self._send_json({"error": "missing_name"}, status=400)
                return
            self._send_json(BRAIN_RUNTIME.enqueue_event(name=name, payload=body))
            return

        self.send_response(404)
        self.end_headers()

    def log_message(self, format, *args):
        return

    def _send_json(self, payload, status=200):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json_body(self):
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length).decode("utf-8", errors="replace") if length > 0 else ""
        try:
            return json.loads(raw) if raw else {}
        except Exception:
            return None

    def _handle_shell_exec(self, payload):
        cmd = str(payload.get("cmd", "") or "")
        raw_args = str(payload.get("args", "") or "")
        cwd = str(payload.get("cwd", "") or "")
        result = _shell_exec_impl(cmd, raw_args, cwd)
        if result.get("status") == "error" and result.get("error") == "command_not_allowed":
            self._send_json({"error": "command_not_allowed"}, status=403)
            return
        self._send_json(result)


def main():
    BRAIN_RUNTIME.maybe_autostart()
    server = HTTPServer(("127.0.0.1", 8776), WorkerHandler)
    server.serve_forever()


if __name__ == "__main__":
    main()
