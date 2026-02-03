from pathlib import Path
from typing import Any, Dict

from .filesystem import FilesystemTool
from .shell import ShellTool


class ToolRouter:
    def __init__(self, base_dir: Path):
        self.base_dir = base_dir
        self.tools = {
            "filesystem": FilesystemTool(base_dir),
            "shell": ShellTool(),
        }

    def invoke(self, name: str, args: Dict[str, Any]) -> Dict[str, Any]:
        tool = self.tools.get(name)
        if not tool:
            return {"status": "error", "error": "unknown_tool"}
        return tool.run(args)
