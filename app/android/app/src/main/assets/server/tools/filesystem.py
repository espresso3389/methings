from pathlib import Path
from typing import Any, Dict


class FilesystemTool:
    def __init__(self, base_dir: Path):
        self.base_dir = base_dir
        self.base_dir.mkdir(parents=True, exist_ok=True)

    def run(self, args: Dict[str, Any]) -> Dict[str, Any]:
        action = args.get("action", "list")
        if action != "list":
            return {"status": "error", "error": "unsupported_action"}

        rel = args.get("path", ".")
        target = (self.base_dir / rel).resolve()
        if self.base_dir not in target.parents and target != self.base_dir:
            return {"status": "error", "error": "path_outside_base"}

        if not target.exists():
            return {"status": "error", "error": "not_found"}

        items = []
        for entry in target.iterdir():
            items.append({
                "name": entry.name,
                "is_dir": entry.is_dir(),
            })
        return {"status": "ok", "items": items}
