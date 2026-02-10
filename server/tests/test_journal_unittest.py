import os
import tempfile
import unittest
from pathlib import Path


class JournalTests(unittest.TestCase):
    def _mk_store(self, root: Path, **cfg_overrides):
        import sys

        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        import journal  # type: ignore

        cfg = journal.JournalConfig(**cfg_overrides)
        return journal.JournalStore(root, config=cfg)

    def test_current_rotation(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td) / "journal"
            js = self._mk_store(root, max_current_bytes=64)
            big = "x" * 200
            res = js.set_current("default", big)
            self.assertEqual(res.get("status"), "ok")
            self.assertTrue(res.get("truncated"))
            sess = root / "default"
            self.assertTrue((sess / "CURRENT.md").exists())
            rotated = [p for p in sess.iterdir() if p.name.startswith("CURRENT.") and p.name.endswith(".md")]
            self.assertTrue(rotated)

    def test_append_and_list(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td) / "journal"
            js = self._mk_store(root, max_entry_inline_bytes=32, rotate_entries_bytes=200)
            # Force an oversized entry to become a stored file.
            res = js.append("s1", kind="milestone", title="t", text="A" * 500, meta={"k": "v"})
            self.assertEqual(res.get("status"), "ok")
            self.assertTrue(res.get("stored_path"))
            stored = Path(res.get("stored_path"))
            self.assertTrue(stored.exists())

            # Add enough entries to rotate.
            for i in range(40):
                js.append("s1", kind="note", title=f"n{i}", text="hello", meta={})

            listing = js.list_entries("s1", limit=20)
            self.assertEqual(listing.get("status"), "ok")
            entries = listing.get("entries")
            self.assertTrue(isinstance(entries, list))
            self.assertLessEqual(len(entries), 20)

            sess = root / "s1"
            rotated = [p for p in sess.iterdir() if p.name.startswith("entries.") and p.name.endswith(".jsonl")]
            # Rotation is best-effort; but with tiny rotate_entries_bytes, it should happen.
            self.assertTrue(rotated)


if __name__ == "__main__":
    unittest.main()

