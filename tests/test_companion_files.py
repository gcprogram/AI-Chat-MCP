"""Companion-Dateien heißen *.char (Inhalt: JSON); *.json sind keine Companions mehr."""
import json
import tempfile
import unittest
from pathlib import Path

from chat_app.persona import Persona, delete_persona, list_personas, load_persona, save_persona, unique_id


class CompanionFileTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)

    def test_companions_are_saved_and_loaded_as_char(self):
        pid = save_persona(Persona(name="Tom Müller"), self.dir)
        self.assertEqual(sorted(p.name for p in self.dir.iterdir()), [f"{pid}.char"])
        self.assertEqual(load_persona(pid, self.dir).name, "Tom Müller")
        self.assertEqual(list_personas(self.dir), [pid])
        self.assertEqual(unique_id("Tom Müller", self.dir), f"{pid}_2")

    def test_json_files_are_ignored(self):
        (self.dir / "alt.json").write_text(json.dumps({"name": "Alt"}), encoding="utf-8")
        self.assertEqual(list_personas(self.dir), [])
        self.assertEqual(unique_id("Alt", self.dir), "alt")

    def test_delete_removes_the_char_file(self):
        pid = save_persona(Persona(name="Weg"), self.dir)
        delete_persona(pid, self.dir)
        self.assertEqual(list(self.dir.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
