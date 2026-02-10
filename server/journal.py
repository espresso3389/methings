import json
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional


def _now_ms() -> int:
    return int(time.time() * 1000)


_SESSION_ID_SAFE = re.compile(r"[^A-Za-z0-9_.-]+")


def sanitize_session_id(session_id: str) -> str:
    s = str(session_id or "").strip()
    if not s:
        return "default"
    s = _SESSION_ID_SAFE.sub("_", s)
    s = s.strip("._-")
    if not s:
        return "default"
    return s[:80]


def _safe_basename(name: str) -> str:
    s = str(name or "").strip()
    if not s:
        return "note"
    s = _SESSION_ID_SAFE.sub("_", s)
    s = s.strip("._-")
    return (s or "note")[:80]


@dataclass(frozen=True)
class JournalConfig:
    max_current_bytes: int = 8 * 1024
    max_entry_inline_bytes: int = 16 * 1024
    rotate_entries_bytes: int = 256 * 1024
    max_list_limit: int = 200


class JournalStore:
    """
    File-backed journal under <user_dir>/journal.

    Layout (per session):
      journal/<sid>/CURRENT.md
      journal/<sid>/entries.jsonl
      journal/<sid>/entries.<ts>.jsonl (rotated)
      journal/<sid>/CURRENT.<ts>.md (rotated)
      journal/<sid>/entry.<ts>.<name>.md (oversize entry payload)
    """

    def __init__(self, root_dir: Path, config: Optional[JournalConfig] = None):
        self.root_dir = Path(root_dir)
        self.cfg = config or JournalConfig()
        self.root_dir.mkdir(parents=True, exist_ok=True)

    def config(self) -> Dict[str, Any]:
        return {
            "max_current_bytes": int(self.cfg.max_current_bytes),
            "max_entry_inline_bytes": int(self.cfg.max_entry_inline_bytes),
            "rotate_entries_bytes": int(self.cfg.rotate_entries_bytes),
            "max_list_limit": int(self.cfg.max_list_limit),
            "root": str(self.root_dir),
        }

    def _session_dir(self, session_id: str) -> Path:
        sid = sanitize_session_id(session_id)
        p = (self.root_dir / sid).resolve()
        # Ensure we don't escape root_dir.
        root = self.root_dir.resolve()
        if not str(p).startswith(str(root)):
            p = root / "default"
        p.mkdir(parents=True, exist_ok=True)
        return p

    def _current_path(self, session_id: str) -> Path:
        return self._session_dir(session_id) / "CURRENT.md"

    def _entries_path(self, session_id: str) -> Path:
        return self._session_dir(session_id) / "entries.jsonl"

    def get_current(self, session_id: str) -> Dict[str, Any]:
        p = self._current_path(session_id)
        try:
            raw = p.read_bytes()
            text = raw.decode("utf-8", errors="replace")
            updated_at = int(p.stat().st_mtime * 1000)
            return {"status": "ok", "session_id": sanitize_session_id(session_id), "text": text, "updated_at": updated_at}
        except FileNotFoundError:
            return {"status": "ok", "session_id": sanitize_session_id(session_id), "text": "", "updated_at": 0}
        except Exception as ex:
            return {"status": "error", "error": "read_failed", "detail": str(ex)}

    def set_current(self, session_id: str, text: str) -> Dict[str, Any]:
        p = self._current_path(session_id)
        sid = sanitize_session_id(session_id)
        body = str(text or "")
        raw = body.encode("utf-8", errors="replace")
        ts = _now_ms()
        try:
            p.parent.mkdir(parents=True, exist_ok=True)
            if len(raw) > int(self.cfg.max_current_bytes):
                # Keep the full text, but cap CURRENT.md for prompt/context efficiency.
                rotated = p.parent / f"CURRENT.{ts}.md"
                rotated.write_bytes(raw)
                head = raw[: max(0, int(self.cfg.max_current_bytes) - 256)].decode("utf-8", errors="replace")
                note = (
                    head.rstrip()
                    + "\n\n"
                    + f"(journal: current note was too large and was moved to {rotated.name})\n"
                )
                p.write_text(note, encoding="utf-8")
                return {
                    "status": "ok",
                    "session_id": sid,
                    "rotated_to": str(rotated),
                    "truncated": True,
                    "updated_at": ts,
                }
            p.write_bytes(raw)
            return {"status": "ok", "session_id": sid, "truncated": False, "updated_at": ts}
        except Exception as ex:
            return {"status": "error", "error": "write_failed", "detail": str(ex)}

    def _rotate_entries_if_needed(self, entries_path: Path) -> Optional[Path]:
        try:
            if not entries_path.exists():
                return None
            size = int(entries_path.stat().st_size)
            if size < int(self.cfg.rotate_entries_bytes):
                return None
            ts = _now_ms()
            rotated = entries_path.parent / f"entries.{ts}.jsonl"
            entries_path.replace(rotated)
            return rotated
        except Exception:
            return None

    def append(
        self,
        session_id: str,
        *,
        kind: str,
        title: str,
        text: str,
        meta: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        sid = sanitize_session_id(session_id)
        k = str(kind or "note").strip() or "note"
        t = str(title or "").strip()
        body = str(text or "")
        m = meta if isinstance(meta, dict) else {}
        ts = _now_ms()

        sess_dir = self._session_dir(sid)
        entries_path = sess_dir / "entries.jsonl"
        rotated = self._rotate_entries_if_needed(entries_path)

        stored_path = ""
        inline_text = body
        raw = body.encode("utf-8", errors="replace")
        if len(raw) > int(self.cfg.max_entry_inline_bytes):
            fname = f"entry.{ts}.{_safe_basename(t)}.md" if t else f"entry.{ts}.md"
            p = sess_dir / fname
            try:
                p.write_bytes(raw)
                stored_path = str(p)
                inline_text = ""
            except Exception:
                # If storing the oversized body fails, fall back to truncation inline.
                inline_text = raw[: int(self.cfg.max_entry_inline_bytes)].decode("utf-8", errors="replace")

        rec = {
            "ts": ts,
            "kind": k,
            "title": t,
            "text": inline_text,
            "stored_path": stored_path,
            "meta": m,
        }
        try:
            entries_path.parent.mkdir(parents=True, exist_ok=True)
            with entries_path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(rec, ensure_ascii=True) + "\n")
            return {
                "status": "ok",
                "session_id": sid,
                "ts": ts,
                "rotated_from": str(rotated) if rotated else "",
                "stored_path": stored_path,
            }
        except Exception as ex:
            return {"status": "error", "error": "append_failed", "detail": str(ex)}

    def _entries_files_newest_first(self, session_id: str) -> List[Path]:
        sess_dir = self._session_dir(session_id)
        cur = sess_dir / "entries.jsonl"
        rotated = sorted(sess_dir.glob("entries.*.jsonl"), reverse=True)
        out: List[Path] = []
        if cur.exists():
            out.append(cur)
        out.extend(rotated)
        return out

    def list_entries(self, session_id: str, limit: int = 50) -> Dict[str, Any]:
        sid = sanitize_session_id(session_id)
        try:
            limit_i = int(limit or 50)
        except Exception:
            limit_i = 50
        limit_i = max(1, min(limit_i, int(self.cfg.max_list_limit)))

        items: List[Dict[str, Any]] = []
        for p in self._entries_files_newest_first(sid):
            if len(items) >= limit_i:
                break
            try:
                text = p.read_text(encoding="utf-8", errors="replace")
            except Exception:
                continue
            lines = [ln for ln in text.splitlines() if ln.strip()]
            for ln in reversed(lines):
                if len(items) >= limit_i:
                    break
                try:
                    rec = json.loads(ln)
                except Exception:
                    continue
                if not isinstance(rec, dict):
                    continue
                # Normalize.
                rec_out = {
                    "ts": int(rec.get("ts") or 0),
                    "kind": str(rec.get("kind") or ""),
                    "title": str(rec.get("title") or ""),
                    "text": str(rec.get("text") or ""),
                    "stored_path": str(rec.get("stored_path") or ""),
                    "meta": rec.get("meta") if isinstance(rec.get("meta"), dict) else {},
                }
                items.append(rec_out)

        items.sort(key=lambda x: int(x.get("ts") or 0))
        if len(items) > limit_i:
            items = items[-limit_i:]
        return {"status": "ok", "session_id": sid, "entries": items, "limit": limit_i}
