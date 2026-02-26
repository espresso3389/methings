#!/usr/bin/env python3
"""
me.things Worker — HTTP server for shell execution, PTY sessions, and file access.

Runs on port 8776. Stdlib only (no external deps).
"""

import errno
import json
import os
import pty
import select
import signal
import socket
import subprocess
import sys
import threading
import time
import uuid
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.parse import urlsplit

PORT = 8776
HOME = os.environ.get("HOME", os.path.expanduser("~"))
SESSION_IDLE_TIMEOUT = 1800  # 30 min
ARDUINO_PROXY_HOST = "127.0.0.1"
ARDUINO_PROXY_PORT = 38888
ARDUINO_DOWNLOAD_HOST = "downloads.arduino.cc"
ARDUINO_DOWNLOAD_IPV4 = "104.18.11.21"

def _ensure_arduino_wrapper(proxy_url):
    """Install a lightweight arduino-cli wrapper under ~/methings/bin.
    The wrapper keeps arduino-cli proxy config in sync."""
    bin_dir = os.path.join(HOME, "methings", "bin")
    wrapper_path = os.path.join(bin_dir, "arduino-cli")
    real_cli = os.environ.get("ARDUINO_CLI_PATH", "arduino-cli")
    shell = os.environ.get("SHELL", "/system/bin/sh")
    script = f"""#!{shell}
set -e
PROXY_URL="${{METHINGS_ARDUINO_PROXY_URL:-{proxy_url}}}"
REAL_CLI="{real_cli}"
if [ ! -x "$REAL_CLI" ]; then
  REAL_CLI="$(command -v arduino-cli 2>/dev/null || true)"
fi
if [ "$REAL_CLI" = "$0" ]; then
  REAL_CLI="{real_cli}"
fi
if [ ! -x "$REAL_CLI" ]; then
  echo "arduino-cli not found" >&2
  exit 127
fi
"$REAL_CLI" config set network.proxy "$PROXY_URL" >/dev/null 2>&1 || true
exec "$REAL_CLI" "$@"
"""
    try:
        os.makedirs(bin_dir, exist_ok=True)
        with open(wrapper_path, "w", encoding="utf-8") as f:
            f.write(script)
        os.chmod(wrapper_path, 0o755)
    except Exception:
        pass


class ArduinoProxyManager:
    """Tiny localhost HTTP proxy with host->IPv4 override for Arduino downloads."""

    def __init__(self):
        self._lock = threading.Lock()
        self._server = None
        self._thread = None
        self._port = ARDUINO_PROXY_PORT
        self._host_map = {ARDUINO_DOWNLOAD_HOST: ARDUINO_DOWNLOAD_IPV4}

    def status(self):
        with self._lock:
            running = self._server is not None
            return {
                "running": running,
                "listen_host": ARDUINO_PROXY_HOST,
                "listen_port": self._port,
                "proxy_url": f"http://{ARDUINO_PROXY_HOST}:{self._port}",
                "host_map": dict(self._host_map),
            }

    def ensure_started(self):
        with self._lock:
            if self._server is not None:
                return {
                    "running": True,
                    "listen_host": ARDUINO_PROXY_HOST,
                    "listen_port": self._port,
                    "proxy_url": f"http://{ARDUINO_PROXY_HOST}:{self._port}",
                    "host_map": dict(self._host_map),
                }
            port = self._port
            manager = self

            class ProxyHandler(BaseHTTPRequestHandler):
                protocol_version = "HTTP/1.1"

                def log_message(self, _format, *_args):
                    pass

                def do_CONNECT(self):
                    host, _, p = self.path.partition(":")
                    host = host.strip()
                    try:
                        dst_port = int(p) if p else 443
                    except ValueError:
                        self.send_error(400, "invalid_port")
                        return
                    dst_ip = manager._resolve_target_ip(host)
                    if not dst_ip:
                        self.send_error(502, "dns_failed")
                        return
                    try:
                        upstream = socket.create_connection((dst_ip, dst_port), timeout=15)
                    except OSError:
                        self.send_error(502, "connect_failed")
                        return
                    self.send_response(200, "Connection Established")
                    self.end_headers()
                    self.connection.setblocking(False)
                    upstream.setblocking(False)
                    sockets = [self.connection, upstream]
                    try:
                        while True:
                            read_ready, _, _ = select.select(sockets, [], [], 30)
                            if not read_ready:
                                break
                            for src in read_ready:
                                try:
                                    data = src.recv(65536)
                                except OSError:
                                    data = b""
                                if not data:
                                    return
                                dst = upstream if src is self.connection else self.connection
                                dst.sendall(data)
                    finally:
                        try:
                            upstream.close()
                        except Exception:
                            pass

                def do_GET(self):
                    self._forward_http()

                def do_POST(self):
                    self._forward_http()

                def do_PUT(self):
                    self._forward_http()

                def do_DELETE(self):
                    self._forward_http()

                def do_PATCH(self):
                    self._forward_http()

                def do_HEAD(self):
                    self._forward_http()

                def _forward_http(self):
                    target = urlsplit(self.path)
                    if target.scheme:
                        host = target.hostname or ""
                        dst_port = target.port or (443 if target.scheme == "https" else 80)
                        req_path = target.path or "/"
                        if target.query:
                            req_path += "?" + target.query
                    else:
                        host_header = self.headers.get("Host", "").strip()
                        host, _, p = host_header.partition(":")
                        try:
                            dst_port = int(p) if p else 80
                        except ValueError:
                            self.send_error(400, "invalid_host_header")
                            return
                        req_path = self.path or "/"
                    if not host:
                        self.send_error(400, "missing_target_host")
                        return
                    dst_ip = manager._resolve_target_ip(host)
                    if not dst_ip:
                        self.send_error(502, "dns_failed")
                        return
                    length = int(self.headers.get("Content-Length", 0))
                    body = self.rfile.read(length) if length > 0 else b""
                    upstream = None
                    try:
                        upstream = socket.create_connection((dst_ip, dst_port), timeout=15)
                        upstream.settimeout(20)
                        req = bytearray()
                        req.extend(f"{self.command} {req_path} HTTP/1.1\r\n".encode("utf-8"))
                        req.extend(f"Host: {host}\r\n".encode("utf-8"))
                        for k, v in self.headers.items():
                            kl = k.lower()
                            if kl in ("host", "proxy-connection", "connection", "keep-alive"):
                                continue
                            req.extend(f"{k}: {v}\r\n".encode("utf-8"))
                        req.extend(b"Connection: close\r\n\r\n")
                        if body:
                            req.extend(body)
                        upstream.sendall(req)
                        while True:
                            chunk = upstream.recv(65536)
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                        self.wfile.flush()
                    except OSError:
                        self.send_error(502, "upstream_failed")
                    finally:
                        try:
                            if upstream is not None:
                                upstream.close()
                        except Exception:
                            pass

            proxy_server = ThreadedHTTPServer((ARDUINO_PROXY_HOST, port), ProxyHandler)
            self._server = proxy_server
            self._thread = threading.Thread(target=proxy_server.serve_forever, daemon=True)
            self._thread.start()
            _ensure_arduino_wrapper(f"http://{ARDUINO_PROXY_HOST}:{port}")
            return {
                "running": True,
                "listen_host": ARDUINO_PROXY_HOST,
                "listen_port": self._port,
                "proxy_url": f"http://{ARDUINO_PROXY_HOST}:{self._port}",
                "host_map": dict(self._host_map),
            }

    def stop(self):
        with self._lock:
            server = self._server
            thread = self._thread
            self._server = None
            self._thread = None
        if server is None:
            return True
        try:
            server.shutdown()
        except Exception:
            pass
        try:
            server.server_close()
        except Exception:
            pass
        if thread is not None and thread.is_alive():
            try:
                thread.join(timeout=1.0)
            except Exception:
                pass
        return True

    def set_port(self, port):
        try:
            p = int(port)
        except (TypeError, ValueError):
            return False
        if p < 1024 or p > 65535:
            return False
        should_restart = False
        with self._lock:
            if self._port == p:
                return True
            self._port = p
            should_restart = self._server is not None
        if should_restart:
            self.stop()
        return True

    def set_download_ip(self, ipv4):
        if not ipv4:
            return False
        try:
            socket.inet_aton(ipv4)
        except OSError:
            return False
        with self._lock:
            self._host_map[ARDUINO_DOWNLOAD_HOST] = ipv4
        return True

    def _resolve_target_ip(self, host):
        with self._lock:
            mapped = self._host_map.get(host.lower())
        if mapped:
            return mapped
        try:
            return socket.gethostbyname(host)
        except OSError:
            return None

    def configure_arduino_cli(self):
        proxy_url = f"http://{ARDUINO_PROXY_HOST}:{self._port}"
        _ensure_arduino_wrapper(proxy_url)
        cmd = (
            "if command -v arduino-cli >/dev/null 2>&1; then "
            f"arduino-cli config set network.proxy '{proxy_url}'; "
            "echo configured; "
            "else echo missing; fi"
        )
        try:
            result = subprocess.run(
                ["bash", "-lc", cmd],
                capture_output=True,
                timeout=20,
                cwd=HOME,
                env=os.environ.copy(),
            )
            out = result.stdout.decode("utf-8", errors="replace").strip()
            return {
                "status": "ok",
                "configured": "configured" in out and result.returncode == 0,
                "cli_found": "missing" not in out,
                "stdout": out,
                "stderr": result.stderr.decode("utf-8", errors="replace"),
            }
        except Exception as e:
            return {"status": "error", "error": str(e)}


arduino_proxy = ArduinoProxyManager()
SESSION_REAP_INTERVAL = 60  # check every 60s

# ─── PTY Session ──────────────────────────────────────────────────

class PtySession:
    """A persistent bash session backed by a PTY."""

    def __init__(self, cwd=None, env=None, rows=24, cols=80):
        self.id = uuid.uuid4().hex[:12]
        self.created_at = time.time()
        self.last_active = time.time()
        self.alive = True
        self.exit_code = None
        self._lock = threading.Lock()
        self._buf = bytearray()

        master_fd, slave_fd = pty.openpty()
        self.master_fd = master_fd

        import fcntl
        import struct
        import termios
        # Set initial terminal size
        winsize = struct.pack("HHHH", rows, cols, 0, 0)
        fcntl.ioctl(slave_fd, termios.TIOCSWINSZ, winsize)

        shell = os.environ.get("SHELL", "/system/bin/sh")
        spawn_env = os.environ.copy()
        spawn_env["TERM"] = "xterm-256color"
        spawn_env.setdefault("DEBIAN_FRONTEND", "noninteractive")
        proxy = arduino_proxy.status().get("proxy_url", f"http://{ARDUINO_PROXY_HOST}:{ARDUINO_PROXY_PORT}")
        spawn_env.setdefault("METHINGS_ARDUINO_PROXY_URL", proxy)
        spawn_env["PATH"] = f"{HOME}/methings/bin:" + spawn_env.get("PATH", "")
        if env:
            spawn_env.update(env)

        work_dir = cwd or HOME
        if not os.path.isdir(work_dir):
            work_dir = HOME

        self.proc = subprocess.Popen(
            [shell, "--login"],
            stdin=slave_fd,
            stdout=slave_fd,
            stderr=slave_fd,
            cwd=work_dir,
            env=spawn_env,
            preexec_fn=os.setsid,
        )
        os.close(slave_fd)

        # Background reader thread
        self._reader = threading.Thread(target=self._read_loop, daemon=True)
        self._reader.start()

    def _read_loop(self):
        while self.alive:
            try:
                ready, _, _ = select.select([self.master_fd], [], [], 0.5)
                if ready:
                    data = os.read(self.master_fd, 65536)
                    if not data:
                        break
                    with self._lock:
                        self._buf.extend(data)
            except OSError:
                break
        self._mark_dead()

    def _mark_dead(self):
        self.alive = False
        try:
            self.proc.wait(timeout=1)
            self.exit_code = self.proc.returncode
        except Exception:
            self.exit_code = -1

    def read_buf(self):
        """Drain and return buffered output."""
        with self._lock:
            data = bytes(self._buf)
            self._buf.clear()
        self.last_active = time.time()
        return data

    def write(self, data):
        """Write raw bytes to PTY stdin."""
        self.last_active = time.time()
        os.write(self.master_fd, data if isinstance(data, bytes) else data.encode())

    def exec_command(self, command, timeout=30):
        """Send a command and collect output until idle or timeout."""
        self.last_active = time.time()
        # Drain any stale output
        self.read_buf()
        # Send the command
        self.write(command.rstrip("\n") + "\n")
        # Collect output
        deadline = time.time() + timeout
        while time.time() < deadline and self.alive:
            time.sleep(0.1)
            # If no new data for 0.5s after initial burst, assume done
            with self._lock:
                snapshot_len = len(self._buf)
            if snapshot_len > 0:
                time.sleep(0.5)
                with self._lock:
                    new_len = len(self._buf)
                if new_len == snapshot_len:
                    break
        return self.read_buf()

    def resize(self, rows, cols):
        import fcntl
        import struct
        import termios
        winsize = struct.pack("HHHH", rows, cols, 0, 0)
        try:
            fcntl.ioctl(self.master_fd, termios.TIOCSWINSZ, winsize)
            # Notify the process group
            os.kill(self.proc.pid, signal.SIGWINCH)
        except Exception:
            pass

    def kill(self):
        self.alive = False
        try:
            os.kill(-self.proc.pid, signal.SIGTERM)
        except Exception:
            pass
        try:
            self.proc.wait(timeout=3)
        except Exception:
            try:
                os.kill(-self.proc.pid, signal.SIGKILL)
            except Exception:
                pass
        try:
            os.close(self.master_fd)
        except Exception:
            pass
        self.exit_code = self.proc.returncode


# ─── Session Store ────────────────────────────────────────────────

sessions = {}
sessions_lock = threading.Lock()


def get_session(sid):
    with sessions_lock:
        return sessions.get(sid)


def create_session(cwd=None, env=None, rows=24, cols=80):
    s = PtySession(cwd=cwd, env=env, rows=rows, cols=cols)
    with sessions_lock:
        sessions[s.id] = s
    return s


def remove_session(sid):
    with sessions_lock:
        s = sessions.pop(sid, None)
    if s:
        s.kill()


def reap_idle_sessions():
    """Periodically remove idle sessions."""
    while True:
        time.sleep(SESSION_REAP_INTERVAL)
        now = time.time()
        to_remove = []
        with sessions_lock:
            for sid, s in sessions.items():
                if not s.alive or (now - s.last_active > SESSION_IDLE_TIMEOUT):
                    to_remove.append(sid)
        for sid in to_remove:
            remove_session(sid)


# ─── File Operations ──────────────────────────────────────────────

def validate_path(path):
    """Resolve path and ensure it's under HOME."""
    resolved = os.path.realpath(os.path.expanduser(path))
    home_resolved = os.path.realpath(HOME)
    if not resolved.startswith(home_resolved + os.sep) and resolved != home_resolved:
        return None
    return resolved


def fs_read(params):
    path = validate_path(params.get("path", ""))
    if not path:
        return 403, {"error": "path_outside_home"}
    if not os.path.isfile(path):
        return 404, {"error": "not_found"}
    max_bytes = min(params.get("max_bytes", 1048576), 10485760)
    offset = max(params.get("offset", 0), 0)
    try:
        size = os.path.getsize(path)
        with open(path, "rb") as f:
            if offset > 0:
                f.seek(offset)
            data = f.read(max_bytes)
        try:
            content = data.decode("utf-8")
            encoding = "utf-8"
        except UnicodeDecodeError:
            import base64
            content = base64.b64encode(data).decode("ascii")
            encoding = "base64"
        return 200, {
            "status": "ok",
            "content": content,
            "encoding": encoding,
            "size": size,
            "offset": offset,
            "read_bytes": len(data),
            "truncated": offset + len(data) < size,
        }
    except Exception as e:
        return 500, {"error": str(e)}


def fs_write(params):
    path = validate_path(params.get("path", ""))
    if not path:
        return 403, {"error": "path_outside_home"}
    content = params.get("content", "")
    encoding = params.get("encoding", "utf-8")
    try:
        parent = os.path.dirname(path)
        os.makedirs(parent, exist_ok=True)
        if encoding == "base64":
            import base64
            data = base64.b64decode(content)
        else:
            data = content.encode("utf-8")
        with open(path, "wb") as f:
            f.write(data)
        return 200, {"status": "ok", "path": path, "size": len(data)}
    except Exception as e:
        return 500, {"error": str(e)}


def fs_list(params):
    path = validate_path(params.get("path", HOME))
    if not path:
        return 403, {"error": "path_outside_home"}
    if not os.path.isdir(path):
        return 404, {"error": "not_a_directory"}
    show_hidden = params.get("show_hidden", False)
    try:
        entries = []
        for name in sorted(os.listdir(path)):
            if not show_hidden and name.startswith("."):
                continue
            full = os.path.join(path, name)
            try:
                st = os.stat(full)
                entries.append({
                    "name": name,
                    "is_dir": os.path.isdir(full),
                    "size": st.st_size,
                    "mtime": st.st_mtime,
                })
            except OSError:
                entries.append({"name": name, "is_dir": False, "size": 0})
        return 200, {"status": "ok", "path": path, "items": entries}
    except Exception as e:
        return 500, {"error": str(e)}


def fs_stat(params):
    path = validate_path(params.get("path", ""))
    if not path:
        return 403, {"error": "path_outside_home"}
    if not os.path.exists(path):
        return 404, {"error": "not_found"}
    try:
        st = os.stat(path)
        return 200, {
            "status": "ok",
            "path": path,
            "is_file": os.path.isfile(path),
            "is_dir": os.path.isdir(path),
            "is_link": os.path.islink(path),
            "size": st.st_size,
            "mtime": st.st_mtime,
            "atime": st.st_atime,
            "mode": oct(st.st_mode),
        }
    except Exception as e:
        return 500, {"error": str(e)}


def fs_mkdir(params):
    path = validate_path(params.get("path", ""))
    if not path:
        return 403, {"error": "path_outside_home"}
    parents = params.get("parents", True)
    try:
        if parents:
            os.makedirs(path, exist_ok=True)
        else:
            os.mkdir(path)
        return 200, {"status": "ok", "path": path}
    except Exception as e:
        return 500, {"error": str(e)}


def fs_delete(params):
    path = validate_path(params.get("path", ""))
    if not path:
        return 403, {"error": "path_outside_home"}
    if not os.path.exists(path):
        return 200, {"status": "ok", "existed": False}
    recursive = params.get("recursive", False)
    try:
        if os.path.isdir(path):
            if recursive:
                import shutil
                shutil.rmtree(path)
            else:
                os.rmdir(path)
        else:
            os.remove(path)
        return 200, {"status": "ok", "existed": True, "deleted": True}
    except Exception as e:
        return 500, {"error": str(e)}


# ─── HTTP Handler ─────────────────────────────────────────────────

class WorkerHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        pass  # suppress default logging

    def _read_body(self):
        length = int(self.headers.get("Content-Length", 0))
        if length > 0:
            return self.rfile.read(length)
        return b""

    def _parse_json(self):
        body = self._read_body()
        if not body:
            return {}
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return None

    def _send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        self.wfile.write(body)

    # ── GET routes ──

    def do_GET(self):
        path = self.path.split("?")[0].rstrip("/")

        if path == "/health":
            return self._send_json(200, {"status": "ok", "pid": os.getpid()})

        if path == "/session/list":
            return self._handle_session_list()

        # GET /session/<id>/status
        if path.startswith("/session/") and path.endswith("/status"):
            sid = path.split("/")[2]
            return self._handle_session_status(sid)

        if path == "/proxy/arduino/status":
            status = arduino_proxy.status()
            status["module"] = "arduino_proxy"
            return self._send_json(200, status)

        self._send_json(404, {"error": "not_found"})

    # ── POST routes ──

    def do_POST(self):
        path = self.path.split("?")[0].rstrip("/")

        if path == "/shutdown":
            self._send_json(200, {"status": "ok"})
            threading.Thread(target=self._delayed_shutdown, daemon=True).start()
            return

        if path == "/exec":
            return self._handle_exec()

        # Legacy: /shell/exec (same as /exec)
        if path == "/shell/exec":
            return self._handle_exec()

        # Session endpoints
        if path == "/session/start":
            return self._handle_session_start()

        if path.startswith("/session/") and path.count("/") >= 3:
            parts = path.split("/")
            sid = parts[2]
            action = parts[3] if len(parts) > 3 else ""
            return self._dispatch_session(sid, action)

        # File system endpoints
        if path.startswith("/fs/"):
            action = path[4:]  # strip "/fs/"
            return self._handle_fs(action)

        if path == "/proxy/arduino/enable":
            return self._handle_arduino_proxy_enable()

        self._send_json(404, {"error": "not_found"})

    # ── Exec ──

    def _handle_exec(self):
        params = self._parse_json()
        if params is None:
            return self._send_json(400, {"error": "invalid_json"})

        command = params.get("command", "")
        # Legacy: support "cmd" + "args" format
        if not command:
            cmd = params.get("cmd", "")
            args = params.get("args", "")
            if cmd:
                command = f"{cmd} {args}".strip()

        if not command:
            return self._send_json(400, {"error": "missing_command"})

        cwd = params.get("cwd", "") or HOME
        if not os.path.isdir(cwd):
            cwd = HOME
        timeout = min(max(params.get("timeout_ms", 60000), 1000), 300000) / 1000.0
        env_extra = params.get("env")

        spawn_env = os.environ.copy()
        # Non-interactive: suppress apt/dpkg prompts that would hang subprocess
        spawn_env.setdefault("DEBIAN_FRONTEND", "noninteractive")
        arduino_proxy.ensure_started()
        proxy = arduino_proxy.status().get("proxy_url", f"http://{ARDUINO_PROXY_HOST}:{ARDUINO_PROXY_PORT}")
        spawn_env.setdefault("METHINGS_ARDUINO_PROXY_URL", proxy)
        spawn_env["PATH"] = f"{HOME}/methings/bin:" + spawn_env.get("PATH", "")
        if env_extra and isinstance(env_extra, dict):
            spawn_env.update(env_extra)

        try:
            result = subprocess.run(
                ["bash", "-lc", command],
                capture_output=True,
                timeout=timeout,
                cwd=cwd,
                env=spawn_env,
            )
            self._send_json(200, {
                "status": "ok",
                "exit_code": result.returncode,
                "stdout": result.stdout.decode("utf-8", errors="replace"),
                "stderr": result.stderr.decode("utf-8", errors="replace"),
            })
        except subprocess.TimeoutExpired:
            self._send_json(200, {
                "status": "timeout",
                "exit_code": -1,
                "stdout": "",
                "stderr": f"Command timed out after {timeout}s",
            })
        except Exception as e:
            self._send_json(500, {"status": "error", "error": str(e)})

    def _handle_arduino_proxy_enable(self):
        params = self._parse_json()
        if params is None:
            return self._send_json(400, {"error": "invalid_json"})
        requested_port = params.get("listen_port")
        if requested_port is not None and requested_port != "":
            if not arduino_proxy.set_port(requested_port):
                return self._send_json(400, {"error": "invalid_listen_port"})
        requested_ip = str(params.get("downloads_ipv4", "")).strip()
        if requested_ip and not arduino_proxy.set_download_ip(requested_ip):
            return self._send_json(400, {"error": "invalid_downloads_ipv4"})
        status = arduino_proxy.ensure_started()
        configured = arduino_proxy.configure_arduino_cli()
        return self._send_json(200, {
            "status": "ok",
            "module": "arduino_proxy",
            "proxy": status,
            "arduino_cli": configured,
        })

    # ── Sessions ──

    def _handle_session_start(self):
        params = self._parse_json()
        if params is None:
            return self._send_json(400, {"error": "invalid_json"})
        s = create_session(
            cwd=params.get("cwd"),
            env=params.get("env"),
            rows=params.get("rows", 24),
            cols=params.get("cols", 80),
        )
        # Wait briefly for shell prompt
        time.sleep(0.5)
        output = s.read_buf()
        self._send_json(200, {
            "status": "ok",
            "session_id": s.id,
            "output": output.decode("utf-8", errors="replace"),
        })

    def _dispatch_session(self, sid, action):
        s = get_session(sid)
        if not s:
            return self._send_json(404, {"error": "session_not_found"})

        if action == "exec":
            return self._handle_session_exec(s)
        elif action == "write":
            return self._handle_session_write(s)
        elif action == "read":
            return self._handle_session_read(s)
        elif action == "resize":
            return self._handle_session_resize(s)
        elif action == "kill":
            remove_session(sid)
            return self._send_json(200, {"status": "ok"})
        elif action == "status":
            return self._handle_session_status(sid)
        else:
            return self._send_json(404, {"error": "unknown_session_action"})

    def _handle_session_exec(self, s):
        params = self._parse_json()
        if params is None:
            return self._send_json(400, {"error": "invalid_json"})
        command = params.get("command", "")
        if not command:
            return self._send_json(400, {"error": "missing_command"})
        timeout = min(max(params.get("timeout", 30), 1), 300)
        output = s.exec_command(command, timeout=timeout)
        self._send_json(200, {
            "status": "ok",
            "output": output.decode("utf-8", errors="replace"),
            "alive": s.alive,
        })

    def _handle_session_write(self, s):
        params = self._parse_json()
        if params is None:
            return self._send_json(400, {"error": "invalid_json"})
        data = params.get("input", "")
        if not data:
            return self._send_json(400, {"error": "missing_input"})
        s.write(data)
        self._send_json(200, {"status": "ok"})

    def _handle_session_read(self, s):
        output = s.read_buf()
        self._send_json(200, {
            "status": "ok",
            "output": output.decode("utf-8", errors="replace"),
            "alive": s.alive,
        })

    def _handle_session_resize(self, s):
        params = self._parse_json()
        if params is None:
            return self._send_json(400, {"error": "invalid_json"})
        rows = params.get("rows", 24)
        cols = params.get("cols", 80)
        s.resize(rows, cols)
        self._send_json(200, {"status": "ok"})

    def _handle_session_status(self, sid):
        s = get_session(sid)
        if not s:
            return self._send_json(404, {"error": "session_not_found"})
        self._send_json(200, {
            "status": "ok",
            "session_id": s.id,
            "alive": s.alive,
            "exit_code": s.exit_code,
            "idle_seconds": int(time.time() - s.last_active),
            "uptime_seconds": int(time.time() - s.created_at),
        })

    def _handle_session_list(self):
        with sessions_lock:
            items = []
            for s in sessions.values():
                items.append({
                    "session_id": s.id,
                    "alive": s.alive,
                    "idle_seconds": int(time.time() - s.last_active),
                    "uptime_seconds": int(time.time() - s.created_at),
                })
        self._send_json(200, {"status": "ok", "sessions": items})

    # ── File System ──

    def _handle_fs(self, action):
        params = self._parse_json()
        if params is None:
            return self._send_json(400, {"error": "invalid_json"})

        dispatch = {
            "read": fs_read,
            "write": fs_write,
            "list": fs_list,
            "stat": fs_stat,
            "mkdir": fs_mkdir,
            "delete": fs_delete,
        }
        handler = dispatch.get(action)
        if not handler:
            return self._send_json(404, {"error": "unknown_fs_action"})

        code, result = handler(params)
        self._send_json(code, result)

    # ── Shutdown ──

    @staticmethod
    def _delayed_shutdown():
        time.sleep(0.3)
        os._exit(0)


class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


# ─── Main ─────────────────────────────────────────────────────────

def main():
    arduino_proxy.ensure_started()
    arduino_proxy.configure_arduino_cli()
    # Start idle-session reaper
    threading.Thread(target=reap_idle_sessions, daemon=True).start()

    server = ThreadedHTTPServer(("127.0.0.1", PORT), WorkerHandler)
    print(f"Worker listening on 127.0.0.1:{PORT}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        # Kill all sessions
        with sessions_lock:
            for s in sessions.values():
                s.kill()


if __name__ == "__main__":
    main()
