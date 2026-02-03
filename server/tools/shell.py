from typing import Any, Dict
import subprocess


class ShellTool:
    def __init__(self, allowlist=None):
        self.allowlist = allowlist or ["ls", "pwd", "whoami"]

    def run(self, args: Dict[str, Any]) -> Dict[str, Any]:
        cmd = args.get("cmd")
        if not cmd or cmd[0] not in self.allowlist:
            return {"status": "error", "error": "command_not_allowed"}

        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=3)
            return {
                "status": "ok",
                "stdout": result.stdout,
                "stderr": result.stderr,
                "code": result.returncode,
            }
        except Exception as ex:
            return {"status": "error", "error": str(ex)}
