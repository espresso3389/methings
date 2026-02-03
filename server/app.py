"""Local HTTP service scaffold for Android Vive Python."""

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse
import asyncio
import json
import time
from typing import Dict
from pathlib import Path
import base64
import hashlib
import secrets
import urllib.parse
import requests

from storage.db import Storage
from providers.webhooks import WebhookSender
from tools.router import ToolRouter

app = FastAPI()

LOG_QUEUE: asyncio.Queue = asyncio.Queue()
data_dir = Path(__file__).parent / "data"
storage = Storage(data_dir / "app.db")
webhooks = WebhookSender(storage)
tool_router = ToolRouter(data_dir)


def _now_ms() -> int:
    return int(time.time() * 1000)


async def _log(event: str, data: Dict):
    await LOG_QUEUE.put({"event": event, "data": data, "ts": _now_ms()})
    storage.add_audit(event, json.dumps(data))


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/sessions")
async def create_session():
    session_id = storage.create_session()
    await _log("session_created", {"id": session_id})
    return {"id": session_id}


@app.get("/sessions/{session_id}")
async def get_session(session_id: str):
    session = storage.get_session(session_id)
    if not session:
        raise HTTPException(status_code=404, detail="session_not_found")
    return session


@app.post("/sessions/{session_id}/messages")
async def add_message(session_id: str, payload: Dict):
    if not storage.get_session(session_id):
        raise HTTPException(status_code=404, detail="session_not_found")
    message_id = storage.add_message(
        session_id=session_id,
        role=payload.get("role", "user"),
        content=payload.get("content", ""),
    )
    await _log("message", {"session": session_id, "message_id": message_id})
    return {"status": "queued"}


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


@app.get("/webhooks")
async def get_webhooks():
    return storage.get_webhooks()


@app.post("/webhooks")
async def set_webhook(payload: Dict):
    provider = payload.get("provider")
    url = payload.get("url")
    enabled = bool(payload.get("enabled", True))
    if provider not in ("slack", "discord"):
        raise HTTPException(status_code=400, detail="unsupported_provider")
    storage.set_webhook(provider, url, enabled)
    await _log("webhook_set", {"provider": provider, "enabled": enabled})
    return {"provider": provider, "enabled": enabled}


@app.post("/webhooks/test")
async def test_webhook(payload: Dict):
    provider = payload.get("provider")
    message = payload.get("message", "Test from Android Vive Python")
    result = webhooks.send(provider, message)
    if not result:
        raise HTTPException(status_code=400, detail="webhook_failed")
    await _log("webhook_test", {"provider": provider})
    return {"status": "sent"}


@app.get("/audit/recent")
async def audit_recent(limit: int = 50):
    return {"events": storage.get_audit(limit)}


@app.post("/auth/{provider}/config")
async def auth_config(provider: str, payload: Dict):
    storage.set_oauth_config(provider, payload)
    await _log("oauth_config_set", {"provider": provider})
    return {"status": "ok"}


@app.get("/auth/{provider}/status")
async def auth_status(provider: str):
    token = storage.get_oauth_token(provider)
    return {"connected": bool(token)}


def _pkce_pair() -> Dict[str, str]:
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(32)).decode("utf-8").rstrip("=")
    digest = hashlib.sha256(verifier.encode("utf-8")).digest()
    challenge = base64.urlsafe_b64encode(digest).decode("utf-8").rstrip("=")
    return {"verifier": verifier, "challenge": challenge}


@app.post("/auth/{provider}/start")
async def auth_start(provider: str, payload: Dict = None):
    config = storage.get_oauth_config(provider)
    if not config or not config.get("auth_url"):
        raise HTTPException(status_code=400, detail="oauth_not_configured")

    pkce = _pkce_pair()
    state = secrets.token_urlsafe(16)
    storage.save_oauth_state(state, provider, pkce["verifier"])

    redirect_uri = config.get("redirect_uri") or "androidvivepython://oauth"
    scope = config.get("scope") or ""
    params = {
        "response_type": "code",
        "client_id": config.get("client_id", ""),
        "redirect_uri": redirect_uri,
        "scope": scope,
        "state": state,
        "code_challenge": pkce["challenge"],
        "code_challenge_method": "S256",
    }
    auth_url = config.get("auth_url") + "?" + urllib.parse.urlencode(params)
    await _log("oauth_start", {"provider": provider})
    return {"auth_url": auth_url, "state": state}


async def _auth_exchange(provider: str, code: str, state: str):
    state_row = storage.get_oauth_state(state)
    if not state_row or state_row.get("provider") != provider:
        raise HTTPException(status_code=400, detail="invalid_state")

    config = storage.get_oauth_config(provider)
    if not config or not config.get("token_url"):
        raise HTTPException(status_code=400, detail="oauth_not_configured")

    redirect_uri = config.get("redirect_uri") or "androidvivepython://oauth"
    data = {
        "grant_type": "authorization_code",
        "client_id": config.get("client_id", ""),
        "code": code,
        "redirect_uri": redirect_uri,
        "code_verifier": state_row.get("code_verifier"),
    }
    if config.get("client_secret"):
        data["client_secret"] = config.get("client_secret")

    try:
        resp = requests.post(config.get("token_url"), data=data, timeout=8)
        if not (200 <= resp.status_code < 300):
            raise HTTPException(status_code=400, detail="token_exchange_failed")
        token = resp.json()
        expires_in = token.get("expires_in")
        expires_at = _now_ms() + int(expires_in) * 1000 if expires_in else None
        storage.save_oauth_token(provider, {
            "access_token": token.get("access_token"),
            "refresh_token": token.get("refresh_token"),
            "expires_at": expires_at,
        })
        storage.delete_oauth_state(state)
        await _log("oauth_connected", {"provider": provider})
        return {"status": "ok"}
    except requests.RequestException:
        raise HTTPException(status_code=400, detail="token_exchange_failed")


@app.post("/auth/{provider}/callback")
async def auth_callback(provider: str, payload: Dict):
    code = payload.get("code")
    state = payload.get("state")
    if not code or not state:
        raise HTTPException(status_code=400, detail="missing_code_or_state")

    return await _auth_exchange(provider, code, state)


@app.post("/auth/callback")
async def auth_callback_generic(payload: Dict):
    code = payload.get("code")
    state = payload.get("state")
    if not code or not state:
        raise HTTPException(status_code=400, detail="missing_code_or_state")
    state_row = storage.get_oauth_state(state)
    if not state_row:
        raise HTTPException(status_code=400, detail="invalid_state")
    provider = state_row.get("provider")
    return await _auth_exchange(provider, code, state)


@app.post("/keys/{provider}")
async def set_api_key(provider: str, payload: Dict):
    api_key = payload.get("api_key", "").strip()
    if not api_key:
        raise HTTPException(status_code=400, detail="missing_api_key")
    storage.set_api_key(provider, api_key)
    await _log("api_key_set", {"provider": provider})
    return {"status": "ok"}


@app.get("/keys/{provider}/status")
async def api_key_status(provider: str):
    key = storage.get_api_key(provider)
    return {"configured": bool(key and key.get("api_key"))}


@app.delete("/keys/{provider}")
async def delete_api_key(provider: str):
    storage.delete_api_key(provider)
    await _log("api_key_deleted", {"provider": provider})
    return {"status": "ok"}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8765)
