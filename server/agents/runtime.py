import json
import re
import threading
import time
from collections import deque
from pathlib import Path
from typing import Callable, Deque, Dict, List, Optional

import requests


class BrainRuntime:
    """Background agent loop that processes chat/event inbox items with a cloud model."""

    def __init__(
        self,
        *,
        user_dir: Path,
        storage,
        emit_log: Callable[[str, Dict], None],
        shell_exec: Callable[[str, str, str], Dict],
        tool_invoke: Callable[[str, Dict, Optional[str], str], Dict],
    ):
        self._user_dir = user_dir
        self._storage = storage
        self._emit_log = emit_log
        self._shell_exec = shell_exec
        self._tool_invoke = tool_invoke

        self._lock = threading.Lock()
        self._queue: Deque[Dict] = deque()
        self._messages: Deque[Dict] = deque(maxlen=200)
        self._thread: Optional[threading.Thread] = None
        self._stop = threading.Event()
        self._busy = False
        self._last_error = ""
        self._last_processed_at = 0

        self._config = self._load_config()

    def _default_config(self) -> Dict:
        return {
            "enabled": False,
            "auto_start": False,
            "provider_url": "https://api.openai.com/v1/chat/completions",
            "model": "",
            "api_key_credential": "openai_api_key",
            "system_prompt": (
                "You are Kugutz Brain running on Android. Produce JSON only with "
                "fields: responses (array of strings) and actions (array). "
                "Available action types: shell_exec, write_file, tool_invoke, sleep. "
                "Never use commands beyond python/pip/uv."
            ),
            "temperature": 0.2,
            "max_actions": 6,
            "idle_sleep_ms": 800,
        }

    def _load_config(self) -> Dict:
        raw = self._storage.get_setting("brain.config.v1")
        cfg = self._default_config()
        if not raw:
            return cfg
        try:
            loaded = json.loads(raw)
            if isinstance(loaded, dict):
                cfg.update(loaded)
        except Exception:
            pass
        return cfg

    def _save_config(self) -> None:
        self._storage.set_setting("brain.config.v1", json.dumps(self._config))

    def get_config(self) -> Dict:
        with self._lock:
            return dict(self._config)

    def update_config(self, patch: Dict) -> Dict:
        with self._lock:
            for key, value in patch.items():
                if key in self._default_config():
                    self._config[key] = value
            self._save_config()
            cfg = dict(self._config)
        self._emit_log("brain_config_updated", {"keys": list(patch.keys())})
        return cfg

    def start(self) -> Dict:
        with self._lock:
            if self._thread and self._thread.is_alive():
                return {"status": "already_running"}
            self._stop.clear()
            self._thread = threading.Thread(target=self._run_loop, daemon=True)
            self._thread.start()
            self._config["enabled"] = True
            self._save_config()
        self._emit_log("brain_started", {})
        return {"status": "started"}

    def stop(self) -> Dict:
        thread = None
        with self._lock:
            self._config["enabled"] = False
            self._save_config()
            self._stop.set()
            thread = self._thread
        if thread and thread.is_alive():
            thread.join(timeout=2.0)
        self._emit_log("brain_stopped", {})
        return {"status": "stopped"}

    def enqueue_chat(self, text: str, meta: Optional[Dict] = None) -> Dict:
        item = {
            "id": f"chat_{int(time.time() * 1000)}",
            "kind": "chat",
            "text": text or "",
            "meta": meta or {},
            "created_at": int(time.time() * 1000),
        }
        with self._lock:
            self._queue.append(item)
        self._emit_log("brain_inbox_chat", {"id": item["id"]})
        return item

    def enqueue_event(self, name: str, payload: Optional[Dict] = None) -> Dict:
        item = {
            "id": f"event_{int(time.time() * 1000)}",
            "kind": "event",
            "name": name or "unnamed_event",
            "payload": payload or {},
            "created_at": int(time.time() * 1000),
        }
        with self._lock:
            self._queue.append(item)
        self._emit_log("brain_inbox_event", {"id": item["id"], "name": item["name"]})
        return item

    def list_messages(self, limit: int = 50) -> List[Dict]:
        limit = max(1, min(int(limit or 50), 200))
        with self._lock:
            return list(self._messages)[-limit:]

    def status(self) -> Dict:
        with self._lock:
            running = self._thread is not None and self._thread.is_alive()
            return {
                "running": running,
                "enabled": bool(self._config.get("enabled")),
                "busy": self._busy,
                "queue_size": len(self._queue),
                "last_error": self._last_error,
                "last_processed_at": self._last_processed_at,
                "model": self._config.get("model", ""),
                "provider_url": self._config.get("provider_url", ""),
            }

    def maybe_autostart(self) -> None:
        cfg = self.get_config()
        if cfg.get("auto_start"):
            self.start()

    def _run_loop(self) -> None:
        while not self._stop.is_set():
            item = None
            with self._lock:
                if self._queue:
                    item = self._queue.popleft()
            if not item:
                sleep_ms = int(self._config.get("idle_sleep_ms", 800) or 800)
                time.sleep(max(0.1, sleep_ms / 1000.0))
                continue
            with self._lock:
                self._busy = True
                self._last_error = ""
            try:
                self._process_item(item)
                with self._lock:
                    self._last_processed_at = int(time.time() * 1000)
            except Exception as ex:
                with self._lock:
                    self._last_error = str(ex)
                self._emit_log("brain_item_failed", {"id": item.get("id"), "error": str(ex)})
            finally:
                with self._lock:
                    self._busy = False

    def _record_message(self, role: str, text: str, meta: Optional[Dict] = None) -> None:
        entry = {
            "ts": int(time.time() * 1000),
            "role": role,
            "text": text,
            "meta": meta or {},
        }
        with self._lock:
            self._messages.append(entry)

    def _process_item(self, item: Dict) -> None:
        self._emit_log("brain_item_started", {"id": item.get("id"), "kind": item.get("kind")})

        plan = self._plan_with_cloud(item)
        responses = plan.get("responses") if isinstance(plan, dict) else []
        actions = plan.get("actions") if isinstance(plan, dict) else []

        if isinstance(responses, list):
            for text in responses:
                if not isinstance(text, str):
                    continue
                self._record_message("assistant", text, {"item_id": item.get("id")})
                self._emit_log("brain_response", {"item_id": item.get("id"), "text": text[:300]})

        if not isinstance(actions, list):
            actions = []
        max_actions = max(0, min(int(self._config.get("max_actions", 6) or 6), 12))
        for action in actions[:max_actions]:
            if not isinstance(action, dict):
                continue
            self._execute_action(item, action)

        self._emit_log("brain_item_done", {"id": item.get("id"), "actions": min(len(actions), max_actions)})

    def _plan_with_cloud(self, item: Dict) -> Dict:
        cfg = self.get_config()
        model = str(cfg.get("model") or "").strip()
        provider_url = str(cfg.get("provider_url") or "").strip()
        key_name = str(cfg.get("api_key_credential") or "").strip()

        if not model or not provider_url or not key_name:
            return {
                "responses": [
                    "Brain is not configured yet. Set provider_url, model, and api_key_credential via /brain/config."
                ],
                "actions": [],
            }

        key_row = self._storage.get_credential(key_name)
        api_key = (key_row or {}).get("value", "")
        if not api_key:
            return {
                "responses": [
                    f"Missing API credential '{key_name}'. Set it in vault, then continue."
                ],
                "actions": [],
            }

        history = self.list_messages(limit=20)
        user_payload = {
            "item": item,
            "recent_messages": history,
            "constraints": {
                "allowed_commands": ["python", "pip", "uv"],
                "root": str(self._user_dir),
            },
        }
        body = {
            "model": model,
            "temperature": float(cfg.get("temperature", 0.2) or 0.2),
            "messages": [
                {"role": "system", "content": str(cfg.get("system_prompt") or "")},
                {
                    "role": "user",
                    "content": (
                        "Return strict JSON object with keys responses (string[]) and actions (object[]). "
                        "Action objects: "
                        "{type:'shell_exec', cmd:'python|pip|uv', args:'...', cwd:'/subdir'} OR "
                        "{type:'write_file', path:'relative/path.py', content:'...'} OR "
                        "{type:'tool_invoke', tool:'filesystem|shell', args:{...}, request_id:'optional', detail:'optional'} OR "
                        "{type:'sleep', seconds:1}. "
                        "Input:\n" + json.dumps(user_payload)
                    ),
                },
            ],
        }

        resp = requests.post(
            provider_url,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
            data=json.dumps(body),
            timeout=25,
        )
        resp.raise_for_status()
        payload = resp.json()
        content = (
            (((payload.get("choices") or [{}])[0]).get("message") or {}).get("content")
            or "{}"
        )
        parsed = self._parse_json_object(content)
        if not isinstance(parsed, dict):
            return {"responses": ["Model response was not valid JSON."], "actions": []}
        parsed.setdefault("responses", [])
        parsed.setdefault("actions", [])
        return parsed

    def _parse_json_object(self, raw: str) -> Dict:
        try:
            return json.loads(raw)
        except Exception:
            pass
        match = re.search(r"\{.*\}", raw, re.DOTALL)
        if not match:
            return {}
        try:
            return json.loads(match.group(0))
        except Exception:
            return {}

    def _safe_write_file(self, rel_path: str, content: str) -> Dict:
        target = (self._user_dir / rel_path.lstrip("/")).resolve()
        if not str(target).startswith(str(self._user_dir.resolve())):
            return {"status": "error", "error": "path_outside_user_dir"}
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        return {"status": "ok", "path": str(target)}

    def _execute_action(self, item: Dict, action: Dict) -> None:
        a_type = str(action.get("type") or "").strip()
        result: Dict

        if a_type == "shell_exec":
            cmd = str(action.get("cmd") or "")
            args = str(action.get("args") or "")
            cwd = str(action.get("cwd") or "")
            if cmd not in {"python", "pip", "uv"}:
                result = {"status": "error", "error": "command_not_allowed"}
            else:
                result = self._shell_exec(cmd, args, cwd)
        elif a_type == "write_file":
            path = str(action.get("path") or "")
            content = str(action.get("content") or "")
            if not path:
                result = {"status": "error", "error": "missing_path"}
            else:
                result = self._safe_write_file(path, content)
        elif a_type == "tool_invoke":
            tool = str(action.get("tool") or "")
            args = action.get("args") if isinstance(action.get("args"), dict) else {}
            request_id = action.get("request_id")
            request_id = str(request_id) if request_id else None
            detail = str(action.get("detail") or "")
            result = self._tool_invoke(tool, args, request_id, detail)
        elif a_type == "sleep":
            seconds = float(action.get("seconds") or 0)
            seconds = max(0.0, min(seconds, 10.0))
            time.sleep(seconds)
            result = {"status": "ok", "slept": seconds}
        else:
            result = {"status": "error", "error": "unsupported_action"}

        self._record_message(
            "tool",
            json.dumps({"action": action, "result": result}),
            {"item_id": item.get("id")},
        )
        self._emit_log(
            "brain_action",
            {
                "item_id": item.get("id"),
                "type": a_type,
                "result": result,
            },
        )
