# Eigene Stimme: Werkzeuge

Vom Einsprechen bis zum Stimmenordner für die Android-App. Das Training selbst läuft mit Piper am PC (NVIDIA-Grafikkarte);
diese Skripte erledigen die Handarbeit davor und danach.

```
Aufnehmen  ──►  Prüfen  ──►  Trainieren (Piper)  ──►  Exportieren  ──►  Verpacken  ──►  Handy
record.py       check_dataset.py   piper.train fit        export_onnx       package_voice.py
```

## Einrichten (Windows, einmalig)

```bash
python -m venv .venv-voice
.venv-voice\Scripts\activate
pip install -r tools/voice/requirements.txt
```

## 1. Aufnehmen: `record.py`

```bash
python tools/voice/record.py --list-devices                       # Mikrofone anzeigen
python tools/voice/record.py --sentences tools/voice/sentences_de.txt --out meine-stimme [--device 5]
```

- Ein Satz nach dem anderen: **Enter** startet, **Enter** beendet die Aufnahme. Danach **Enter** = behalten, **w** = wiederholen,
  **h** = anhören, **q** = beenden. Du kannst jederzeit aufhören und später weitermachen (es geht beim nächsten offenen Satz weiter).
- Gespeichert wird als `meine-stimme/wavs/0001.wav, …` (22.050 Hz, mono, 16 Bit, Stille vorn/hinten gekürzt) und `metadata.csv`
  (`0001.wav|Text`). Das Programm warnt bei zu leisen oder übersteuerten Aufnahmen.
- Sätze: `sentences_de.txt` und `sentences_en.txt` (je 126). Eigene Listen gehen auch (eine Zeile je Satz, Zahlen und Abkürzungen ausschreiben).
- **So wird es gut:** ruhiger Raum ohne Hall (Kleiderschrank oder Decke hilft), immer gleicher Abstand (eine Handbreit), gleicher Eingangspegel,
  normales Sprechtempo und natürliche Betonung, nicht vorlesen im „Nachrichtenton“. Lieber an mehreren Tagen je 20 Minuten als alles auf einmal.
- Menge: **mindestens 30 Minuten**, besser 1 bis 2 Stunden (das sind etwa 400 bis 1.200 Sätze). 126 Sätze sind der Anfang, nicht das Ziel:
  mit weiteren Listen oder eigenen Texten (Buchseiten, Nachrichten, Dialoge in der Art, wie der Companion sprechen soll) auffüllen.

## 2. Prüfen: `check_dataset.py`

```bash
python tools/voice/check_dataset.py --dataset meine-stimme
python tools/voice/check_dataset.py --dataset fremde-aufnahmen --fix-to meine-stimme-fertig   # Fremdaufnahmen umrechnen
```

Prüft Format, Dauer, Lautstärke und deren Schwankung, Übersteuerung, Stille, fehlende Dateien und den Text, und sagt, ob die Menge reicht.
Lass Fehler vor dem Training beheben: Das Modell lernt auch Rauschen, Klicks und schwankende Lautstärke mit.

## 3. Trainieren mit Piper (Linux oder WSL2 mit NVIDIA-Treiber)

Quelle: `docs/TRAINING.md` im Repository `OHF-Voice/piper1-gpl`. Dort steht der aktuelle Stand; die Befehle hier folgen ihm.
Ein Grafikkarten-Speicher von 8 GB reicht laut Anleitung (Referenz: 24 bis 48 GB).

```bash
sudo apt-get install build-essential cmake ninja-build espeak-ng
git clone https://github.com/OHF-voice/piper1-gpl.git && cd piper1-gpl
python3 -m venv .venv && source .venv/bin/activate
python3 -m pip install -e '.[train]'
./build_monotonic_align.sh
```

Als Ausgangspunkt eine vorhandene Stimme feinabstimmen, am besten **medium** in deiner Sprache (andere Sprachen gehen auch). Die Checkpoints liegen auf
<https://huggingface.co/datasets/rhasspy/piper-checkpoints> (z. B. unter `de/de_DE/…/medium/`). Dann:

```bash
python3 -m piper.train fit \
  --data.voice_name "meine-stimme" \
  --data.csv_path /pfad/zu/meine-stimme/metadata.csv \
  --data.audio_dir /pfad/zu/meine-stimme/wavs/ \
  --model.sample_rate 22050 \
  --data.espeak_voice "de" \
  --data.cache_dir /pfad/zum/cache/ \
  --data.config_path /pfad/zu/meine-stimme.onnx.json \
  --data.batch_size 32 \
  --ckpt_path /pfad/zum/checkpoint.ckpt \
  --trainer.max_epochs 2000
```

- `--data.espeak_voice`: `de` für Deutsch, `en-us` oder `en-gb` für Englisch, `fr`, `es`, `it` …
- Bei weniger als etwa 5 Stunden Audio nennt die Anleitung **2000 Epochen** (die Zahl muss die schon im Checkpoint enthaltenen Epochen mitzählen).
  Eine Epoche ist ein Durchlauf durch alle Aufnahmen; bei 1 Stunde Audio dauert das Training je nach Karte Stunden bis einen Tag.
- Zwischenstände (`.ckpt`) werden regelmäßig gespeichert. Mit einem Zwischenstand exportieren und anhören; oft klingt es schon nach einem Bruchteil der Epochen brauchbar.
- Nur die Stufe **medium** (22.050 Hz) ist ohne weitere Anpassungen unterstützt; sie läuft auch auf älteren Handys flüssig.

Exportieren:

```bash
python3 -m piper.train.export_onnx --checkpoint /pfad/zum/checkpoint.ckpt --output-file /pfad/zu/meine-stimme.onnx
```

Die Konfiguration `meine-stimme.onnx.json` (aus `--data.config_path`) gehört zum Modell, beide Dateien brauchst du im nächsten Schritt.

## 4. Verpacken: `package_voice.py`

Macht aus Modell und Konfiguration den Ordner im Format der sherpa-onnx-Pakete (tokens.txt, Metadaten im Modell, Ordnername `de_DE-meine-stimme-medium`).

```bash
pip install -r tools/voice/requirements.txt          # onnx, onnxruntime (und sherpa-onnx für den Testsatz)
python tools/voice/package_voice.py \
  --onnx meine-stimme.onnx --config meine-stimme.onnx.json \
  --name meine-stimme --lang de_DE --int8 \
  --espeak-data pfad/zu/espeak-ng-data \
  --test "Hallo, das ist meine eigene Stimme." --out stimmen
```

- `--int8`: halb so groß und auf älteren Handys schneller, mit kaum hörbarem Verlust (die fertigen sherpa-Stimmen sind ebenfalls int8).
- `--espeak-data`: den Ordner `espeak-ng-data` aus einem sherpa-Paket nehmen (`vits-piper-de_DE-…`); er wird nur für den **ersten** Import auf dem Handy gebraucht.
- `--test` erzeugt `test.wav` im Ordner (am PC anhören, bevor du etwas kopierst; braucht `sherpa-onnx==1.13.8`).

## 5. Aufs Handy

Den Ordner (`stimmen/de_DE-meine-stimme-medium`) kopieren (USB, Download-Ordner). In der App: **Einstellungen → Piper-Stimmen → Stimmenordner wählen…**,
danach im **Companion-Editor** unter **Stimme** auswählen und mit **Anhören** prüfen.

## Hinweise

- **Nur deine eigene Stimme** oder die einer Person, die ausdrücklich zugestimmt hat.
- Die Skripte laden nichts hoch; alles bleibt auf deinem Rechner.
- Lizenz: Piper (piper1-gpl) steht unter GPL-3.0, genau wie espeak-ng. Das Modell, das du trainierst, ist deine Aufnahme-Ableitung; die Lizenz des Ausgangs-Checkpoints (Datensatz-Lizenz) gilt mit.
