# Translate (Karte „Translate“, Android) – Stand und Übergabe

Stand: 28.11.2026, letzter Commit `a609d66` auf `main`. Dieses Dokument ist für die nächste Sitzung gedacht, die hier weitermacht.

## Was es ist

Fünfte Karte der unteren Leiste (🌐). Übersetzt Dokumente (TXT, MD, DOCX, PDF, EPUB) Abschnitt für Abschnitt, **offline auf dem Handy** mit
TranslateGemma 4B (llama.cpp, nur CPU) oder über den Server aus den Einstellungen (LM Studio usw.). Der Algorithmus ist ein Port aus dem Projekt
`gcprogram/AI-Translator` (Ordner `C:\TEMP\Programmieren\AI-Translator`): Abschnitte an Absätzen, **Vorgänger als Kontext statt Überlappung**, Glossar,
Überschriften bleiben, Bilder als `[[Bild n]]`, Restzeit, Wiederholung bei Verbindungsfehlern (3/10/30 s), Fortsetzen nach Abbruch.
**Änderungen an der Übersetzungslogik gehören in beide Projekte** (Kotlin-Tests wurden mitkopiert).

## Wo was liegt

| Was | Datei |
|---|---|
| Kern (reines Kotlin, JVM-testbar) | `android/core/src/main/kotlin/app/aichatmcp/core/translate/` (`Pipeline`, `Chunking`, `Prompts`, `Glossary`, `Images`, `Headings`, `Blocks`, `Eta`, `Languages`, `TranslateBackend`, `LmStudioBackend`, `TranslateConfig`) |
| Tests dazu | `android/core/src/test/kotlin/app/aichatmcp/core/translate/` und `ReaderTest` (Mitlesen) |
| Dateileser und -schreiber (Android: PDFBox, jsoup) | `android/app/src/main/kotlin/app/aichatmcp/translate/` (`DocumentReader`, `Exporters`, `PdfExporter`, `PdfBookmarks`, `PdfTextExtractor`, `TranslateStore`) |
| Ablauf, Zustand, Sitzung | `TranslateViewModel.kt` (Einstellungen in SharedPreferences `translate`, Sitzung `files/translate_session.json`, Bilder `files/translate_images/`) |
| Oberfläche | `ui/TranslateUi.kt`; Karte in `ui/StartBar.kt`; Knopf „Übersetzen…“ an der Dokumentkarte in `ui/ChatScreen.kt` (`DocCard`) |
| Modell auf dem Handy | `local/LocalLlm.kt` → `translateBackend(modelRef, threads)` (Roh-Prompt, ersetzt das Chat-Modell im Speicher, wird nach dem Lauf freigegeben) |
| Modellkatalog | `core/Catalog.kt`, Eintrag `translategemma-4b` (mradermacher, Q4_K_S, 2,4 GB; gleiche Datei wie am PC getestet) |
| Vorlesen / Mitlesen | `core/Reader.kt`, `core/Readable.kt` (`ReadPlan.growing()`), `ChatViewModel` (`liveRead*`, `readTranslation`), Verdrahtung in `MainActivity` (`LiveReadHook`) |

## Verhalten, das man kennen muss

- **Vorlesen nach der Übersetzung:** Knopf „Vorlesen“ → `ChatViewModel.readTranslation` (Klartext, Sprache wird am Text erkannt).
- **Mitlesen** (Schalter „Beim Übersetzen mitlesen“): jeder fertige Abschnitt wird an einen **offenen** `ReadPlan` angehängt. Der `Reader` wartet am Ende
  (`onWaiting`), hängt neuen Text noch während des Sprechens an und endet erst nach `closePlan()` (Übersetzung fertig, abgebrochen, Fehler).
  Holt er die Übersetzung ein: Meldung „warte auf die Übersetzung (ca. N Min.)“ (Schätzung `TranslateViewModel.waitHint`), danach sinkt das Tempo auf
  88 % (`liveFactor`, nicht unter 60 %); bei mehr als 12 Elementen Vorrat steigt es um 5 %.
- **Pause (||)** pausiert nur das Vorlesen, die Übersetzung läuft im Vordergrunddienst (`GenerationService`) weiter. **Stopp (■)** beendet das Mitlesen für den
  Rest der Übersetzung (`liveSuppressed`). Der **Schalter** lässt sich mitten im Lauf aus- und einschalten (`liveSent` merkt, was schon übergeben wurde;
  Rest eines schon übergebenen, aber nicht gesprochenen Abschnitts geht beim Wiedereinschalten verloren).
- **Stimmen:** Erzähler, Frauen, Männer wie beim normalen Vorlesen (`dialogueVoices`), aber nur mit Piper-Erzählerstimme **in der Zielsprache**; Zuordnung der
  Reden je Abschnitt (`ReadPlan.of(..., dialogue = true)`), ohne früheren Kontext, ohne KI-Rückfall.
- Die Karte hält den Bildschirm an, solange übersetzt wird (`keepScreenOn`); im Chat gilt das beim Vorlesen nicht (der Bildschirm sperrt, Vorlesen läuft weiter).
- Standard: Englisch → Deutsch, Modell TranslateGemma wird vorgewählt, wenn vorhanden.

## Gemessen (OnePlus 15, TranslateGemma 4B Q4_K_S, CPU, 4 Threads)

- „Per Anhalter durch die Galaxis“ (EPUB, DE→EN, 107 Abschnitte à ca. 3.300 Zeichen): 2 bis 3,5 Minuten je Abschnitt, **17 bis 25 Zeichen/s**,
  das ganze Buch gut **4 Stunden**. Ein kleines Dokument (2 Kapitel, 600 Zeichen) in unter 30 s.
- Mit parallel laufendem Vorlesen 6 bis 20 % langsamer (Stichproben). Die englische Stimme liest bei `speech_rate` 1,2 etwa 21 bis 26 Zeichen/s: schneller
  als die Übersetzung (daher die Selbstbremse). Das Mitlesen funktionierte auf dem Gerät (Abschnitt für Abschnitt angehängt, Warten beim Einholen gesehen).
- Qualität: für ein 4B-Modell brauchbar, kleine Schwächen (ein englisches Wort blieb stehen, ein Anführungszeichen in eigener Zeile).

## Noch nicht geprüft (nächste Schritte, in dieser Reihenfolge)

1. **Auf dem Handy:** Wartemeldung mit Schätzung und Selbstbremse (`liveFactor`) tatsächlich beobachten; Pause lässt die Übersetzung weiterlaufen; Schalter aus/an mitten im
   Lauf; Stimmenwechsel beim Mitlesen (Piper-Stimmen für die Zielsprache in den Einstellungen setzen); Anruf während des Wartens.
2. **Export aus der App:** DOCX, PDF (Lesezeichen), EPUB, MD, TXT; mit Bildern (EPUB/DOCX/PDF); Datei im Dateimanager öffnen.
3. Längere Dokumente, PDF mit Bildern, Server-Modus, Fortsetzen nach Abbruch/Neustart, Knopf „Übersetzen…“ an der Dokumentkarte, Glossar-Feld.
4. Nicht mitgenommen aus dem Translator: `IoTest` (Dateileser/-schreiber), Glossar-Bildschirm mit mehreren Glossaren, Abschnitt einzeln neu übersetzen,
   Übersetzung bearbeiten, Suchen/Ersetzen, Bildvorschau, KI-Glossarvorschlag für Chat-Modelle (hier nur das Textfeld und TranslateGemma).

## Ideen

- **Schnellerer Start des Mitlesens:** der erste Abschnitt ist 3.300 Zeichen groß, das Vorlesen beginnt erst nach 2 bis 3 Minuten. Ein kleiner erster Abschnitt (z. B. 800 Zeichen)
  würde das auf unter eine Minute drücken.
- **Tempo:** llama.cpp mit weiteren ARM-Befehlssätzen (i8mm/SME auf dem Snapdragon 8 Elite Gen 5) oder GPU (OpenCL/Adreno, braucht Q4_0-Quantisierung); eine NPU-Fassung von
  TranslateGemma habe ich nicht gefunden. Gemma 4 E4B als Vergleich (allgemeines Modell, kein Übersetzer).
- Tempo der Stimme: Standard 1,2 passt für Deutsch; für Englisch ist es zu schnell (Einstellungen → Stimmen → Geschwindigkeit).

## Tests und Arbeitsweise

- Kern: `cd android && ./gradlew :core:test` (488 Tests, alle grün). App: `./gradlew :app:assembleDebug`, installieren mit `adb -s <Seriennummer> install -r app/build/outputs/apk/debug/app-debug.apk`
  (Daten bleiben). Das Handy des Besitzers ist ein OnePlus 15 (`adb devices`: `3B15CW00TA200000`); es ist sein echtes Gerät mit persönlichen Daten: nur mit seiner Zustimmung installieren, im
  Dateiwähler nicht in seinen Ordnern stöbern (Suche nach dem Dateinamen reicht), Testdateien danach löschen.
- Handy-Bedienung per adb: Bildschirm entsperrt halten (sonst nichts bedienbar), `uiautomator dump` zeigt keine Schubladen/Menüs des Systemdialogs (dort Screenshot), Koordinaten des
  Screenshots mal 1,08 = echte Pixel. Fortschritt der Übersetzung ohne Oberfläche: `adb exec-out run-as app.aichatmcp cat files/translate_session.json` (fertige und offene Abschnitte).
  Modelle liegen unter `/sdcard/Android/data/app.aichatmcp/files/models/`.
- Windows/Git Bash: `adb push` braucht `MSYS_NO_PATHCONV=1` und den Windows-Pfad (`C:/...`) für die lokale Datei; sonst wird `/sdcard` zu `C:/Program Files/Git/sdcard`.
- Stolpersteine im Code: Compose-`Text` im Paket `app.aichatmcp.ui` ist der übersetzende (nicht `material3.Text` importieren); Property-Setter und gleichnamige Funktionen kollidieren in Kotlin
  (`setX` gegen `private set`); `ReaderTest.kt` enthält zwei Klassen (`ReaderTest`, `ReaderInterruptionTest`) mit verschiedenen Fakes; im Arbeitsbaum haben ältere Dateien teils CRLF (der Index ist LF):
  Dateien mit Skript bearbeiten und die Zeilenenden erhalten.
- Regeln des Besitzers: kein Name/keine E-Mail in Code, Commits, Doku; Commit-Nachrichten englisch mit Co-Authored-By-Zeile, Kommentare/Oberfläche deutsch; LF; bei grünen Tests committen und pushen;
  `meine-stimme.zip` im Wurzelordner gehört dem Besitzer (nicht hinzufügen).
