"""Gemeinsame Hilfen für die Sprach-Werkzeuge (Aufnehmen, Prüfen, Verpacken). Nur numpy und die Standardbibliothek."""
from __future__ import annotations

import json
import math
import sys
import wave
from pathlib import Path

import numpy as np

TARGET_RATE = 22050  # Piper-Modelle der Stufen medium/high; low/x_low nutzen 16000 (das Training legt die Rate fest)

# Sprachname für die Modell-Metadaten (sherpa-onnx liest "language"); unbekannte Kürzel bleiben als Kürzel stehen
LANGUAGE_NAMES = {
    "de": "German", "en": "English", "fr": "French", "es": "Spanish", "it": "Italian", "nl": "Dutch", "pt": "Portuguese",
    "pl": "Polish", "ru": "Russian", "uk": "Ukrainian", "cs": "Czech", "sv": "Swedish", "da": "Danish", "no": "Norwegian",
    "fi": "Finnish", "hu": "Hungarian", "tr": "Turkish", "el": "Greek", "ro": "Romanian", "ca": "Catalan", "ja": "Japanese",
    "zh": "Chinese", "ar": "Arabic", "hi": "Hindi", "vi": "Vietnamese", "sk": "Slovak", "sl": "Slovenian", "bg": "Bulgarian",
}


# --- Sätze und Metadaten -------------------------------------------------------------------------------------------

def setup_console() -> None:
    """Windows-Konsolen nutzen oft cp1252: Pfeile, Anführungszeichen und Umlaute würden sonst einen Fehler auslösen."""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass


def load_sentences(path: str | Path) -> list[str]:
    """Sätze aus einer Textdatei: eine Zeile je Satz, leere Zeilen und Zeilen mit # werden ignoriert."""
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    return [line.strip() for line in lines if line.strip() and not line.lstrip().startswith("#")]


def read_metadata(path: str | Path) -> list[tuple[str, str]]:
    """metadata.csv im LJSpeech-Format ('datei|text'); Dateinamen mit oder ohne .wav."""
    rows: list[tuple[str, str]] = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        name, _, text = line.partition("|")
        rows.append((name.strip(), text.strip()))
    return rows


def wav_name(entry: str) -> str:
    return entry if entry.lower().endswith(".wav") else entry + ".wav"


# --- Audio ---------------------------------------------------------------------------------------------------------

def read_wav(path: str | Path) -> tuple[np.ndarray, int, int]:
    """Liest eine PCM-WAV-Datei. Liefert (Mono-Samples als float32 in [-1, 1], Abtastrate, Kanäle der Datei)."""
    with wave.open(str(path), "rb") as w:
        channels, width, rate, frames = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        raw = w.readframes(frames)
    if width == 2:
        data = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    elif width == 4:
        data = np.frombuffer(raw, dtype="<i4").astype(np.float32) / 2147483648.0
    elif width == 3:
        b = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3).astype(np.int32)
        value = b[:, 0] | (b[:, 1] << 8) | (b[:, 2] << 16)
        value = np.where(value >= 1 << 23, value - (1 << 24), value)
        data = value.astype(np.float32) / 8388608.0
    elif width == 1:
        data = (np.frombuffer(raw, dtype=np.uint8).astype(np.float32) - 128.0) / 128.0
    else:
        raise ValueError(f"Nicht unterstützte Sample-Breite: {width} Byte")
    if channels > 1:
        data = data[: len(data) // channels * channels].reshape(-1, channels).mean(axis=1)
    return data.astype(np.float32), rate, channels


def write_wav(path: str | Path, samples: np.ndarray, rate: int = TARGET_RATE) -> None:
    """Schreibt Mono, 16 Bit."""
    pcm = (np.clip(samples, -1.0, 1.0) * 32767.0).astype("<i2")
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())


def resample(x: np.ndarray, rate_in: int, rate_out: int) -> np.ndarray:
    """Einfache Umrechnung der Abtastrate (Mittelung gegen Aliasing beim Verkleinern, dann lineare Interpolation).
    Für Sprache mit einem normalen Mikrofon reicht das; wer es genauer will, nimmt gleich mit der Zielrate auf."""
    if rate_in == rate_out or len(x) == 0:
        return x.astype(np.float32)
    data = x.astype(np.float64)
    if rate_out < rate_in:
        k = max(1, int(round(rate_in / rate_out)))
        if k > 1:
            kernel = np.ones(k) / k
            data = np.convolve(data, kernel, mode="same")
    n_out = int(round(len(data) * rate_out / rate_in))
    positions = np.linspace(0, len(data) - 1, n_out)
    return np.interp(positions, np.arange(len(data)), data).astype(np.float32)


def db(value: float) -> float:
    return 20.0 * math.log10(max(value, 1e-9))


def _active_range(x: np.ndarray, rate: int, threshold_db: float) -> tuple[int, int] | None:
    """Erster und letzter Abschnitt (10 ms) oberhalb der Schwelle relativ zur Spitze; None bei Stille."""
    if len(x) == 0:
        return None
    peak = float(np.max(np.abs(x)))
    if peak < 1e-4:
        return None
    window = max(1, rate // 100)
    n = len(x) // window
    if n == 0:
        return 0, len(x)
    levels = np.sqrt(np.mean(x[: n * window].reshape(n, window) ** 2, axis=1))
    active = np.nonzero(levels > peak * 10 ** (threshold_db / 20.0))[0]
    if len(active) == 0:
        return None
    return int(active[0]) * window, min(len(x), (int(active[-1]) + 1) * window)


def trim_silence(x: np.ndarray, rate: int, threshold_db: float = -45.0, margin_s: float = 0.25) -> np.ndarray:
    """Schneidet Stille vorn und hinten ab, lässt aber [margin_s] Rand stehen (Atem und Ausklang sollen nicht fehlen)."""
    found = _active_range(x, rate, threshold_db)
    if found is None:
        return x
    start, end = found
    margin = int(margin_s * rate)
    return x[max(0, start - margin): min(len(x), end + margin)]


def stats(x: np.ndarray, rate: int) -> dict:
    """Kennzahlen einer Aufnahme: Dauer, Spitze, mittlere Lautstärke (dBFS, nur Sprachanteile), Übersteuerung, Stille vorn/hinten."""
    duration = len(x) / rate if rate else 0.0
    if len(x) == 0:
        return {"duration": 0.0, "peak": 0.0, "rms_db": -120.0, "clip": 0.0, "lead": 0.0, "tail": 0.0}
    peak = float(np.max(np.abs(x)))
    found = _active_range(x, rate, -40.0)
    if found is None:
        return {"duration": duration, "peak": peak, "rms_db": db(float(np.sqrt(np.mean(x ** 2)))), "clip": 0.0, "lead": duration, "tail": 0.0}
    start, end = found
    speech = x[start:end]
    return {
        "duration": duration,
        "peak": peak,
        "rms_db": db(float(np.sqrt(np.mean(speech ** 2)))),
        "clip": float(np.mean(np.abs(x) >= 0.999)),
        "lead": start / rate,
        "tail": (len(x) - end) / rate,
    }


# --- Piper-Konfiguration -------------------------------------------------------------------------------------------

def tokens_from_config(config: dict) -> str:
    """tokens.txt für sherpa-onnx aus phoneme_id_map der Piper-Konfiguration (wie das offizielle Umwandlungsskript)."""
    lines = []
    for symbol, ident in config["phoneme_id_map"].items():
        if symbol == "\n":
            continue
        if isinstance(ident, list):
            ident = ident[0]
        lines.append(f"{symbol} {ident}")
    return "\n".join(lines) + "\n"


def model_metadata(config: dict, lang_code: str) -> dict:
    """Metadaten, die sherpa-onnx im Modell erwartet (wie das offizielle Skript add_meta_data.py)."""
    sample_rate = config["audio"]["sample_rate"]
    if sample_rate == 22500:
        sample_rate = 22050
    voice = config.get("lang_code") or config["espeak"]["voice"]
    if voice == "pt-PT":
        voice = "pt"
    language = LANGUAGE_NAMES.get(lang_code.split("_")[0].lower(), lang_code.split("_")[0])
    return {
        "model_type": "vits",
        "comment": "piper",  # muss "piper" heißen
        "language": language,
        "voice": voice,  # espeak-Stimme, z. B. de oder en-us
        "version": 1,
        "has_espeak": 1,
        "has_g2pw": 0,
        "n_speakers": config.get("num_speakers", 1),
        "sample_rate": sample_rate,
    }


def load_config(path: str | Path) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))
