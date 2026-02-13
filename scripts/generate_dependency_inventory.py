#!/usr/bin/env python3
"""Generate dependency inventory and full-text licenses datasets."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import urllib.request
import zipfile
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
        license_name = ""
        try:
            with zipfile.ZipFile(whl, "r") as zf:
                meta_name = next((n for n in zf.namelist() if n.endswith(".dist-info/METADATA")), "")
                if meta_name:
                    meta = zf.read(meta_name).decode("utf-8", errors="ignore")
                    m_lic = re.search(r"^License:\s*(.+)$", meta, re.MULTILINE)
                    if m_lic:
                        license_name = m_lic.group(1).strip()
                    if not license_name:
                        classifiers = re.findall(r"^Classifier:\s*License :: (.+)$", meta, re.MULTILINE)
                        if classifiers:
                            license_name = classifiers[0].strip()
        except Exception:
            pass
        out.append(
            {
                "ecosystem": "python-wheel",
                "name": name,
                "version": version,
                "license": license_name,
                "source_path": str(whl.relative_to(repo_root)),
            }
        )
    return out


def detect_license_from_text(text: str) -> str:
    t = text.lower()
    if "mit license" in t:
        return "MIT"
    if "apache license" in t and "version 2" in t:
        return "Apache-2.0"
    if "bsd 3-clause" in t or "redistribution and use in source and binary forms" in t:
        return "BSD-3-Clause"
    if "bsd 2-clause" in t:
        return "BSD-2-Clause"
    if "gnu lesser general public license" in t or "lgpl" in t:
        return "LGPL"
    if "gnu general public license" in t or "gpl" in t:
        return "GPL"
    if "mozilla public license" in t:
        return "MPL"
    return ""


def parse_third_party(repo_root: Path) -> List[Dict[str, str]]:
    third_party = repo_root / "third_party"
    if not third_party.exists():
        return []
    out: List[Dict[str, str]] = []
    for p in sorted(third_party.iterdir()):
        if not p.is_dir():
            continue
        lic = ""
        for cand in sorted(p.rglob("*")):
            if not cand.is_file():
                continue
            n = cand.name.lower()
            if not (n.startswith("license") or n.startswith("copying")):
                continue
            try:
                txt = cand.read_text(encoding="utf-8", errors="ignore")
            except Exception:
                continue
            lic = detect_license_from_text(txt)
            if lic:
                break
        out.append(
            {
                "ecosystem": "vendored-native",
                "name": p.name,
                "version": "",
                "license": lic,
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


def read_ignore_config(repo_root: Path) -> Dict[str, List[str]]:
    p = repo_root / "licenses" / "ignore_list.json"
    if not p.exists():
        return {"ignore_ecosystems": [], "ignore_names": [], "ignore_name_patterns": []}
    try:
        obj = json.loads(p.read_text(encoding="utf-8"))
    except Exception:
        return {"ignore_ecosystems": [], "ignore_names": [], "ignore_name_patterns": []}
    return {
        "ignore_ecosystems": [str(x).strip() for x in obj.get("ignore_ecosystems", [])],
        "ignore_names": [normalize_name(str(x)) for x in obj.get("ignore_names", [])],
        "ignore_name_patterns": [normalize_name(str(x)) for x in obj.get("ignore_name_patterns", [])],
    }


def read_gradle_license_map(repo_root: Path) -> List[Dict[str, str]]:
    p = repo_root / "licenses" / "gradle_license_map.json"
    if not p.exists():
        return []
    try:
        obj = json.loads(p.read_text(encoding="utf-8"))
    except Exception:
        return []
    rows = obj.get("prefix_mappings", [])
    out: List[Dict[str, str]] = []
    for r in rows:
        if not isinstance(r, dict):
            continue
        prefix = str(r.get("group_prefix", "")).strip()
        url = str(r.get("license_url", "")).strip()
        title = str(r.get("title", "")).strip()
        if not prefix or not url:
            continue
        out.append({"group_prefix": prefix, "license_url": url, "title": title})
    out.sort(key=lambda x: len(x["group_prefix"]), reverse=True)
    return out


def is_ignored(dep: Dict[str, str], cfg: Dict[str, List[str]]) -> bool:
    eco = str(dep.get("ecosystem", "")).strip()
    if eco in cfg.get("ignore_ecosystems", []):
        return True
    name = normalize_name(str(dep.get("name", "")))
    if name in cfg.get("ignore_names", []):
        return True
    for pat in cfg.get("ignore_name_patterns", []):
        if pat and pat in name:
            return True
    return False


def find_local_license_files(base_dir: Path) -> List[Path]:
    if not base_dir.exists():
        return []
    out: List[Path] = []
    allowed_exts = {"", ".txt", ".md", ".markdown", ".rst"}
    skip_exts = {".cmake", ".c", ".cc", ".cpp", ".h", ".hpp", ".py", ".java", ".kt", ".js", ".ts", ".sh"}
    for p in sorted(base_dir.rglob("*")):
        if not p.is_file():
            continue
        n = p.name.lower()
        stem = p.stem.lower()
        ext = p.suffix.lower()
        is_license_name = (
            n == "license"
            or n == "copying"
            or n == "notice"
            or n.startswith("license.")
            or n.startswith("copying.")
            or n.startswith("notice.")
        )
        if not is_license_name:
            continue
        if ext in skip_exts:
            continue
        if ext not in allowed_exts:
            continue
        if stem in {"license", "copying", "notice"}:
            out.append(p)
    return out[:4]


def docs_for_node(repo_root: Path, dep: Dict[str, str]) -> List[Dict[str, str]]:
    pkg_json = repo_root / dep.get("source_path", "")
    base = pkg_json.parent
    return docs_from_files(repo_root, find_local_license_files(base))


def docs_from_zip_member(zf: zipfile.ZipFile, member: str, title: str) -> Dict[str, str] | None:
    try:
        raw = zf.read(member)
    except Exception:
        return None
    text = raw.decode("utf-8", errors="ignore").strip()
    if not text:
        return None
    lower = member.lower()
    fmt = "markdown" if lower.endswith(".md") else "text"
    return {"title": title, "format": fmt, "text": text}


def docs_for_gradle(dep: Dict[str, str]) -> List[Dict[str, str]]:
    name = dep.get("name", "")
    if ":" not in name:
        return []
    group, artifact = name.split(":", 1)
    version = dep.get("version", "")
    cache_root = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1" / group / artifact / version
    if not cache_root.exists():
        return []
    docs: List[Dict[str, str]] = []
    local_files = []
    for f in sorted(cache_root.rglob("*")):
        if not f.is_file():
            continue
        n = f.name.lower()
        if n.startswith("license") or n.startswith("copying") or n.startswith("notice"):
            local_files.append(f)
    docs.extend(docs_from_files(cache_root, local_files[:3]))
    if docs:
        return docs
    for arc in sorted(cache_root.rglob("*.jar"))[:3]:
        try:
            with zipfile.ZipFile(arc, "r") as zf:
                members = [n for n in zf.namelist() if re.search(r"(^|/)(license|copying|notice)([^/]*)$", n, re.IGNORECASE)]
                for m in members[:2]:
                    d = docs_from_zip_member(zf, m, f"{arc.name}:{m}")
                    if d:
                        docs.append(d)
        except Exception:
            continue
    if docs:
        return docs[:3]
    mapped = docs_for_gradle_prefix_map(group)
    if mapped:
        docs.append(mapped)
        return docs[:3]
    if group.startswith("androidx."):
        txt = fetch_text_url("https://raw.githubusercontent.com/androidx/androidx/refs/heads/androidx-main/LICENSE.txt")
        if txt:
            docs.append(
                {
                    "title": "AndroidX LICENSE.txt",
                    "format": "text",
                    "text": txt,
                }
            )
            return docs[:3]
    if group.startswith("com.github."):
        owner = group.replace("com.github.", "", 1).strip()
        repo = artifact.strip()
        gh = fetch_github_license(owner, repo)
        if gh:
            docs.append(gh)
            return docs[:3]
    return docs[:3]


_GRADLE_LICENSE_MAPPINGS: List[Dict[str, str]] = []


def docs_for_gradle_prefix_map(group: str) -> Dict[str, str] | None:
    for row in _GRADLE_LICENSE_MAPPINGS:
        prefix = row.get("group_prefix", "")
        if not prefix or not group.startswith(prefix):
            continue
        src = row.get("license_url", "")
        if not src:
            continue
        text = fetch_text_url(src)
        if not text:
            continue
        title = row.get("title", "").strip() or f"{prefix} LICENSE"
        fmt = "markdown" if src.lower().endswith(".md") else "text"
        return {"title": title, "format": fmt, "text": text}
    return None


def fetch_github_license(owner: str, repo: str) -> Dict[str, str] | None:
    if not owner or not repo:
        return None
    branches = ["main", "master"]
    names = ["LICENSE", "LICENSE.md", "LICENSE.txt", "COPYING", "COPYING.md", "COPYING.txt"]
    for branch in branches:
        for name in names:
            url = f"https://raw.githubusercontent.com/{owner}/{repo}/{branch}/{name}"
            try:
                with urllib.request.urlopen(url, timeout=6) as resp:
                    if int(getattr(resp, "status", 200)) >= 400:
                        continue
                    raw = resp.read()
                text = raw.decode("utf-8", errors="ignore").strip()
                if not text:
                    continue
                fmt = "markdown" if name.lower().endswith(".md") else "text"
                return {
                    "title": f"{owner}/{repo}:{name}",
                    "format": fmt,
                    "text": text,
                }
            except Exception:
                continue
    return None


def fetch_text_url(url: str) -> str:
    candidates = [url]
    m = re.match(r"^https://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.*)$", url)
    if m:
        owner, repo, branch, path = m.groups()
        candidates.insert(0, f"https://raw.githubusercontent.com/{owner}/{repo}/{branch}/{path}")
    for u in candidates:
        try:
            with urllib.request.urlopen(u, timeout=8) as resp:
                if int(getattr(resp, "status", 200)) >= 400:
                    continue
                raw = resp.read()
            text = raw.decode("utf-8", errors="ignore").strip()
            if text:
                return text
        except Exception:
            continue
    return ""


def docs_for_vendored(repo_root: Path, dep: Dict[str, str]) -> List[Dict[str, str]]:
    base = repo_root / dep.get("source_path", "")
    return docs_from_files(repo_root, find_local_license_files(base))


def docs_for_python_wheel(repo_root: Path, dep: Dict[str, str]) -> List[Dict[str, str]]:
    whl = repo_root / dep.get("source_path", "")
    if not whl.exists():
        return []
    docs: List[Dict[str, str]] = []
    try:
        with zipfile.ZipFile(whl, "r") as zf:
            names = [n for n in zf.namelist() if re.search(r"\.dist-info/(license|copying|notice|licenses?/)", n, re.IGNORECASE)]
            for n in names[:3]:
                d = docs_from_zip_member(zf, n, f"{whl.name}:{n}")
                if d:
                    docs.append(d)
    except Exception:
        return []
    return docs


def docs_from_files(root: Path, files: List[Path]) -> List[Dict[str, str]]:
    out: List[Dict[str, str]] = []
    for p in files:
        try:
            text = p.read_text(encoding="utf-8", errors="ignore").strip()
        except Exception:
            continue
        if not text:
            continue
        lower = p.name.lower()
        fmt = "markdown" if lower.endswith(".md") or lower.endswith(".markdown") else "text"
        try:
            title = str(p.relative_to(root))
        except Exception:
            title = p.name
        out.append({"title": title, "format": fmt, "text": text})
    return out


def build_full_licenses(repo_root: Path, deps: List[Dict[str, str]]) -> Dict[str, object]:
    section_labels = {
        "android-gradle": "Android (Gradle)",
        "node": "Node.js",
        "python-wheel": "Python Wheels",
        "vendored-native": "Vendored Native",
    }
    grouped: Dict[str, List[Dict[str, object]]] = {}
    for dep in deps:
        eco = dep.get("ecosystem", "other")
        docs: List[Dict[str, str]] = []
        if eco == "node":
            docs = docs_for_node(repo_root, dep)
        elif eco == "vendored-native":
            docs = docs_for_vendored(repo_root, dep)
        elif eco == "android-gradle":
            docs = docs_for_gradle(dep)
        elif eco == "python-wheel":
            docs = docs_for_python_wheel(repo_root, dep)
        item = {
            "name": dep.get("name", ""),
            "version": dep.get("version", ""),
            "license": dep.get("license", ""),
            "source_path": dep.get("source_path", ""),
            "documents": docs,
        }
        grouped.setdefault(eco, []).append(item)

    sections = []
    order = ["android-gradle", "node", "python-wheel", "vendored-native"]
    ecos = order + sorted([k for k in grouped.keys() if k not in order])
    for eco in ecos:
        items = grouped.get(eco, [])
        if not items:
            continue
        items.sort(key=lambda x: (str(x.get("name", "")), str(x.get("version", ""))))
        sections.append({"ecosystem": eco, "label": section_labels.get(eco, eco), "dependencies": items})

    return {
        "schema_version": 1,
        "generated_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "sections": sections,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate dependency inventory JSON.")
    parser.add_argument("--output", required=True, help="Output inventory JSON path")
    parser.add_argument("--licenses-output", required=False, help="Output full licenses JSON path")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent
    global _GRADLE_LICENSE_MAPPINGS
    _GRADLE_LICENSE_MAPPINGS = read_gradle_license_map(repo_root)
    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    cfg = read_ignore_config(repo_root)

    deps = dedupe(
        parse_gradle_dependencies(repo_root)
        + parse_node_modules(repo_root)
        + parse_wheels(repo_root)
        + parse_third_party(repo_root)
    )
    deps = [d for d in deps if not is_ignored(d, cfg)]

    data = {
        "schema_version": 1,
        "dependencies": deps,
        "ignore_config": cfg,
    }
    output.write_text(json.dumps(data, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    print(f"Wrote {len(deps)} dependencies to {output}")

    if args.licenses_output:
        lout = Path(args.licenses_output).resolve()
        lout.parent.mkdir(parents=True, exist_ok=True)
        full = build_full_licenses(repo_root, deps)
        lout.write_text(json.dumps(full, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
        print(f"Wrote full licenses to {lout}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
