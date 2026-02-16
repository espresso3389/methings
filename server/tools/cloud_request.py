import json
import os
import time
import urllib.error
import urllib.request
from typing import Any, Dict, List


class CloudRequestTool:
    """
    Thin Python wrapper around Kotlin's /cloud/request endpoint.

    Supports two input styles:
    - Raw request passthrough (existing behavior):
      {request:{url, method, headers, json/body/...}}
    - Provider adapter mode:
      {
        adapter:{provider, task, model, text/messages/image_path(s)/audio_path,...}
      }

    The model can use adapter mode to avoid per-provider wire-format differences.
    Kotlin still enforces permission gating and placeholder expansion server-side.
    """

    _PROVIDER_DEFAULTS: Dict[str, Dict[str, str]] = {
        "openai": {
            "credential": "openai_api_key",
            "chat_url": "https://api.openai.com/v1/chat/completions",
            "responses_url": "https://api.openai.com/v1/responses",
        },
        "deepseek": {
            "credential": "deepseek_api_key",
            "chat_url": "https://api.deepseek.com/v1/chat/completions",
            "responses_url": "https://api.deepseek.com/v1/responses",
        },
        "kimi": {
            "credential": "kimi_api_key",
            "chat_url": "https://api.moonshot.cn/v1/chat/completions",
            "responses_url": "https://api.moonshot.cn/v1/responses",
        },
        "gemini": {
            "credential": "gemini_api_key",
            "chat_url": "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            "responses_url": "https://generativelanguage.googleapis.com/v1beta/openai/responses",
        },
        "anthropic": {
            "credential": "anthropic_api_key",
            "messages_url": "https://api.anthropic.com/v1/messages",
        },
    }

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

    @staticmethod
    def _image_media_type(path: str) -> str:
        p = str(path or "").lower()
        if p.endswith(".png"):
            return "image/png"
        if p.endswith(".webp"):
            return "image/webp"
        if p.endswith(".gif"):
            return "image/gif"
        return "image/jpeg"

    @staticmethod
    def _audio_format(path: str, explicit_fmt: str = "") -> str:
        fmt = str(explicit_fmt or "").strip().lower()
        if fmt:
            return fmt
        p = str(path or "").lower()
        if p.endswith(".mp3"):
            return "mp3"
        if p.endswith(".m4a"):
            return "m4a"
        if p.endswith(".ogg"):
            return "ogg"
        if p.endswith(".flac"):
            return "flac"
        return "wav"

    @staticmethod
    def _ensure_messages(adapter: Dict[str, Any], default_text: str) -> List[Dict[str, Any]]:
        msgs = adapter.get("messages")
        if isinstance(msgs, list) and msgs:
            out: List[Dict[str, Any]] = []
            for m in msgs:
                if not isinstance(m, dict):
                    continue
                role = str(m.get("role") or "user").strip() or "user"
                content = m.get("content")
                if isinstance(content, str):
                    out.append({"role": role, "content": content})
                elif isinstance(content, list):
                    out.append({"role": role, "content": content})
            if out:
                return out
        text = default_text if isinstance(default_text, str) else ""
        return [{"role": "user", "content": text or "Hello"}]

    def _build_adapter_request(self, adapter: Dict[str, Any]) -> Dict[str, Any]:
        provider = str(adapter.get("provider") or "").strip().lower()
        task = str(adapter.get("task") or "chat").strip().lower() or "chat"
        model = str(adapter.get("model") or "").strip()
        if provider not in self._PROVIDER_DEFAULTS:
            return {"status": "error", "error": "unsupported_provider", "provider": provider}

        defaults = self._PROVIDER_DEFAULTS[provider]
        credential = str(adapter.get("api_key_credential") or defaults.get("credential") or "").strip()
        if not credential:
            return {"status": "error", "error": "missing_api_key_credential", "provider": provider}

        temp = adapter.get("temperature")
        max_tokens = adapter.get("max_tokens")
        timeout_s = adapter.get("timeout_s")
        allow_insecure = bool(adapter.get("allow_insecure_http", False))

        headers: Dict[str, str] = {"Content-Type": "application/json"}
        if provider == "anthropic":
            headers["x-api-key"] = "${vault:%s}" % credential
            headers["anthropic-version"] = str(adapter.get("anthropic_version") or "2023-06-01")
        else:
            headers["Authorization"] = "Bearer ${vault:%s}" % credential

        if task in {"chat", "vision"}:
            if provider == "anthropic":
                url = str(adapter.get("base_url") or defaults.get("messages_url") or "").strip()
                if not url:
                    return {"status": "error", "error": "missing_base_url", "provider": provider, "task": task}
                text = str(adapter.get("text") or "").strip()
                messages = self._ensure_messages(adapter, text)
                if task == "vision":
                    image_paths: List[str] = []
                    one = str(adapter.get("image_path") or "").strip()
                    if one:
                        image_paths.append(one)
                    many = adapter.get("image_paths")
                    if isinstance(many, list):
                        image_paths.extend([str(x).strip() for x in many if str(x).strip()])
                    if image_paths:
                        blocks: List[Dict[str, Any]] = []
                        if text:
                            blocks.append({"type": "text", "text": text})
                        for p in image_paths[:8]:
                            blocks.append(
                                {
                                    "type": "image",
                                    "source": {
                                        "type": "base64",
                                        "media_type": self._image_media_type(p),
                                        "data": "${file:%s:base64}" % p,
                                    },
                                }
                            )
                        messages = [{"role": "user", "content": blocks}]
                body: Dict[str, Any] = {
                    "model": model,
                    "messages": messages,
                    "max_tokens": int(max_tokens) if isinstance(max_tokens, (int, float, str)) and str(max_tokens).strip() else 1024,
                }
                if isinstance(temp, (int, float)):
                    body["temperature"] = float(temp)
                return {
                    "status": "ok",
                    "request": {
                        "method": "POST",
                        "url": url,
                        "headers": headers,
                        "json": body,
                        "timeout_s": float(timeout_s) if isinstance(timeout_s, (int, float, str)) and str(timeout_s).strip() else 45.0,
                        "allow_insecure_http": allow_insecure,
                    },
                }

            # OpenAI-compatible providers (OpenAI/DeepSeek/Kimi/Gemini OpenAI-compat)
            url = str(adapter.get("base_url") or defaults.get("chat_url") or "").strip()
            if not url:
                return {"status": "error", "error": "missing_base_url", "provider": provider, "task": task}
            text = str(adapter.get("text") or "").strip()
            messages = self._ensure_messages(adapter, text)
            if task == "vision":
                image_paths: List[str] = []
                one = str(adapter.get("image_path") or "").strip()
                if one:
                    image_paths.append(one)
                many = adapter.get("image_paths")
                if isinstance(many, list):
                    image_paths.extend([str(x).strip() for x in many if str(x).strip()])
                if image_paths:
                    content: List[Dict[str, Any]] = []
                    if text:
                        content.append({"type": "text", "text": text})
                    for p in image_paths[:8]:
                        content.append(
                            {
                                "type": "image_url",
                                "image_url": {
                                    "url": "data:%s;base64,${file:%s:base64}" % (self._image_media_type(p), p)
                                },
                            }
                        )
                    messages = [{"role": "user", "content": content}]
            body = {
                "model": model,
                "messages": messages,
            }
            if isinstance(temp, (int, float)):
                body["temperature"] = float(temp)
            if isinstance(max_tokens, (int, float)):
                body["max_tokens"] = int(max_tokens)
            return {
                "status": "ok",
                "request": {
                    "method": "POST",
                    "url": url,
                    "headers": headers,
                    "json": body,
                    "timeout_s": float(timeout_s) if isinstance(timeout_s, (int, float, str)) and str(timeout_s).strip() else 45.0,
                    "allow_insecure_http": allow_insecure,
                },
            }

        if task == "stt":
            # JSON STT adapter currently implemented for OpenAI-compatible Responses API only.
            if provider not in {"openai", "deepseek", "kimi", "gemini"}:
                return {
                    "status": "error",
                    "error": "unsupported_task_for_provider",
                    "provider": provider,
                    "task": task,
                }
            audio_path = str(adapter.get("audio_path") or "").strip()
            if not audio_path:
                return {"status": "error", "error": "audio_path_required", "provider": provider, "task": task}
            prompt = str(adapter.get("prompt") or "Transcribe the audio.").strip()
            audio_fmt = self._audio_format(audio_path, str(adapter.get("audio_format") or ""))
            url = str(adapter.get("base_url") or defaults.get("responses_url") or "").strip()
            if not url:
                return {"status": "error", "error": "missing_base_url", "provider": provider, "task": task}
            body = {
                "model": model,
                "input": [
                    {
                        "role": "user",
                        "content": [
                            {"type": "input_text", "text": prompt},
                            {
                                "type": "input_audio",
                                "input_audio": {
                                    "data": "${file:%s:base64}" % audio_path,
                                    "format": audio_fmt,
                                },
                            },
                        ],
                    }
                ],
            }
            return {
                "status": "ok",
                "request": {
                    "method": "POST",
                    "url": url,
                    "headers": headers,
                    "json": body,
                    "timeout_s": float(timeout_s) if isinstance(timeout_s, (int, float, str)) and str(timeout_s).strip() else 90.0,
                    "allow_insecure_http": allow_insecure,
                },
            }

        return {"status": "error", "error": "unsupported_task", "task": task}

    def run(self, args: Dict[str, Any]) -> Dict[str, Any]:
        identity = str(args.get("identity") or args.get("session_id") or "").strip()
        if identity:
            self.set_identity(identity)

        adapter = args.get("adapter") if isinstance(args.get("adapter"), dict) else None
        if adapter is None and isinstance(args.get("provider_adapter"), dict):
            adapter = args.get("provider_adapter")

        req = args.get("request")
        payload: Dict[str, Any]

        if adapter is not None:
            adapted = self._build_adapter_request(adapter)
            if adapted.get("status") != "ok":
                return adapted
            payload = adapted.get("request") if isinstance(adapted.get("request"), dict) else {}
            # Optional raw request overlay for advanced users.
            if isinstance(req, dict):
                merged = dict(payload)
                merged.update(req)
                payload = merged
        else:
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
