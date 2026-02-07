import contextlib
import hashlib
import json
import os
import re
import threading
import time
from collections import deque
from pathlib import Path
from typing import Any, Callable, Deque, Dict, List, Optional

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

        # Ephemeral per-session notes (no permissions required).
        self._session_notes: Dict[str, Dict[str, str]] = {}
        # Cache capability -> permission_id so the model doesn't need to remember ids.
        # This is session-scoped (in-memory) and resets when the brain restarts.
        self._capability_permissions: Dict[str, str] = {}
        # Used to tag Kotlin permission requests so approvals can be reused per chat session.
        self._active_identity: str = ""
        # Cache user-root policy docs; re-read when file changes on disk (mtime/size).
        self._user_root_doc_cache: Dict[str, Dict[str, Any]] = {}

        self._config = self._load_config()

    def _read_user_root_doc(self, name: str, *, max_chars: int = 20000) -> Dict[str, Any]:
        """
        Read and cache a user-root doc (AGENTS.md/TOOLS.md). If the file changes on disk (mtime/size),
        re-read and update the cache.
        """
        filename = str(name or "").strip()
        if not filename:
            return {"exists": False, "mtime_ns": 0, "size": 0, "sha256": "", "content": "", "sig": (0, 0)}

        path = (self._user_dir / filename).resolve()
        if not str(path).startswith(str(self._user_dir.resolve())):
            return {"exists": False, "mtime_ns": 0, "size": 0, "sha256": "", "content": "", "sig": (0, 0)}

        try:
            st = path.stat()
        except Exception:
            cached = self._user_root_doc_cache.get(filename)
            return cached or {"exists": False, "mtime_ns": 0, "size": 0, "sha256": "", "content": "", "sig": (0, 0)}

        mtime_ns = int(getattr(st, "st_mtime_ns", int(st.st_mtime * 1e9)))
        size = int(st.st_size)
        sig = (mtime_ns, size)
        cached = self._user_root_doc_cache.get(filename)
        if cached and cached.get("sig") == sig:
            return cached

        try:
            raw = path.read_bytes()
        except Exception:
            # Keep previous cached content if any.
            return cached or {"exists": True, "mtime_ns": mtime_ns, "size": size, "sha256": "", "content": "", "sig": sig}

        sha256 = hashlib.sha256(raw).hexdigest()
        text = raw.decode("utf-8", errors="replace")
        if max_chars > 0 and len(text) > max_chars:
            text = text[:max_chars] + "\n\n...(truncated)...\n"

        entry = {
            "exists": True,
            "mtime_ns": mtime_ns,
            "size": size,
            "sha256": sha256,
            "content": text,
            "sig": sig,
        }
        self._user_root_doc_cache[filename] = entry
        return entry

    def _user_root_policy_blob(self) -> str:
        """
        System-prompt appendix containing user-root policy docs.
        These are injected automatically and reloaded if updated on disk.
        """
        agents = self._read_user_root_doc("AGENTS.md")
        tools = self._read_user_root_doc("TOOLS.md")

        parts: List[str] = []
        parts.append(
            "User-root docs (auto-injected; reloaded if changed on disk).\n"
            "Do NOT call filesystem tools to read `AGENTS.md`/`TOOLS.md` unless the user explicitly asks.\n"
        )
        for name, doc in (("AGENTS.md", agents), ("TOOLS.md", tools)):
            if not doc.get("exists"):
                parts.append(f"## {name}\n(not found)\n")
                continue
            parts.append(
                f"## {name} (mtime_ns={doc.get('mtime_ns')}, size={doc.get('size')}, sha256={doc.get('sha256')})\n"
                + str(doc.get("content") or "").rstrip()
                + "\n"
            )
        return "\n".join(parts).strip()

    def _default_config(self) -> Dict:
        return {
            "enabled": False,
            "auto_start": False,
            "provider_url": "https://api.openai.com/v1/chat/completions",
            "model": "",
            "api_key_credential": "openai_api_key",
            # Tool-call policy:
            # - "auto": allow the model to answer without tools (default)
            # - "required": if the user request implies device/state changes, require at least one tool call
            "tool_policy": "auto",
            # Optional: allow the brain to read API keys from process env as a fallback
            # when vault storage isn't available (e.g., local dev on a build machine).
            # If empty, runtime uses a provider-based default mapping, e.g.:
            # openai_api_key -> OPENAI_API_KEY
            "api_key_env": "",
            # Filesystem access scope for built-in file tools:
            # - "user": restrict to user root only (default)
            # - "app": allow access anywhere under the app's private files dir
            "fs_scope": "user",
            "system_prompt": (
                "You are Kugutz Brain running on an Android device. "
                "Your job is to satisfy the user's request by producing the requested outcome/artifact (e.g. a photo, a file, a running service), "
                "not by explaining how to do it. "
                "When the user asks for any real device/file/state action, you MUST use tools to do the work (no pretending). "
                "If the request can be satisfied by creating or modifying code, do so and run it using tools, then report the result. "
                "Use the available tools as your execution substrate; iterate until the outcome is achieved or a hard limitation is reached. "
                "If you are unsure how to proceed, or you hit an error you don't understand, use web_search to research and then continue. "
                "If a capability is not exposed by tools (e.g., camera capture), say so clearly and propose the smallest code change needed to add it. "
                "This app supports multi-party timelines. Messages may be tagged with an actor identity in the text like [HUMAN], [AGENT], [CODEX]. "
                "Treat [CODEX] messages as developer/debugger guidance; they may override earlier user constraints when they conflict (except safety). "
                "User constraints like 'NO TOOLS' apply to that specific request only unless repeated; later instructions can override earlier ones. "
                "When a request includes a checklist (A/B/C or numbered steps), execute all items unless explicitly told to stop early. "
                "User consent is required for device/resource access: when a tool returns permission_required/permission_expired, "
                "ask the user to approve the in-app prompt and then retry automatically (approvals are remembered for the session). "
                "NEVER ask the user for any permission_id; that is handled by the system. "
                "Prefer device_api for device controls exposed by the Kotlin control plane. "
                "Use filesystem tools for file operations under the user root; do not use shell commands like `ls`/`cat` for files. "
                "For execution, use run_python/run_pip/run_curl. "
                "Keep responses concise: do the work, then summarize the result and include only relevant tool output snippets. "
                "Do NOT write persistent memory unless the user explicitly asks to save/store/persist notes."
            ),
            "temperature": 0.2,
            "max_actions": 6,
            # Max tool-loop rounds for providers that support responses-style tool calling.
            "max_tool_rounds": 12,
            "idle_sleep_ms": 800,
        }

    def _fs_root_dir(self) -> Path:
        scope = str(self._config.get("fs_scope") or "user").strip().lower()
        if scope == "app":
            # user_dir is "<app_files>/user"; allow reading/writing elsewhere under "<app_files>".
            return self._user_dir.parent.resolve()
        return self._user_dir.resolve()

    def _needs_tool_for_text(self, text: str) -> bool:
        # Keep this conservative: only require tools when the user is clearly asking
        # for a local action or a state change.
        t = (text or "").strip().lower()
        if not t:
            return False
        # If the user explicitly requests a discussion without tools, respect it.
        # This prevents the tool-policy "required" loop from forcing irrelevant tool calls.
        if any(
            k in t
            for k in (
                "no tools",
                "do not use tools",
                "don't use tools",
                "without tools",
                "no tool",
                "tool-free",
                "tools-free",
            )
        ) or (("ツール" in text) and any(k in text for k in ("使わない", "不要", "無し", "なし"))):
            return False
        # "Remember ..." can be satisfied by session context (recent dialogue) without any device action.
        # Only require tools if the user explicitly asks to persist/save memory.
        if any(k in t for k in ("remember", "memorize", "覚えて")):
            if any(k in t for k in ("save", "store", "persist", "persistent", "memory", "保存", "永続", "メモ")):
                return True
            return False
        keywords = (
            # Japanese (common UI queries)
            "バージョン",
            "確認",
            "実行",
            "一覧",
            "表示",
            "教えて",
            "起動",
            "停止",
            "再起動",
            "run ",
            "execute",
            "ls",
            "dir",
            "pwd",
            "create ",
            "write ",
            "edit ",
            "delete ",
            "move ",
            "copy ",
            "list ",
            "show ",
            "check ",
            "status",
            "restart",
            "start ",
            "stop ",
            "enable",
            "disable",
            "install",
            "curl ",
            "curl",
            "ssh",
            "python",
            "worker",
            "device",
            "file",
            "directory",
            "folder",
        )
        return any(k in t for k in keywords)

    def _explicit_persist_memory_requested(self, text: str) -> bool:
        t = (text or "").strip().lower()
        if not t:
            return False
        # English
        if ("save" in t or "store" in t or "persist" in t) and ("memory" in t or "note" in t or "notes" in t):
            return True
        if "save this" in t or "save it" in t or "persist this" in t:
            return True
        # Japanese
        if "保存" in text or "永続" in text or "メモリに" in text:
            return True
        return False

    def _resolve_user_path(self, rel_path: str) -> Path | None:
        raw = (rel_path or "").strip()
        target = Path(raw) if raw else Path(".")
        root = self._fs_root_dir()
        if not target.is_absolute():
            target = (root / target).resolve()
        else:
            target = target.resolve()
        if not str(target).startswith(str(root)):
            return None
        return target

    def _fs_list_dir(self, path: str, show_hidden: bool, limit: int) -> Dict:
        target = self._resolve_user_path(path)
        if target is None:
            return {"status": "error", "error": "path_outside_user_dir"}
        if not target.exists():
            return {"status": "error", "error": "not_found"}
        if not target.is_dir():
            return {"status": "error", "error": "not_a_directory"}
        limit = max(1, min(int(limit or 200), 5000))
        entries: List[Dict[str, Any]] = []
        try:
            for child in sorted(target.iterdir(), key=lambda p: p.name.lower()):
                name = child.name
                if not show_hidden and name.startswith("."):
                    continue
                with contextlib.suppress(Exception):
                    st = child.stat()
                    entries.append(
                        {
                            "name": name,
                            "type": "dir" if child.is_dir() else "file",
                            "size": int(getattr(st, "st_size", 0) or 0),
                            "mtime": int(getattr(st, "st_mtime", 0) or 0),
                        }
                    )
                if len(entries) >= limit:
                    break
        except Exception as ex:
            return {"status": "error", "error": "list_failed", "detail": str(ex)}
        return {"status": "ok", "path": str(target), "entries": entries, "truncated": len(entries) >= limit}

    def _fs_read_file(self, path: str, max_bytes: int) -> Dict:
        target = self._resolve_user_path(path)
        if target is None:
            return {"status": "error", "error": "path_outside_user_dir"}
        if not target.exists():
            return {"status": "error", "error": "not_found"}
        if not target.is_file():
            return {"status": "error", "error": "not_a_file"}
        max_bytes = max(1024, min(int(max_bytes or 262144), 2 * 1024 * 1024))
        try:
            data = target.read_bytes()
            truncated = len(data) > max_bytes
            if truncated:
                data = data[:max_bytes]
            text = data.decode("utf-8", errors="replace")
            return {"status": "ok", "path": str(target), "content": text, "truncated": truncated}
        except Exception as ex:
            return {"status": "error", "error": "read_failed", "detail": str(ex)}

    def _fs_mkdir(self, path: str, parents: bool) -> Dict:
        target = self._resolve_user_path(path)
        if target is None:
            return {"status": "error", "error": "path_outside_user_dir"}
        try:
            target.mkdir(parents=bool(parents), exist_ok=True)
            return {"status": "ok", "path": str(target)}
        except Exception as ex:
            return {"status": "error", "error": "mkdir_failed", "detail": str(ex)}

    def _fs_delete(self, path: str, recursive: bool) -> Dict:
        target = self._resolve_user_path(path)
        if target is None:
            return {"status": "error", "error": "path_outside_user_dir"}
        if not target.exists():
            return {"status": "ok", "deleted": False}
        try:
            if target.is_dir():
                if recursive:
                    for p in sorted(target.rglob("*"), key=lambda p: len(str(p)), reverse=True):
                        if p.is_dir():
                            p.rmdir()
                        else:
                            p.unlink()
                    target.rmdir()
                else:
                    target.rmdir()
            else:
                target.unlink()
            return {"status": "ok", "deleted": True, "path": str(target)}
        except Exception as ex:
            return {"status": "error", "error": "delete_failed", "detail": str(ex)}

    def _fs_move(self, src: str, dst: str, overwrite: bool) -> Dict:
        src_p = self._resolve_user_path(src)
        dst_p = self._resolve_user_path(dst)
        if src_p is None or dst_p is None:
            return {"status": "error", "error": "path_outside_user_dir"}
        if not src_p.exists():
            return {"status": "error", "error": "src_not_found"}
        try:
            dst_p.parent.mkdir(parents=True, exist_ok=True)
            if dst_p.exists():
                if not overwrite:
                    return {"status": "error", "error": "dst_exists"}
                if dst_p.is_dir():
                    dst_p.rmdir()
                else:
                    dst_p.unlink()
            src_p.replace(dst_p)
            return {"status": "ok", "src": str(src_p), "dst": str(dst_p)}
        except Exception as ex:
            return {"status": "error", "error": "move_failed", "detail": str(ex)}

    def _env_key_name_for_credential(self, credential_name: str) -> str:
        # Keep mapping explicit (avoid guessing) but cover common providers.
        name = (credential_name or "").strip().lower()
        if not name:
            return ""
        if name in {"openai_api_key", "openai.key", "openai"}:
            return "OPENAI_API_KEY"
        if name in {"anthropic_api_key", "anthropic.key", "anthropic"}:
            return "ANTHROPIC_API_KEY"
        if name in {"kimi_api_key", "kimi.key", "moonshot_api_key", "moonshot.key"}:
            return "KIMI_API_KEY"
        return ""

    def _get_api_key(self, key_name: str) -> str:
        # 1) Primary path: local vault credential.
        key_row = self._storage.get_credential(key_name)
        api_key = (key_row or {}).get("value", "")
        if api_key:
            return str(api_key)

        # 2) Fallback path: env var (explicit override in config if set).
        env_override = str(self._config.get("api_key_env") or "").strip()
        env_name = env_override or self._env_key_name_for_credential(key_name)
        if not env_name:
            return ""
        return str(os.environ.get(env_name) or "")

    def _get_permission_status(self, permission_id: str) -> str:
        pid = (permission_id or "").strip()
        if not pid:
            return ""
        try:
            resp = requests.get(f"http://127.0.0.1:8765/permissions/{pid}", timeout=3)
            if resp.status_code != 200:
                return ""
            body = resp.json() if resp.content else {}
            if not isinstance(body, dict):
                return ""
            return str(body.get("status") or "").strip()
        except Exception:
            return ""

    def _wait_for_permission(self, permission_id: str, *, timeout_s: float = 45.0, poll_s: float = 1.0) -> str:
        pid = (permission_id or "").strip()
        if not pid:
            return "invalid"
        deadline = time.time() + max(1.0, float(timeout_s or 45.0))
        poll_s = max(0.2, min(float(poll_s or 1.0), 5.0))
        while time.time() < deadline:
            status = self._get_permission_status(pid)
            if status in {"approved", "denied", "used"}:
                return status
            time.sleep(poll_s)
        return "timeout"

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

    def debug_post_comment(
        self,
        *,
        session_id: str,
        role: str,
        text: str,
        meta: Optional[Dict] = None,
    ) -> Dict:
        """
        Debug helper: insert a message directly into the chat timeline for a given session.

        This does NOT enqueue agent work. It only records a message for UI/debugging/instruction.
        """
        sid = str(session_id or "default").strip() or "default"
        r = str(role or "assistant").strip() or "assistant"
        t = str(text or "")
        m = dict(meta or {})
        m.setdefault("session_id", sid)
        m.setdefault("debug", True)
        m.setdefault("source", "debug_post_comment")
        m.setdefault("actor", "codex")
        self._record_message(r, t, m)
        return {"status": "ok", "session_id": sid, "role": r, "text": t, "meta": m}

    def list_messages(self, limit: int = 50) -> List[Dict]:
        limit = max(1, min(int(limit or 50), 200))
        # Prefer persistent storage when available so UI can restore after WebView/activity recreation
        # and so agent context survives python worker restarts.
        try:
            if hasattr(self._storage, "list_chat_messages"):
                rows = self._storage.list_chat_messages("default", limit=limit)
                out: List[Dict] = []
                for r in rows:
                    meta = {}
                    raw_meta = r.get("meta")
                    if isinstance(raw_meta, str) and raw_meta.strip():
                        try:
                            meta = json.loads(raw_meta)
                        except Exception:
                            meta = {}
                    out.append(
                        {
                            "ts": r.get("created_at"),
                            "role": r.get("role"),
                            "text": r.get("text"),
                            "meta": meta,
                        }
                    )
                return out[-limit:]
        except Exception:
            pass
        with self._lock:
            return list(self._messages)[-limit:]

    def list_messages_for_session(self, *, session_id: str, limit: int = 200) -> List[Dict]:
        sid = (session_id or "default").strip() or "default"
        limit = max(1, min(int(limit or 200), 500))
        try:
            if hasattr(self._storage, "list_chat_messages"):
                rows = self._storage.list_chat_messages(sid, limit=limit)
                out: List[Dict] = []
                for r in rows:
                    meta = {}
                    raw_meta = r.get("meta")
                    if isinstance(raw_meta, str) and raw_meta.strip():
                        try:
                            meta = json.loads(raw_meta)
                        except Exception:
                            meta = {}
                    out.append(
                        {
                            "ts": r.get("created_at"),
                            "role": r.get("role"),
                            "text": r.get("text"),
                            "meta": meta,
                        }
                    )
                return out
        except Exception:
            pass
        # Fallback to in-memory filter.
        with self._lock:
            items = list(self._messages)
        out: List[Dict] = []
        for msg in reversed(items):
            if len(out) >= limit:
                break
            meta = msg.get("meta") if isinstance(msg.get("meta"), dict) else {}
            if str((meta or {}).get("session_id") or "default") != sid:
                continue
            out.append(msg)
        return list(reversed(out))

    def list_sessions(self, limit: int = 50) -> List[Dict]:
        try:
            if hasattr(self._storage, "list_chat_sessions"):
                return self._storage.list_chat_sessions(limit=limit)
        except Exception:
            pass
        return []

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
                # Surface failures into the chat timeline so the UI isn't stuck "waiting forever".
                # Avoid including any secrets; `str(ex)` should be safe (requests errors include URL/status).
                try:
                    sid = self._session_id_for_item(item)
                    item_id = item.get("id")
                    msg = str(ex) or "Unknown error"
                    if "401" in msg and "Unauthorized" in msg:
                        msg = "Unauthorized (401). Check your API key in Settings."
                    self._record_message(
                        "assistant",
                        f"Error: {msg}",
                        {"item_id": item_id, "session_id": sid, "error": "brain_item_failed"},
                    )
                except Exception:
                    pass
                self._emit_log("brain_item_failed", {"id": item.get("id"), "error": str(ex)})
            finally:
                with self._lock:
                    self._busy = False

    def _record_message(self, role: str, text: str, meta: Optional[Dict] = None) -> None:
        meta = dict(meta or {})
        # Ensure every timeline row has an explicit actor identity for UI rendering
        # and for optional model-side disambiguation.
        if "actor" not in meta or not str(meta.get("actor") or "").strip():
            if role == "user":
                meta["actor"] = "human"
            elif role == "assistant":
                meta["actor"] = "agent"
            elif role == "tool":
                meta["actor"] = "tool"
            else:
                meta["actor"] = "system"
        entry = {
            "ts": int(time.time() * 1000),
            "role": role,
            "text": text,
            "meta": meta,
        }
        with self._lock:
            self._messages.append(entry)
        try:
            if hasattr(self._storage, "add_chat_message"):
                sid = str(meta.get("session_id") or "default").strip() or "default"
                self._storage.add_chat_message(sid, role, text, json.dumps(meta, ensure_ascii=True))
        except Exception:
            pass

    def _session_id_for_item(self, item: Dict) -> str:
        meta = item.get("meta") if isinstance(item.get("meta"), dict) else {}
        sid = str((meta or {}).get("session_id") or "").strip()
        return sid or "default"

    def _list_dialogue(self, *, session_id: str, limit: int = 24) -> List[Dict[str, str]]:
        # Return only user/assistant messages for the given session_id.
        limit = max(1, min(int(limit or 24), 120))
        msgs = self.list_messages_for_session(session_id=session_id, limit=max(limit, 1))
        out: List[Dict[str, Any]] = []
        for msg in msgs[-limit:]:
            role = str(msg.get("role") or "")
            if role not in {"user", "assistant"}:
                continue
            text = str(msg.get("text") or "")
            if not text.strip():
                continue
            meta = msg.get("meta") if isinstance(msg.get("meta"), dict) else {}
            out.append({"role": role, "text": text, "meta": meta})
        return out

    def _get_persistent_memory(self) -> str:
        # Stored on the Kotlin control-plane (LocalHttpServer) as a small text blob.
        try:
            resp = requests.get("http://127.0.0.1:8765/brain/memory", timeout=2)
            if not resp.ok:
                return ""
            payload = resp.json() if resp.headers.get("Content-Type", "").startswith("application/json") else {}
            content = payload.get("content") if isinstance(payload, dict) else ""
            return str(content or "")
        except Exception:
            return ""

    def _update_session_notes(self, session_id: str, text: str) -> Dict[str, str]:
        t = (text or "").strip()
        if not t:
            return {}
        notes = dict(self._session_notes.get(session_id) or {})
        changed: Dict[str, str] = {}

        m = re.search(r"\bmy favorite colou?r is\s+([a-zA-Z][a-zA-Z\s\-]{0,40})\b", t, re.IGNORECASE)
        if m:
            val = m.group(1).strip()
            if notes.get("favorite_color") != val:
                notes["favorite_color"] = val
                changed["favorite_color"] = val

        m = re.search(r"\bmy name is\s+([^\n\r]{1,80})", t, re.IGNORECASE)
        if m:
            val = m.group(1).strip().strip(".")
            if notes.get("name") != val:
                notes["name"] = val
                changed["name"] = val

        m = re.search(r"好きな色は\s*([^\n\r]{1,20})", t)
        if m:
            val = m.group(1).strip()
            if notes.get("favorite_color") != val:
                notes["favorite_color"] = val
                changed["favorite_color"] = val

        if notes:
            self._session_notes[session_id] = notes

        # Prevent unbounded growth.
        if len(self._session_notes) > 50:
            for k in list(self._session_notes.keys())[:10]:
                self._session_notes.pop(k, None)
        return changed

    def _process_item(self, item: Dict) -> None:
        self._emit_log("brain_item_started", {"id": item.get("id"), "kind": item.get("kind")})
        # Record the user message so the agent has per-session context.
        if str(item.get("kind") or "") == "chat":
            sid = self._session_id_for_item(item)
            text = str(item.get("text") or "")
            raw_meta = item.get("meta") if isinstance(item.get("meta"), dict) else {}
            changed = self._update_session_notes(sid, text)
            msg_meta: Dict[str, Any] = {"item_id": item.get("id"), "session_id": sid}
            # Preserve actor/debug/source tags (used for multi-party timelines and debugger notes).
            for k in ("actor", "debug", "source", "tag"):
                if k in raw_meta:
                    msg_meta[k] = raw_meta.get(k)
            self._record_message(
                "user",
                text,
                msg_meta,
            )
            # Handle simple session-memory cases locally (no cloud/tool calls).
            # This makes "remember previous discussion" reliable without requiring permissions.
            if not self._needs_tool_for_text(text):
                t_low = (text or "").strip().lower()
                if changed and "?" not in text and "save" not in t_low and "persist" not in t_low and "store" not in t_low:
                    if "favorite_color" in changed:
                        self._record_message(
                            "assistant",
                            f"Got it. For this session, I'll remember your favorite color is {changed['favorite_color']}.",
                            {"item_id": item.get("id"), "session_id": sid},
                        )
                        self._emit_log("brain_response", {"item_id": item.get("id"), "text": "session_note_ack"})
                        return
                    if "name" in changed:
                        self._record_message(
                            "assistant",
                            f"Got it. For this session, I'll remember your name is {changed['name']}.",
                            {"item_id": item.get("id"), "session_id": sid},
                        )
                        self._emit_log("brain_response", {"item_id": item.get("id"), "text": "session_note_ack"})
                        return

                if ("favorite color" in t_low) or ("好きな色" in text):
                    fav = (self._session_notes.get(sid) or {}).get("favorite_color", "")
                    if fav:
                        self._record_message(
                            "assistant",
                            f"Your favorite color (in this session) is {fav}.",
                            {"item_id": item.get("id"), "session_id": sid},
                        )
                        self._emit_log("brain_response", {"item_id": item.get("id"), "text": "session_note_answer"})
                        return

        cfg = self.get_config()
        provider_url = str(cfg.get("provider_url") or "").strip()
        if provider_url.rstrip("/").endswith("/responses"):
            self._process_with_responses_tools(item)
            self._emit_log("brain_item_done", {"id": item.get("id"), "actions": "tool_loop"})
            return

        max_actions = max(0, min(int(self._config.get("max_actions", 6) or 6), 12))
        max_rounds = 3
        tool_results: List[Dict] = []
        total_actions = 0

        for round_idx in range(max_rounds):
            plan = self._plan_with_cloud(item, tool_results)
            responses = plan.get("responses") if isinstance(plan, dict) else []
            actions = plan.get("actions") if isinstance(plan, dict) else []

            if isinstance(responses, list):
                for text in responses:
                    if not isinstance(text, str):
                        continue
                    # Include session_id so session-scoped polling (`/brain/messages?session_id=...`)
                    # can see the assistant reply.
                    self._record_message(
                        "assistant",
                        text,
                        {"item_id": item.get("id"), "session_id": self._session_id_for_item(item)},
                    )
                    self._emit_log("brain_response", {"item_id": item.get("id"), "text": text[:300]})

            if not isinstance(actions, list):
                actions = []
            if not actions:
                break

            round_results: List[Dict] = []
            for action in actions[:max_actions]:
                if not isinstance(action, dict):
                    continue
                result = self._execute_action(item, action)
                round_results.append({"action": action, "result": result})
                total_actions += 1
            tool_results = round_results
            if not round_results:
                break

        self._emit_log("brain_item_done", {"id": item.get("id"), "actions": total_actions})

    def _responses_tools(self) -> List[Dict[str, Any]]:
        return [
            {
                "type": "function",
                "name": "list_dir",
                "description": "List files/directories under the user root (safe alternative to `ls`).",
                "parameters": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "path": {"type": "string"},
                        "show_hidden": {"type": "boolean"},
                        "limit": {"type": "integer"},
                    },
                    "required": ["path", "show_hidden", "limit"],
                },
            },
            {
                "type": "function",
                "name": "read_file",
                "description": "Read a UTF-8 text file under the user root.",
                "parameters": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "path": {"type": "string"},
                        "max_bytes": {"type": "integer"},
                    },
                    "required": ["path", "max_bytes"],
                },
            },
            {
                "type": "function",
                "name": "device_api",
                "description": "Invoke allowlisted local device API action on 127.0.0.1:8765.",
                "parameters": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "action": {
                            "type": "string",
                            "enum": [
                                "python.status",
                                "python.restart",
                                "ssh.status",
                                "ssh.config",
                                "ssh.pin.status",
                                "ssh.pin.start",
                                "ssh.pin.stop",
                                "usb.list",
                                "usb.open",
                                "usb.close",
                                "usb.control_transfer",
                                "usb.raw_descriptors",
                                "usb.claim_interface",
                                "usb.release_interface",
                                "usb.bulk_transfer",
                                "brain.memory.get",
                                "brain.memory.set",
                            ],
                        },
                        "payload": {"type": "object", "additionalProperties": True},
                        "detail": {"type": "string"},
                    },
                    "required": ["action", "payload", "detail"],
                },
            },
            {
                "type": "function",
                "name": "memory_get",
                "description": "Read persistent memory (notes) stored on the device.",
                "parameters": {"type": "object", "additionalProperties": False, "properties": {}, "required": []},
            },
            {
                "type": "function",
                "name": "memory_set",
                "description": "Replace persistent memory (notes) stored on the device.",
                "parameters": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {"content": {"type": "string"}},
                    "required": ["content"],
                },
            },
            {
                "type": "function",
                "name": "run_python",
                "description": "Run Python locally (equivalent to: python <args>) within the user directory.",
                "parameters": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "args": {"type": "string"},
                        "cwd": {"type": "string"},
                    },
                    "required": ["args", "cwd"],
                },
            },
            {
                "type": "function",
                "name": "run_pip",
                "description": "Run pip locally (equivalent to: pip <args>) within the user directory.",
                "parameters": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "args": {"type": "string"},
                        "cwd": {"type": "string"},
                    },
                    "required": ["args", "cwd"],
                },
            },
            {
                "type": "function",
                "name": "run_curl",
                "description": "Run curl locally (equivalent to: curl <args>) within the user directory.",
                "parameters": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "args": {"type": "string"},
                        "cwd": {"type": "string"},
                    },
                    "required": ["args", "cwd"],
                },
            },
            {
                "type": "function",
                "name": "web_search",
                "description": "Search the web (permission-gated). Provider defaults to auto (Brave if configured, else DuckDuckGo Instant Answer).",
                "parameters": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "query": {"type": "string"},
                        "max_results": {"type": "integer"},
                        "provider": {"type": "string"},
                    },
                    "required": ["query", "max_results"],
                },
            },
            {
                "type": "function",
                "name": "write_file",
                "description": "Write UTF-8 text file under user root.",
                "parameters": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "path": {"type": "string"},
                        "content": {"type": "string"},
                    },
                    "required": ["path", "content"],
                },
            },
            {
                "type": "function",
                "name": "mkdir",
                "description": "Create a directory under the user root.",
                "parameters": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "path": {"type": "string"},
                        "parents": {"type": "boolean"},
                    },
                    "required": ["path", "parents"],
                },
            },
            {
                "type": "function",
                "name": "move_path",
                "description": "Move/rename a file or directory within the user root.",
                "parameters": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "src": {"type": "string"},
                        "dst": {"type": "string"},
                        "overwrite": {"type": "boolean"},
                    },
                    "required": ["src", "dst", "overwrite"],
                },
            },
            {
                "type": "function",
                "name": "delete_path",
                "description": "Delete a file or directory under the user root.",
                "parameters": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "path": {"type": "string"},
                        "recursive": {"type": "boolean"},
                    },
                    "required": ["path", "recursive"],
                },
            },
            {
                "type": "function",
                "name": "sleep",
                "description": "Pause execution for small delay.",
                "parameters": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "seconds": {"type": "number"},
                    },
                    "required": ["seconds"],
                },
            },
        ]

    def _process_with_responses_tools(self, item: Dict) -> None:
        session_id = self._session_id_for_item(item)
        self._active_identity = session_id or "default"
        persistent_memory = self._get_persistent_memory()
        dialogue = self._list_dialogue(session_id=session_id, limit=30)
        # _process_item already recorded the current user message; don't duplicate it in the prompt.
        if dialogue and dialogue[-1].get("role") == "user" and dialogue[-1].get("text") == str(item.get("text") or ""):
            dialogue = dialogue[:-1]
        cfg = self.get_config()
        model = str(cfg.get("model") or "").strip()
        provider_url = str(cfg.get("provider_url") or "").strip()
        key_name = str(cfg.get("api_key_credential") or "").strip()
        tool_policy = str(cfg.get("tool_policy") or "auto").strip().lower()
        require_tool = tool_policy == "required" and self._needs_tool_for_text(str(item.get("text") or ""))
        tool_required_unsatisfied = bool(require_tool)
        if not model or not provider_url or not key_name:
            self._record_message(
                "assistant",
                "Brain is not configured yet. Set provider_url, model, and api_key_credential via /brain/config.",
                {"item_id": item.get("id")},
            )
            return

        api_key = self._get_api_key(key_name)
        if not api_key:
            self._record_message(
                "assistant",
                f"Missing API credential '{key_name}'. Set it in vault or provide env var, then continue.",
                {"item_id": item.get("id")},
            )
            return

        tools = self._responses_tools()
        # Some models require several tool rounds before they "decide" to stop. Keep this
        # configurable so we can tune per-provider/model without shipping a new APK.
        max_rounds = int(self._config.get("max_tool_rounds", 12) or 12)
        max_rounds = max(1, min(max_rounds, 24))
        max_actions = max(1, min(int(self._config.get("max_actions", 6) or 6), 12))
        previous_response_id: Optional[str] = None
        forced_rounds = 0
        last_tool_summaries: List[Dict[str, Any]] = []
        # Build a normal conversation for the model so it can use context naturally.
        pending_input: List[Dict[str, Any]] = []
        pending_input.append(
            {
                "role": "user",
                "content": (
                    "Session notes (ephemeral, no permissions required):\n"
                    + json.dumps(self._session_notes.get(session_id) or {}, ensure_ascii=True)
                    + "\n\nPersistent memory (may be empty; writing may require permission):\n"
                    + (persistent_memory.strip() or "(empty)")
                ),
            }
        )
        def _decorate_with_actor(role: str, text: str, meta: Dict) -> str:
            actor = str((meta or {}).get("actor") or "").strip().lower()
            if not actor:
                return text
            # Keep these prefixes stable; they become part of the model-visible transcript.
            if actor == "codex":
                return "[CODEX] " + text
            if actor in {"human", "user"}:
                return text
            if actor == "agent":
                return text
            return f"[{actor.upper()}] " + text

        for msg in dialogue:
            role = str(msg.get("role") or "")
            text = msg.get("text")
            meta = msg.get("meta") if isinstance(msg.get("meta"), dict) else {}
            if role in {"user", "assistant"} and isinstance(text, str) and text.strip():
                pending_input.append({"role": role, "content": _decorate_with_actor(role, text, meta)})
        cur_text = str(item.get("text") or "")
        cur_meta = item.get("meta") if isinstance(item.get("meta"), dict) else {}
        pending_input.append({"role": "user", "content": _decorate_with_actor("user", cur_text, cur_meta)})

        for _ in range(max_rounds):
            system_prompt = str(cfg.get("system_prompt") or "")
            policy_blob = self._user_root_policy_blob()
            if policy_blob:
                system_prompt = (system_prompt + "\n\n" + policy_blob).strip()

            body: Dict[str, Any] = {
                "model": model,
                "tools": tools,
                "input": pending_input,
                # Keep instructions on every round; some models drift once the tool loop begins.
                "instructions": system_prompt,
            }
            if previous_response_id:
                body["previous_response_id"] = previous_response_id

            resp = requests.post(
                provider_url,
                headers={
                    "Authorization": f"Bearer {api_key}",
                    "Content-Type": "application/json",
                },
                data=json.dumps(body),
                timeout=40,
            )
            resp.raise_for_status()
            payload = resp.json()
            previous_response_id = payload.get("id")

            output_items = payload.get("output") or []
            message_texts: List[str] = []
            for out in output_items:
                if not isinstance(out, dict) or out.get("type") != "message":
                    continue
                text_parts: List[str] = []
                for part in out.get("content") or []:
                    if isinstance(part, dict) and part.get("type") == "output_text":
                        t = str(part.get("text") or "").strip()
                        if t:
                            text_parts.append(t)
                if text_parts:
                    message_texts.append("\n".join(text_parts))

            calls = [o for o in output_items if isinstance(o, dict) and o.get("type") == "function_call"]
            if not calls:
                # If we require tool calls for this user request, don't accept a "plain" response
                # until we've observed at least one tool call.
                if tool_required_unsatisfied and forced_rounds < 1:
                    forced_rounds += 1
                    pending_input = [
                        {
                            "role": "user",
                            "content": (
                                "Tool policy is REQUIRED for this request. "
                                "You MUST call one or more tools (device_api, run_python/run_pip/run_curl, "
                                "filesystem tools, write_file, sleep) to perform the action(s), "
                                "then summarize after tool outputs are provided. "
                                "Do not claim you executed anything without tool output."
                            ),
                        }
                    ]
                    continue

                # Accept and record any assistant message text (if present).
                for text in message_texts:
                    self._record_message("assistant", text, {"item_id": item.get("id"), "session_id": session_id})
                    self._emit_log("brain_response", {"item_id": item.get("id"), "text": text[:300]})
                return

            tool_required_unsatisfied = False

            # Record assistant message text only once we have tool calls for this round.
            for text in message_texts:
                self._record_message("assistant", text, {"item_id": item.get("id"), "session_id": session_id})
                self._emit_log("brain_response", {"item_id": item.get("id"), "text": text[:300]})

            pending_input = []
            last_tool_summaries = []
            for call in calls[:max_actions]:
                name = str(call.get("name") or "")
                call_id = str(call.get("call_id") or "")
                raw_args = call.get("arguments")
                try:
                    args = json.loads(raw_args) if isinstance(raw_args, str) else (raw_args or {})
                except Exception:
                    args = {}
                if not isinstance(args, dict):
                    args = {}
                result = self._execute_function_tool(item, name, args)
                last_tool_summaries.append(
                    {
                        "tool": name,
                        "args": args,
                        "status": (result.get("status") if isinstance(result, dict) else None),
                        "error": (result.get("error") if isinstance(result, dict) else None),
                    }
                )
                # If a permission gate is hit, surface it immediately and stop.
                if isinstance(result, dict) and str(result.get("status") or "") in {"permission_required", "permission_expired"}:
                    req = result.get("request") if isinstance(result.get("request"), dict) else {}
                    tool = str(req.get("tool") or name or "unknown")
                    self._record_message(
                        "assistant",
                        f"Permission required for '{tool}'. Please approve the in-app prompt/notification to continue.",
                        {"item_id": item.get("id"), "session_id": session_id},
                    )
                    self._emit_log("brain_response", {"item_id": item.get("id"), "text": "permission_required"})
                    return
                # If the tool is blocked by policy, stop the loop and surface it clearly. Otherwise models
                # often keep retrying until max_rounds is exhausted.
                if isinstance(result, dict) and str(result.get("status") or "") == "error":
                    err = str(result.get("error") or "")
                    if err in {"command_not_allowed", "path_not_allowed", "invalid_path"}:
                        self._record_message(
                            "assistant",
                            f"Tool '{name}' failed with {err}. "
                            "This is blocked by local policy/sandbox. Try a different approach or change the policy.",
                            {"item_id": item.get("id"), "session_id": session_id},
                        )
                        self._emit_log("brain_response", {"item_id": item.get("id"), "text": "tool_error_blocked"})
                        return
                pending_input.append(
                    {
                        "type": "function_call_output",
                        "call_id": call_id,
                        "output": json.dumps(result),
                    }
                )
            # Nudge the model to stop once it has enough information.
            pending_input.append(
                {
                    "role": "user",
                    "content": (
                        "Tool outputs have been provided. "
                        "If the user's request is fully satisfied, respond with the final answer and STOP. "
                        "If there are still outstanding checklist items or follow-up actions needed to satisfy the request, "
                        "call additional tools now (within the remaining rounds) and only stop once the checklist is complete."
                    ),
                }
            )
            if not pending_input:
                return

        # If we reach here, we exhausted max_rounds without a final assistant message.
        # Avoid leaving the UI stuck waiting.
        summary = ""
        if last_tool_summaries:
            # Keep it short so we don't spam the UI. This is for debugging.
            parts = []
            for s in last_tool_summaries[:6]:
                tool = str(s.get("tool") or "")
                status = str(s.get("status") or "")
                err = str(s.get("error") or "")
                if err:
                    parts.append(f"{tool}={status}/{err}")
                else:
                    parts.append(f"{tool}={status}")
            summary = " Last tools: " + ", ".join(parts) + "."
        self._record_message(
            "assistant",
            "Agent tool loop did not finish within the allowed rounds. "
            "The last tool outputs may contain the error (e.g., permission_required or command_not_allowed). "
            "Please retry or rephrase, and approve any pending permissions if prompted."
            + summary,
            {"item_id": item.get("id"), "session_id": session_id},
        )
        self._emit_log("brain_response", {"item_id": item.get("id"), "text": "tool_loop_exhausted"})
        return

    def _heuristic_plan(self, item: Dict) -> Dict:
        text = str(item.get("text") or "").lower()
        responses: List[str] = []
        actions: List[Dict] = []

        def device_status() -> None:
            responses.append("Checking SSH and Python status.")
            actions.append(
                {
                    "type": "tool_invoke",
                    "tool": "device_api",
                    "args": {
                        "action": "ssh.status",
                        "payload": {},
                        "detail": "Check SSH service status",
                    },
                }
            )
            actions.append(
                {
                    "type": "tool_invoke",
                    "tool": "device_api",
                    "args": {
                        "action": "python.status",
                        "payload": {},
                        "detail": "Check Python worker status",
                    },
                }
            )

        if any(k in text for k in ("status", "check", "state")) and any(
            k in text for k in ("ssh", "python", "worker", "device")
        ):
            device_status()
        elif "restart" in text and "python" in text:
            responses.append("Restarting Python worker.")
            actions.append(
                {
                    "type": "tool_invoke",
                    "tool": "device_api",
                    "args": {
                        "action": "python.restart",
                        "payload": {},
                        "detail": "Restart Python worker from agent request",
                    },
                }
            )
        elif "enable" in text and "ssh" in text:
            responses.append("Enabling SSH service.")
            actions.append(
                {
                    "type": "tool_invoke",
                    "tool": "device_api",
                    "args": {
                        "action": "ssh.config",
                        "payload": {"enabled": True},
                        "detail": "Enable SSH service from agent request",
                    },
                }
            )
        elif ("pin" in text and "ssh" in text) and any(k in text for k in ("start", "enable", "use")):
            responses.append("Starting SSH PIN authentication window.")
            actions.append(
                {
                    "type": "tool_invoke",
                    "tool": "device_api",
                    "args": {
                        "action": "ssh.pin.start",
                        "payload": {"seconds": 20},
                        "detail": "Start SSH PIN auth",
                    },
                }
            )
        elif "memory" in text and any(k in text for k in ("show", "get", "read")):
            responses.append("Reading persistent memory.")
            actions.append(
                {
                    "type": "tool_invoke",
                    "tool": "device_api",
                    "args": {
                        "action": "brain.memory.get",
                        "payload": {},
                        "detail": "Read persistent memory",
                    },
                }
            )

        return {"responses": responses, "actions": actions}

    def _plan_with_cloud(self, item: Dict, tool_results: Optional[List[Dict]] = None) -> Dict:
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

        api_key = self._get_api_key(key_name)
        if not api_key:
            return {
                "responses": [
                    f"Missing API credential '{key_name}'. Set it in vault or provide env var, then continue."
                ],
                "actions": [],
            }

        session_id = self._session_id_for_item(item)
        persistent_memory = self._get_persistent_memory()
        history = self._list_dialogue(session_id=session_id, limit=20)
        user_payload = {
            "item": item,
            "recent_messages": history,
            "persistent_memory": persistent_memory,
            "constraints": {
                "device_api_actions": [
                    "python.status",
                    "python.restart",
                    "ssh.status",
                    "ssh.config",
                    "ssh.pin.status",
                    "ssh.pin.start",
                    "ssh.pin.stop",
                    "brain.memory.get",
                    "brain.memory.set",
                ],
                "root": str(self._user_dir),
            },
            "tool_results": tool_results or [],
        }
        planner_prompt = (
            "Return strict JSON object with keys responses (string[]) and actions (object[]). "
            "Action objects: "
            "{type:'shell_exec', cmd:'python|pip|curl', args:'...', cwd:'/subdir'} OR "
            "{type:'filesystem', op:'list_dir|read_file|mkdir|move_path|delete_path', ...} OR "
            "{type:'write_file', path:'relative/path.py', content:'...'} OR "
            "{type:'tool_invoke', tool:'device_api', args:{...}, detail:'optional'} OR "
            "{type:'sleep', seconds:1}. "
            "Filesystem action shapes: "
            "- list_dir: {type:'filesystem', op:'list_dir', path:'relative/or/absolute', show_hidden:false, limit:200} "
            "- read_file: {type:'filesystem', op:'read_file', path:'relative/or/absolute', max_bytes:262144} "
            "- mkdir: {type:'filesystem', op:'mkdir', path:'relative/or/absolute', parents:true} "
            "- move_path: {type:'filesystem', op:'move_path', src:'...', dst:'...', overwrite:false} "
            "- delete_path: {type:'filesystem', op:'delete_path', path:'...', recursive:false}. "
            "For device actions, use tool='device_api' and args shape: "
            "{action:'python.status|python.restart|ssh.status|ssh.config|ssh.pin.status|ssh.pin.start|ssh.pin.stop|brain.memory.get|brain.memory.set', payload:{...}, detail:'...'}."
            "If user asks to check status, include at least one device_api status action. "
            "If user asks to change device state, include one device_api mutating action with minimal payload. "
            "Example output for status request: "
            "{\"responses\":[\"Checking current SSH and Python status.\"],"
            "\"actions\":[{\"type\":\"tool_invoke\",\"tool\":\"device_api\",\"args\":{\"action\":\"ssh.status\",\"payload\":{},\"detail\":\"Check SSH service status\"}},"
            "{\"type\":\"tool_invoke\",\"tool\":\"device_api\",\"args\":{\"action\":\"python.status\",\"payload\":{},\"detail\":\"Check Python worker status\"}}]}. "
            "If Input.tool_results is non-empty, use those results to decide next actions or final responses. "
            "Set actions=[] when the task is complete. "
            "Input:\n" + json.dumps(user_payload)
        )
        system_prompt = str(cfg.get("system_prompt") or "")
        policy_blob = self._user_root_policy_blob()
        if policy_blob:
            system_prompt = (system_prompt + "\n\n" + policy_blob).strip()
        if provider_url.rstrip("/").endswith("/responses"):
            body = {
                "model": model,
                "instructions": system_prompt,
                "input": [{"role": "user", "content": planner_prompt}],
            }
        else:
            body = {
                "model": model,
                "temperature": float(cfg.get("temperature", 0.2) or 0.2),
                "messages": [
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": planner_prompt},
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
            payload.get("output_text")
            or (((payload.get("choices") or [{}])[0]).get("message") or {}).get("content")
            or "{}"
        )
        if not isinstance(content, str):
            content = "{}"
            for out_item in payload.get("output") or []:
                if not isinstance(out_item, dict):
                    continue
                if out_item.get("type") != "message":
                    continue
                for part in out_item.get("content") or []:
                    if isinstance(part, dict) and part.get("type") == "output_text":
                        content = str(part.get("text") or "")
                        break
                if content != "{}":
                    break
        parsed = self._parse_json_object(content)
        if not isinstance(parsed, dict):
            return {"responses": ["Model response was not valid JSON."], "actions": []}
        parsed.setdefault("responses", [])
        parsed.setdefault("actions", [])
        if not parsed.get("responses") and not parsed.get("actions"):
            heuristic = self._heuristic_plan(item)
            if heuristic.get("responses") or heuristic.get("actions"):
                return heuristic
            return {
                "responses": [
                    "Model returned no actionable plan. Please retry with a clearer request."
                ],
                "actions": [],
            }
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

    def _execute_function_tool(self, item: Dict, name: str, args: Dict[str, Any]) -> Dict[str, Any]:
        if name == "list_dir":
            action = {
                "type": "filesystem",
                "op": "list_dir",
                "path": str(args.get("path") or ""),
                "show_hidden": bool(args.get("show_hidden") or False),
                "limit": int(args.get("limit") or 200),
            }
            return self._execute_action(item, action)
        if name == "read_file":
            action = {
                "type": "filesystem",
                "op": "read_file",
                "path": str(args.get("path") or ""),
                "max_bytes": int(args.get("max_bytes") or 262144),
            }
            return self._execute_action(item, action)
        if name == "device_api":
            action_name = str(args.get("action") or "")
            if action_name == "brain.memory.set" and not self._explicit_persist_memory_requested(str(item.get("text") or "")):
                return {
                    "status": "error",
                    "error": "command_not_allowed",
                    "detail": "Persistent memory writes require an explicit user request to save/persist.",
                }
            action = {
                "type": "tool_invoke",
                "tool": "device_api",
                "args": {
                    "action": action_name,
                    "payload": args.get("payload") if isinstance(args.get("payload"), dict) else {},
                    "detail": str(args.get("detail") or ""),
                },
            }
            return self._execute_action(item, action)
        if name == "memory_get":
            action = {
                "type": "tool_invoke",
                "tool": "device_api",
                "args": {"action": "brain.memory.get", "payload": {}, "detail": "Read persistent memory"},
            }
            return self._execute_action(item, action)
        if name == "memory_set":
            if not self._explicit_persist_memory_requested(str(item.get("text") or "")):
                return {
                    "status": "error",
                    "error": "command_not_allowed",
                    "detail": "Persistent memory writes require an explicit user request to save/persist.",
                }
            action = {
                "type": "tool_invoke",
                "tool": "device_api",
                "args": {
                    "action": "brain.memory.set",
                    "payload": {"content": str(args.get("content") or "")},
                    "detail": "Write persistent memory",
                },
            }
            return self._execute_action(item, action)
        if name == "shell_exec":
            action = {
                "type": "shell_exec",
                "cmd": str(args.get("cmd") or ""),
                "args": str(args.get("args") or ""),
                "cwd": str(args.get("cwd") or ""),
            }
            return self._execute_action(item, action)
        if name == "run_python":
            action = {
                "type": "shell_exec",
                "cmd": "python",
                "args": str(args.get("args") or ""),
                "cwd": str(args.get("cwd") or ""),
            }
            return self._execute_action(item, action)
        if name == "run_pip":
            action = {
                "type": "shell_exec",
                "cmd": "pip",
                "args": str(args.get("args") or ""),
                "cwd": str(args.get("cwd") or ""),
            }
            return self._execute_action(item, action)
        if name == "run_curl":
            action = {
                "type": "shell_exec",
                "cmd": "curl",
                "args": str(args.get("args") or ""),
                "cwd": str(args.get("cwd") or ""),
            }
            return self._execute_action(item, action)
        if name == "web_search":
            query = str(args.get("query") or "").strip()
            if not query:
                return {"status": "error", "error": "missing_query"}
            try:
                max_results = int(args.get("max_results") or 5)
            except Exception:
                max_results = 5
            max_results = max(1, min(max_results, 10))
            provider = str(args.get("provider") or "").strip()
            capability = "web.search"
            permission_id = self._capability_permissions.get(capability, "")

            def do_request(pid: str) -> tuple[int, Dict[str, Any]]:
                headers = {}
                if self._active_identity:
                    headers["X-Kugutz-Identity"] = self._active_identity
                resp = requests.post(
                    "http://127.0.0.1:8765/web/search",
                    json={
                        "query": query,
                        "max_results": max_results,
                        "provider": provider,
                        "permission_id": (pid or ""),
                        "identity": self._active_identity,
                    },
                    headers=headers,
                    timeout=15,
                )
                body = resp.json() if resp.content else {}
                return resp.status_code, body if isinstance(body, dict) else {"raw": body}

            try:
                http_status, body = do_request(permission_id)
            except Exception as ex:
                return {"status": "error", "error": "search_failed", "detail": str(ex)}

            if http_status == 403 and body.get("status") == "permission_required":
                req = body.get("request") if isinstance(body.get("request"), dict) else {}
                req_id = str(req.get("id") or "").strip()
                if req_id:
                    self._capability_permissions[capability] = req_id
                    wait_status = self._wait_for_permission(req_id, timeout_s=float(self._config.get("permission_timeout_s", 45)))
                    if wait_status == "approved":
                        try:
                            http_status, body = do_request(req_id)
                        except Exception as ex:
                            return {"status": "error", "error": "search_failed", "detail": str(ex)}
                    elif wait_status == "denied":
                        return {"status": "error", "error": "permission_denied", "detail": "Web search permission was denied."}
                    else:
                        return {
                            "status": "error",
                            "error": "permission_timeout",
                            "detail": "Web search requires permission. Please approve the prompt/notification in the app and retry.",
                        }
                else:
                    return {
                        "status": "error",
                        "error": "permission_required",
                        "detail": "Web search requires permission. Please approve the prompt/notification in the app and retry.",
                    }

            if http_status not in (200, 201) or not isinstance(body, dict) or body.get("status") != "ok":
                return {"status": "error", "error": "upstream_error", "http_status": http_status, "body": body}

            if self._capability_permissions.get(capability):
                # Keep whatever permission id we used last for future calls.
                pass
            return body
        if name == "write_file":
            action = {
                "type": "write_file",
                "path": str(args.get("path") or ""),
                "content": str(args.get("content") or ""),
            }
            return self._execute_action(item, action)
        if name == "mkdir":
            action = {
                "type": "filesystem",
                "op": "mkdir",
                "path": str(args.get("path") or ""),
                "parents": bool(args.get("parents") or False),
            }
            return self._execute_action(item, action)
        if name == "move_path":
            action = {
                "type": "filesystem",
                "op": "move_path",
                "src": str(args.get("src") or ""),
                "dst": str(args.get("dst") or ""),
                "overwrite": bool(args.get("overwrite") or False),
            }
            return self._execute_action(item, action)
        if name == "delete_path":
            action = {
                "type": "filesystem",
                "op": "delete_path",
                "path": str(args.get("path") or ""),
                "recursive": bool(args.get("recursive") or False),
            }
            return self._execute_action(item, action)
        if name == "sleep":
            action = {
                "type": "sleep",
                "seconds": float(args.get("seconds") or 0),
            }
            return self._execute_action(item, action)
        result = {"status": "error", "error": "unknown_tool"}
        self._record_message(
            "tool",
            json.dumps({"tool_name": name, "args": args, "result": result}),
            {"item_id": item.get("id")},
        )
        return result

    def _execute_action(self, item: Dict, action: Dict) -> Dict:
        a_type = str(action.get("type") or "").strip()
        result: Dict
        session_id = self._session_id_for_item(item)

        if a_type == "shell_exec":
            cmd = str(action.get("cmd") or "")
            args = str(action.get("args") or "")
            cwd = str(action.get("cwd") or "")
            if cmd not in {"python", "pip", "curl"}:
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
        elif a_type == "filesystem":
            op = str(action.get("op") or "")
            if op == "list_dir":
                result = self._fs_list_dir(
                    path=str(action.get("path") or ""),
                    show_hidden=bool(action.get("show_hidden") or False),
                    limit=int(action.get("limit") or 200),
                )
            elif op == "read_file":
                result = self._fs_read_file(
                    path=str(action.get("path") or ""),
                    max_bytes=int(action.get("max_bytes") or 262144),
                )
            elif op == "mkdir":
                result = self._fs_mkdir(
                    path=str(action.get("path") or ""),
                    parents=bool(action.get("parents") or False),
                )
            elif op == "move_path":
                result = self._fs_move(
                    src=str(action.get("src") or ""),
                    dst=str(action.get("dst") or ""),
                    overwrite=bool(action.get("overwrite") or False),
                )
            elif op == "delete_path":
                result = self._fs_delete(
                    path=str(action.get("path") or ""),
                    recursive=bool(action.get("recursive") or False),
                )
            else:
                result = {"status": "error", "error": "unsupported_fs_op"}
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
            {"item_id": item.get("id"), "session_id": session_id},
        )
        self._emit_log(
            "brain_action",
            {
                "item_id": item.get("id"),
                "type": a_type,
                "result": result,
            },
        )
        return result
