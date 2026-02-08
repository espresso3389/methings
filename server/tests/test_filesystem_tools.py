import sys
import unittest
from pathlib import Path


class _FakeStorage:
    def __init__(self):
        self._settings = {}
        self._creds = {}

    def get_setting(self, key: str):
        return self._settings.get(key)

    def set_setting(self, key: str, value: str):
        self._settings[key] = value

    def get_credential(self, name: str):
        return self._creds.get(name)


class FilesystemToolsTest(unittest.TestCase):
    def test_list_dir_and_read_file_scoped_to_user_dir(self):
        server_dir = Path(__file__).resolve().parents[1]
        if str(server_dir) not in sys.path:
            sys.path.insert(0, str(server_dir))

        from agents import runtime as rt

        user_dir = Path("/tmp/methings-fs-test-user")
        (user_dir / "sub").mkdir(parents=True, exist_ok=True)
        (user_dir / "hello.txt").write_text("hello\n", encoding="utf-8")

        brain = rt.BrainRuntime(
            user_dir=user_dir,
            storage=_FakeStorage(),
            emit_log=lambda *_: None,
            shell_exec=lambda *_: {"status": "ok"},
            tool_invoke=lambda *_: {"status": "ok"},
        )

        item = {"id": "chat_1", "kind": "chat", "text": "ls", "meta": {}, "created_at": 0}
        out = brain._execute_function_tool(item, "list_dir", {"path": "", "show_hidden": False, "limit": 50})
        self.assertEqual(out.get("status"), "ok")
        names = [e.get("name") for e in out.get("entries") or []]
        self.assertIn("hello.txt", names)
        self.assertIn("sub", names)

        read = brain._execute_function_tool(item, "read_file", {"path": "hello.txt", "max_bytes": 1024})
        self.assertEqual(read.get("status"), "ok")
        self.assertEqual(read.get("content"), "hello\n")


if __name__ == "__main__":
    unittest.main()

