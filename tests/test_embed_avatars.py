import base64
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image

from chat_app.avatar import AvatarError, encode_avatar
from chat_app.persona import Persona, embedded_avatar_file, load_persona, remove_avatar_file, save_persona

SPEC = importlib.util.spec_from_file_location("embed_avatars", Path(__file__).resolve().parent.parent / "tools" / "embed_avatars.py")
embed_avatars = importlib.util.module_from_spec(SPEC)
sys.modules["embed_avatars"] = embed_avatars
SPEC.loader.exec_module(embed_avatars)


def picture(path: Path, size=(600, 400), color=(200, 30, 30)) -> Path:
    Image.new("RGB", size, color).save(path)
    return path


class EmbeddedAvatarTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def test_encoded_image_is_a_square_jpeg_within_the_budget(self):
        data = encode_avatar(picture(self.dir / "a.png", size=(1200, 900)))
        raw = base64.b64decode(data)
        self.assertEqual(b"\xff\xd8", raw[:2])
        import io
        with Image.open(io.BytesIO(raw)) as img:
            self.assertEqual((768, 768), img.size)  # glattes Bild: die größte Stufe passt ins Budget
        self.assertLessEqual(len(data), 100 * 1024)

    def test_detailed_image_gets_a_smaller_size_to_stay_within_100_kb(self):
        import random
        rnd = random.Random(1)
        noisy = Image.new("RGB", (1200, 1200))
        noisy.putdata([(rnd.randrange(256), rnd.randrange(256), rnd.randrange(256)) for _ in range(1200 * 1200)])
        noisy.save(self.dir / "n.png")
        data = encode_avatar(self.dir / "n.png", budget_kb=100)
        import io
        with Image.open(io.BytesIO(base64.b64decode(data))) as img:
            self.assertLess(img.size[0], 768)  # Rauschen lässt sich nicht klein komprimieren: die Kantenlänge sinkt
        self.assertLessEqual(len(encode_avatar(self.dir / "n.png", budget_kb=400)), 400 * 1024)

    def test_small_images_are_not_scaled_up(self):
        import io
        data = encode_avatar(picture(self.dir / "k.png", size=(300, 200)))
        with Image.open(io.BytesIO(base64.b64decode(data))) as img:
            self.assertEqual((200, 200), img.size)

    def test_missing_or_broken_image_is_an_error(self):
        with self.assertRaises(AvatarError):
            encode_avatar(self.dir / "nix.png")
        (self.dir / "kaputt.png").write_bytes(b"kein bild")
        with self.assertRaises(AvatarError):
            encode_avatar(self.dir / "kaputt.png")

    def test_persona_keeps_the_image_in_its_file_and_it_wins_over_the_old_path(self):
        data = encode_avatar(picture(self.dir / "a.png"))
        save_persona(Persona(name="Nyx", avatar="avatars/alt.png", avatar_data=data, id="nyx"), self.dir)
        text = (self.dir / "nyx.json").read_text(encoding="utf-8")
        self.assertIn('"avatar_data"', text)
        loaded = load_persona("nyx", self.dir)
        self.assertEqual(data, loaded.avatar_data)
        path = loaded.avatar_path(self.dir)
        self.assertIsNotNone(path)
        self.assertEqual(base64.b64decode(data), path.read_bytes())  # die ausgepackte Cache-Datei ist das eingebettete Bild
        self.assertIsNone(loaded.legacy_avatar_path(self.dir))  # die alte Datei fehlt, das Bild kommt trotzdem
        save_persona(Persona(name="Leer", id="leer"), self.dir)
        self.assertNotIn("avatar_data", (self.dir / "leer.json").read_text(encoding="utf-8"))

    def test_old_files_and_broken_data_still_work(self):
        picture(self.dir / "avatars_x.png")
        (self.dir / "x.json").write_text(json.dumps({"name": "X", "avatar": "avatars_x.png"}), encoding="utf-8")
        self.assertIsNotNone(load_persona("x", self.dir).avatar_path(self.dir))  # älteres Format
        (self.dir / "y.json").write_text(json.dumps({"name": "Y", "avatar_data": 5}), encoding="utf-8")
        self.assertEqual("", load_persona("y", self.dir).avatar_data)
        self.assertIsNone(embedded_avatar_file("!!!kein base64!!!"))
        self.assertIsNone(embedded_avatar_file(""))

    def test_removing_a_companion_never_deletes_the_cache_file(self):
        data = encode_avatar(picture(self.dir / "a.png"))
        persona = Persona(name="N", avatar_data=data, id="n")
        cache = persona.avatar_path(self.dir)
        remove_avatar_file(persona, self.dir)
        self.assertTrue(cache.is_file())


class EmbedToolTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)
        for cid, name in (("kaeptn_brise", "Käpt'n Brise"), ("zero", "Zero"), ("nyx", "Nyx")):
            (self.dir / f"{cid}.json").write_text(json.dumps({"name": name, "avatar": ""}), encoding="utf-8")
        (self.dir / "mcp.json").write_text(json.dumps({"servers": {}}), encoding="utf-8")  # kein Companion

    def data_of(self, cid):
        return json.loads((self.dir / f"{cid}.json").read_text(encoding="utf-8")).get("avatar_data", "")

    def test_auto_assigns_loose_images_by_name_including_umlauts(self):
        picture(self.dir / "Käptn_Brise.png")
        picture(self.dir / "Zero.png", color=(10, 10, 200))
        report = embed_avatars.embed(self.dir, {}, auto=True, dry_run=False)
        self.assertTrue(self.data_of("kaeptn_brise").startswith("/9j/"))
        self.assertTrue(self.data_of("zero").startswith("/9j/"))
        self.assertEqual("", self.data_of("nyx"))  # kein Bild dazu
        self.assertEqual(2, len(report))
        self.assertTrue((self.dir / "Zero.png").is_file())  # das Original bleibt

    def test_legacy_avatar_file_is_embedded_and_the_path_cleared(self):
        (self.dir / "avatars").mkdir()
        picture(self.dir / "avatars" / "nyx.png")
        (self.dir / "nyx.json").write_text(json.dumps({"name": "Nyx", "avatar": "avatars/nyx.png"}), encoding="utf-8")
        embed_avatars.embed(self.dir, {}, auto=False, dry_run=False)
        saved = json.loads((self.dir / "nyx.json").read_text(encoding="utf-8"))
        self.assertEqual("", saved["avatar"])
        self.assertTrue(saved["avatar_data"])

    def test_assign_replaces_and_dry_run_writes_nothing(self):
        picture(self.dir / "neu.png")
        embed_avatars.embed(self.dir, {"neu.png": "nyx"}, auto=False, dry_run=True)
        self.assertEqual("", self.data_of("nyx"))
        embed_avatars.embed(self.dir, {"neu.png": "nyx", "neu.png2": "gibtsnicht"}, auto=False, dry_run=False)
        self.assertTrue(self.data_of("nyx"))
        report = embed_avatars.embed(self.dir, {"neu.png": "gibtsnicht"}, auto=False, dry_run=False)
        self.assertIn("Companion unbekannt", report[0])


if __name__ == "__main__":
    unittest.main()
