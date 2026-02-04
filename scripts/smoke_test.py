"""Simple localhost smoke test for the Python service."""

import json
import urllib.request

BASE = "http://127.0.0.1:8765"


def _post(path, payload):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE + path,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=3) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _get(path):
    with urllib.request.urlopen(BASE + path, timeout=3) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main():
    print(_get("/health"))
    perm = _post("/permissions/request", {"tool": "filesystem", "detail": "list", "scope": "once"})
    print("permission", perm)
    _post(f"/permissions/{perm['id']}/approve", {})
    result = _post("/tools/filesystem/invoke", {"request_id": perm["id"], "args": {"action": "list", "path": "."}})
    print("tool", result)


if __name__ == "__main__":
    main()
