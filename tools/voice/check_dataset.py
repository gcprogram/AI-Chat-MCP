#!/usr/bin/env python3
"""Prüft einen Aufnahme-Ordner (wavs/ + metadata.csv), bevor du Stunden ins Training steckst.

    python check_dataset.py --dataset meine-stimme
    python check_dataset.py --dataset fremde-aufnahmen --fix-to meine-stimme-fertig   (umrechnen: 22.050 Hz, mono, 16 Bit, Stille gekürzt)

Geprüft werden: Format, Dauer, Lautstärke und deren Schwankung, Übersteuerung, Stille vorn/hinten, fehlende Dateien und der
Text (Ziffern, Abkürzungen). Der Rückgabewert ist 1, wenn etwas das Training verderben würde.
"""
from __future__ import annotations

import argparse
import re
import statistics
import sys
from pathlib import Path

import voicelib as v


def check(dataset: Path, expected_rate: int = v.TARGET_RATE) -> tuple[dict, list[str], list[str]]:
    """Liefert (Zusammenfassung, Fehler, Hinweise). Fehler = das Training würde schlechter oder scheitern."""
    errors: list[str] = []
    notes: list[str] = []
    meta_path = dataset / "metadata.csv"
    if not meta_path.exists():
        return {}, [f"{meta_path} fehlt (Format: dateiname.wav|gesprochener Text, eine Zeile je Aufnahme)"], []
    rows = v.read_metadata(meta_path)
    wav_dir = dataset / "wavs"
    levels, durations = [], []
    seen: set[str] = set()
    for entry, text in rows:
        name = v.wav_name(entry)
        path = wav_dir / name
        if name in seen:
            errors.append(f"{name}: kommt mehrfach in metadata.csv vor")
        seen.add(name)
        if not text:
            errors.append(f"{name}: kein Text")
        if not path.exists():
            errors.append(f"{name}: Datei fehlt in wavs/")
            continue
        try:
            samples, rate, channels = v.read_wav(path)
        except Exception as e:  # noqa: BLE001
            errors.append(f"{name}: nicht lesbar ({e})")
            continue
        if rate != expected_rate:
            errors.append(f"{name}: Abtastrate {rate} Hz statt {expected_rate} Hz (mit --fix-to umrechnen)")
        if channels != 1:
            errors.append(f"{name}: {channels} Kanäle statt mono (mit --fix-to umrechnen)")
        s = v.stats(samples, rate)
        durations.append(s["duration"])
        levels.append(s["rms_db"])
        if s["duration"] < 0.8:
            notes.append(f"{name}: sehr kurz ({s['duration']:.1f} s)")
        if s["duration"] > 15:
            notes.append(f"{name}: sehr lang ({s['duration']:.1f} s), besser in zwei Sätze teilen")
        if s["peak"] < 0.1:
            notes.append(f"{name}: sehr leise (Spitze {v.db(s['peak']):.0f} dBFS)")
        if s["clip"] > 0.0005:
            errors.append(f"{name}: übersteuert ({s['clip'] * 100:.2f} % der Samples am Anschlag)")
        if s["lead"] > 1.2 or s["tail"] > 1.5:
            notes.append(f"{name}: lange Stille (vorn {s['lead']:.1f} s, hinten {s['tail']:.1f} s)")
        if re.search(r"\d", text):
            notes.append(f"{name}: Ziffern im Text; besser ausschreiben (\"zwölf\" statt \"12\"), sonst lernt das Modell die Aussprache schlechter")
        if re.search(r"\b(z\. ?B\.|usw\.|bzw\.|Dr\.|Nr\.|etc\.)", text):
            notes.append(f"{name}: Abkürzung im Text; besser ausschreiben")
    on_disk = {p.name for p in wav_dir.glob("*.wav")} if wav_dir.exists() else set()
    for extra in sorted(on_disk - seen):
        notes.append(f"{extra}: liegt in wavs/, steht aber nicht in metadata.csv (wird nicht benutzt)")

    minutes = sum(durations) / 60
    spread = statistics.pstdev(levels) if len(levels) > 1 else 0.0
    if spread > 5.0:
        errors.append(f"Die Lautstärke schwankt stark zwischen den Aufnahmen ({spread:.1f} dB Streuung): gleichen Abstand/Pegel halten oder normalisieren")
    elif spread > 3.0:
        notes.append(f"Die Lautstärke schwankt etwas ({spread:.1f} dB Streuung)")
    if minutes < 15:
        errors.append(f"Nur {minutes:.1f} Minuten Sprache: zu wenig, auch für eine Feinabstimmung")
    elif minutes < 30:
        notes.append(f"{minutes:.1f} Minuten: knapp, reicht nur für eine Feinabstimmung einer vorhandenen Stimme")
    elif minutes < 60:
        notes.append(f"{minutes:.1f} Minuten: reicht für eine Feinabstimmung; mehr Aufnahmen verbessern das Ergebnis")
    summary = {
        "count": len(rows), "minutes": minutes, "spread_db": spread,
        "mean_level_db": statistics.fmean(levels) if levels else 0.0,
        "median_s": statistics.median(durations) if durations else 0.0,
    }
    return summary, errors, notes


def fix_to(dataset: Path, target: Path, rate: int = v.TARGET_RATE) -> int:
    """Schreibt umgerechnete Kopien (mono, Zielrate, 16 Bit, Stille gekürzt) und eine neue metadata.csv. Liefert die Anzahl."""
    rows = v.read_metadata(dataset / "metadata.csv")
    count = 0
    lines = []
    for entry, text in rows:
        name = v.wav_name(entry)
        src = dataset / "wavs" / name
        if not src.exists():
            continue
        samples, in_rate, _ = v.read_wav(src)
        samples = v.trim_silence(v.resample(samples, in_rate, rate), rate)
        v.write_wav(target / "wavs" / name, samples, rate)
        lines.append(f"{name}|{text}")
        count += 1
    (target).mkdir(parents=True, exist_ok=True)
    (target / "metadata.csv").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return count


def main() -> int:
    v.setup_console()
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", required=True, help="Ordner mit wavs/ und metadata.csv")
    parser.add_argument("--rate", type=int, default=v.TARGET_RATE, help="erwartete Abtastrate (Standard 22050; low/x_low-Training: 16000)")
    parser.add_argument("--fix-to", help="umgerechnete Kopie in diesen Ordner schreiben")
    args = parser.parse_args()

    dataset = Path(args.dataset)
    if args.fix_to:
        n = fix_to(dataset, Path(args.fix_to), args.rate)
        print(f"{n} Dateien umgerechnet nach {args.fix_to} ({args.rate} Hz, mono, 16 Bit, Stille gekürzt).")
        dataset = Path(args.fix_to)
    summary, errors, notes = check(dataset, args.rate)
    if summary:
        print(f"{summary['count']} Aufnahmen, {summary['minutes']:.1f} Minuten, Median {summary['median_s']:.1f} s je Satz, "
              f"mittlere Lautstärke {summary['mean_level_db']:.0f} dBFS (Streuung {summary['spread_db']:.1f} dB)")
    for line in errors:
        print("FEHLER  " + line)
    for line in notes[:60]:
        print("Hinweis " + line)
    if len(notes) > 60:
        print(f"… und {len(notes) - 60} weitere Hinweise")
    if not errors:
        print("Keine Fehler gefunden." + (" (Hinweise sind Empfehlungen.)" if notes else ""))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
