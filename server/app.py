"""Local HTTP service scaffold for Kugutz."""

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
import asyncio
import json
import time
import os
import sys
import subprocess
import threading
import signal
import secrets
import socket
import hashlib
import base64
import getpass
from typing import Dict, Optional
from pathlib import Path

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
data_dir = Path(__file__).parent / "data"
storage = Storage(data_dir / "app.db")
tool_router = ToolRouter(data_dir)
program_dir = data_dir / "programs"
program_dir.mkdir(parents=True, exist_ok=True)
content_dir = Path(__file__).parent.parent / "www"
content_dir.mkdir(parents=True, exist_ok=True)
app.mount("/ui", StaticFiles(directory=content_dir, html=True), name="ui")
ssh_dir = data_dir / "ssh"
ssh_dir.mkdir(parents=True, exist_ok=True)
ssh_home_dir = data_dir
ssh_noauth_file = ssh_dir / "noauth_until"
ssh_pin_file = ssh_dir / "pin_auth"
ssh_noauth_prompt_dir = ssh_dir / "noauth_prompts"
ssh_noauth_prompt_dir.mkdir(parents=True, exist_ok=True)

_PROGRAMS: Dict[str, Dict] = {}
_PROGRAM_LOCK = threading.Lock()
_UI_VERSION = 0
_SSH_PORT = None
_SSH_LAST_ERROR = None
_SSH_LAST_ERROR_TS = None


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


@app.get("/health")
async def health():
    return {"status": "ok"}


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
    if not request_id:
        request = await permission_request({
            "tool": tool_name,
            "detail": payload.get("detail", "")
        })
        return JSONResponse(
            status_code=403,
            content={"status": "permission_required", "request": request},
        )

    request = storage.get_permission_request(request_id)
    if not request or request.get("status") != "approved":
        return JSONResponse(
            status_code=403,
            content={"status": "permission_required", "request": request},
        )

    expires_at = request.get("expires_at")
    if expires_at and _now_ms() > int(expires_at):
        storage.update_permission_status(request_id, "expired")
        return JSONResponse(
            status_code=403,
            content={"status": "permission_expired", "request": request},
        )

    args = payload.get("args", {})
    result = tool_router.invoke(tool_name, args)
    await _log("tool_invoked", {"tool": tool_name, "result": result})

    if request.get("scope") == "once":
        storage.mark_permission_used(request_id)
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


def _ssh_bin() -> Path:
    env_path = os.environ.get("DROPBEAR_BIN")
    if env_path:
        return Path(env_path)
    candidate = Path(__file__).parent.parent / "bin" / "dropbear"
    return candidate


def _ssh_keygen_bin() -> Path:
    env_path = os.environ.get("DROPBEARKEY_BIN")
    if env_path:
        return Path(env_path)
    candidate = Path(__file__).parent.parent / "bin" / "dropbearkey"
    return candidate


def _ssh_key_path() -> Path:
    return ssh_home_dir / ".ssh" / "authorized_keys"


def _ssh_pid_path() -> Path:
    return ssh_dir / "dropbear.pid"


def _ssh_host_key_paths() -> Dict[str, Path]:
    return {
        "rsa": ssh_dir / "dropbear_rsa_host_key",
        "ecdsa": ssh_dir / "dropbear_ecdsa_host_key",
        "ed25519": ssh_dir / "dropbear_ed25519_host_key",
    }


def _allow_noauth(seconds: int = 10) -> Dict:
    expires_at = int(time.time()) + max(1, seconds)
    try:
        ssh_noauth_file.write_text(str(expires_at), encoding="utf-8")
        os.chmod(ssh_noauth_file, 0o600)
    except Exception as ex:
        raise HTTPException(status_code=500, detail=f"noauth_write_failed: {ex}")
    return {"expires_at": expires_at}


def _allow_pin(pin: str, seconds: int = 10) -> Dict:
    expires_at = int(time.time()) + max(1, seconds)
    try:
        ssh_pin_file.write_text(f"{expires_at} {pin}", encoding="utf-8")
        os.chmod(ssh_pin_file, 0o600)
    except Exception as ex:
        raise HTTPException(status_code=500, detail=f"pin_write_failed: {ex}")
    return {"expires_at": expires_at}


def _clear_pin() -> None:
    try:
        if ssh_pin_file.exists():
            ssh_pin_file.unlink()
    except Exception:
        pass


def _noauth_expires() -> Optional[int]:
    try:
        if not ssh_noauth_file.exists():
            return None
        raw = ssh_noauth_file.read_text(encoding="utf-8").strip()
        if not raw:
            return None
        expires = int(raw)
        if expires <= 0:
            return None
        if time.time() > expires:
            return None
        return expires
    except Exception:
        return None


def _pin_expires() -> Optional[int]:
    try:
        if not ssh_pin_file.exists():
            return None
        raw = ssh_pin_file.read_text(encoding="utf-8").strip()
        if not raw:
            return None
        parts = raw.split()
        if not parts:
            return None
        expires = int(parts[0])
        if expires <= 0:
            return None
        if time.time() > expires:
            return None
        return expires
    except Exception:
        return None


def _read_noauth_prompt(path: Path) -> Optional[Dict]:
    try:
        raw = path.read_text(encoding="utf-8").strip()
    except Exception:
        return None
    if not raw:
        return None
    parts = raw.split("\t")
    if len(parts) < 4:
        return None
    req_id = parts[0].strip()
    user = parts[1].strip()
    addr = parts[2].strip()
    try:
        created_at = int(parts[3])
    except Exception:
        created_at = 0
    if not req_id:
        return None
    return {
        "id": req_id,
        "user": user,
        "addr": addr,
        "created_at": created_at,
    }


def _list_noauth_prompts() -> list[Dict]:
    if not ssh_noauth_prompt_dir.exists():
        return []
    now = int(time.time())
    requests = []
    for path in sorted(ssh_noauth_prompt_dir.glob("*.req"), key=lambda p: p.stat().st_mtime):
        try:
            mtime = int(path.stat().st_mtime)
        except Exception:
            mtime = now
        if now - mtime > 60:
            try:
                resp = ssh_noauth_prompt_dir / f"{path.stem}.resp"
                path.unlink(missing_ok=True)
                resp.unlink(missing_ok=True)
            except Exception:
                pass
            continue
        resp = ssh_noauth_prompt_dir / f"{path.stem}.resp"
        if resp.exists():
            continue
        req = _read_noauth_prompt(path)
        if req:
            requests.append(req)
    return requests


def _respond_noauth_prompt(req_id: str, allow: bool) -> None:
    if not ssh_noauth_prompt_dir.exists():
        ssh_noauth_prompt_dir.mkdir(parents=True, exist_ok=True)
    resp_path = ssh_noauth_prompt_dir / f"{req_id}.resp"
    resp_path.write_text("allow" if allow else "deny", encoding="utf-8")
    os.chmod(resp_path, 0o600)


def _ensure_host_keys() -> None:
    keygen = _ssh_keygen_bin()
    if not keygen.exists():
        _emit_log("ssh_keygen_missing", {"path": str(keygen)})
        return
    for algo, path in _ssh_host_key_paths().items():
        if path.exists():
            continue
        try:
            subprocess.run(
                [str(keygen), "-t", algo, "-f", str(path)],
                cwd=str(ssh_dir),
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        except Exception as ex:
            _emit_log("ssh_keygen_failed", {"algo": algo, "error": str(ex)})


def _ssh_fingerprint(key: str) -> str:
    digest = hashlib.sha256(key.encode("utf-8")).digest()
    return base64.urlsafe_b64encode(digest).decode("utf-8").rstrip("=")


def _ssh_has_pubkeys() -> bool:
    try:
        return len(storage.list_active_ssh_keys()) > 0
    except Exception:
        return False


def _ssh_pin_active() -> bool:
    expires = _pin_expires()
    if expires is None:
        return False
    return expires > int(time.time())


def _ssh_allow_noauth() -> bool:
    return not _ssh_has_pubkeys() and not _ssh_pin_active()


def _restart_dropbear_if_running() -> None:
    if not _dropbear_running():
        return
    port = _SSH_PORT if _SSH_PORT else _get_setting_int("ssh_port", 2222)
    _stop_dropbear(log_event=False)
    time.sleep(0.1)
    _start_dropbear(port, log_event=False)

def _get_setting_bool(key: str, default: bool) -> bool:
    raw = storage.get_setting(key)
    if raw is None:
        storage.set_setting(key, "true" if default else "false")
        return default
    return raw.strip().lower() in ("1", "true", "yes", "on")

def _get_setting_int(key: str, default: int) -> int:
    raw = storage.get_setting(key)
    if raw is None:
        storage.set_setting(key, str(default))
        return default
    try:
        return int(raw)
    except Exception:
        return default

def _set_setting_bool(key: str, value: bool) -> None:
    storage.set_setting(key, "true" if value else "false")

def _set_setting_int(key: str, value: int) -> None:
    storage.set_setting(key, str(value))


def _write_authorized_keys():
    keys = storage.list_active_ssh_keys()
    lines = [row["key"] for row in keys]
    key_path = _ssh_key_path()
    try:
        key_path.parent.mkdir(parents=True, exist_ok=True)
    except Exception:
        pass
    if not lines and key_path.exists():
        try:
            existing = key_path.read_text(encoding="utf-8").strip()
        except Exception:
            existing = ""
        if existing:
            try:
                os.chmod(ssh_dir, 0o700)
                os.chmod(key_path.parent, 0o700)
                os.chmod(key_path, 0o600)
            except Exception:
                pass
            return
    content = "\n".join(lines) + ("\n" if lines else "")
    key_path.write_text(content, encoding="utf-8")
    try:
        os.chmod(ssh_dir, 0o700)
        os.chmod(key_path.parent, 0o700)
        os.chmod(key_path, 0o600)
    except Exception:
        pass


def _ssh_username() -> str:
    try:
        import pwd  # type: ignore

        return pwd.getpwuid(os.getuid()).pw_name
    except Exception:
        try:
            return getpass.getuser()
        except Exception:
            return "user"


def _dropbear_running() -> bool:
    pid_path = _ssh_pid_path()
    if not pid_path.exists():
        return False
    try:
        pid = int(pid_path.read_text().strip())
    except Exception:
        return False
    try:
        os.kill(pid, 0)
        return True
    except Exception:
        return False


def _record_ssh_error(error: str | None) -> None:
    global _SSH_LAST_ERROR, _SSH_LAST_ERROR_TS
    _SSH_LAST_ERROR = error
    _SSH_LAST_ERROR_TS = _now_ms() if error else None

def _start_dropbear(port: int, log_event: bool = True) -> Dict:
    try:
        bin_path = _ssh_bin()
        if not bin_path.exists():
            raise RuntimeError("dropbear_missing")
        if _dropbear_running():
            return {"status": "already_running"}
        global _SSH_PORT
        _SSH_PORT = port
        _write_authorized_keys()
        _ensure_host_keys()
        host_keys = _ssh_host_key_paths()
        pid_path = _ssh_pid_path()
        env = dict(os.environ)
        ssh_work_dir = ssh_home_dir
        env["HOME"] = str(ssh_home_dir)
        env["PWD"] = str(ssh_work_dir)
        env["DROPBEAR_PIN_FILE"] = str(ssh_pin_file)
        if _ssh_allow_noauth():
            env["DROPBEAR_NOAUTH_FILE"] = str(ssh_noauth_file)
            env["DROPBEAR_NOAUTH_PROMPT_DIR"] = str(ssh_noauth_prompt_dir)
            env["DROPBEAR_NOAUTH_PROMPT_TIMEOUT"] = str(
                _get_setting_int("ssh_noauth_prompt_timeout", 10)
            )
        log_path = ssh_dir / "dropbear.log"

        log_fh = open(log_path, "a", encoding="utf-8", buffering=1)
        args = [
            str(bin_path),
            "-F",
            "-R",
            "-p",
            str(port),
            "-D",
            str(ssh_home_dir / ".ssh"),
            "-r",
            str(host_keys["rsa"]),
            "-r",
            str(host_keys["ecdsa"]),
            "-r",
            str(host_keys["ed25519"]),
            "-P",
            str(pid_path),
        ]
        verbose_level = os.environ.get("DROPBEAR_VERBOSE", "").strip()
        if verbose_level == "1":
            args.append("-v")
        elif verbose_level == "2":
            args.extend(["-v", "-v"])
        elif verbose_level == "3":
            args.extend(["-v", "-v", "-v"])
        proc = subprocess.Popen(
            args,
            cwd=str(ssh_work_dir),
            env=env,
            stdout=log_fh,
            stderr=log_fh,
        )
        time.sleep(0.2)
        if proc.poll() is not None:
            raise RuntimeError(f"dropbear_exited:{proc.returncode}")
    except Exception as ex:
        _record_ssh_error(str(ex))
        _emit_log("ssh_start_failed", {"error": str(ex)})
        raise HTTPException(status_code=500, detail=f"ssh_start_failed: {ex}")
    if log_event:
        asyncio.create_task(_log("ssh_started", {"port": port, "pid": proc.pid}))
    _record_ssh_error(None)
    return {"status": "started", "port": port, "pid": proc.pid}

def _stop_dropbear(log_event: bool = True) -> Dict:
    pid_path = _ssh_pid_path()
    if not pid_path.exists():
        return {"status": "not_running"}
    try:
        pid = int(pid_path.read_text().strip())
    except Exception:
        return {"status": "not_running"}
    try:
        os.kill(pid, signal.SIGTERM)
    except Exception:
        pass
    if log_event:
        asyncio.create_task(_log("ssh_stopped", {"pid": pid}))
    _record_ssh_error(None)
    return {"status": "stopping"}


@app.post("/ssh/keys")
async def ssh_add_key(payload: Dict):
    permission_id = payload.get("permission_id")
    _require_permission("ssh", permission_id)
    key = (payload.get("key") or "").strip()
    if not key:
        raise HTTPException(status_code=400, detail="missing_key")
    label = (payload.get("label") or "").strip() or None
    ttl_min = payload.get("ttl_min")
    expires_at = None
    if ttl_min is not None:
        try:
            ttl_min = int(ttl_min)
            if ttl_min > 0:
                expires_at = _now_ms() + ttl_min * 60 * 1000
        except Exception:
            pass
    fingerprint = _ssh_fingerprint(key)
    storage.add_ssh_key(fingerprint, key, label, expires_at)
    _write_authorized_keys()
    _restart_dropbear_if_running()
    await _log("ssh_key_added", {"fingerprint": fingerprint, "label": label})
    return {"fingerprint": fingerprint, "expires_at": expires_at}


@app.get("/ssh/keys")
async def ssh_list_keys(permission_id: str):
    _require_permission("ssh", permission_id)
    return {"keys": storage.list_ssh_keys()}


@app.delete("/ssh/keys/{fingerprint}")
async def ssh_delete_key(fingerprint: str, permission_id: str):
    _require_permission("ssh", permission_id)
    storage.delete_ssh_key(fingerprint)
    _write_authorized_keys()
    _restart_dropbear_if_running()
    await _log("ssh_key_deleted", {"fingerprint": fingerprint})
    return {"status": "ok"}


@app.post("/ssh/start")
async def ssh_start(payload: Dict):
    permission_id = payload.get("permission_id")
    _require_permission("ssh", permission_id)
    port = int(payload.get("port") or 2222)
    return _start_dropbear(port)


@app.post("/ssh/stop")
async def ssh_stop(payload: Dict):
    permission_id = payload.get("permission_id")
    _require_permission("ssh", permission_id)
    return _stop_dropbear()


@app.post("/ssh/noauth/allow")
async def ssh_noauth_allow(payload: Dict):
    permission_id = payload.get("permission_id")
    ui_consent = payload.get("ui_consent") is True
    if not ui_consent:
        _require_permission("ssh_noauth", permission_id)
    if not _ssh_allow_noauth():
        raise HTTPException(status_code=409, detail="noauth_disabled_by_auth")
    seconds = payload.get("seconds")
    try:
        seconds = int(seconds) if seconds is not None else 10
    except Exception:
        seconds = 10
    if seconds <= 0:
        raise HTTPException(status_code=400, detail="invalid_seconds")
    if not _dropbear_running():
        _start_dropbear(_SSH_PORT if _SSH_PORT else _get_setting_int("ssh_port", 2222))
    result = _allow_noauth(seconds)
    await _log("ssh_noauth_allowed", {"expires_at": result["expires_at"], "seconds": seconds})
    return {"status": "ok", **result}


@app.get("/ssh/noauth/requests")
async def ssh_noauth_requests():
    return {"requests": _list_noauth_prompts()}


@app.post("/ssh/noauth/respond")
async def ssh_noauth_respond(payload: Dict):
    permission_id = payload.get("permission_id")
    ui_consent = payload.get("ui_consent") is True
    if not ui_consent:
        _require_permission("ssh_noauth", permission_id)
    if not _ssh_allow_noauth():
        raise HTTPException(status_code=409, detail="noauth_disabled_by_auth")
    req_id = (payload.get("id") or "").strip()
    if not req_id:
        raise HTTPException(status_code=400, detail="missing_id")
    allow = payload.get("allow") is True
    _respond_noauth_prompt(req_id, allow)
    await _log("ssh_noauth_prompt", {"id": req_id, "allow": allow})
    return {"status": "ok"}


@app.post("/ssh/pin/allow")
async def ssh_pin_allow(payload: Dict):
    permission_id = payload.get("permission_id")
    ui_consent = payload.get("ui_consent") is True
    if not ui_consent:
        _require_permission("ssh_pin", permission_id)
    pin = (payload.get("pin") or "").strip()
    if not pin or not pin.isdigit() or len(pin) != 6:
        raise HTTPException(status_code=400, detail="invalid_pin")
    seconds = payload.get("seconds")
    try:
        seconds = int(seconds) if seconds is not None else 30
    except Exception:
        seconds = 30
    if seconds <= 0:
        raise HTTPException(status_code=400, detail="invalid_seconds")
    if not _dropbear_running():
        _start_dropbear(_SSH_PORT if _SSH_PORT else _get_setting_int("ssh_port", 2222))
    result = _allow_pin(pin, seconds)
    _restart_dropbear_if_running()
    await _log("ssh_pin_allowed", {"expires_at": result["expires_at"], "seconds": seconds})
    return {"status": "ok", **result}


@app.post("/ssh/pin/clear")
async def ssh_pin_clear(payload: Dict):
    permission_id = payload.get("permission_id")
    ui_consent = payload.get("ui_consent") is True
    if not ui_consent:
        _require_permission("ssh_pin", permission_id)
    _clear_pin()
    _restart_dropbear_if_running()
    await _log("ssh_pin_cleared", {})
    return {"status": "ok"}


@app.get("/ssh/status")
async def ssh_status():
    port = _SSH_PORT if _SSH_PORT else _get_setting_int("ssh_port", 2222)
    return {
        "running": _dropbear_running(),
        "host_key": {k: str(v) for k, v in _ssh_host_key_paths().items()},
        "port": port,
        "enabled": _get_setting_bool("ssh_enabled", True),
        "username": _ssh_username(),
        "noauth_until": _noauth_expires(),
        "pin_until": _pin_expires(),
        "last_error": _SSH_LAST_ERROR,
        "last_error_ts": _SSH_LAST_ERROR_TS,
    }


@app.post("/ssh/config")
async def ssh_config(payload: Dict):
    permission_id = payload.get("permission_id")
    ui_consent = payload.get("ui_consent") is True
    if not ui_consent:
        _require_permission("ssh", permission_id)
    enabled = payload.get("enabled")
    port = payload.get("port")
    if isinstance(port, str) and port.strip().isdigit():
        port = int(port.strip())
    if isinstance(port, int) and port > 0:
        _set_setting_int("ssh_port", port)
        global _SSH_PORT
        _SSH_PORT = port
    if isinstance(enabled, bool):
        _set_setting_bool("ssh_enabled", enabled)
    if enabled is True:
        result = _start_dropbear(_SSH_PORT)
    elif enabled is False:
        result = _stop_dropbear()
    else:
        result = {"status": "ok"}
    return {
        "enabled": _get_setting_bool("ssh_enabled", True),
        "port": _SSH_PORT,
        **result,
    }


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

def _init_ssh_settings():
    global _SSH_PORT
    _SSH_PORT = _get_setting_int("ssh_port", 2222)
    enabled = _get_setting_bool("ssh_enabled", True)
    if enabled:
        try:
            _start_dropbear(_SSH_PORT, log_event=False)
        except Exception:
            pass


if __name__ == "__main__":
    import uvicorn

    _start_ui_watcher()
    _init_ssh_settings()
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
