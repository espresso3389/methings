#!/usr/bin/env python3
"""
Build tiny pure-Python facade wheels for Android.

Goal:
- Provide facade wheels only for packages that don't publish Android wheels but are convenient to
  have as "installed" distributions.
- Prefer real upstream wheels for pure-Python packages (e.g. `pyusb`) instead of facades.

At the moment we ship:
- `opencv-python` facade: provides `opencv_android` helper + a `cv2` stub explaining that the
  real bindings are not bundled.
- `pyuvc` facade: `pyuvc.load()` helper for the app-bundled `libuvc.so` (package is not on PyPI).

This script intentionally avoids external build tooling (setuptools/build/wheel).
"""

from __future__ import annotations

import base64
import csv
import hashlib
import os
from pathlib import Path
import sys
import time
import zipfile


def _norm_dist(name: str) -> str:
    # PEP 427 wheel filename normalization: replace '-' with '_'.
    return name.replace("-", "_")


def _sha256_b64(data: bytes) -> str:
    digest = hashlib.sha256(data).digest()
    return base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")


def _wheel_metadata(dist_name: str, version: str) -> bytes:
    # Minimal METADATA (RFC 822 style).
    # Keep it small: pip only needs Name/Version for dependency satisfaction.
    lines = [
        "Metadata-Version: 2.1",
        f"Name: {dist_name}",
        f"Version: {version}",
        "Summary: Kugutz Android facade package (native .so bundled by app).",
        "",
    ]
    return ("\n".join(lines)).encode("utf-8")


def _wheel_wheel_file() -> bytes:
    lines = [
        "Wheel-Version: 1.0",
        "Generator: kugutz/scripts/build_facade_wheels.py",
        "Root-Is-Purelib: true",
        "Tag: py3-none-any",
        "",
    ]
    return ("\n".join(lines)).encode("utf-8")


def build_wheel(*, dist_name: str, version: str, src_root: Path, out_dir: Path) -> Path:
    dist_norm = _norm_dist(dist_name)
    wheel_name = f"{dist_norm}-{version}-py3-none-any.whl"
    out_path = out_dir / wheel_name
    dist_info = f"{dist_norm}-{version}.dist-info"

    files: list[tuple[str, bytes]] = []

    # Package files under src_root are stored with forward slashes.
    for p in sorted(src_root.rglob("*")):
        if p.is_dir():
            continue
        rel = p.relative_to(src_root).as_posix()
        files.append((rel, p.read_bytes()))

    files.append((f"{dist_info}/METADATA", _wheel_metadata(dist_name, version)))
    files.append((f"{dist_info}/WHEEL", _wheel_wheel_file()))
    files.append((f"{dist_info}/top_level.txt", b""))  # optional

    # RECORD must exist. We can write it last with hashes/sizes for other files.
    record_rows: list[list[str]] = []
    for name, data in files:
        record_rows.append([name, f"sha256={_sha256_b64(data)}", str(len(data))])

    record_path = f"{dist_info}/RECORD"
    # RECORD row for itself has empty hash/size.
    record_rows.append([record_path, "", ""])
    record_bytes = _render_record(record_rows)
    files.append((record_path, record_bytes))

    out_dir.mkdir(parents=True, exist_ok=True)
    tmp = out_path.with_suffix(out_path.suffix + ".tmp")
    if tmp.exists():
        tmp.unlink()

    with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_DEFLATED) as z:
        # Use a stable timestamp to keep wheels deterministic-ish across builds.
        fixed_dt = (1980, 1, 1, 0, 0, 0)
        for name, data in files:
            zi = zipfile.ZipInfo(name, date_time=fixed_dt)
            zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, data)

    tmp.replace(out_path)
    return out_path


def _render_record(rows: list[list[str]]) -> bytes:
    # CSV with \n line endings (wheel expectation).
    from io import StringIO

    s = StringIO()
    w = csv.writer(s, lineterminator="\n")
    for r in rows:
        w.writerow(r)
    return s.getvalue().encode("utf-8")


def main() -> int:
    repo = Path(__file__).resolve().parents[1]
    out_dir = repo / "app/android/app/src/main/assets/wheels/common"
    if len(sys.argv) >= 3 and sys.argv[1] == "--out":
        out_dir = Path(sys.argv[2]).resolve()

    opencv_version = (
        os.environ.get("KUGUTZ_FACADE_OPENCV_PYTHON_VERSION", "").strip() or "4.12.0.88+kugutz1"
    )

    pkgs = [
        {
            # Distribution name must match the ecosystem expectation so dependency resolution works.
            # The wheel provides an `opencv_android` helper and a `cv2` stub that raises a clear error.
            "dist": "opencv-python",
            "src": repo / "server/facades/opencv-python/src",
            "version": opencv_version,
        },
        {
            # `pyuvc` is currently not published on PyPI. We ship a small facade so that
            # `pip install pyuvc` can resolve offline from our wheelhouse.
            "dist": "pyuvc",
            "src": repo / "server/facades/pyuvc/src",
            "version": os.environ.get("KUGUTZ_FACADE_PYUVC_VERSION", "").strip() or "0.0.0+kugutz1",
        },
    ]

    built: list[Path] = []
    for p in pkgs:
        src = Path(p["src"])
        if not src.exists():
            raise SystemExit(f"Missing src dir: {src}")
        built.append(
            build_wheel(
                dist_name=str(p["dist"]),
                version=str(p["version"]),
                src_root=src,
                out_dir=out_dir,
            )
        )
    sys.stdout.write("Built wheels:\n")
    for b in built:
        sys.stdout.write(f"- {b}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
