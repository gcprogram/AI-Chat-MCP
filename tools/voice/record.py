#!/usr/bin/env python3
"""Nimmt Sätze für das Training einer eigenen Piper-Stimme auf.

Du bekommst nacheinander Sätze angezeigt, sprichst sie ein und bestätigst oder wiederholst sie. Gespeichert werden
`wavs/NNNN.wav` (22.050 Hz, mono, 16 Bit, Stille vorn und hinten gekürzt) und `metadata.csv` im LJSpeech-Format
(`datei.wav|Text`). Das Programm macht dort weiter, wo du aufgehört hast.

    python record.py --sentences sentences_de.txt --out meine-stimme
    python record.py --list-devices          (Mikrofone anzeigen)
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import numpy as np

import voicelib as v


def import_sounddevice():
    try:
        import sounddevice as sd  # noqa: WPS433
    except ImportError:
        sys.exit("Das Paket 'sounddevice' fehlt: pip install sounddevice")
    return sd


def open_rate(sd, device, wanted: int) -> int:
    """Die Aufnahmerate: die Zielrate, wenn das Mikrofon sie kann, sonst 48000 oder 44100 (wird danach umgerechnet)."""
    for rate in (wanted, 48000, 44100):
        try:
            sd.check_input_settings(device=device, channels=1, samplerate=rate)
            return rate
        except Exception:  # noqa: BLE001 - jede Ablehnung heißt: nächste Rate probieren
            continue
    sys.exit("Das Mikrofon unterstützt keine der Raten 22050/48000/44100 Hz. Mit --list-devices ein anderes wählen (--device).")


def record_one(sd, device, rate: int) -> np.ndarray:
    chunks: list[np.ndarray] = []
    stream = sd.InputStream(samplerate=rate, channels=1, dtype="float32", device=device, callback=lambda data, *_: chunks.append(data.copy()))
    started = time.time()
    with stream:
        input(f"   ● Aufnahme läuft … sprich jetzt, danach [Enter]")
    seconds = time.time() - started
    data = np.concatenate(chunks)[:, 0] if chunks else np.zeros(0, dtype=np.float32)
    print(f"   {seconds:.1f} s")
    return data


def judge(samples: np.ndarray, rate: int) -> list[str]:
    s = v.stats(samples, rate)
    problems = []
    if s["duration"] < 1.0:
        problems.append("sehr kurz")
    if s["peak"] < 0.1:
        problems.append("zu leise (näher ans Mikrofon oder Eingangspegel erhöhen)")
    if s["clip"] > 0.0005:
        problems.append("übersteuert (leiser sprechen oder Eingangspegel senken)")
    if s["lead"] > 1.5:
        problems.append("lange Pause am Anfang")
    return problems


def main() -> None:
    v.setup_console()
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sentences", help="Textdatei mit den Sätzen (eine Zeile je Satz)")
    parser.add_argument("--out", default="meine-stimme", help="Ausgabeordner (wavs/ und metadata.csv)")
    parser.add_argument("--device", help="Mikrofon (Nummer oder Name, siehe --list-devices)")
    parser.add_argument("--rate", type=int, default=v.TARGET_RATE, help="Zielabtastrate (Standard 22050)")
    parser.add_argument("--no-extension", action="store_true", help="in metadata.csv Namen ohne .wav schreiben (ältere Piper-Versionen)")
    parser.add_argument("--list-devices", action="store_true", help="Mikrofone anzeigen und beenden")
    args = parser.parse_args()

    sd = import_sounddevice()
    if args.list_devices:
        print(sd.query_devices())
        return
    if not args.sentences:
        parser.error("--sentences fehlt")
    device = int(args.device) if args.device and args.device.isdigit() else args.device

    sentences = v.load_sentences(args.sentences)
    out = Path(args.out)
    wavs = out / "wavs"
    wavs.mkdir(parents=True, exist_ok=True)
    meta_path = out / "metadata.csv"
    done = {text for _, text in v.read_metadata(meta_path)} if meta_path.exists() else set()
    existing = len(v.read_metadata(meta_path)) if meta_path.exists() else 0
    todo = [s for s in sentences if s not in done]
    if not todo:
        print(f"Alle {len(sentences)} Sätze sind schon aufgenommen ({out}).")
        return

    rate_in = open_rate(sd, device, args.rate)
    print(f"{len(todo)} von {len(sentences)} Sätzen offen. Mikrofonrate {rate_in} Hz, Ziel {args.rate} Hz.")
    print("Tipps: ruhiger Raum, immer gleicher Abstand (eine Handbreit), normales Sprechtempo, natürlich betonen.")
    print("Pro Satz: [Enter] startet die Aufnahme, [Enter] beendet sie. Danach: [Enter] behalten, [w] wiederholen,")
    print("[h] anhören, [s] überspringen, [q] beenden und speichern. (Ein Fehler beim Sprechen? Einfach [w].)\n")

    number = existing
    total_seconds = 0.0
    for index, sentence in enumerate(todo, 1):
        while True:
            print(f"[{index}/{len(todo)}]  {sentence}")
            choice = input("   [Enter] = Aufnahme starten, [s] überspringen, [q] beenden: ").strip().lower()
            if choice == "s":
                break
            if choice == "q":
                _finish(out, number, total_seconds)
                return
            raw = record_one(sd, device, rate_in)
            samples = v.trim_silence(v.resample(raw, rate_in, args.rate), args.rate)
            problems = judge(samples, args.rate)
            if problems:
                print("   ⚠ " + "; ".join(problems))
            while True:
                answer = input("   [Enter] behalten, [w] wiederholen, [h] anhören, [q] beenden: ").strip().lower()
                if answer == "h":
                    sd.play(samples, args.rate)
                    sd.wait()
                    continue
                break
            if answer == "w":
                continue
            if answer == "q":
                print("Beendet. Der aktuelle Satz wurde nicht gespeichert.")
                _finish(out, number, total_seconds)
                return
            number += 1
            name = f"{number:04d}"
            v.write_wav(wavs / f"{name}.wav", samples, args.rate)
            with meta_path.open("a", encoding="utf-8", newline="\n") as f:
                f.write(f"{name if args.no_extension else name + '.wav'}|{sentence}\n")
            total_seconds += len(samples) / args.rate
            print(f"   gespeichert als {name}.wav\n")
            break
    _finish(out, number, total_seconds)


def _finish(out: Path, count: int, new_seconds: float) -> None:
    print(f"\n{count} Aufnahmen in {out}. Als Nächstes prüfen: python check_dataset.py --dataset {out}")


if __name__ == "__main__":
    main()
