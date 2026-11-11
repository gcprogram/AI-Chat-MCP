"""Bettet die Avatar-Bilder in die Companion-Dateien ein (Feld "avatar_data", Base64-JPEG, höchstens 100 KB, 512 bis 768 Pixel), damit Companion und Bild
zusammenbleiben. Die großen Originale bleiben, wo sie sind; sie werden nicht verändert.

Aufruf:
    set PYTHONPATH=. && python tools/embed_avatars.py                 # Bilddateien aus "avatar" einbetten (ältere Companions)
    set PYTHONPATH=. && python tools/embed_avatars.py --auto          # zusätzlich lose Bilder im Ordner nach Namen zuordnen
    set PYTHONPATH=. && python tools/embed_avatars.py --assign Zero.png=zero --assign "Bild.png=nyx"
    ... --dry-run   nur anzeigen, nichts schreiben
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from chat_app.avatar import AvatarError, encode_avatar  # noqa: E402

IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp"}
UMLAUTS = str.maketrans({"ä": "ae", "ö": "oe", "ü": "ue", "ß": "ss", "Ä": "ae", "Ö": "oe", "Ü": "ue"})


def key(text: str) -> str:
    """Vergleichsschlüssel: Umlaute aufgelöst, nur Kleinbuchstaben und Ziffern ("Käptn_Brise" = "kaeptn brise")."""
    return re.sub(r"[^a-z0-9]+", "", text.translate(UMLAUTS).lower())


def load(path: Path) -> dict | None:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return data if isinstance(data, dict) and "name" in data else None  # andere JSON-Dateien sind keine Companions


def embed(directory: Path, assign: dict[str, str], auto: bool, dry_run: bool, redo: bool = False) -> list[str]:
    """Liefert die Meldungen. assign: Bilddatei (Name oder Pfad) -> Companion-Kennung."""
    report: list[str] = []
    companions = {p.stem: d for p in sorted(directory.glob("*.json")) if (d := load(p)) is not None}
    todo: dict[str, Path] = {}  # Kennung -> Bild

    for cid, data in companions.items():  # ältere Companions: Datei aus "avatar"
        legacy = str(data.get("avatar") or "")
        if legacy and (redo or not data.get("avatar_data")):
            path = Path(legacy) if Path(legacy).is_absolute() else directory / legacy
            if path.is_file():
                todo[cid] = path
            else:
                report.append(f"{cid}: Bilddatei '{legacy}' fehlt, übersprungen")
    if auto:
        loose = [p for p in sorted(directory.iterdir()) if p.is_file() and p.suffix.lower() in IMAGE_SUFFIXES]
        for cid, data in companions.items():
            if (data.get("avatar_data") and not redo) or cid in todo:
                continue
            wanted = {key(cid), key(str(data.get("name") or ""))}
            match = next((p for p in loose if key(p.stem) in wanted), None)
            if match is not None:
                todo[cid] = match
    for file, cid in assign.items():
        path = Path(file) if Path(file).is_file() else directory / file
        if cid not in companions:
            report.append(f"--assign {file}={cid}: Companion unbekannt")
        elif not path.is_file():
            report.append(f"--assign {file}={cid}: Bild nicht gefunden")
        else:
            todo[cid] = path

    for cid, path in todo.items():
        try:
            data_b64 = encode_avatar(path)
        except AvatarError as exc:
            report.append(f"{cid}: {exc}")
            continue
        report.append(f"{cid}: {path.name} -> eingebettet ({len(data_b64) // 1024} KB)")
        if dry_run:
            continue
        data = companions[cid]
        data["avatar_data"] = data_b64
        data["avatar"] = ""
        (directory / f"{cid}.json").write_text(json.dumps(data, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
    if not todo and not report:
        report.append("nichts zu tun")
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--personas", default=str(Path(__file__).resolve().parent.parent / "personas"), help="Ordner der Companions")
    parser.add_argument("--auto", action="store_true", help="lose Bilder im Ordner per Namen den Companions zuordnen")
    parser.add_argument("--assign", action="append", default=[], metavar="BILD=KENNUNG", help="Bild einem Companion zuweisen (ersetzt sein Bild)")
    parser.add_argument("--redo", action="store_true", help="auch Companions mit eingebettetem Bild neu einbetten (z. B. nach einer Änderung der Größe)")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    assign = {}
    for item in args.assign:
        file, _, cid = item.rpartition("=")
        if not file or not cid:
            print(f"--assign erwartet BILD=KENNUNG, nicht '{item}'")
            return 2
        assign[file] = cid
    for line in embed(Path(args.personas), assign, args.auto, args.dry_run, args.redo):
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
