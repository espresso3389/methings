#!/usr/bin/env python3
"""Regression test for SSH curl support on methings device."""

import argparse
import json
import subprocess
import tempfile
from pathlib import Path


def run_ssh(host: str, port: int, user: str, known_hosts: Path, remote_cmd: str) -> subprocess.CompletedProcess[str]:
    cmd = [
        "ssh",
        "-o",
        "BatchMode=yes",
        "-o",
        "StrictHostKeyChecking=no",
        "-o",
        f"UserKnownHostsFile={known_hosts}",
        "-p",
        str(port),
        f"{user}@{host}",
        remote_cmd,
    ]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=20)


def assert_ok(name: str, proc: subprocess.CompletedProcess[str]) -> None:
    if proc.returncode != 0:
        raise RuntimeError(f"{name} failed rc={proc.returncode}\nstdout={proc.stdout}\nstderr={proc.stderr}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="192.168.23.44")
    parser.add_argument("--port", type=int, default=2222)
    parser.add_argument("--user", default="methings")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="methings_known_hosts_") as tmp:
        known_hosts = Path(tmp) / "known_hosts"
        known_hosts.write_text("", encoding="utf-8")

        # 1) Basic GET should return JSON health payload.
        p1 = run_ssh(
            args.host,
            args.port,
            args.user,
            known_hosts,
            "curl -sS http://127.0.0.1:8765/health",
        )
        assert_ok("health_get", p1)
        health = json.loads(p1.stdout.strip() or "{}")
        if health.get("status") != "ok":
            raise RuntimeError(f"health payload unexpected: {p1.stdout}")

        # 2) -i should include response status/headers + body.
        p2 = run_ssh(
            args.host,
            args.port,
            args.user,
            known_hosts,
            "curl -sS -i http://127.0.0.1:8765/health",
        )
        assert_ok("include_headers", p2)
        if "HTTP/1.1 200" not in p2.stdout:
            raise RuntimeError(f"missing HTTP status in -i output: {p2.stdout}")
        if '{"status":"ok"' not in p2.stdout and '"status":"ok"' not in p2.stdout:
            raise RuntimeError(f"missing body in -i output: {p2.stdout}")

        # 3) -w should expose status code while body is discarded via -o.
        p3 = run_ssh(
            args.host,
            args.port,
            args.user,
            known_hosts,
            "curl -sS -o /dev/null -w '%{http_code}' http://127.0.0.1:8765/health",
        )
        assert_ok("write_out_status", p3)
        if p3.stdout.strip() != "200":
            raise RuntimeError(f"unexpected write-out status: {p3.stdout!r}")

        # 4) HTTP 404 without --fail should still exit 0.
        p4 = run_ssh(
            args.host,
            args.port,
            args.user,
            known_hosts,
            "curl -sS -o /dev/null -w '%{http_code}' http://127.0.0.1:8765/does-not-exist",
        )
        assert_ok("http_error_no_fail", p4)
        if p4.stdout.strip() != "404":
            raise RuntimeError(f"expected 404 for missing endpoint: {p4.stdout!r}")

        # 5) HTTP 404 with --fail should return non-zero.
        p5 = run_ssh(
            args.host,
            args.port,
            args.user,
            known_hosts,
            "curl -sS --fail -o /dev/null http://127.0.0.1:8765/does-not-exist",
        )
        if p5.returncode == 0:
            raise RuntimeError("expected non-zero exit for --fail 404")

    print("OK: SSH curl regression checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
