import sqlite3
from pathlib import Path
from typing import Dict, List, Optional
import time


def _now_ms() -> int:
    return int(time.time() * 1000)

def _row_factory(cursor, row):
    return {desc[0]: row[idx] for idx, desc in enumerate(cursor.description)}


class Storage:
    def __init__(self, db_path: Path):
        self.db_path = db_path
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._encryption_mode = "sqlite"
        self._init_db()

    def _connect(self):
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = _row_factory
        return conn

    def encryption_status(self) -> Dict[str, object]:
        return {
            "encrypted": False,
            "mode": self._encryption_mode,
        }

    def _init_db(self):
        with self._connect() as conn:
            self._create_schema(conn)

    def _create_schema(self, conn):
        cur = conn.cursor()
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
            CREATE TABLE IF NOT EXISTS credentials (
                name TEXT PRIMARY KEY,
                value TEXT,
                updated_at INTEGER
            )
            """
        )
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS services (
                name TEXT PRIMARY KEY,
                code_hash TEXT,
                token TEXT,
                created_at INTEGER,
                updated_at INTEGER
            )
            """
        )
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS service_credentials (
                service_name TEXT,
                name TEXT,
                value TEXT,
                updated_at INTEGER,
                PRIMARY KEY(service_name, name)
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
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT,
                updated_at INTEGER
            )
            """
        )

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

    def get_setting(self, key: str) -> Optional[str]:
        with self._connect() as conn:
            cur = conn.execute("SELECT value FROM settings WHERE key = ?", (key,))
            row = cur.fetchone()
            return row["value"] if row else None

    def set_setting(self, key: str, value: str) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO settings (key, value, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(key) DO UPDATE SET
                    value=excluded.value,
                    updated_at=excluded.updated_at
                """,
                (key, value, _now_ms()),
            )

    def set_credential(self, name: str, value: str) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO credentials (name, value, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET
                    value=excluded.value,
                    updated_at=excluded.updated_at
                """,
                (name, value, _now_ms()),
            )

    def get_credential(self, name: str) -> Optional[Dict]:
        with self._connect() as conn:
            cur = conn.execute("SELECT * FROM credentials WHERE name = ?", (name,))
            row = cur.fetchone()
            return dict(row) if row else None

    def delete_credential(self, name: str) -> None:
        with self._connect() as conn:
            conn.execute("DELETE FROM credentials WHERE name = ?", (name,))

    def list_credentials(self) -> List[Dict]:
        with self._connect() as conn:
            cur = conn.execute("SELECT name, updated_at FROM credentials ORDER BY name")
            return [dict(r) for r in cur.fetchall()]

    def upsert_service(self, name: str, code_hash: str, token: str) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO services (name, code_hash, token, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET
                    code_hash=excluded.code_hash,
                    token=excluded.token,
                    updated_at=excluded.updated_at
                """,
                (name, code_hash, token, _now_ms(), _now_ms()),
            )

    def get_service(self, name: str) -> Optional[Dict]:
        with self._connect() as conn:
            cur = conn.execute("SELECT * FROM services WHERE name = ?", (name,))
            row = cur.fetchone()
            return dict(row) if row else None

    def list_services(self) -> List[Dict]:
        with self._connect() as conn:
            cur = conn.execute("SELECT name, code_hash, updated_at FROM services ORDER BY name")
            return [dict(r) for r in cur.fetchall()]

    def set_service_credential(self, service_name: str, name: str, value: str) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO service_credentials (service_name, name, value, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(service_name, name) DO UPDATE SET
                    value=excluded.value,
                    updated_at=excluded.updated_at
                """,
                (service_name, name, value, _now_ms()),
            )

    def get_service_credential(self, service_name: str, name: str) -> Optional[Dict]:
        with self._connect() as conn:
            cur = conn.execute(
                "SELECT * FROM service_credentials WHERE service_name = ? AND name = ?",
                (service_name, name),
            )
            row = cur.fetchone()
            return dict(row) if row else None

    def list_service_credentials(self, service_name: str) -> List[Dict]:
        with self._connect() as conn:
            cur = conn.execute(
                "SELECT name, updated_at FROM service_credentials WHERE service_name = ? ORDER BY name",
                (service_name,),
            )
            return [dict(r) for r in cur.fetchall()]

    def delete_service_credentials(self, service_name: str) -> None:
        with self._connect() as conn:
            conn.execute("DELETE FROM service_credentials WHERE service_name = ?", (service_name,))
