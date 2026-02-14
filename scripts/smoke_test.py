"""Simple localhost smoke test for the Python service."""

import json
import time
import urllib.request
from urllib.error import URLError

BASE = "http://127.0.0.1:33389"


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
    deadline = time.time() + 30
    while True:
        try:
            print(_get("/health"))
            break
        except URLError:
            if time.time() > deadline:
                raise
            time.sleep(0.5)
    perm = _post("/permissions/request", {"tool": "filesystem", "detail": "list", "scope": "once"})
    print("permission", perm)
    _post(f"/permissions/{perm['id']}/approve", {})
    result = _post("/tools/filesystem/invoke", {"request_id": perm["id"], "args": {"action": "list", "path": "."}})
    print("tool", result)


if __name__ == "__main__":
    main()
