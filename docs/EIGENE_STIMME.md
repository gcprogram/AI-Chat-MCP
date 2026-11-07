# Eigene Stimme für einen Companion (Piper, offline)

Die Android-App kann Companions mit **Piper-Stimmen** sprechen lassen. Das läuft komplett auf dem Handy (sherpa-onnx), ohne
Internet und ohne die Google-Sprachausgabe. Eine eigene Stimme ist ein Piper-Modell, das am PC trainiert wird; die App spielt es nur ab.

## 1. Zuerst mit einer fertigen Stimme ausprobieren

Das prüft die App-Seite, bevor du Zeit in Aufnahmen steckst. Die sherpa-onnx-Pakete enthalten alles in dem Format, das die App erwartet:

1. Von <https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models> ein Paket `vits-piper-de_DE-…` laden, z. B.
   `vits-piper-de_DE-thorsten-medium-int8.tar.bz2` (21 MB, Lizenz der Stimme: CC0). Die Modellkarte (`MODEL_CARD`) nennt die Lizenz je Stimme.
2. Entpacken. Der Ordner enthält `*.onnx`, `*.onnx.json`, `tokens.txt` und `espeak-ng-data/`.
3. Den ganzen Ordner aufs Handy kopieren (z. B. nach `Download/voices/`).
4. In der App: **Einstellungen → Piper-Stimmen → Stimmenordner wählen…**, den Ordner auswählen. Mit *Anhören* prüfen.
5. **Companions → Companion wählen → Stimme**: die Piper-Stimme steht in der Liste (nur Stimmen der Companion-Sprache).

`espeak-ng-data` (die Aussprachedaten, für alle Stimmen und Sprachen gleich) wird nur beim **ersten** Import kopiert und von allen Stimmen
gemeinsam benutzt. Weitere Stimmen brauchen nur `*.onnx`, `tokens.txt` und `*.onnx.json`.

Die Sprache einer Stimme liest die App aus `language.code` in der `*.onnx.json` (z. B. `de_DE`), sonst aus dem Ordnernamen (`de_DE-…`).
Eine Stimme wird nur für Texte in ihrer Sprache benutzt, sonst spricht die Systemstimme.

## 2. Die eigene Stimme erzeugen

**Die Werkzeuge dafür liegen in `tools/voice/`** (Aufnehmen mit Satzlisten, Datensatz prüfen, Stimmenordner verpacken); Schritt für Schritt mit allen Befehlen in
[`tools/voice/README.md`](../tools/voice/README.md). Der Überblick:

Was du brauchst und was es kostet, ehrlich:

| | |
|---|---|
| **Aufnahmen** | Mindestens etwa 30 Minuten sauberer Sprache für eine Feinabstimmung auf einer vorhandenen Stimme, besser 1 bis 2 Stunden. Ruhiger Raum, gleicher Abstand zum Mikrofon, gleiche Lautstärke, keine Musik. Jeder Satz als eigene WAV-Datei, dazu der gesprochene Text. |
| **Rechner** | Training am PC mit NVIDIA-Grafikkarte (CUDA). Eine Feinabstimmung dauert Stunden bis einen Tag, ein Training von Null deutlich länger und braucht mehr Daten. |
| **Ergebnis** | Brauchbar bis gut, etwas „Vorleser-Ton“; kein perfekter Klon. |
| **Tempo auf dem Handy** | Wähle beim Training die Qualität *medium* oder *low*: Auf einem Galaxy S9 liefen diese in etwa der halben bis 0,9-fachen Sprechzeit, *high* war 3,8-mal zu langsam. Neuere Handys sind schneller. |

Ablauf:

1. **Aufnehmen und beschriften.** Aufnahmen als 22.050 Hz, mono, 16 Bit; dazu eine Liste `metadata.csv` im LJSpeech-Format
   (`dateiname|Text`). Lies abwechslungsreiche Sätze in deinem normalen Sprechtempo vor.
2. **Trainieren** mit Piper (Repository `OHF-Voice/piper1-gpl`, `docs/TRAINING.md`; Linux oder WSL2). Für wenig Daten von einem vorhandenen Checkpoint
   aus feinabstimmen (Checkpoints: `huggingface.co/datasets/rhasspy/piper-checkpoints`), statt bei Null anzufangen. Der Befehl `python3 -m piper.train fit …`
   steht mit allen Parametern in `tools/voice/README.md`.
3. **Nach ONNX exportieren**: `python3 -m piper.train.export_onnx --checkpoint <ckpt> --output-file meine-stimme.onnx`.
   Die dazugehörige `meine-stimme.onnx.json` (Piper-Konfiguration) wird beim Training mit angelegt und gehört zum Modell.
4. **Für sherpa-onnx umwandeln** (Metadaten ins Modell schreiben, `tokens.txt` aus der Konfiguration erzeugen, auf Wunsch int8): `tools/voice/package_voice.py`
   erledigt das und legt den fertigen Ordner an (gleiches Ergebnis wie das offizielle Skript von sherpa-onnx, geprüft an einer Stimme der sherpa-Pakete).
5. **Ordner zusammenstellen**, Name z. B. `de_DE-meine-stimme`:

   ```
   de_DE-meine-stimme/
     meine-stimme.onnx
     meine-stimme.onnx.json
     tokens.txt
     espeak-ng-data/        (nur beim ersten Import nötig, aus jedem sherpa-Paket oder espeak-ng-data.tar.bz2)
   ```

6. Aufs Handy kopieren und importieren wie in Abschnitt 1.

Zum Probehören am PC vor dem Kopieren: `pip install sherpa-onnx`, dann
`sherpa-onnx-offline-tts --vits-model=… --vits-tokens=tokens.txt --vits-data-dir=espeak-ng-data --output-filename=test.wav "Hallo"`.

## 3. Wichtig

- **Nur deine eigene Stimme** oder die einer Person, die ausdrücklich zugestimmt hat. Die Aufnahmen und das Modell bleiben bei dir; die App
  lädt nichts hoch.
- **Lizenzen**: Fertige Piper-Stimmen haben je eigene Lizenzen (`MODEL_CARD`). espeak-ng, das die Aussprache erzeugt, steht unter GPL-3.0;
  das gilt, falls du die App samt Bibliothek weitergibst. sherpa-onnx und onnxruntime sind Apache-2.0 bzw. MIT.
- Bei mehreren Companions kann jeder seine eigene Stimme haben (auch mehrere eigene Stimmen, z. B. eine pro Stimmung).
- Die Stimme gilt für Antworten und für das Vorlesen von Dokumenten.
