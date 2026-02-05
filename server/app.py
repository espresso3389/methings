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
import threading
import subprocess
import signal
import secrets
import socket
import shutil
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
