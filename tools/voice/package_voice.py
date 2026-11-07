#!/usr/bin/env python3
"""Macht aus einer trainierten Piper-Stimme einen Stimmenordner für die Android-App (Format der sherpa-onnx-Pakete).

Eingabe: das exportierte Modell (`.onnx`) und seine Piper-Konfiguration (`.onnx.json`). Das Skript

  1. erzeugt tokens.txt aus der Konfiguration,
  2. schreibt die Metadaten ins Modell, die sherpa-onnx erwartet (model_type, language, voice, sample_rate …),
  3. rechnet auf Wunsch auf int8 um (halb so groß, auf älteren Handys schneller),
  4. legt den Ordner `<sprache>-<name>[-qualität]` an (mit .onnx.json) und, wenn angegeben, kopiert espeak-ng-data hinein,
  5. prüft den Ordner und kann einen Testsatz als test.wav erzeugen.

    python package_voice.py --onnx meine-stimme.onnx --config meine-stimme.onnx.json --name meine-stimme --lang de_DE --int8
    python package_voice.py ... --espeak-data pfad/zu/espeak-ng-data --test "Hallo, das ist meine Stimme."

Voraussetzung: pip install onnx   (für --int8 zusätzlich onnxruntime, für --test sherpa-onnx)
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

import voicelib as v


def import_onnx():
    try:
        import onnx  # noqa: WPS433
    except ImportError:
        sys.exit("Das Paket 'onnx' fehlt: pip install onnx")
    return onnx


def add_metadata(model_path: Path, meta: dict) -> None:
    onnx = import_onnx()
    model = onnx.load(str(model_path))
    while len(model.metadata_props):
        model.metadata_props.pop()
    for key, value in meta.items():
        prop = model.metadata_props.add()
        prop.key, prop.value = key, str(value)
    onnx.save(model, str(model_path))


def quantize_int8(src: Path, dst: Path) -> None:
    try:
        from onnxruntime.quantization import QuantType, quantize_dynamic
    except ImportError:
        sys.exit("Für --int8 fehlt onnxruntime: pip install onnxruntime")
    quantize_dynamic(model_input=str(src), model_output=str(dst), weight_type=QuantType.QUInt8)


def check_folder(folder: Path, need_espeak: bool) -> list[str]:
    problems = []
    if not list(folder.glob("*.onnx")):
        problems.append("keine .onnx-Datei")
    if not (folder / "tokens.txt").is_file():
        problems.append("tokens.txt fehlt")
    if need_espeak and not (folder / "espeak-ng-data" / "phontab").is_file():
        problems.append("espeak-ng-data fehlt (nötig beim ersten Import auf dem Handy, danach nicht mehr)")
    return problems


def render_test(folder: Path, text: str) -> bool:
    try:
        import sherpa_onnx  # noqa: WPS433
        import wave
    except ImportError:
        print("Für --test fehlt sherpa-onnx: pip install sherpa-onnx (Testsatz übersprungen)")
        return False
    onnx_path = sorted(folder.glob("*.onnx"))[0]
    espeak = folder / "espeak-ng-data"
    config = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(onnx_path), tokens=str(folder / "tokens.txt"), data_dir=str(espeak)),
            num_threads=2,
        )
    )
    tts = sherpa_onnx.OfflineTts(config)
    audio = tts.generate(text, sid=0, speed=1.0)
    out = folder / "test.wav"
    pcm = (v.np.clip(v.np.array(audio.samples), -1, 1) * 32767).astype("<i2")
    with wave.open(str(out), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(audio.sample_rate)
        w.writeframes(pcm.tobytes())
    print(f"Testsatz: {out} ({len(audio.samples) / audio.sample_rate:.1f} s)")
    return True


def main() -> int:
    v.setup_console()
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--onnx", required=True, help="exportiertes Modell (.onnx)")
    parser.add_argument("--config", help="Piper-Konfiguration (.onnx.json); Standard: <modell>.onnx.json")
    parser.add_argument("--name", required=True, help="Name der Stimme, z. B. meine-stimme")
    parser.add_argument("--lang", required=True, help="Sprache mit Land, z. B. de_DE oder en_GB")
    parser.add_argument("--quality", help="Qualitätsstufe im Ordnernamen (Standard: aus der Konfiguration, z. B. medium)")
    parser.add_argument("--int8", action="store_true", help="auf int8 umrechnen (kleiner und schneller)")
    parser.add_argument("--espeak-data", help="Pfad zu espeak-ng-data (aus einem sherpa-Paket vits-piper-…); wird mit in den Ordner kopiert")
    parser.add_argument("--out", default=".", help="Ordner, in dem der Stimmenordner angelegt wird")
    parser.add_argument("--test", help="Testsatz als test.wav erzeugen (braucht sherpa-onnx)")
    args = parser.parse_args()

    onnx_path = Path(args.onnx)
    config_path = Path(args.config) if args.config else Path(str(onnx_path) + ".json")
    if not onnx_path.is_file():
        sys.exit(f"Modell nicht gefunden: {onnx_path}")
    if not config_path.is_file():
        sys.exit(f"Konfiguration nicht gefunden: {config_path} (die .onnx.json, die beim Training/Export neben dem Modell liegt)")
    config = v.load_config(config_path)
    for key in ("phoneme_id_map", "audio"):
        if key not in config:
            sys.exit(f"In der Konfiguration fehlt '{key}': das ist keine Piper-Konfiguration.")
    if config.get("phoneme_type", "espeak") not in ("espeak", None):
        sys.exit(f"phoneme_type '{config['phoneme_type']}' wird nicht unterstützt (nur espeak-Stimmen).")

    quality = args.quality or config["audio"].get("quality") or ""
    folder_name = "-".join(p for p in (args.lang, args.name, quality) if p)
    folder = Path(args.out) / folder_name
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / (f"{args.lang}-{args.name}" + (f"-{quality}" if quality else "") + ".onnx")

    meta = v.model_metadata(config, args.lang)
    print("Metadaten:", meta)
    if args.int8:
        print("Rechne auf int8 um …")
        quantize_int8(onnx_path, target)
    else:
        shutil.copyfile(onnx_path, target)
    add_metadata(target, meta)
    (folder / "tokens.txt").write_text(v.tokens_from_config(config), encoding="utf-8", newline="\n")
    shutil.copyfile(config_path, folder / (target.name + ".json"))
    if args.espeak_data:
        src = Path(args.espeak_data)
        if not (src / "phontab").is_file():
            sys.exit(f"{src} sieht nicht nach espeak-ng-data aus (phontab fehlt)")
        shutil.copytree(src, folder / "espeak-ng-data", dirs_exist_ok=True)

    problems = check_folder(folder, need_espeak=False)
    if problems:
        print("FEHLER: " + "; ".join(problems))
        return 1
    size = target.stat().st_size / 1e6
    print(f"\nFertig: {folder}  (Modell {size:.0f} MB)")
    if not (folder / "espeak-ng-data").exists():
        print("Hinweis: espeak-ng-data liegt nicht im Ordner. Beim ersten Import auf dem Handy wird es gebraucht; "
              "kopiere es aus einem sherpa-Paket (vits-piper-…) hinein oder nutze --espeak-data.")
    if args.test:
        if (folder / "espeak-ng-data").exists():
            render_test(folder, args.test)
        else:
            print("Für --test wird espeak-ng-data im Ordner gebraucht (--espeak-data).")
    print("\nAufs Handy: den Ordner kopieren (USB oder Download-Ordner), dann in der App:")
    print("Einstellungen → Piper-Stimmen → Stimmenordner wählen…, danach im Companion-Editor unter „Stimme“ auswählen.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
