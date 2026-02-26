#!/usr/bin/env python3
"""Generate a local curated Arduino package bundle for me.things.

Output layout:
  <out-dir>/package_methings_index.json
  <out-dir>/files/<archives...>
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import shutil
import sys
import tarfile
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


LOCAL_BASE_URL = "http://127.0.0.1:33389/arduino/curated/files"


@dataclass(frozen=True)
class ToolKey:
    packager: str
    name: str
    version: str


@dataclass
class Asset:
    rel_path: str
    source_url: str
    checksum: str
    size: int
    kind: str
    key: str


def _normalized_marker(path: Path) -> Path:
    return Path(str(path) + ".methings-normalized")


def _is_tar_archive(path: Path) -> bool:
    name = path.name.lower()
    return (
        name.endswith(".tar")
        or name.endswith(".tar.gz")
        or name.endswith(".tgz")
        or name.endswith(".tar.bz2")
        or name.endswith(".tbz2")
        or name.endswith(".tar.xz")
        or name.endswith(".txz")
    )


def _tar_write_mode(path: Path) -> str:
    name = path.name.lower()
    if name.endswith(".tar.gz") or name.endswith(".tgz"):
        return "w:gz"
    if name.endswith(".tar.bz2") or name.endswith(".tbz2"):
        return "w:bz2"
    if name.endswith(".tar.xz") or name.endswith(".txz"):
        return "w:xz"
    return "w"


def _resolve_member_name(member_name: str, linkname: str) -> str:
    link_clean = linkname.lstrip("/")
    if linkname.startswith("/"):
        return link_clean
    # Most Arduino archives store hardlink targets as archive-rooted paths.
    if "/" in linkname:
        return link_clean
    base = os.path.dirname(member_name)
    return os.path.normpath(os.path.join(base, linkname))


def _resolve_hardlink_target(src: tarfile.TarFile, member: tarfile.TarInfo) -> tarfile.TarInfo:
    cur = member
    for _ in range(32):
        if not cur.islnk():
            break
        candidates = []
        canonical = _resolve_member_name(cur.name, cur.linkname)
        if canonical:
            candidates.append(canonical)
        relative = os.path.normpath(os.path.join(os.path.dirname(cur.name), cur.linkname))
        if relative and relative not in candidates:
            candidates.append(relative)
        direct = cur.linkname.lstrip("/")
        if direct and direct not in candidates:
            candidates.append(direct)

        target = None
        for cand in candidates:
            try:
                target = src.getmember(cand)
                break
            except KeyError:
                continue
        if target is None:
            raise RuntimeError(f"hardlink target not found: {member.name} -> {cur.linkname}")
        cur = target
    if cur.islnk():
        raise RuntimeError(f"hardlink chain too deep: {member.name}")
    if not cur.isfile():
        raise RuntimeError(f"hardlink target is not a regular file: {member.name} -> {cur.name}")
    return cur


def _copy_member_metadata(src_member: tarfile.TarInfo, dst_member: tarfile.TarInfo) -> None:
    dst_member.mode = src_member.mode
    dst_member.uid = src_member.uid
    dst_member.gid = src_member.gid
    dst_member.uname = src_member.uname
    dst_member.gname = src_member.gname
    dst_member.mtime = src_member.mtime
    dst_member.pax_headers = dict(src_member.pax_headers or {})


def _normalize_tar_hardlinks(path: Path) -> bool:
    with tarfile.open(path, mode="r:*") as src:
        members = src.getmembers()
        if not any(m.islnk() for m in members):
            return False

        tmp = Path(str(path) + ".normalize.part")
        with tarfile.open(tmp, mode=_tar_write_mode(path)) as dst:
            for member in members:
                if member.islnk():
                    target = _resolve_hardlink_target(src, member)
                    fileobj = src.extractfile(target)
                    if fileobj is None:
                        raise RuntimeError(f"cannot read hardlink target data: {target.name}")
                    out = tarfile.TarInfo(name=member.name)
                    _copy_member_metadata(member, out)
                    out.type = tarfile.REGTYPE
                    out.size = target.size
                    out.linkname = ""
                    dst.addfile(out, fileobj=fileobj)
                    continue

                out = copy.copy(member)
                if member.isfile():
                    fileobj = src.extractfile(member)
                    if fileobj is None:
                        raise RuntimeError(f"cannot read tar member data: {member.name}")
                    dst.addfile(out, fileobj=fileobj)
                else:
                    dst.addfile(out)

        tmp.replace(path)
        return True


def _load_json(source: str) -> dict[str, Any]:
    if source.startswith("http://") or source.startswith("https://"):
        with urllib.request.urlopen(source) as resp:
            return json.loads(resp.read().decode("utf-8"))
    with open(source, "r", encoding="utf-8") as f:
        return json.load(f)


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def _verify_asset_file(path: Path, checksum: str | None, expected_size: int | None) -> None:
    if expected_size is not None:
        actual_size = path.stat().st_size
        if actual_size != expected_size:
            raise RuntimeError(f"size mismatch: {path} expected={expected_size} actual={actual_size}")
    if checksum:
        csum = checksum.strip()
        if ":" in csum:
            algo, val = csum.split(":", 1)
            algo = algo.strip().lower()
            val = val.strip().lower()
        else:
            algo, val = "sha-256", csum.lower()
        if algo not in {"sha-256", "sha256"}:
            return
        actual = _sha256_file(path).lower()
        if actual != val:
            raise RuntimeError(f"checksum mismatch: {path} expected={val} actual={actual}")


def _download(url: str, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_suffix(dst.suffix + ".part")
    with urllib.request.urlopen(url) as resp, tmp.open("wb") as out:
        shutil.copyfileobj(resp, out)
    tmp.replace(dst)


def _safe_name(s: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "_", s)


def _platform_asset_rel(packager: str, architecture: str, version: str, archive_name: str) -> str:
    return "/".join(
        [
            _safe_name(packager),
            "platforms",
            _safe_name(architecture),
            _safe_name(version),
            _safe_name(archive_name),
        ]
    )


def _tool_asset_rel(packager: str, name: str, version: str, host: str, archive_name: str) -> str:
    return "/".join(
        [
            _safe_name(packager),
            "tools",
            _safe_name(name),
            _safe_name(version),
            _safe_name(host),
            _safe_name(archive_name),
        ]
    )


def _basename_from_url(url: str) -> str:
    parsed = urllib.parse.urlparse(url)
    base = os.path.basename(parsed.path)
    return base or "archive.bin"


def _find_package(index: dict[str, Any], package_name: str) -> dict[str, Any]:
    for pkg in index.get("packages", []):
        if pkg.get("name") == package_name:
            return pkg
    raise RuntimeError(f"package not found in index: {package_name}")


def _find_platform(pkg: dict[str, Any], architecture: str, version: str) -> dict[str, Any]:
    for p in pkg.get("platforms", []):
        if p.get("architecture") == architecture and p.get("version") == version:
            return p
    raise RuntimeError(f"platform not found: {pkg.get('name')}:{architecture}@{version}")


def _find_tool_version(pkg: dict[str, Any], name: str, version: str) -> dict[str, Any]:
    for t in pkg.get("tools", []):
        if t.get("name") == name and t.get("version") == version:
            return t
    raise RuntimeError(f"tool not found: {pkg.get('name')}:{name}@{version}")


def main() -> int:
    ap = argparse.ArgumentParser(description="Generate curated Arduino bundle.")
    ap.add_argument(
        "--source-index",
        default="/tmp/methings-arduino-host/data/package_index.json",
        help="Source package index JSON path or URL",
    )
    ap.add_argument("--package", default="esp32", help="Target package name (default: esp32)")
    ap.add_argument("--architecture", default="esp32", help="Target architecture (default: esp32)")
    ap.add_argument("--platform-version", default="3.3.7", help="Target platform version")
    ap.add_argument("--tool-host-regex", default=r"aarch64-linux-gnu|arm64", help="Regex to select tool host entries")
    ap.add_argument("--out-dir", default="tmp/arduino-curated-bundle", help="Output bundle directory")
    ap.add_argument("--skip-download", action="store_true", help="Do not download archives")
    ap.add_argument(
        "--normalize-hardlinks",
        dest="normalize_hardlinks",
        action="store_true",
        default=True,
        help="Rewrite tar hardlinks as regular files after download (default: enabled)",
    )
    ap.add_argument(
        "--no-normalize-hardlinks",
        dest="normalize_hardlinks",
        action="store_false",
        help="Disable tar hardlink normalization",
    )
    args = ap.parse_args()

    index = _load_json(args.source_index)
    packages = {pkg.get("name"): pkg for pkg in index.get("packages", [])}
    if args.package not in packages:
        raise RuntimeError(f"target package not found: {args.package}")

    target_pkg = packages[args.package]
    platform = _find_platform(target_pkg, args.architecture, args.platform_version)

    host_re = re.compile(args.tool_host_regex)
    required_tools: set[ToolKey] = set()
    for dep in platform.get("toolsDependencies", []):
        required_tools.add(
            ToolKey(
                packager=str(dep.get("packager", "")),
                name=str(dep.get("name", "")),
                version=str(dep.get("version", "")),
            )
        )

    selected_tool_versions: dict[ToolKey, dict[str, Any]] = {}
    selected_tool_systems: dict[ToolKey, list[dict[str, Any]]] = {}
    assets: list[Asset] = []

    # Platform archive
    platform_archive_name = platform.get("archiveFileName") or _basename_from_url(platform.get("url", ""))
    p_rel = _platform_asset_rel(args.package, args.architecture, args.platform_version, platform_archive_name)
    assets.append(
        Asset(
            rel_path=p_rel,
            source_url=str(platform.get("url", "")),
            checksum=str(platform.get("checksum", "")),
            size=int(platform.get("size", 0)),
            kind="platform",
            key=f"{args.package}:{args.architecture}:{args.platform_version}",
        )
    )

    for key in sorted(required_tools, key=lambda k: (k.packager, k.name, k.version)):
        pkg = packages.get(key.packager)
        if not pkg:
            raise RuntimeError(f"dependency packager missing in index: {key.packager}")
        tool_ver = _find_tool_version(pkg, key.name, key.version)
        systems = tool_ver.get("systems", [])
        picked = [s for s in systems if host_re.search(str(s.get("host", "")))]
        if not picked:
            raise RuntimeError(
                f"no tool systems matched host regex for {key.packager}:{key.name}@{key.version}"
            )
        selected_tool_versions[key] = tool_ver
        selected_tool_systems[key] = picked

        for sys_ent in picked:
            archive_name = sys_ent.get("archiveFileName") or _basename_from_url(str(sys_ent.get("url", "")))
            rel = _tool_asset_rel(key.packager, key.name, key.version, str(sys_ent.get("host", "")), archive_name)
            assets.append(
                Asset(
                    rel_path=rel,
                    source_url=str(sys_ent.get("url", "")),
                    checksum=str(sys_ent.get("checksum", "")),
                    size=int(sys_ent.get("size", 0)),
                    kind="tool",
                    key=f"{key.packager}:{key.name}:{key.version}:{sys_ent.get('host', '')}",
                )
            )

    out_dir = Path(args.out_dir).resolve()
    files_dir = out_dir / "files"
    files_dir.mkdir(parents=True, exist_ok=True)

    # Download and verify
    if not args.skip_download:
        for a in assets:
            dst = files_dir / a.rel_path
            marker = _normalized_marker(dst)
            if not dst.exists():
                print(f"download: {a.source_url} -> {dst}")
                _download(a.source_url, dst)
            if marker.exists():
                print(f"use normalized archive (skip upstream checksum): {dst}")
            else:
                _verify_asset_file(dst, a.checksum if a.checksum else None, a.size if a.size > 0 else None)
            if args.normalize_hardlinks and _is_tar_archive(dst):
                changed = _normalize_tar_hardlinks(dst)
                if changed:
                    marker.write_text("normalized-hardlinks\n", encoding="utf-8")
                    print(f"normalized hardlinks: {dst}")
    else:
        for a in assets:
            dst = files_dir / a.rel_path
            marker = _normalized_marker(dst)
            if dst.exists():
                if marker.exists():
                    print(f"use normalized archive (skip upstream checksum): {dst}")
                else:
                    _verify_asset_file(dst, a.checksum if a.checksum else None, a.size if a.size > 0 else None)
                if args.normalize_hardlinks and _is_tar_archive(dst):
                    changed = _normalize_tar_hardlinks(dst)
                    if changed:
                        marker.write_text("normalized-hardlinks\n", encoding="utf-8")
                        print(f"normalized hardlinks: {dst}")

    # Rewrite target platform
    platform_copy = json.loads(json.dumps(platform))
    platform_copy["url"] = f"{LOCAL_BASE_URL}/{p_rel}"

    # Build package outputs by packager
    by_packager_tools: dict[str, list[ToolKey]] = {}
    for tk in required_tools:
        by_packager_tools.setdefault(tk.packager, []).append(tk)

    out_packages: list[dict[str, Any]] = []
    packager_order = [args.package] + sorted(p for p in by_packager_tools.keys() if p != args.package)
    for packager in packager_order:
        src_pkg = packages.get(packager)
        if not src_pkg:
            continue
        pkg_copy = json.loads(json.dumps(src_pkg))

        if packager == args.package:
            pkg_copy["platforms"] = [platform_copy]
        else:
            pkg_copy["platforms"] = []

        out_tools: list[dict[str, Any]] = []
        for tk in sorted(by_packager_tools.get(packager, []), key=lambda k: (k.name, k.version)):
            base_tool = json.loads(json.dumps(selected_tool_versions[tk]))
            rewritten_systems: list[dict[str, Any]] = []
            for s in selected_tool_systems[tk]:
                sc = json.loads(json.dumps(s))
                archive_name = sc.get("archiveFileName") or _basename_from_url(str(sc.get("url", "")))
                rel = _tool_asset_rel(tk.packager, tk.name, tk.version, str(sc.get("host", "")), archive_name)
                sc["url"] = f"{LOCAL_BASE_URL}/{rel}"
                rewritten_systems.append(sc)
            base_tool["systems"] = rewritten_systems
            out_tools.append(base_tool)
        pkg_copy["tools"] = out_tools
        out_packages.append(pkg_copy)

    out_index = {"packages": out_packages}
    out_index_path = out_dir / "package_methings_index.json"
    out_index_path.write_text(json.dumps(out_index, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")

    print(f"wrote index: {out_index_path}")
    print(f"assets listed: {len(assets)}")
    print(f"packages in curated index: {[p.get('name') for p in out_packages]}")
    if args.skip_download:
        print("skip-download enabled; ensure files are present before staging.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise
