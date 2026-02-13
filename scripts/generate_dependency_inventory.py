#!/usr/bin/env python3
"""Generate a deterministic dependency inventory JSON for app licenses."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Dict, Iterable, List, Tuple


def normalize_name(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name.strip().lower())


def parse_gradle_dependencies(repo_root: Path) -> List[Dict[str, str]]:
    build_file = repo_root / "app" / "android" / "app" / "build.gradle.kts"
    if not build_file.exists():
        return []
    text = build_file.read_text(encoding="utf-8", errors="ignore")
    pat = re.compile(r'(implementation|kapt)\("([^":]+):([^":]+):([^"]+)"\)')
    out: List[Dict[str, str]] = []
    for m in pat.finditer(text):
        cfg, group, artifact, version = m.groups()
        out.append(
            {
                "ecosystem": "android-gradle",
                "name": f"{group}:{artifact}",
                "version": version,
                "source_path": str(build_file.relative_to(repo_root)),
                "note": cfg,
            }
        )
    return out


def parse_node_modules(repo_root: Path) -> List[Dict[str, str]]:
    node_root = repo_root / "app" / "android" / "app" / "src" / "main" / "assets" / "node" / "usr" / "lib" / "node_modules"
    if not node_root.exists():
        return []
    out: List[Dict[str, str]] = []
    for pkg_file in sorted(node_root.glob("*/package.json")):
        try:
            obj = json.loads(pkg_file.read_text(encoding="utf-8"))
        except Exception:
            continue
        name = str(obj.get("name") or pkg_file.parent.name).strip()
        version = str(obj.get("version") or "").strip()
        license_name = str(obj.get("license") or "").strip()
        out.append(
            {
                "ecosystem": "node",
                "name": name,
                "version": version,
                "license": license_name,
                "source_path": str(pkg_file.relative_to(repo_root)),
            }
        )
    return out


def parse_wheels(repo_root: Path) -> List[Dict[str, str]]:
    wheels_root = repo_root / "app" / "android" / "app" / "src" / "main" / "assets" / "wheels"
    if not wheels_root.exists():
        return []
    out: List[Dict[str, str]] = []
    wheel_pat = re.compile(r"^([A-Za-z0-9_.-]+)-([0-9][A-Za-z0-9_.!+-]*)-")
    for whl in sorted(wheels_root.rglob("*.whl")):
        m = wheel_pat.match(whl.name)
        if m:
            raw_name, version = m.groups()
            name = normalize_name(raw_name)
        else:
            name = whl.stem
            version = ""
        out.append(
            {
                "ecosystem": "python-wheel",
                "name": name,
                "version": version,
                "source_path": str(whl.relative_to(repo_root)),
            }
        )
    return out


def parse_third_party(repo_root: Path) -> List[Dict[str, str]]:
    third_party = repo_root / "third_party"
    if not third_party.exists():
        return []
    out: List[Dict[str, str]] = []
    for p in sorted(third_party.iterdir()):
        if not p.is_dir():
            continue
        out.append(
            {
                "ecosystem": "vendored-native",
                "name": p.name,
                "version": "",
                "source_path": str(p.relative_to(repo_root)),
            }
        )
    return out


def dedupe(items: Iterable[Dict[str, str]]) -> List[Dict[str, str]]:
    seen: set[Tuple[str, str, str, str]] = set()
    out: List[Dict[str, str]] = []
    for item in items:
        eco = item.get("ecosystem", "").strip()
        name = item.get("name", "").strip()
        ver = item.get("version", "").strip()
        src = item.get("source_path", "").strip()
        key = (eco, name, ver, src)
        if key in seen:
            continue
        seen.add(key)
        out.append(item)
    out.sort(key=lambda x: (x.get("ecosystem", ""), x.get("name", ""), x.get("version", ""), x.get("source_path", "")))
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate dependency inventory JSON.")
    parser.add_argument("--output", required=True, help="Output JSON path")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent
    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    deps = dedupe(
        parse_gradle_dependencies(repo_root)
        + parse_node_modules(repo_root)
        + parse_wheels(repo_root)
        + parse_third_party(repo_root)
    )
    data = {
        "schema_version": 1,
        "dependencies": deps,
    }
    output.write_text(json.dumps(data, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    print(f"Wrote {len(deps)} dependencies to {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
