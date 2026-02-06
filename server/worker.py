import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import contextlib
import io
import runpy
import shlex
import sys


BASE_DIR = Path(__file__).resolve().parent.parent
USER_DIR = BASE_DIR / "user"
USER_DIR.mkdir(parents=True, exist_ok=True)


class WorkerHandler(BaseHTTPRequestHandler):
    server_version = "KugutzWorker/0.1"

    def do_GET(self):
        if self.path == "/health":
            self._send_json({"status": "ok"})
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        if self.path == "/shutdown":
            self._send_json({"status": "stopping"})
            threading.Thread(target=self.server.shutdown, daemon=True).start()
            return
        if self.path == "/shell/exec":
            length = int(self.headers.get("Content-Length", "0") or "0")
            raw = self.rfile.read(length).decode("utf-8", errors="replace")
            try:
                payload = json.loads(raw) if raw else {}
            except Exception:
                self._send_json({"error": "invalid_json"}, status=400)
                return
            self._handle_shell_exec(payload)
            return
        self.send_response(404)
        self.end_headers()

    def log_message(self, format, *args):
        return

    def _send_json(self, payload, status=200):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _resolve_cwd(self, cwd):
        if not cwd:
            return USER_DIR
        candidate = (USER_DIR / str(cwd).lstrip("/")).resolve()
        user_root = USER_DIR.resolve()
        if str(candidate).startswith(str(user_root)):
            return candidate
        return user_root

    def _handle_shell_exec(self, payload):
        cmd = str(payload.get("cmd", "") or "")
        raw_args = str(payload.get("args", "") or "")
        cwd = str(payload.get("cwd", "") or "")

        if cmd not in {"python", "pip"}:
            self._send_json({"error": "command_not_allowed"}, status=403)
            return

        workdir = self._resolve_cwd(cwd)
        args = shlex.split(raw_args)
        output = io.StringIO()
        code = 0

        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            try:
                if cmd == "pip":
                    try:
                        from pip._internal.cli.main import main as pip_main
                    except Exception:
                        try:
                            import ensurepip
                            ensurepip.bootstrap(upgrade=True)
                            from pip._internal.cli.main import main as pip_main
                        except Exception:
                            from pip._internal import main as pip_main  # type: ignore
                    code = int(pip_main(args) or 0)
                else:
                    if not args:
                        raise RuntimeError("interactive_not_supported")
                    if args[0] in {"-V", "--version"}:
                        print(sys.version)
                    elif args[0] == "-c":
                        if len(args) < 2:
                            raise RuntimeError("missing_code")
                        exec_globals = {"__name__": "__main__"}
                        exec(args[1], exec_globals, exec_globals)
                    else:
                        script_path = Path(args[0])
                        if not script_path.is_absolute():
                            script_path = (workdir / script_path).resolve()
                        runpy.run_path(str(script_path), run_name="__main__")
            except SystemExit as exc:
                code = int(getattr(exc, "code", 0) or 0)
            except Exception as exc:
                code = 1
                print(f"error: {exc}")

        self._send_json({"status": "ok", "code": code, "output": output.getvalue()})


def main():
    server = HTTPServer(("127.0.0.1", 8776), WorkerHandler)
    server.serve_forever()


if __name__ == "__main__":
    main()
