"""Tests der Sprach-Werkzeuge (tools/voice): Aufnahme-Ablauf mit Schein-Mikrofon, Datensatzprüfung, Umrechnen, Piper-Metadaten."""
import io
import re
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools" / "voice"))

import check_dataset  # noqa: E402
import record  # noqa: E402
import voicelib as v  # noqa: E402


def tone(seconds: float, rate: int = v.TARGET_RATE, amp: float = 0.4, lead: float = 0.0, tail: float = 0.0) -> np.ndarray:
    t = np.arange(int(seconds * rate)) / rate
    body = amp * np.sin(2 * np.pi * 220 * t) * (0.6 + 0.4 * np.sin(2 * np.pi * 3 * t))
    return np.concatenate([np.zeros(int(lead * rate)), body, np.zeros(int(tail * rate))]).astype(np.float32)


def make_dataset(folder: Path, count: int, seconds: float = 3.0, rate: int = v.TARGET_RATE, text: str = "Das ist ein Satz.") -> None:
    lines = []
    for i in range(1, count + 1):
        v.write_wav(folder / "wavs" / f"{i:04d}.wav", tone(seconds, rate, lead=0.2, tail=0.2), rate)
        lines.append(f"{i:04d}.wav|{text} {i}x")
    (folder / "metadata.csv").write_text("\n".join(lines) + "\n", encoding="utf-8")


class AudioHelpersTest(unittest.TestCase):
    def test_wav_roundtrip_and_stereo_mixdown(self):
        with tempfile.TemporaryDirectory() as d:
            x = tone(1.0)
            v.write_wav(Path(d) / "a.wav", x)
            y, rate, channels = v.read_wav(Path(d) / "a.wav")
            self.assertEqual((rate, channels, len(y)), (v.TARGET_RATE, 1, len(x)))
            self.assertLess(float(np.max(np.abs(x - y))), 1e-3)

    def test_trim_keeps_margin_and_speech(self):
        x = tone(2.0, lead=3.0, tail=3.0)
        trimmed = v.trim_silence(x, v.TARGET_RATE, margin_s=0.25)
        self.assertAlmostEqual(len(trimmed) / v.TARGET_RATE, 2.5, delta=0.1)
        self.assertEqual(len(v.trim_silence(np.zeros(1000, dtype=np.float32), v.TARGET_RATE)), 1000)  # Stille bleibt

    def test_resample_changes_length_not_pitch_range(self):
        x = tone(1.0, 48000)
        y = v.resample(x, 48000, v.TARGET_RATE)
        self.assertAlmostEqual(len(y) / v.TARGET_RATE, 1.0, delta=0.01)
        self.assertAlmostEqual(float(np.max(np.abs(y))), float(np.max(np.abs(x))), delta=0.05)

    def test_stats_detect_clipping_and_silence(self):
        s = v.stats(tone(2.0, lead=1.0, tail=0.5), v.TARGET_RATE)
        self.assertAlmostEqual(s["lead"], 1.0, delta=0.05)
        self.assertAlmostEqual(s["tail"], 0.5, delta=0.05)
        loud = np.clip(tone(1.0, amp=3.0), -1, 1)
        self.assertGreater(v.stats(loud, v.TARGET_RATE)["clip"], 0.001)


class SentencesTest(unittest.TestCase):
    def test_shipped_sentence_lists_are_clean(self):
        for name in ("sentences_de.txt", "sentences_en.txt"):
            lines = v.load_sentences(ROOT / "tools" / "voice" / name)
            self.assertGreater(len(lines), 100, name)
            self.assertEqual(len(lines), len(set(lines)), f"{name}: doppelte Sätze")
            for line in lines:
                self.assertNotRegex(line, r"\d", f"{name}: Ziffer in '{line}'")
                self.assertGreaterEqual(len(line.split()), 3, f"{name}: zu kurz: {line}")
                self.assertLess(len(line), 140, f"{name}: zu lang: {line}")

    def test_comments_and_blank_lines_are_ignored(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "s.txt"
            p.write_text("# Kommentar\n\nEins zwei drei.\n  \n  # noch einer\nVier fünf sechs.\n", encoding="utf-8")
            self.assertEqual(v.load_sentences(p), ["Eins zwei drei.", "Vier fünf sechs."])


class SplitSentencesTest(unittest.TestCase):
    def test_book_text_becomes_recordable_sentences(self):
        text = ("Das war Dr. Meier, wie er leibt und lebt. „Kommst du heute Abend?“, fragte sie leise. Er antwortete nicht! "
                "Warum auch,\ndachte er; es war ohnehin alles gesagt. Am 12. Mai war es so weit. Ein Hund, z. B. ein Dackel, "
                "bellte laut im Hof.\n\nNach der Rück-\nkehr aß man zu Abend. Ja. Nach der Rückkehr aß man zu Abend.")
        self.assertEqual(
            v.split_sentences(text),
            ["Das war Dr. Meier, wie er leibt und lebt.", "Kommst du heute Abend? fragte sie leise.",
             "Warum auch, dachte er; es war ohnehin alles gesagt.", "Ein Hund, z. B. ein Dackel, bellte laut im Hof.",
             "Nach der Rückkehr aß man zu Abend."],  # ohne: Ziffern, Zu-Kurzes, Dubletten
        )

    def test_long_sentences_are_split_at_semicolons_or_dropped(self):
        long = "Eins zwei drei vier; fünf sechs sieben acht " + " ".join(["wort"] * 40) + "."
        self.assertEqual(v.split_sentences(long), ["Eins zwei drei vier."])  # der zweite Teil ist zu lang und fällt weg
        self.assertEqual(v.split_sentences("Wort " * 40 + "Ende."), [])


class CheckDatasetTest(unittest.TestCase):
    def test_good_dataset_passes(self):
        with tempfile.TemporaryDirectory() as d:
            make_dataset(Path(d), 400, seconds=4.0)  # ~27 Minuten
            summary, errors, notes = check_dataset.check(Path(d))
            self.assertEqual(errors, [])
            self.assertEqual(summary["count"], 400)
            self.assertTrue(any("knapp" in n for n in notes))  # 27 Minuten: nur Feinabstimmung

    def test_problems_are_reported(self):
        with tempfile.TemporaryDirectory() as d:
            folder = Path(d)
            make_dataset(folder, 3, rate=44100)
            v.write_wav(folder / "wavs" / "0004.wav", np.clip(tone(2.0, amp=3.0), -1, 1))
            with (folder / "metadata.csv").open("a", encoding="utf-8") as f:
                f.write("0004.wav|Zwölf Äpfel kosten 12 Euro.\n0005.wav|Fehlt.\n")
            _, errors, notes = check_dataset.check(folder)
            joined = "\n".join(errors + notes)
            self.assertIn("44100 Hz statt 22050 Hz", joined)
            self.assertIn("übersteuert", joined)
            self.assertIn("0005.wav: Datei fehlt", joined)
            self.assertIn("Ziffern im Text", joined)
            self.assertIn("zu wenig", joined)  # nur wenige Sekunden

    def test_fix_to_converts_rate_channels_and_trims(self):
        with tempfile.TemporaryDirectory() as d:
            src, dst = Path(d) / "src", Path(d) / "dst"
            make_dataset(src, 4, rate=48000)
            n = check_dataset.fix_to(src, dst)
            self.assertEqual(n, 4)
            _, errors, _ = check_dataset.check(dst)
            self.assertFalse([e for e in errors if "Hz" in e or "Kanäle" in e], errors)
            y, rate, channels = v.read_wav(dst / "wavs" / "0001.wav")
            self.assertEqual((rate, channels), (v.TARGET_RATE, 1))


class FakeStream:
    """Schein-Mikrofon: liefert beim Öffnen sofort einen Ton an den Rückruf."""

    def __init__(self, samplerate, channels, dtype, device, callback):
        self.callback = callback
        self.rate = samplerate

    def __enter__(self):
        block = tone(1.5, self.rate)[:, None]
        self.callback(block, len(block), None, None)
        return self

    def __exit__(self, *exc):
        return False


class FakeSoundDevice:
    InputStream = FakeStream

    def __init__(self):
        self.played = 0

    def check_input_settings(self, device=None, channels=1, samplerate=0):
        if samplerate != v.TARGET_RATE:
            raise ValueError("nur Zielrate")

    def play(self, data, rate):
        self.played += 1

    def wait(self):
        pass


class RecordFlowTest(unittest.TestCase):
    def test_recording_flow_keep_repeat_skip_and_resume(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / "stimme"
            sents = Path(d) / "s.txt"
            sents.write_text("Erster Satz hier.\nZweiter Satz hier.\nDritter Satz hier.\n", encoding="utf-8")
            fake = FakeSoundDevice()
            # Satz 1: start, [Enter] Ende, hören (h), behalten; Satz 2: start, Ende, wiederholen (w), start, Ende, behalten;
            # Satz 3: überspringen
            answers = iter(["", "", "h", "", "", "", "w", "", "", "", "s"])
            argv = ["record.py", "--sentences", str(sents), "--out", str(out)]
            with mock.patch.object(record, "import_sounddevice", return_value=fake), mock.patch("builtins.input", lambda *_: next(answers)), \
                    mock.patch.object(sys, "argv", argv), redirect_stdout(io.StringIO()):
                record.main()
            rows = v.read_metadata(out / "metadata.csv")
            self.assertEqual([t for _, t in rows], ["Erster Satz hier.", "Zweiter Satz hier."])
            self.assertEqual(rows[0][0], "0001.wav")
            self.assertEqual(fake.played, 1)
            self.assertEqual(len(list((out / "wavs").glob("*.wav"))), 2)
            y, rate, channels = v.read_wav(out / "wavs" / "0001.wav")
            self.assertEqual((rate, channels), (v.TARGET_RATE, 1))
            # zweiter Lauf: nur der übersprungene Satz ist offen
            answers2 = iter(["", "", ""])
            with mock.patch.object(record, "import_sounddevice", return_value=fake), mock.patch("builtins.input", lambda *_: next(answers2)), \
                    mock.patch.object(sys, "argv", argv), redirect_stdout(io.StringIO()) as buf:
                record.main()
            self.assertIn("1 von 3 Sätzen offen", buf.getvalue())
            self.assertEqual(len(v.read_metadata(out / "metadata.csv")), 3)


class PiperConfigTest(unittest.TestCase):
    CONFIG = {
        "audio": {"sample_rate": 22050, "quality": "medium"},
        "espeak": {"voice": "de"},
        "num_speakers": 1,
        "phoneme_id_map": {"_": [0], "^": [1], "$": [2], " ": [3], "a": [14], "\n": [99]},
    }

    def test_tokens_like_official_script(self):
        self.assertEqual(v.tokens_from_config(self.CONFIG), "_ 0\n^ 1\n$ 2\n  3\na 14\n")

    def test_metadata_like_official_script(self):
        meta = v.model_metadata(self.CONFIG, "de_DE")
        self.assertEqual(
            meta,
            {"model_type": "vits", "comment": "piper", "language": "German", "voice": "de", "version": 1, "has_espeak": 1,
             "has_g2pw": 0, "n_speakers": 1, "sample_rate": 22050},
        )
        odd = dict(self.CONFIG, audio={"sample_rate": 22500})
        self.assertEqual(v.model_metadata(odd, "en_GB")["sample_rate"], 22050)  # krumme Rate wie im offiziellen Skript
        self.assertEqual(v.model_metadata(self.CONFIG, "xx_YY")["language"], "xx")


if __name__ == "__main__":
    unittest.main()
