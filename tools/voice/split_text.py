#!/usr/bin/env python3
"""Zerlegt ein Buch oder einen Text in Sätze zum Einsprechen (eine Zeile je Satz) für record.py.

    python split_text.py buch.txt --out sentences_buch.txt [--max 400] [--shuffle]

Behalten werden Sätze mit 4 bis 28 Wörtern ohne Ziffern. Wähle Texte, die so klingen, wie dein Companion sprechen soll (Dialoge,
lockere Erzählung, Briefe), nicht Fachtexte. Gemeinfreie Bücher gibt es z. B. bei Projekt Gutenberg (gutenberg.org, Reiner Text .txt).
"""
from __future__ import annotations

import argparse
import random
import sys
from pathlib import Path

import voicelib as v


def main() -> int:
    v.setup_console()
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("textfile", help="Textdatei (UTF-8)")
    parser.add_argument("--out", required=True, help="Ausgabedatei mit den Sätzen")
    parser.add_argument("--max", type=int, default=0, help="höchstens so viele Sätze schreiben (0 = alle)")
    parser.add_argument("--shuffle", action="store_true", help="Reihenfolge mischen (sonst wie im Text)")
    parser.add_argument("--min-words", type=int, default=4)
    parser.add_argument("--max-words", type=int, default=28)
    args = parser.parse_args()
    raw = Path(args.textfile).read_bytes()
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        text = raw.decode("cp1252", errors="replace")
    sentences = v.split_sentences(text, args.min_words, args.max_words)
    if args.shuffle:
        random.shuffle(sentences)
    if args.max:
        sentences = sentences[: args.max]
    Path(args.out).write_text("\n".join(sentences) + "\n", encoding="utf-8", newline="\n")
    words = sum(len(s.split()) for s in sentences)
    print(f"{len(sentences)} Sätze nach {args.out} (etwa {words / 2.4 / 60:.0f} Minuten Sprechzeit bei 2,4 Wörtern pro Sekunde).")
    return 0 if sentences else 1


if __name__ == "__main__":
    sys.exit(main())
