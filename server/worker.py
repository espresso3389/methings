import contextlib
import io
import json
import os
import re
import runpy
import shlex
import ssl
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Dict, Optional
from urllib.parse import parse_qs, urlparse
import urllib.request
import urllib.error

from agents.runtime import BrainRuntime
from storage.db import Storage
from tools.router import ToolRouter


BASE_DIR = Path(__file__).resolve().parent.parent
USER_DIR = BASE_DIR / "user"
USER_DIR.mkdir(parents=True, exist_ok=True)
DATA_DIR = BASE_DIR / "protected"
DATA_DIR.mkdir(parents=True, exist_ok=True)
PYENV_DIR = BASE_DIR / "pyenv"
if PYENV_DIR.exists():
    # Some tools (notably pip build isolation) may spawn Python subprocesses using sys.executable.
    # Our embedded launcher expects KUGUTZ_PYENV to locate the runtime.
    os.environ.setdefault("KUGUTZ_PYENV", str(PYENV_DIR))

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
    raw = Path(str(cwd))
    if raw.is_absolute():
        candidate = raw.resolve()
    else:
        candidate = (USER_DIR / str(cwd).lstrip("/")).resolve()
    user_root = USER_DIR.resolve()
    if str(candidate).startswith(str(user_root)):
        return candidate
    return user_root


def _resolve_user_file(workdir: Path, path: str) -> Path | None:
    target = Path(path)
    if not target.is_absolute():
        target = (workdir / target).resolve()
    else:
        target = target.resolve()
    root = USER_DIR.resolve()
    if not str(target).startswith(str(root)):
        return None
    return target


def _render_write_out(template: str, meta: dict[str, str]) -> str:
    template = template.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
    return re.sub(r"%\{([a-zA-Z0-9_]+)\}", lambda m: meta.get(m.group(1), ""), template)


def _print_headers(status: int, reason: str, headers) -> None:
    status_text = reason or ""
    print(f"HTTP/1.1 {status} {status_text}".rstrip())
    for key, value in headers:
        print(f"{key}: {value}")
    print()


def _expand_short_flags(args: list[str]) -> list[str]:
    expanded: list[str] = []
    combinable = {"s", "S", "L", "f", "I", "i"}
    for token in args:
        if token.startswith("--") or not token.startswith("-") or len(token) <= 2:
            expanded.append(token)
            continue
        chars = token[1:]
        if all(ch in combinable for ch in chars):
            expanded.extend([f"-{ch}" for ch in chars])
            continue
        expanded.append(token)
    return expanded


def _run_curl_args(args: list[str], workdir: Path) -> int:
    args = _expand_short_flags(args)
    method = "GET"
    url = ""
    headers: list[tuple[str, str]] = []
    body_data: bytes | None = None
    output_file = ""
    head_only = False
    include_headers = False
    write_out = ""
    silent = False
    show_error = False
    fail_mode = ""
    insecure = False
    i = 0
    while i < len(args):
        token = args[i]
        if token in {"-s", "--silent"}:
            silent = True
            i += 1
            continue
        if token in {"-S", "--show-error"}:
            show_error = True
            i += 1
            continue
        if token in {"-k", "--insecure"}:
            insecure = True
            i += 1
            continue
        if token in {"-L", "--location"}:
            i += 1
            continue
        if token in {"-f", "--fail"}:
            fail_mode = "fail"
            i += 1
            continue
        if token == "--fail-with-body":
            fail_mode = "fail-with-body"
            i += 1
            continue
        if token in {"-I", "--head"}:
            head_only = True
            if method == "GET":
                method = "HEAD"
            i += 1
            continue
        if token in {"-i", "--include"}:
            include_headers = True
            i += 1
            continue
        if token in {"-w", "--write-out"} and i + 1 < len(args):
            write_out = args[i + 1]
            i += 2
            continue
        if token in {"-X", "--request"} and i + 1 < len(args):
            method = args[i + 1].upper()
            i += 2
            continue
        if token in {"-H", "--header"} and i + 1 < len(args):
            raw = args[i + 1]
            key, _, value = raw.partition(":")
            headers.append((key.strip(), value.strip()))
            i += 2
            continue
        if token in {"-d", "--data", "--data-raw"} and i + 1 < len(args):
            body_data = args[i + 1].encode("utf-8")
            if method == "GET":
                method = "POST"
            i += 2
            continue
        if token == "--json" and i + 1 < len(args):
            body_data = args[i + 1].encode("utf-8")
            if method == "GET":
                method = "POST"
            headers.append(("Content-Type", "application/json"))
            i += 2
            continue
        if token in {"-o", "--output"} and i + 1 < len(args):
            output_file = args[i + 1]
            i += 2
            continue
        if token.startswith("http://") or token.startswith("https://"):
            url = token
            i += 1
            continue
        if token.startswith("-"):
            raise RuntimeError(f"unsupported_option:{token}")
        if not url:
            url = token
            i += 1
            continue
        raise RuntimeError(f"unexpected_argument:{token}")

    if not url:
        raise RuntimeError("missing_url")
    discard_output = output_file == "/dev/null"

    req = urllib.request.Request(url, data=body_data, method=method)
    for key, value in headers:
        req.add_header(key, value)

    status = 0
    body_len = 0
    start = time.monotonic()

    def _make_ssl_context() -> ssl.SSLContext:
        if insecure:
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            return ctx

        cafile = str(os.environ.get("SSL_CERT_FILE") or "").strip()
        if cafile and Path(cafile).exists():
            return ssl.create_default_context(cafile=cafile)

        # Fallback to certifi if present in the embedded pyenv.
        try:
            import certifi  # type: ignore

            return ssl.create_default_context(cafile=certifi.where())
        except Exception:
            return ssl.create_default_context()

    try:
        with urllib.request.urlopen(req, timeout=30, context=_make_ssl_context()) as resp:
            status = int(getattr(resp, "status", 0) or 0)
            reason = str(getattr(resp, "reason", "") or "")
            if include_headers or head_only:
                _print_headers(status, reason, resp.getheaders())
            if head_only:
                body = b""
            else:
                body = resp.read()
            body_len = len(body)
            if discard_output:
                pass
            elif output_file:
                target = _resolve_user_file(workdir, output_file)
                if target is None:
                    raise RuntimeError("output_path_outside_user_root")
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(body)
            elif not head_only:
                print(body.decode("utf-8", errors="replace"), end="")
        code = 0
    except urllib.error.HTTPError as ex:
        status = int(ex.code or 0)
        payload = ex.read()
        body_len = len(payload)
        if include_headers or head_only:
            _print_headers(status, str(getattr(ex, "reason", "") or ""), list(ex.headers.items()))
        if discard_output:
            pass
        elif output_file and payload:
            target = _resolve_user_file(workdir, output_file)
            if target is None:
                raise RuntimeError("output_path_outside_user_root")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(payload)
        should_print_body = not head_only and not output_file and not discard_output and (fail_mode != "fail")
        if should_print_body and payload:
            print(payload.decode("utf-8", errors="replace"), end="")
        if fail_mode in {"fail", "fail-with-body"}:
            if (not silent) or show_error:
                print(f"curl: (22) The requested URL returned error: {status}")
            code = 22
        else:
            code = 0
    except Exception as ex:
        if (not silent) or show_error:
            print(f"curl: (1) {ex}")
        status = 0
        code = 1

    if write_out:
        elapsed = time.monotonic() - start
        meta = {
            "http_code": f"{status:03d}" if status > 0 else "000",
            "response_code": f"{status:03d}" if status > 0 else "000",
            "url_effective": url,
            "size_download": str(body_len),
            "time_total": f"{elapsed:.6f}",
        }
        print(_render_write_out(write_out, meta), end="")
    return code


def _shell_exec_impl(cmd: str, raw_args: str, cwd: str) -> Dict:
    if cmd not in {"python", "pip", "curl"}:
        return {"status": "error", "error": "command_not_allowed"}

    workdir = _resolve_cwd(cwd)
    args = shlex.split(raw_args or "")
    output = io.StringIO()
    code = 0

    with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
        try:
            if cmd == "pip":
                # Heuristic guardrail:
                # People (and LLM agents) often confuse the UVC camera bindings package name.
                # - The widely used distribution name is `pupil-labs-uvc` (import name: `pyuvc`).
                # - A different/unrelated package name `uvc` may exist in other ecosystems.
                # If the user asks for both `uvc` and `pyuvc`/`pupil-labs-uvc` in one install,
                # treat `uvc` as a likely mistake to avoid hard failures on Android/offline.
                if args and args[0] == "install":
                    has_pyuvc = any(a == "pyuvc" for a in args)
                    has_pupil = any(a == "pupil-labs-uvc" for a in args)
                    if (has_pyuvc or has_pupil) and any(a == "uvc" for a in args):
                        args = [a for a in args if a != "uvc"]
                        print(
                            "note: dropped `uvc` from pip install args (likely confusion). "
                            "For UVC camera bindings use `pupil-labs-uvc` (import: `pyuvc`)."
                        )

                # Avoid attempting source builds on-device by default (no compiler toolchain).
                # Users can override by explicitly passing --no-binary/--only-binary themselves.
                if args and args[0] == "install":
                    has_only_binary = any(a.startswith("--only-binary") for a in args)
                    has_no_binary = any(a.startswith("--no-binary") for a in args)
                    if not has_only_binary and not has_no_binary:
                        args = ["install", "--only-binary=:all:"] + args[1:]

                # pip build isolation uses temp directories; ensure they point to app-writable paths.
                tmp_dir = (workdir / ".tmp").resolve()
                cache_dir = (workdir / ".cache" / "pip").resolve()
                tmp_dir.mkdir(parents=True, exist_ok=True)
                cache_dir.mkdir(parents=True, exist_ok=True)
                os.environ["TMPDIR"] = str(tmp_dir)
                os.environ["TMP"] = str(tmp_dir)
                os.environ["TEMP"] = str(tmp_dir)
                os.environ["PIP_CACHE_DIR"] = str(cache_dir)
                os.environ.setdefault("PIP_DISABLE_PIP_VERSION_CHECK", "1")

                # pip spawns subprocesses (PEP 517 build isolation). In embedded Python,
                # sys.executable may be empty, which makes pip fail with Errno 13 on ''.
                if not getattr(sys, "executable", ""):
                    cand = (os.environ.get("KUGUTZ_PYTHON_EXE") or "").strip()
                    if not cand:
                        nat = (os.environ.get("KUGUTZ_NATIVELIB") or "").strip()
                        if nat:
                            cand = str(Path(nat) / "libkugutzpy.so")
                    if cand and Path(cand).exists():
                        sys.executable = cand
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
            elif cmd == "curl":
                code = _run_curl_args(args, workdir)
            else:
                if not args:
                    raise RuntimeError("interactive_not_supported")
                if args[0] in {"-V", "--version"}:
                    print(sys.version)
                elif args[0] == "-":
                    raise RuntimeError("stdin_not_supported (use: python -c \"...\" or run a script file)")
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
    # device_api performs its own permission flow via Kotlin /permissions/*.
    if tool_name == "device_api":
        result = TOOL_ROUTER.invoke(tool_name, args)
        _emit_log("tool_invoked", {"tool": tool_name, "result": result})
        return result

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
            session_id = str((query.get("session_id") or [""])[0] or "").strip()
            try:
                limit = int((query.get("limit") or ["50"])[0])
            except Exception:
                limit = 50
            if session_id:
                self._send_json({"messages": BRAIN_RUNTIME.list_messages_for_session(session_id=session_id, limit=limit)})
            else:
                self._send_json({"messages": BRAIN_RUNTIME.list_messages(limit=limit)})
            return
        if parsed.path == "/brain/sessions":
            query = parse_qs(parsed.query or "")
            try:
                limit = int((query.get("limit") or ["50"])[0])
            except Exception:
                limit = 50
            self._send_json({"sessions": BRAIN_RUNTIME.list_sessions(limit=limit)})
            return
        if parsed.path.startswith("/vault/credentials/"):
            name = parsed.path.removeprefix("/vault/credentials/").strip()
            if not name:
                self._send_json({"error": "missing_name"}, status=400)
                return
            row = STORAGE.get_credential(name)
            if not row:
                self._send_json({"error": "not_found"}, status=404)
                return
            self._send_json(
                {
                    "name": row.get("name"),
                    "value": row.get("value"),
                    "updated_at": row.get("updated_at"),
                }
            )
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
                # Backward-compat: accept {messages:[{role,content},...]} and use last user message.
                messages = (payload or {}).get("messages")
                if isinstance(messages, list):
                    for msg in reversed(messages):
                        if not isinstance(msg, dict):
                            continue
                        if str(msg.get("role") or "") != "user":
                            continue
                        content = msg.get("content")
                        if isinstance(content, str) and content.strip():
                            text = content
                            break
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
        if parsed.path == "/brain/debug/comment":
            # Debug-only: insert a message into a given session without enqueuing agent work.
            sid = str((payload or {}).get("session_id") or "default").strip() or "default"
            role = str((payload or {}).get("role") or "assistant").strip() or "assistant"
            text = str((payload or {}).get("text") or "")
            meta = (payload or {}).get("meta")
            if not isinstance(meta, dict):
                meta = {}
            # Keep roles constrained; avoid confusing the dialogue builder.
            if role not in {"assistant", "user", "tool"}:
                self._send_json({"error": "invalid_role"}, status=400)
                return
            self._send_json(BRAIN_RUNTIME.debug_post_comment(session_id=sid, role=role, text=text, meta=meta))
            return
        if parsed.path.startswith("/vault/credentials/"):
            name = parsed.path.removeprefix("/vault/credentials/").strip()
            if not name:
                self._send_json({"error": "missing_name"}, status=400)
                return
            value = str((payload or {}).get("value") or "")
            STORAGE.set_credential(name, value)
            row = STORAGE.get_credential(name) or {"name": name, "updated_at": _now_ms()}
            self._send_json(
                {
                    "name": row.get("name"),
                    "updated_at": row.get("updated_at"),
                }
            )
            return

        self.send_response(404)
        self.end_headers()

    def do_DELETE(self):
        parsed = urlparse(self.path)
        if parsed.path.startswith("/vault/credentials/"):
            name = parsed.path.removeprefix("/vault/credentials/").strip()
            if not name:
                self._send_json({"error": "missing_name"}, status=400)
                return
            STORAGE.delete_credential(name)
            self._send_json({"status": "ok"})
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
