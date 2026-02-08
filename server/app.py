"""Local HTTP service scaffold for methings."""

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
import asyncio
import json
import time
import os
import sys
import threading
import subprocess
import signal
import secrets
import socket
import shutil
from typing import Dict
from pathlib import Path
import contextlib
import io
import runpy
import shlex
import re
import urllib.request
import urllib.error

from agents.runtime import BrainRuntime
from storage.db import Storage
from tools.router import ToolRouter

app = FastAPI()
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
    allow_credentials=False,
)

LOG_QUEUE: asyncio.Queue = asyncio.Queue()
base_dir = Path(__file__).parent.parent
legacy_data_dir = Path(__file__).parent / "data"
protected_dir = base_dir / "protected"
protected_dir.mkdir(parents=True, exist_ok=True)
if legacy_data_dir.exists():
    for entry in legacy_data_dir.iterdir():
        dest = protected_dir / entry.name
        if dest.exists():
            continue
        try:
            shutil.move(str(entry), str(dest))
        except Exception:
            pass
data_dir = protected_dir
storage = Storage(data_dir / "app.db")
tool_router = ToolRouter(data_dir)
user_dir = base_dir / "user"
user_dir.mkdir(parents=True, exist_ok=True)

legacy_www = base_dir / "www"
legacy_python = base_dir / "python"
if legacy_www.exists() and not (user_dir / "www").exists():
    try:
        shutil.move(str(legacy_www), str(user_dir / "www"))
    except Exception:
        pass
if legacy_python.exists() and not (user_dir / "python").exists():
    try:
        shutil.move(str(legacy_python), str(user_dir / "python"))
    except Exception:
        pass

python_dir = user_dir / "python"
program_dir = python_dir / "apps"
program_dir.mkdir(parents=True, exist_ok=True)
content_dir = user_dir / "www"
content_dir.mkdir(parents=True, exist_ok=True)
app.mount("/ui", StaticFiles(directory=content_dir, html=True), name="ui")

_PROGRAMS: Dict[str, Dict] = {}
_PROGRAM_LOCK = threading.Lock()
_UI_VERSION = 0


def _now_ms() -> int:
    return int(time.time() * 1000)


async def _log(event: str, data: Dict):
    await LOG_QUEUE.put({"event": event, "data": data, "ts": _now_ms()})
    storage.add_audit(event, json.dumps(data))


@app.middleware("http")
async def add_ui_cache_headers(request, call_next):
    response = await call_next(request)
    if request.url.path.startswith("/ui/"):
        if request.url.path.endswith(".html") or request.url.path.endswith("/"):
            response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
        else:
            response.headers["Cache-Control"] = "no-cache"
    return response


def _emit_log(event: str, data: Dict):
    try:
        LOG_QUEUE.put_nowait({"event": event, "data": data, "ts": _now_ms()})
    except asyncio.QueueFull:
        pass
    storage.add_audit(event, json.dumps(data))


def _start_ui_watcher():
    def _scan():
        latest = 0
        for root, _, files in os.walk(content_dir):
            for name in files:
                try:
                    mtime = int(Path(root, name).stat().st_mtime * 1000)
                    if mtime > latest:
                        latest = mtime
                except Exception:
                    continue
        return latest

    def _worker():
        global _UI_VERSION
        _UI_VERSION = _scan()
        while True:
            try:
                latest = _scan()
                if latest != _UI_VERSION:
                    _UI_VERSION = latest
                time.sleep(2)
            except Exception:
                time.sleep(2)

    threading.Thread(target=_worker, daemon=True).start()


def _spawn_program(code: str, args: Dict) -> Dict:
    program_id = f"p_{_now_ms()}_{secrets.token_hex(4)}"
    program_path = program_dir / f"{program_id}.py"
    program_path.write_text(code, encoding="utf-8")

    env = dict(os.environ)
    env.setdefault("PYTHONUNBUFFERED", "1")
    python_exe = sys.executable
    if not python_exe:
        python_home = env.get("PYTHONHOME")
        if python_home:
            candidate = str(Path(python_home) / "bin" / "python")
            python_exe = candidate
    python_exe = python_exe or env.get("PYTHON") or "python"
    argv = args.get("argv") if isinstance(args, dict) else None
    if not isinstance(argv, list):
        argv = []
    argv = [str(a) for a in argv]

    popen = subprocess.Popen(
        [python_exe, str(program_path), *argv],
        cwd=str(program_dir),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
    )

    entry = {
        "id": program_id,
        "path": str(program_path),
        "pid": popen.pid,
        "started_at": _now_ms(),
        "process": popen,
    }

    def _read_stream(stream, name: str):
        for line in iter(stream.readline, ""):
            _emit_log("program_output", {"id": program_id, "stream": name, "line": line.rstrip("\n")})
        stream.close()

    threading.Thread(target=_read_stream, args=(popen.stdout, "stdout"), daemon=True).start()
    threading.Thread(target=_read_stream, args=(popen.stderr, "stderr"), daemon=True).start()

    def _waiter():
        code = popen.wait()
        _emit_log("program_exit", {"id": program_id, "exit_code": code})
        with _PROGRAM_LOCK:
            if program_id in _PROGRAMS:
                _PROGRAMS[program_id]["exit_code"] = code
                _PROGRAMS[program_id]["stopped_at"] = _now_ms()

    threading.Thread(target=_waiter, daemon=True).start()
    return entry


def _vault_request(command: str, name: str, payload: str = "") -> str:
    try:
        with socket.create_connection(("127.0.0.1", 8766), timeout=2.0) as sock:
            msg = f"{command} {name} {payload}".strip() + "\n"
            sock.sendall(msg.encode("utf-8"))
            data = sock.recv(65536).decode("utf-8").strip()
            if not data.startswith("OK "):
                return ""
            return data[3:]
    except Exception:
        return ""


def _resolve_user_cwd(cwd: str) -> Path:
    if not cwd:
        resolved = user_dir
    elif Path(cwd).is_absolute():
        resolved = Path(cwd)
    else:
        resolved = user_dir / cwd.lstrip("/")
    try:
        resolved = resolved.resolve()
    except Exception:
        resolved = user_dir
    if not str(resolved).startswith(str(user_dir.resolve())):
        resolved = user_dir
    return resolved


def _resolve_user_file(workdir: Path, path: str) -> Path | None:
    target = Path(path)
    if not target.is_absolute():
        target = (workdir / target).resolve()
    else:
        target = target.resolve()
    root = user_dir.resolve()
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

    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
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

    resolved = _resolve_user_cwd(cwd)
    args = shlex.split(raw_args or "")
    output = io.StringIO()
    exit_code = 0

    with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
        try:
            if cmd == "pip":
                # Heuristic guardrail:
                # People (and LLM agents) often confuse the UVC camera bindings package name.
                # - On Android we control UVC devices via the app's USB/UVC device_api actions.
                # - A different/unrelated package name `uvc` exists in some ecosystems.
                # If the user asks for `uvc`, warn and continue; don't special-case installs.
                if args and args[0] == "install":
                    if any(a == "uvc" for a in args):
                        print(
                            "note: `pip install uvc` is usually not what you want on Android. "
                            "Use device_api USB/UVC actions (usb.*, uvc.ptz.*) for camera control."
                        )

                # Avoid attempting source builds on-device by default (no compiler toolchain).
                # Users can override by explicitly passing --no-binary/--only-binary themselves.
                if args and args[0] == "install":
                    has_only_binary = any(a.startswith("--only-binary") for a in args)
                    has_no_binary = any(a.startswith("--no-binary") for a in args)
                    if not has_only_binary and not has_no_binary:
                        args = ["install", "--only-binary=:all:"] + args[1:]

                # pip build isolation uses temp directories; ensure they point to app-writable paths.
                tmp_dir = (resolved / ".tmp").resolve()
                cache_dir = (resolved / ".cache" / "pip").resolve()
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
                    cand = (os.environ.get("METHINGS_PYTHON_EXE") or "").strip()
                    if not cand:
                        nat = (os.environ.get("METHINGS_NATIVELIB") or "").strip()
                        if nat:
                            cand = str(Path(nat) / "libmethingspy.so")
                    if cand and Path(cand).exists():
                        sys.executable = cand
                try:
                    from pip._internal.cli.main import main as pip_main
                except Exception:
                    from pip._internal import main as pip_main  # type: ignore
                exit_code = pip_main(args)
            elif cmd == "curl":
                exit_code = _run_curl_args(args, resolved)
            else:
                # Make app-provided Python helpers importable for run_python scripts.
                # The UI can reset these into <user_dir>/lib (see Reset Agent Docs).
                try:
                    lib_dir = (resolved / "lib").resolve()
                    if lib_dir.exists():
                        lib_str = str(lib_dir)
                        if lib_str not in sys.path:
                            sys.path.insert(0, lib_str)
                except Exception:
                    pass

                if not args:
                    raise RuntimeError("interactive_not_supported")
                if args[0] in {"-V", "--version"}:
                    print(sys.version)
                    exit_code = 0
                elif args[0] == "-":
                    raise RuntimeError("stdin_not_supported (use: python -c \"...\" or run a script file)")
                elif args[0] == "-c":
                    if len(args) < 2:
                        raise RuntimeError("missing_code")
                    code = args[1]
                    exec_globals = {"__name__": "__main__"}
                    exec(code, exec_globals, exec_globals)
                    exit_code = 0
                else:
                    script_path = Path(args[0])
                    if not script_path.is_absolute():
                        script_path = resolved / script_path
                    runpy.run_path(str(script_path), run_name="__main__")
                    exit_code = 0
        except SystemExit as exc:
            exit_code = int(getattr(exc, "code", 0) or 0)
        except Exception as exc:
            exit_code = 1
            print(f"error: {exc}")

    return {"status": "ok", "code": exit_code, "output": output.getvalue()}


def _create_permission_request_sync(
    tool: str,
    detail: str = "",
    scope: str = "once",
    duration_min: int = 0,
) -> Dict:
    expires_at = None
    if scope == "session" and duration_min > 0:
        expires_at = _now_ms() + duration_min * 60 * 1000
    request_id = storage.create_permission_request(
        tool=tool,
        detail=detail,
        scope=scope,
        expires_at=expires_at,
    )
    request = storage.get_permission_request(request_id)
    _emit_log("permission_requested", request)
    return request


def _tool_invoke_impl(
    tool_name: str,
    args: Dict,
    request_id: str = None,
    detail: str = "",
) -> Dict:
    # Some tools perform their own permission flow in the Kotlin control plane.
    # - device_api: device capabilities are gated by Kotlin /permissions/*
    # - cloud_request: cloud uploads are gated by Kotlin /cloud/request (cloud.media_upload)
    if tool_name in {"device_api", "cloud_request"}:
        result = tool_router.invoke(tool_name, args)
        _emit_log("tool_invoked", {"tool": tool_name, "result": result})
        return result

    if not request_id:
        request = _create_permission_request_sync(tool_name, detail=detail)
        return {"status": "permission_required", "request": request}

    request = storage.get_permission_request(request_id)
    if not request or request.get("status") != "approved":
        return {"status": "permission_required", "request": request}

    expires_at = request.get("expires_at")
    if expires_at and _now_ms() > int(expires_at):
        storage.update_permission_status(request_id, "expired")
        return {"status": "permission_expired", "request": request}

    result = tool_router.invoke(tool_name, args)
    _emit_log("tool_invoked", {"tool": tool_name, "result": result})

    if request.get("scope") == "once":
        storage.mark_permission_used(request_id)
    return result


BRAIN_RUNTIME = BrainRuntime(
    user_dir=user_dir,
    storage=storage,
    emit_log=_emit_log,
    shell_exec=_shell_exec_impl,
    tool_invoke=_tool_invoke_impl,
)


@app.get("/health")
async def health():
    return {"status": "ok", "db": storage.encryption_status()}


@app.get("/ui/version")
async def ui_version():
    return {"version": _UI_VERSION}


@app.post("/shutdown")
async def shutdown():
    server = globals().get("_UVICORN_SERVER")
    if server is None:
        return {"status": "no_server"}
    server.should_exit = True
    server.force_exit = True
    try:
        loop = asyncio.get_running_loop()
        loop.call_soon(lambda: setattr(server, "should_exit", True))
        loop.call_later(0.2, lambda: setattr(server, "force_exit", True))
    except RuntimeError:
        pass
    return {"status": "stopping"}


@app.post("/programs/start")
async def programs_start(payload: Dict):
    code = payload.get("code", "")
    if not code.strip():
        raise HTTPException(status_code=400, detail="missing_code")
    args = payload.get("args") or {}
    entry = _spawn_program(code, args)
    with _PROGRAM_LOCK:
        _PROGRAMS[entry["id"]] = entry
    await _log("program_start", {"id": entry["id"], "pid": entry["pid"]})
    return {"id": entry["id"], "pid": entry["pid"], "path": entry["path"]}


@app.post("/shell/exec")
async def shell_exec(payload: Dict):
    cmd = payload.get("cmd", "")
    raw_args = payload.get("args", "") or ""
    cwd = payload.get("cwd", "") or ""
    result = _shell_exec_impl(cmd, raw_args, cwd)
    if result.get("status") == "error" and result.get("error") == "command_not_allowed":
        raise HTTPException(status_code=403, detail="command_not_allowed")
    return result


@app.get("/programs")
async def programs_list():
    with _PROGRAM_LOCK:
        snapshot = {
            pid: {
                "id": pid,
                "pid": meta.get("pid"),
                "path": meta.get("path"),
                "started_at": meta.get("started_at"),
                "stopped_at": meta.get("stopped_at"),
                "exit_code": meta.get("exit_code"),
                "running": meta.get("process") is not None and meta["process"].poll() is None,
            }
            for pid, meta in _PROGRAMS.items()
        }
    return {"programs": snapshot}


@app.post("/programs/{program_id}/stop")
async def programs_stop(program_id: str):
    with _PROGRAM_LOCK:
        meta = _PROGRAMS.get(program_id)
    if not meta or not meta.get("process"):
        raise HTTPException(status_code=404, detail="program_not_found")

    proc: subprocess.Popen = meta["process"]
    if proc.poll() is not None:
        return {"status": "already_stopped"}

    try:
        if os.name == "nt":
            proc.terminate()
        else:
            proc.send_signal(signal.SIGTERM)
        await _log("program_stop", {"id": program_id, "pid": proc.pid})
    except Exception:
        raise HTTPException(status_code=500, detail="stop_failed")

    return {"status": "stopping"}


@app.post("/permissions/request")
async def permission_request(payload: Dict):
    scope = payload.get("scope", "once")
    duration_min = int(payload.get("duration_min", 0) or 0)
    expires_at = None
    if scope == "session" and duration_min > 0:
        expires_at = _now_ms() + duration_min * 60 * 1000
    request_id = storage.create_permission_request(
        tool=payload.get("tool"),
        detail=payload.get("detail"),
        scope=scope,
        expires_at=expires_at,
    )
    request = storage.get_permission_request(request_id)
    await _log("permission_requested", request)
    return request


@app.get("/permissions/pending")
async def permission_pending():
    pending = storage.get_pending_permissions()
    return {"pending": pending}


@app.post("/permissions/{request_id}/approve")
async def permission_approve(request_id: str):
    if not storage.get_permission_request(request_id):
        raise HTTPException(status_code=404, detail="permission_not_found")
    storage.update_permission_status(request_id, "approved")
    request = storage.get_permission_request(request_id)
    await _log("permission_approved", request)
    return request


@app.get("/permissions/{request_id}")
async def permission_status(request_id: str):
    request = storage.get_permission_request(request_id)
    if not request:
        raise HTTPException(status_code=404, detail="permission_not_found")
    return request


@app.post("/permissions/{request_id}/deny")
async def permission_deny(request_id: str):
    if not storage.get_permission_request(request_id):
        raise HTTPException(status_code=404, detail="permission_not_found")
    storage.update_permission_status(request_id, "denied")
    request = storage.get_permission_request(request_id)
    await _log("permission_denied", request)
    return request


@app.post("/tools/{tool_name}/invoke")
async def tool_invoke(tool_name: str, payload: Dict):
    request_id = payload.get("request_id") or payload.get("requestId")
    args = payload.get("args", {})
    result = _tool_invoke_impl(
        tool_name=tool_name,
        args=args,
        request_id=request_id,
        detail=payload.get("detail", ""),
    )
    if result.get("status") in {"permission_required", "permission_expired"}:
        return JSONResponse(status_code=403, content=result)
    return result


@app.get("/logs/stream")
async def logs_stream():
    async def event_generator():
        while True:
            item = await LOG_QUEUE.get()
            data = json.dumps(item)
            yield f"data: {data}\n\n"

    return StreamingResponse(event_generator(), media_type="text/event-stream")


@app.get("/audit/recent")
async def audit_recent(limit: int = 50):
    return {"events": storage.get_audit(limit)}


@app.get("/brain/status")
async def brain_status():
    return BRAIN_RUNTIME.status()


@app.get("/brain/config")
async def brain_config():
    return BRAIN_RUNTIME.get_config()


@app.post("/brain/config")
async def brain_set_config(payload: Dict):
    return BRAIN_RUNTIME.update_config(payload or {})


@app.post("/brain/start")
async def brain_start():
    return BRAIN_RUNTIME.start()


@app.post("/brain/stop")
async def brain_stop():
    return BRAIN_RUNTIME.stop()


@app.post("/brain/inbox/chat")
async def brain_inbox_chat(payload: Dict):
    text = str(payload.get("text") or "")
    meta = payload.get("meta") if isinstance(payload.get("meta"), dict) else {}
    if not text.strip():
        # Backward-compat: accept {messages:[{role,content},...]} and use last user message.
        messages = payload.get("messages")
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
        raise HTTPException(status_code=400, detail="missing_text")
    return BRAIN_RUNTIME.enqueue_chat(text, meta=meta)


@app.post("/brain/inbox/event")
async def brain_inbox_event(payload: Dict):
    name = str(payload.get("name") or "")
    body = payload.get("payload") if isinstance(payload.get("payload"), dict) else {}
    if not name.strip():
        raise HTTPException(status_code=400, detail="missing_name")
    return BRAIN_RUNTIME.enqueue_event(name=name, payload=body)


@app.get("/brain/messages")
async def brain_messages(limit: int = 50, session_id: str = ""):
    sid = (session_id or "").strip()
    if sid:
        return {"messages": BRAIN_RUNTIME.list_messages_for_session(session_id=sid, limit=limit)}
    return {"messages": BRAIN_RUNTIME.list_messages(limit=limit)}

@app.get("/brain/sessions")
async def brain_sessions(limit: int = 50):
    return {"sessions": BRAIN_RUNTIME.list_sessions(limit=limit)}


def _require_permission(tool: str, permission_id: str) -> Dict:
    if not permission_id:
        raise HTTPException(status_code=403, detail="permission_required")
    request = storage.get_permission_request(permission_id)
    if not request or request.get("tool") != tool:
        raise HTTPException(status_code=403, detail="invalid_permission")
    if request.get("status") != "approved":
        raise HTTPException(status_code=403, detail="permission_not_approved")
    expires_at = request.get("expires_at")
    if expires_at and _now_ms() > int(expires_at):
        storage.update_permission_status(permission_id, "expired")
        raise HTTPException(status_code=403, detail="permission_expired")
    if request.get("scope") == "once":
        storage.mark_permission_used(permission_id)
    return request



@app.get("/vault/credentials")
async def vault_list(permission_id: str):
    _require_permission("credentials", permission_id)
    return {"credentials": storage.list_credentials()}


@app.get("/vault/credentials/{name}")
async def vault_get(name: str, permission_id: str):
    _require_permission("credentials", permission_id)
    row = storage.get_credential(name)
    if not row:
        raise HTTPException(status_code=404, detail="credential_not_found")
    return {"name": row.get("name"), "value": row.get("value"), "updated_at": row.get("updated_at")}


@app.post("/vault/credentials/{name}")
async def vault_set(name: str, payload: Dict):
    permission_id = payload.get("permission_id")
    _require_permission("credentials", permission_id)
    value = payload.get("value", "")
    if not value:
        raise HTTPException(status_code=400, detail="missing_value")
    storage.set_credential(name, value)
    await _log("credential_set", {"name": name})
    return {"status": "ok"}


@app.delete("/vault/credentials/{name}")
async def vault_delete(name: str, payload: Dict):
    permission_id = payload.get("permission_id")
    _require_permission("credentials", permission_id)
    storage.delete_credential(name)
    await _log("credential_deleted", {"name": name})
    return {"status": "ok"}


def _require_service_access(name: str, code_hash: str, token: str) -> Dict:
    service = storage.get_service(name)
    if not service:
        raise HTTPException(status_code=404, detail="service_not_found")
    if service.get("code_hash") != code_hash:
        raise HTTPException(status_code=403, detail="code_hash_mismatch")
    if service.get("token") != token:
        raise HTTPException(status_code=403, detail="invalid_service_token")
    return service


@app.post("/services/register")
async def register_service(payload: Dict):
    name = (payload.get("name") or "").strip()
    code_hash = (payload.get("code_hash") or "").strip()
    permission_id = payload.get("permission_id")
    credential_names = payload.get("credentials") or []
    if not name or not code_hash:
        raise HTTPException(status_code=400, detail="missing_fields")
    if not isinstance(credential_names, list):
        raise HTTPException(status_code=400, detail="invalid_credentials")

    _require_permission("credentials", permission_id)
    token = secrets.token_urlsafe(16)
    _vault_request("CREATE", name)
    storage.upsert_service(name, code_hash, token)
    storage.delete_service_credentials(name)

    missing = []
    for cred in credential_names:
        cred_name = str(cred).strip()
        if not cred_name:
            continue
        row = storage.get_credential(cred_name)
        if not row:
            missing.append(cred_name)
            continue
        enc = _vault_request("ENCRYPT", name, row.get("value") or "")
        if enc:
            storage.set_service_credential(name, cred_name, enc)

    await _log("service_registered", {"name": name, "credentials": credential_names})
    return {"name": name, "token": token, "missing": missing}


@app.get("/services")
async def list_services():
    return {"services": storage.list_services()}


@app.get("/services/{name}")
async def get_service(name: str):
    service = storage.get_service(name)
    if not service:
        raise HTTPException(status_code=404, detail="service_not_found")
    return {
        "name": service.get("name"),
        "code_hash": service.get("code_hash"),
        "updated_at": service.get("updated_at"),
        "credentials": storage.list_service_credentials(name),
    }


@app.get("/services/{name}/vault/{credential}")
async def service_get_credential(name: str, credential: str, code_hash: str, token: str):
    _require_service_access(name, code_hash, token)
    row = storage.get_service_credential(name, credential)
    if not row:
        raise HTTPException(status_code=404, detail="credential_not_found")
    decrypted = _vault_request("DECRYPT", name, row.get("value") or "")
    if not decrypted:
        raise HTTPException(status_code=500, detail="vault_decrypt_failed")
    await _log("service_credential_get", {"service": name, "credential": credential})
    return {"name": row.get("name"), "value": decrypted, "updated_at": row.get("updated_at")}

if __name__ == "__main__":
    import uvicorn

    _start_ui_watcher()
    BRAIN_RUNTIME.maybe_autostart()
    config = uvicorn.Config(
        app,
        host="127.0.0.1",
        port=8765,
        log_level="info",
        lifespan="off",
    )
    server = uvicorn.Server(config)
    globals()["_UVICORN_SERVER"] = server
    server.run()
