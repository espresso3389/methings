import sqlite3
import os
import base64
from pathlib import Path
from typing import Dict, List, Optional
import time


def _now_ms() -> int:
    return int(time.time() * 1000)


class Storage:
    def __init__(self, db_path: Path):
        self.db_path = db_path
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._init_db()

    def _connect(self):
        key = self._load_sqlcipher_key()
        if key:
            try:
                import pysqlcipher3
                conn = pysqlcipher3.dbapi2.connect(self.db_path)
                conn.execute(f"PRAGMA key = '{key}';")
            except Exception:
                conn = sqlite3.connect(self.db_path)
        else:
            conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        return conn

    def _load_sqlcipher_key(self):
        key_file = os.environ.get("SQLCIPHER_KEY_FILE")
        if not key_file:
            return None
        try:
            raw = Path(key_file).read_text().strip()
            if not raw:
                return None
            return raw
        except Exception:
            return None

    def _init_db(self):
        with self._connect() as conn:
            cur = conn.cursor()
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY,
                    created_at INTEGER
                )
                """
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT,
                    role TEXT,
                    content TEXT,
                    created_at INTEGER
                )
                """
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS permissions (
                    id TEXT PRIMARY KEY,
                    tool TEXT,
                    detail TEXT,
                    status TEXT,
                    scope TEXT,
                    expires_at INTEGER,
                    created_at INTEGER
                )
                """
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS webhooks (
                    provider TEXT PRIMARY KEY,
                    url TEXT,
                    enabled INTEGER,
                    updated_at INTEGER
                )
                """
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS oauth_config (
                    provider TEXT PRIMARY KEY,
                    client_id TEXT,
                    client_secret TEXT,
                    auth_url TEXT,
                    token_url TEXT,
                    redirect_uri TEXT,
                    scope TEXT,
                    updated_at INTEGER
                )
                """
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS oauth_state (
                    state TEXT PRIMARY KEY,
                    provider TEXT,
                    code_verifier TEXT,
                    created_at INTEGER
                )
                """
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS oauth_token (
                    provider TEXT PRIMARY KEY,
                    access_token TEXT,
                    refresh_token TEXT,
                    expires_at INTEGER,
                    updated_at INTEGER
                )
                """
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS api_keys (
                    provider TEXT PRIMARY KEY,
                    api_key TEXT,
                    updated_at INTEGER
                )
                """
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS audit_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event TEXT,
                    data TEXT,
                    created_at INTEGER
                )
                """
            )

    def create_session(self) -> str:
        session_id = f"s_{_now_ms()}"
        with self._connect() as conn:
            conn.execute(
                "INSERT INTO sessions (id, created_at) VALUES (?, ?)",
                (session_id, _now_ms()),
            )
        return session_id

    def get_session(self, session_id: str) -> Optional[Dict]:
        with self._connect() as conn:
            cur = conn.execute("SELECT * FROM sessions WHERE id = ?", (session_id,))
            row = cur.fetchone()
            if not row:
                return None
            msg_cur = conn.execute(
                "SELECT role, content, created_at FROM messages WHERE session_id = ? ORDER BY id",
                (session_id,),
            )
            messages = [dict(m) for m in msg_cur.fetchall()]
        return {"id": row["id"], "created_at": row["created_at"], "messages": messages}

    def add_message(self, session_id: str, role: str, content: str) -> int:
        with self._connect() as conn:
            cur = conn.execute(
                "INSERT INTO messages (session_id, role, content, created_at) VALUES (?, ?, ?, ?)",
                (session_id, role, content, _now_ms()),
            )
            return cur.lastrowid

    def create_permission_request(self, tool: str, detail: str, scope: str, expires_at: int | None) -> str:
        request_id = f"p_{_now_ms()}"
        with self._connect() as conn:
            conn.execute(
                "INSERT INTO permissions (id, tool, detail, status, scope, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                (request_id, tool, detail, "pending", scope, expires_at, _now_ms()),
            )
        return request_id

    def get_permission_request(self, request_id: str) -> Optional[Dict]:
        with self._connect() as conn:
            cur = conn.execute("SELECT * FROM permissions WHERE id = ?", (request_id,))
            row = cur.fetchone()
            return dict(row) if row else None

    def get_pending_permissions(self) -> List[Dict]:
        with self._connect() as conn:
            cur = conn.execute("SELECT * FROM permissions WHERE status = 'pending' ORDER BY created_at")
            return [dict(r) for r in cur.fetchall()]

    def update_permission_status(self, request_id: str, status: str) -> None:
        with self._connect() as conn:
            conn.execute(
                "UPDATE permissions SET status = ? WHERE id = ?",
                (status, request_id),
            )

    def mark_permission_used(self, request_id: str) -> None:
        with self._connect() as conn:
            conn.execute(
                "UPDATE permissions SET status = ? WHERE id = ?",
                ("used", request_id),
            )

    def set_webhook(self, provider: str, url: str, enabled: bool) -> None:
        with self._connect() as conn:
            conn.execute(
                "INSERT INTO webhooks (provider, url, enabled, updated_at) VALUES (?, ?, ?, ?)"
                " ON CONFLICT(provider) DO UPDATE SET url=excluded.url, enabled=excluded.enabled, updated_at=excluded.updated_at",
                (provider, url, 1 if enabled else 0, _now_ms()),
            )

    def get_webhooks(self) -> Dict[str, Dict]:
        with self._connect() as conn:
            cur = conn.execute("SELECT * FROM webhooks")
            rows = [dict(r) for r in cur.fetchall()]
        return {r["provider"]: r for r in rows}

    def add_audit(self, event: str, data: str) -> None:
        with self._connect() as conn:
            conn.execute(
                "INSERT INTO audit_log (event, data, created_at) VALUES (?, ?, ?)",
                (event, data, _now_ms()),
            )

    def get_audit(self, limit: int = 50) -> List[Dict]:
        with self._connect() as conn:
            cur = conn.execute(
                "SELECT event, data, created_at FROM audit_log ORDER BY id DESC LIMIT ?",
                (limit,),
            )
            return [dict(r) for r in cur.fetchall()]

    def set_oauth_config(self, provider: str, config: Dict) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO oauth_config
                (provider, client_id, client_secret, auth_url, token_url, redirect_uri, scope, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(provider) DO UPDATE SET
                    client_id=excluded.client_id,
                    client_secret=excluded.client_secret,
                    auth_url=excluded.auth_url,
                    token_url=excluded.token_url,
                    redirect_uri=excluded.redirect_uri,
                    scope=excluded.scope,
                    updated_at=excluded.updated_at
                """,
                (
                    provider,
                    config.get("client_id"),
                    config.get("client_secret"),
                    config.get("auth_url"),
                    config.get("token_url"),
                    config.get("redirect_uri"),
                    config.get("scope"),
                    _now_ms(),
                ),
            )

    def get_oauth_config(self, provider: str) -> Optional[Dict]:
        with self._connect() as conn:
            cur = conn.execute("SELECT * FROM oauth_config WHERE provider = ?", (provider,))
            row = cur.fetchone()
            return dict(row) if row else None

    def save_oauth_state(self, state: str, provider: str, code_verifier: str) -> None:
        with self._connect() as conn:
            conn.execute(
                "INSERT INTO oauth_state (state, provider, code_verifier, created_at) VALUES (?, ?, ?, ?)",
                (state, provider, code_verifier, _now_ms()),
            )

    def get_oauth_state(self, state: str) -> Optional[Dict]:
        with self._connect() as conn:
            cur = conn.execute("SELECT * FROM oauth_state WHERE state = ?", (state,))
            row = cur.fetchone()
            return dict(row) if row else None

    def delete_oauth_state(self, state: str) -> None:
        with self._connect() as conn:
            conn.execute("DELETE FROM oauth_state WHERE state = ?", (state,))

    def save_oauth_token(self, provider: str, token: Dict) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO oauth_token (provider, access_token, refresh_token, expires_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(provider) DO UPDATE SET
                    access_token=excluded.access_token,
                    refresh_token=excluded.refresh_token,
                    expires_at=excluded.expires_at,
                    updated_at=excluded.updated_at
                """,
                (
                    provider,
                    token.get("access_token"),
                    token.get("refresh_token"),
                    token.get("expires_at"),
                    _now_ms(),
                ),
            )

    def get_oauth_token(self, provider: str) -> Optional[Dict]:
        with self._connect() as conn:
            cur = conn.execute("SELECT * FROM oauth_token WHERE provider = ?", (provider,))
            row = cur.fetchone()
            return dict(row) if row else None

    def set_api_key(self, provider: str, api_key: str) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO api_keys (provider, api_key, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(provider) DO UPDATE SET
                    api_key=excluded.api_key,
                    updated_at=excluded.updated_at
                """,
                (provider, api_key, _now_ms()),
            )

    def get_api_key(self, provider: str) -> Optional[Dict]:
        with self._connect() as conn:
            cur = conn.execute("SELECT * FROM api_keys WHERE provider = ?", (provider,))
            row = cur.fetchone()
            return dict(row) if row else None

    def delete_api_key(self, provider: str) -> None:
        with self._connect() as conn:
            conn.execute("DELETE FROM api_keys WHERE provider = ?", (provider,))
