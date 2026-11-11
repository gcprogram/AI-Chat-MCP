# AI-Chat-MCP – Projektkontext für Claude

Offline-Chat-Client (Tkinter) mit Persönlichkeit/Avatar-Companion gegen LM Studio oder einen anderen
OpenAI-kompatiblen Endpunkt. Lokale Modelle haben nur ca. 8192 Token Kontext. Abgespalten aus einem früheren
kombinierten Translator/Chat-Client (Original zuletzt im Repo `gcprogram/Python`, Ordner `AIChat-Client`,
Commit `217b144`). Der Übersetzer lebt jetzt in `gcprogram/AI-Translator`.

## Regeln des Besitzers
- **Kein Name, keine E-Mail im Code, in Commits, READMEs oder Kommentaren.** Git-Identität ist die
  GitHub-noreply-Adresse (global gesetzt) – nicht ändern. Vor jedem Push `git log --format='%an %ae'` prüfen.
- Bei grünen Tests sofort committen und pushen (ohne Rückfrage), bei roten nicht; sonst nur auf Wunsch.
  Commit-Nachrichten englisch, Code-Kommentare/UI deutsch.
- Kommunikation auf Deutsch.

## Entscheidungen
- **Backend bleibt LM Studio über HTTP.** Client ist zustandslos: `/v1/chat/completions` mit vollständigem
  `messages`-Array pro Anfrage. Das Original nutzte `/api/v1/chat` mit `previous_response_id` *und* Verlaufstext
  im `input` – das verdoppelte den Kontext (Ursache für „nach ein paar Antworten Schluss“) und das fehlende
  System-Prompt für die endlosen Geschichten und Du/Ich-Verwechslungen.
- Persönlichkeit = `personas/*.json` → `Persona.system_prompt()` (Rollenregeln, Du/Ich, kurze Chat-Antworten),
  Stop-Sequenzen gegen selbst weitergeschriebene Dialoge, `max_reply_tokens` begrenzt Antworten.
  Begrüßung steht im System-Prompt, nicht als Assistant-Nachricht (manche Templates verlangen `user` zuerst).
  **Du/Ich**: Der Prompt nennt das Modell "du"; früher stand in einer Regel "du" auch für den Nutzer (Widerspruch, Ursache der Verwechslungen). Jetzt: Pronomen je Rolle erklärt (`_pronoun_rules`/`pronounRules`), Spiegelregel mit Beispielen ("Ich bin müde" -> "Du klingst müde"), Erinnerung am Ende; bei nicht deutscher Antwortsprache kurze englische Regel. Python und Kotlin gleich. Wirkung am echten Modell NICHT geprüft.
- Reasoning (`reasoning_content`) wird angezeigt, aber nie in den Verlauf gespeichert.
- LM Studio verlangt einen **API-Token** (Developer-Einstellungen) → in den Einstellungen eintragen
  (`config.json`, gitignored). Token niemals ins Repo oder in Chat-Ausgaben schreiben.
- Server-URL ist frei (inkl. `/v1`), damit Online-Dienste ohne Codeänderung gehen.

## Tests / Start
```bash
python -m venv .venv && .venv\Scripts\activate && pip install -r requirements.txt
set PYTHONPATH=. && python -m unittest discover -s tests      # 110 Tests inkl. GUI-End-to-End mit Fake-LLM-Server und echtem MCP-Demo-Server
python main.py
```

Modellvergleich fuer Sekretariatsaufgaben gegen LM Studio: `set PYTHONPATH=. && python tools/model_eval.py [-m Modell ...] [--repeat 3] [--report x.md]` (21 feste Saetze: Todo/Wetter-Marker mit Argumenten und Chatfaelle ohne Marker; echter Prompt der App, nur Text, keine Ausfuehrung; Test `tests/test_model_eval.py` mit Schein-Server). Gemessen (Marker-Modus, 22 Faelle je Durchgang, Nov 2026): `thedrummer_gemmasutra-small-4b-v1` 6/22 (Ø 11,7 s), `silicon-maid-7b` 9/22 (Ø 13,9 s) - beides Rollenspiel-Feinabstimmungen, die den Marker kaum schreiben und Chatfaelle mit erfundenen Aufrufen beantworten; `qwen3-8b-hivemind…` brach in LM Studio ab (`Engine protocol predict request failed`). Instruct-Modelle: `qwen3-4b-instruct-2507` (Q4) **17/22 (Ø 2,8 s, GPU)** - Fehler: erfundenes Jahr (`2023-11-25`), geratene Nummer bei todo-done, zweimal "Erledigt" behauptet ohne Marker; `gemma-3-4b-it` (Q4_K_M, lmstudio-community, ueber `llama-server.exe` von LM Studio auf der CPU, Ø 21,6 s nicht vergleichbar) **13/22** - schreibt den Marker oft nicht, beantwortet Todo-Saetze frei und kopiert das Beispiel aus dem Prompt (`Call the dentist`). Empfehlung fuer Werkzeuge: Qwen3-4B-Instruct-2507; Gemma haette den besseren Ton, taugt aber ohne nativen Modus kaum fuer Marker. LM Studio erkannte die von Hand geladene Gemma-Datei nicht (`lms get`/`lms import` hingen), deshalb der eigene Server.

## Entscheidungen (Erweiterung)
- **Wiederholungen** (4B-Modell): Sampling-Parameter (`Config.sampling_params`, `repeat_penalty` 0 = nicht senden),
  Prompt-Regeln und vor allem `reply.py`: Antwort wird **vor** dem Speichern im Verlauf bereinigt (wiederholte Sätze,
  Markup/Rollenfragmente, Schleifen, abgebrochene Sätze), damit das Modell sich nicht selbst nachahmt;
  `StreamGuard` bricht erkennbare Schleifen beim Streamen ab.
- **MCP per Textmarker** `[[mcp:name|arg|arg]]` (kein Function-Calling): `tools.py` (Parser, Registry, Prompt-Abschnitt),
  `mcp_client.py` (stdio-JSON-RPC ohne SDK). Beim ersten kompletten Marker wird der Stream abgebrochen; das Ergebnis
  geht als `user`-Nachricht (`Message.kind = tool_result/tool_error`, Rollen bleiben abwechselnd) zurück. Ergebnisse
  werden gekürzt, Marker darin entschärft, Aufrufe standardmäßig bestätigt. Geladene Chats führen **nie** aus.
- Companions: `personas/*.json` + `personas/avatars/`, Editor in `persona_editor.py`; Werkzeuge pro Companion
  (`tools`), davon vorab genehmigte ohne Rückfrage (`approved_tools`, schlägt globale Einstellung und Alias-`confirm`). Chats: `chatlog.py` (Endung **`.aichat`** = JSON vollständig, damit ein Chat nie eine Companion-Datei `*.json` überschreibt; ältere `.json` laden weiter; TXT lesbar/ladbar; Android: `ChatLog.fileName`, interner Chat `chats/<id>.aichat` mit Rückfall auf `.json`), Ordner `chats/` (gitignored).
- `mcp_servers.json` ist gitignored (kann Schlüssel enthalten); Vorlage in `examples/`.
- **MCP-Transporte**: `McpClient` (Basis, `mcp_client.py`) mit `McpServer` (stdio) und `HttpMcpServer` (`mcp_http.py`,
  Streamable HTTP: JSON oder SSE, `Mcp-Session-Id`, einmalige Neuanmeldung bei 404). Eintrag mit `url` statt `command`;
  Header mit `${ENV}` (Token nie in die Datei), `http://` nur lokal, `cert` als einziger Vertrauensanker, keine
  Weiterleitungen. Gegen die echte Fernbedienungs-App geprüft (per USB: `adb forward`, `tools/list`, `status`, `buttons`; `send` noch nicht;
  die App ist zustandslos, ohne Sitzungs-ID, und erzeugt ihr Zertifikat bei IP-Wechsel neu).
- **Alias-Layer** (`aliases` in `mcp_servers.json`, `ToolSpec.fixed/defaults/result/status/confirm/remember`): Kurzname →
  echtes MCP-Tool mit festen Argumenten, damit das kleine Modell nur einen Namen (plus ≤1 Argument) schreiben muss.
  `result`: `model` (Antwortrunde) | `show` (Klartext im Chat, `Message.kind = tool_shown`, keine zweite Runde) |
  `discard` (nur Statuszeile) | `both` (Klartext im Chat als reine Anzeige UND Modellrunde auf das Ergebnis; im Verlauf `tool_result`).
  Bei `show` ruft das 4B-Modell Folgefragen mit demselben Werkzeug erneut auf (Verlauf voller Aufrufe, nie eine Antwort); Versuche, Aufruf und Ergebnis
  im Verlauf zu einer Assistenten-Nachricht zu verschmelzen, halfen nicht (ohne Marker erfindet es Daten) - `both` ist die Lösung. Angezeigte Ergebnisse gehen erst mit der **nächsten Nutzernachricht** ans Modell
  (`_wire_history` verpackt sie dort; Rollen bleiben abwechselnd). Fehler gehen immer ans Modell. `raw: false` pro
  Server blendet rohe Tools aus. Eigene Server in `servers/` (Wetter, Websuche; Datumsrechnung im Server, nicht im Modell). **Werkzeugnamen, Parameter,
  Beschreibungen und der Prompt-Abschnitt sind englisch** (`weather`, `weather-past`, `websearch`, `tv-p1`…, `tv-off`):
  kleine Modelle folgen englischen Anweisungen besser. Marker für nicht freigeschaltete Werkzeuge werden im Chat gemeldet
  (sonst wirkt ein Companion ohne `tools` wie „Werkzeuge gehen nicht“).
- **Tasten und Makros** (`button`/`macro` im Alias; Python `tools.py`, Kotlin `Tools.kt`, gleiche Regeln): `{"server":"remote","button":"P1"}` löst die Beschriftung über das Werkzeug `buttons` des Servers auf
  (`ToolRegistry.button_codes`/`buttonCodes`, einmal gelesen und gemerkt; Schlüssel Beschriftung, Hex, Dezimal; Code kommt als `"0x11"` an `send`), Standard `result: discard`, `status` = Beschriftung.
  Die Codes stehen so nur im Profil der Fernbedienung (Broadcast-Remote, Profile in der App), nicht mehr in `mcp_servers.json` (Broadcast-Remote selbst musste nicht geändert werden). `macro`: Liste aus Alias (optional `alias|Arg`),
  Zahl oder `{"wait":n}` (0 bis 60 s, höchstens 20 Schritte); keine Makros in Makros, unbekannte Schritte verwerfen das Makro mit Fehlermeldung, erster Fehler beendet (`Schritt N (alias): …`);
  das Makro ruft die Aliase über dieselbe `run`-Logik (ohne eigene Rückfrage je Schritt: das Makro selbst hat `confirm`). Pause blockiert den Werkzeug-Thread (kein Abbruch mitten in der Pause). **Aliase vom Server lesen**: `chat_app/server_import.py`
  (CLI `python -m chat_app.server_import <server> --prefix tv-`, schreibt `.bak`), Kotlin `core/ServerImport.kt` + `ChatViewModel.importServerTools` + Werkzeuge-Bildschirm (fügt nur in das Textfeld ein, gespeichert wird von Hand); vorhandene Aliase bleiben, `raw:false` wird beim Server gesetzt.
  Tests `tests/test_buttons_macros.py`, `ButtonsMacrosTest` (Schein-Fernbedienung). NICHT geprüft: gegen die echte Fernbedienung (`buttons` mit Beschriftungen), Importknopf auf dem Gerät.
- **Ein Alias fuer die ganze Fernbedienung** (`"buttons": true`, `ToolSpec.buttons`, Python `_build_button_set`/`_run_button`, Kotlin `buildButtonSet`/`runButton`): die Taste ist das Argument `button` (`[[mcp:tv|P3]]`, `off` ruft `off`),
  Beschriftungen holt die Registry beim Aufbau vom Server (`buttons`), der Prompt-Abschnitt listet sie (`Buttons: P1, …, off`), unbekannte Tasten kommen mit der Liste der erlaubten zurueck. Ersetzt die 19 Einzel-Aliase `tv-*` (kleinere
  `mcp_servers.json`, kleinerer Prompt). **Pro Companion** `tool_choices` (`{"tv": ["P1","off"]}`, fehlt = alle; Python `Persona.tool_choices`, Kotlin `toolChoices`): nur diese Tasten stehen im Prompt und nur diese laufen (`prompt_section(aliases, choices)`, `run(..., choices)`, `native_tools(..., choices)` mit `enum`);
  Editoren (Tk und Compose) zeigen dafuer ein Textfeld unter dem Werkzeug. `ToolRegistry.declared`/`selectable()`: alle Aliase der Konfiguration, auch die gerade nicht erreichbaren, sind im Editor waehlbar (Hinweis "Server nicht erreichbar"); sie gelten, sobald der Server laeuft. Der Import vom Server (`server_import`) erzeugt jetzt diesen einen Alias.
  Handy: `mcp_servers.json` und Companions wurden per `run-as` migriert (Sicherung `files/mcp_servers.pre-tv.json`). NICHT geprueft: Tastendruck an der echten Fernbedienung (nur Schein-Server), Editor-Feld fuer Tasten auf dem Geraet.
- **Nativer Modus** (`Persona.tool_mode = "native"`, Schalter im Companion-Editor): Aliase gehen als `tools`-Parameter
  (`ToolRegistry.native_tools`, klein dank fester `args`). `llm_client` setzt `delta.tool_calls` zusammen und liefert
  `StreamEvent("tool_call")`; die GUI macht daraus per `ToolRegistry.native_call` einen Marker (Anzeige/Verlauf/Speichern
  bleiben wie im Marker-Modus) und führt mit den echten JSON-Argumenten aus (`ToolCall.arguments`, `ChatApp.native_args`).
  Erst beim Senden wandelt `ChatSession._wire_native` den Verlauf in `tool_calls`/`tool`-Nachrichten um (Aufruf ohne
  Ergebnis → Text; `tool_shown` → `tool`-Nachricht). Werkzeugbeschreibungen zählen in die Kontext-Schätzung. Nur der
  erste Aufruf pro Antwort. Ungeprüft: echtes Modell/LM Studio (nur Fake-Server-Tests).
- **Avatar in der Companion-Datei** (`avatar_data` = Base64-JPEG 256x256, Python `avatar.encode_avatar`/`Persona.avatar_path` (packt in eine Cache-Datei im Temp-Ordner aus), Kotlin `EmbeddedAvatar` + `AvatarEncoder` + `AppStorage.avatarFile(persona)` (Cache `avatar-cache/`)):
  hat Vorrang vor dem alten `avatar` (Dateipfad, laedt weiter). Ein im Editor gewaehltes Bild ersetzt das eingebettete; Entfernen leert beide Felder. `Persona.to_dict` schreibt `avatar_data` nur, wenn gesetzt. Export und Import der App tragen das Bild jetzt mit.
  `tools/embed_avatars.py [--auto] [--assign Bild=kennung]` bettet vorhandene Bilder ein (Originale bleiben). Base64 wird strikt dekodiert (Zeilenumbrueche erlaubt, Muell nicht). Die Dateien in `personas/avatars/` werden von eingebetteten Companions nicht mehr gebraucht. Nyx hat ein selbst gezeichnetes Platzhalterbild (Pillow), kein KI-Bild.
- Tests mit Tk: Variablen des Editors vorher im Hauptthread per `gc.collect` freigeben (sonst `Tcl_AsyncDelete`-Abbruch,
  wenn ein Streaming-Thread die GC auslöst).

- **Android-App** (`android/`, Gradle: `:core` reine Kotlin-Logik, `:app` Compose): Port der Python-Logik, Erwartungswerte der
  Tests stammen aus Python (`difflib`-Nachbau, Bereinigung, Wächter). Build mit **JDK 17** (JDK 25 bricht Kotlin 2.0).
  `ChatEngine` (core) führt ganze Runden (in Python steckt das in `gui.py`); pro Runde ein eigenes `CancelToken`
  (stilles Stoppen durch den Wächter darf nicht in die nächste Runde wirken). Eingebaute Server statt Unterprozesse;
  Desktop-Konfiguration (`servers/weather_server.py`) wird erkannt. Tests: `cd android && gradlew :core:test` (49).
  Auf dem Gerät geprüft (Galaxy S21, per `adb`). Nativer Modus fehlt. Avatare: `AppStorage.avatarFile/importAvatar`, Import über
  `OpenDocumentTree` (Samsung-Picker fragt den Ordnerzugriff mit einem Dialog ab) oder `OpenDocument`; `AvatarView` lädt verkleinert.
  **Android-Regex ist ICU**: kein `(?U)`, `\p{L}\p{N}` statt `\w` für Unicode; `androidTest/CoreOnDeviceTest` prüft die Logik auf dem
  echten Laufzeitsystem (`adb install -t` + `am instrument`, Gradle `connected…` würde die App deinstallieren).
- **Sprache** (`speech/SpeechIo.kt`): `SpeechInput` = Android-`SpeechRecognizer` mit gezielt dem Google-Dienst (`com.google.android.tts`; `createOnDeviceSpeechRecognizer` NICHT
  benutzen: Geräte-Erkenner kennt die Sprache nicht), erst offline, bei Fehler 11 bis 13 (Sprachpaket fehlt) einmal online (`speech_online`);
  `SpeechOutput` = `TextToSpeech` (Systemstandard, bei diesem Gerät Google);
  Sprachwahl/Textaufbereitung testbar im Kern (`Speech`). Der Geräte-Test lässt die Synthese in eine Datei schreiben (kein Ton) und prüft
  nur die Verfügbarkeit der Erkennung (Sprechen kann ein Test nicht). Echte Spracheingabe per Mikrofon noch nicht von Hand geprüft.
  **Offline-Pakete** (`SpeechPacks`: `checkRecognitionSupport` API 33, `triggerModelDownload` API 34): Der Google-Dienst versucht dabei, eine eigene
  `DownloadActivity` (`com.google.recognition.action.DOWNLOAD_LANGUAGE`) zu starten, was Android 15 als Hintergrundstart blockiert (`BAL_BLOCK`) - es kommt
  nie eine Rückmeldung, der Fortschritt bleibt bei 0 %. Deshalb startet die App den Dialog selbst (`openDownloadDialog`, Extra `EXTRA_LANGUAGE`) und fragt
  den Status alle 4 s ab (`pollPack`, 5 min). Auf dem Gerät geprüft (de, en-US, ja-JP).
  **Stimmen** (`SpeechOutput.installedVoices`: `Voice.features` ohne `notInstalled`; `openVoiceInstall` = `ACTION_INSTALL_TTS_DATA`) und Test-Satz in den Einstellungen.
  **Stimme pro Companion** (`Persona.voice`, Python reicht das Feld nur durch): `SpeechOutput.voice` (gesetzt per `ChatViewModel.syncVoice` bei Start/Wechsel/Speichern), `voicesFor(tag)`,
  `applyLanguageAndVoice` = `setLanguage` (setzt wieder die Standardstimme) und danach `setVoice`, nur wenn installiert und Sprache passt. Stimmen heißen nur *Stimme N* (Android liefert kein Geschlecht);
  der Platzhalter `xx-XX-language` fällt weg, `-network`-Stimmen brauchen Internet. Gerätetest `SpeechOnDeviceTest.chosenVoiceDiffersFromDefaultAndDefaultComesBack` (Audiodatei-Vergleich). BlueStacks hat keine
  Sprachausgabe (App läuft dort per arm64-Übersetzung, ADB `127.0.0.1:5555`); Stimmen dort erst nach Installation der Google-Sprachausgabe.
  **Piper-Stimmen** (`core/Piper.kt` = `PiperStore`/`PiperNames`, `speech/PiperSpeaker.kt`, `Persona.voice = "piper:<Ordner>"`): sherpa-onnx **1.13.8** (nicht 2.x: dort fehlt die
  espeak-ng-Phonemisierung, Piper bräuchte einen eigenen Phonemisierer), native Bibliotheken per Gradle-Task `fetchSherpa` mit SHA-256 aus `android/sherpa-onnx.version` nach
  `android/third_party/sherpa-onnx` (gitignored), Kotlin-API `com.k2fsa.sherpa.onnx.Tts.kt` (Apache-2.0) liegt im Quelltext. **JNI-Falle:** der native Code ruft den Rückruf als
  `invoke([F)Ljava/lang/Integer;` auf; eine Kotlin-Lambda hat das nach D8 nicht (Absturz `NoSuchMethodError` mit SIGABRT), deshalb ein `object : (FloatArray) -> Int`.
  Satzweise Erzeugung (`maxNumSentences = 1`) in einen fortlaufenden `AudioTrack` (Float, Mono); „gehört“ meldet ein Beobachter über `playbackHeadPosition`, nicht das Schreiben;
  Stopp verwirft alles (neue Generation, Track wird neu angelegt). `espeak-ng-data` einmal gemeinsam in `files/piper/`. Piper wird nur gewählt, wenn Sprache passt (`SpeechOutput.piperFor`),
  sonst Systemstimme; ohne Systemengine (BlueStacks) funktioniert Piper trotzdem. **Schwankung** `Config.piperNoise/piperNoiseW` (noise_scale 0.667/noise_w 0.8, Einstellungen): stecken im geladenen Modell, eine Änderung lädt die Stimmen neu (`PiperSpeaker.loadedNoise`). **Mehrere Sprecher** (`speaker_id_map` im `.onnx.json`, z. B. Thorsten Emotional = Stimmungen; vorher wurde immer Sprecher 0 = "amused" gesprochen): Kennung `piper:<Ordner>#<sid>`, `PiperVoice.choices` = ein Eintrag je Sprecher, Modell wird je Ordner nur einmal geladen. Ob es besser klingt: nicht geprüft (Gehör). **Gemessen auf dem S21** (`PiperOnDeviceTest.everyInstalledVoiceIsMeasured`, Faktor = Rechenzeit/Audiolänge): thorsten_emotional medium 0,32, en_GB cori medium 0,13, en_US lessac medium 0,11 (alle deutlich schneller als Sprechen; `high`-Stimmen auf dem S21 nicht gemessen, auf dem S9 war thorsten-high 3,8). Importierte Companions werden sofort ausgewählt (`importPersona`); **Export** im Companions-Bildschirm („Exportieren“, `ChatViewModel.exportPersona`, Datei `<id>.json` im Format der PC-App, ohne Avatar-Bild). **Ordner importieren** (`ChatViewModel.importPersonaFolder`, `PersonaStore.importFile`, `CompanionImport.report`): alle `.json` im obersten Ordner werden Companions (Kennung = Dateiname, Vorhandenes wird NICHT überschrieben), Bilder aus dem Unterordner `avatars` kommen mit; Dateien ohne Feld `name` (z. B. andere JSON-Dateien) meldet der Bericht und überspringt sie (`Persona.parseCompanion`, auch beim Einzelimport; `Persona.parse` bleibt tolerant). Die Schaltflächen stehen in einer `FlowRow` unter der Auswahl (sonst wurde die Auswahlbox zu schmal). **Fallen:** Nach einem Import muss `selected` im Companions-Bildschirm dem aktiven Companion folgen (`LaunchedEffect(vm.persona.id)`), sonst zeigt das Formular weiter den alten Companion samt Werkzeugen und ein Speichern ueberschreibt ihn. Freigeschaltete, aber in `mcp_servers.json` nicht mehr vorhandene Werkzeuge (z. B. alte `tv-s1`) bleiben unsichtbar im Companion; jetzt rot gelistet mit Knopf "Nicht vorhandene entfernen". Werkzeuge-Bildschirm: "Exportieren" schreibt den Text des Feldes als `mcp_servers_phone.json` (Geheimnisse liegen separat und sind nicht enthalten).
  Gerätetest `PiperOnDeviceTest` (Lautstärke 0, Stimmen per `run-as … tar -xf -` in `files/piper`;
  **Git-Bash:** bei adb mit Geräte-Pfaden `MSYS_NO_PATHCONV=1`, sonst wird `/data/…` zu `C:/Program Files/Git/data/…`; viele Einzeldateien per `adb push` hängen, besser ein tar).
  Auf dem Galaxy S9 (Android 10) geprüft: alle 10 deutschen int8-Stimmen von sherpa-onnx laufen, Faktor 0,5 bis 0,9, `thorsten-high` 3,8 (zu langsam). Anleitung für eigene Stimmen: `docs/EIGENE_STIMME.md`; Werkzeuge `tools/voice/` (`record.py` Aufnahme mit Satzlisten, `check_dataset.py`, `package_voice.py` = tokens.txt + Metadaten wie das offizielle `add_meta_data.py`, int8 optional; `voicelib.py` gemeinsam, Tests `tests/test_voice_tools.py` mit Schein-Mikrofon; Training selbst nicht automatisiert/ungetestet: Piper piper1-gpl, Linux/WSL2).
  **Wörtliche Rede mit Stimmen pro Rolle** (Vorlesen, nur Piper; `Persona.voiceMale/voiceFemale`): `core/Dialogue.kt` = `QuoteStyle` (Stil am Text erkannt: „…“, »…«, “…”, "…"), `Dialogue.analyze(plan)` (Reden finden, Geschlecht per Begleitsatz-Regex `VERB`/`WHO` + `FirstNames` + Absatzwechsel/„dicht dahinter“, vorwärts und rückwärts; `Source` TAG/SAME/FLIP/JUDGE), Zerlegung der Abschnitte in `ReadItem.Speech.parts` (`Utterance(text, Role)`), `DialogueText` (echte Absätze: Leerzeilen, sonst jede Zeile; Abschnitte aus ganzen Absätzen, `paragraphStart`). **Wichtig:** `ReadPlan.of(doc, dialogue = true)` baut die Abschnitte im Dialogmodus aus echten Absätzen (Zeilenumbruch im Abschnittstext); der normale Weg (`Documents.speechChunks`) macht aus Absätzen ". " und verliert die Absatzgrenzen, die für den Sprecherwechsel nötig sind. Der Text selbst bleibt unverändert, `parts` hängen nur dran: Abschnittsnummern, Leseposition und Bilder bleiben an ihrer Stelle. `Reader` ruft bei Abschnitten mit `parts` `ReaderOutput.speakParts` (Standard: alles zusammen), `SpeechOutput.speakItemParts` bildet Rollen auf Stimmen ab (nicht installierte oder falsche Sprache -> Erzähler; ohne Piper-Erzähler alles Systemstimme). `PiperSpeaker`: bis 3 Stimmen gleichzeitig geladen (LRU), Aufträge aus `Segment`s, Rückmeldung erst nach dem letzten; **alle Stimmen werden auf 22.050 Hz angeglichen** (`AudioMath.resample`), weil der AudioTrack nur eine Rate hat (low/x_low = 16 kHz). KI-Rückfall: `SpeakerJudge` (ein Buchstabe M/F/?, englische Anweisung, max. 6 Token) für `Dialogue.unresolvedFrom(...)`, nur mit `Config.dialogueJudge` und Server-Betrieb, im Hintergrund ab der Lesestelle, höchstens 400 Reden, 3 Fehler brechen ab; Antworten setzen die Rolle nachträglich (`Dialogue.assign`), schon Gesprochenes bleibt. Geprüft: JVM-Tests (`DialogueTest`), Gerätetests `PiperOnDeviceTest` auf BlueStacks (Mehrstimmen mit 16/22-kHz-Stimmen, ganze Kette Dokument -> Reader -> Sprachausgabe; Stimmen dort per `Android/data/app.aichatmcp/files/piper-test` + `adb shell tar`, weil `run-as` im Emulator fehlt), Oberfläche per Screenshot. NICHT geprüft: Trefferquote an echten Büchern, KI-Rückfall gegen ein echtes LM Studio, Hören der Stimmen, EPUB/PDF mit Dialogen auf einem Telefon. Grenzen: Reden über mehrere Absätze mit erneutem Öffnungszeichen werden nur im letzten Stück erkannt; zwei Sprecher gleichen Geschlechts nacheinander werden als Wechsel gelesen; Chat-Antworten (nicht das Vorlesen von Dokumenten) nutzen die Rollenstimmen nicht.
  **Stimmen-Studio** (`android/voicestudio`, eigene APK `app.aichatmcp.voicestudio`, nutzt `:core`): Aufnahme-App für die eigene Stimme. Reine Logik in `core/studio/` (`SentenceSplitter` = Port von `split_sentences` im Python-Werkzeug, Erwartungswerte von dort; `Audio.kt`: WAV, Umrechnen per gefenstertem Sinc mit Phasen-Cache, Stille kürzen, Kennzahlen; `StudioProject` = Ordner im Format des Python-Werkzeugs, `check()` = Port von `check_dataset.py`, `exportZip`). App: `Recorder` (AudioRecord 44,1/48 kHz, bevorzugt UNPROCESSED, sonst VOICE_RECOGNITION: Rauschunterdrückung/AGC des Telefons würden die Stimme verändern; die ersten 120 ms fallen weg = Tippgeräusch), `Player` (AudioTrack statisch), ViewModel mit Phasen READY/RECORDING/REVIEW. Satzlisten kommen per Gradle-Task `copySentences` aus `tools/voice` in die Assets. Geprüft: JVM-Tests (`StudioTest`), Gerätetest `StudioOnDeviceTest` auf dem S21 (Regex ICU, echte Mikrofonaufnahme, Speichern, Prüfen, ZIP, stumme Wiedergabe; vorher `adb shell pm grant app.aichatmcp.voicestudio android.permission.RECORD_AUDIO`), Oberfläche per Screenshots auf BlueStacks, ZIP vom Gerät mit dem Python-`check_dataset.py` gegengeprüft. NICHT geprüft: Aufnahme mit echter Sprache, SAF-Export-Dialog von Hand, Textimport über den Dateiauswahldialog. BlueStacks blendet Werbe-Benachrichtigungen ein (ein Tippen kann im Browser landen).
  **Spracheingabe mit Whisper** (`Config.speechEngine` = `google`|`whisper`, `whisperModel`; `core/Whisper.kt` = `WhisperStore` (Ordner mit Encoder/Decoder/`*tokens.txt`, int8 bevorzugt,
  beim Import wird die große Fassung weggelassen, wenn es die int8-Fassung gibt), `WhisperNames`, `WhisperAudio` (Stille-Prüfung gegen erfundenen Text, bekannte Geisterätze wie "Untertitel …"),
  `SilenceDetector` (Aufnahme endet 1,6 s nach der Sprache); `speech/WhisperInput.kt`): eigene Aufnahme (`AudioRecord`, 16 kHz, `VOICE_RECOGNITION`), Erkennung am Stück nach dem Ende
  (kein Mitlesen), höchstens 29 s (Whisper-Fenster 30 s), Modell wird schon beim Start der Aufnahme geladen. sherpa-onnx-ASR-Kotlin-API (`OfflineRecognizer.kt`, `OfflineStream.kt`,
  `FeatureConfig.kt`, `QnnConfig.kt`, aus dem Tag v1.13.8, Hilfsfunktion `getOfflineModelConfig` weggelassen) liegt im Quelltext; die JNI-Bibliothek enthält die ASR schon. large-v3/turbo brauchen 128 Mel-Bänder
  (`featureDim`), `.en`-Modelle nur Englisch. Import wie bei Piper per Ordnerauswahl (`ChatViewModel.copyTree` gemeinsam). Gerätetest `WhisperOnDeviceTest` (Piper spricht einen Satz, Whisper erkennt ihn, misst den Echtzeitfaktor; Modelle über `files/whisper` bzw. `files/piper` im App-Speicher, auf dem S21 per `adb exec-in "run-as app.aichatmcp tar -xf - -C files/whisper" < x.tar`; der externe Ordner `Android/data/…/files/whisper-test` geht dort NICHT, die App darf die per `adb push` angelegten Ordner (Gruppe `ext_data_rw`) nicht lesen, im Emulator schon). **Gemessen (S21, Android 15, whisper-small int8 von Hugging Face `csukuangfj/sherpa-onnx-whisper-small`, 4 Threads):** 6,7 s Audio in 3,9 s (Faktor 0,58), Laden etwa 2 s; Erkennung gut, ein Wortfehler. Am PC Faktor 0,45. Das Fenster ist fest 30 s, deshalb kostet ein kurzer Satz fast dasselbe. **medium (gleicher Satz, gleiche Stimme): 6,9 s Audio in 17,1 s (Faktor 2,5), Laden zusätzlich ~5 s, Satz fehlerfrei**; small lief im selben Durchgang danach mit 7,5 s (Faktor 1,1, also schwankt die Zeit je nach Wärme/Last, vorher 3,9 s). medium = grob 2- bis 4-mal langsamer als small. **Sprache** (`Config.whisperLanguage`): leer = Antwortsprache des Companions (Whisper schreibt dann in dieser Sprache und übersetzt so, z. B. gesprochenes Deutsch zu einem englischen Companion; am PC mit small und medium geprüft, gleiches Ergebnis wie `task=translate`, das bei medium doppelt so lange braucht), `auto` = gesprochene Sprache erkennen, sonst Code (`WhisperNames.targetLanguage`). Der Mikrofon-Knopf ist gesperrt, solange Whisper rechnet (`ChatViewModel.recognizing`; dann ist die Aufnahme zu Ende und nicht mehr zu stoppen). **NICHT geprüft:** echtes Mikrofon, Berechtigungsablauf, Aufnahme-Ende per Pause. Whisper übersetzt hier nicht (task `transcribe`).
  **Organizer** (Plan und Entscheidungen: `docs/ORGANIZER.md`; Android-only): eingebauter MCP-Server `organizer` (`core/Organizer.kt`: `TodoStore` = `todos.json`, `DateWords` (today/tomorrow/Wochentage/ISO/25.11.), `OrganizerServer`, `DailyUpdate`),
  Aliase `todo-add|Text|Datum`, `todo-list`, `todo-done|Nr oder Textteil` (englisch, `result: both`, ohne Rückfrage). **Tagesupdate** (`Persona.dailyUpdate`, Schalter im Companion-Editor): einmal pro Tag **insgesamt** (hat heute ein Companion das Update gezeigt, zeigen es die anderen nicht erneut; `Config.briefingDays` hält nur den letzten Tag, `DailyUpdate.alreadyShown`) legt `ChatViewModel.showDailyUpdate` beim Start/Companion-Wechsel
  einen von der App zusammengestellten Text (ohne Modell) als `tool_shown` (`daily-update`) in den Verlauf; das Modell sieht ihn mit der nächsten Nutzernachricht. Neue Standard-Aliase kommen über `AppStorage.mergeNewDefaults` einmal in eine bestehende `mcp_servers.json`
  (`defaults_offered.txt` merkt sich Angebotenes, Gelöschtes kommt nicht wieder). Geprüft: JVM-Tests (`OrganizerTest`), Gerätetest `OrganizerOnDeviceTest` und App-Start auf dem Emulator. NICHT geprüft: ob das 4B-Modell `[[mcp:todo-add|…]]` zuverlässig schreibt,
  Tagesupdate und Editor-Schalter in der Oberfläche von Hand. **Push (Phase 2)**: `Config.dailyReminder`/`dailyReminderTime` ("HH:mm", Einstellungen, Schalter holt ab Android 13 `POST_NOTIFICATIONS`), `core/Reminder.kt` (`parseTime`, `next`, `text` = Kurzübersicht der Zahl offener Todos, bewusst ohne Inhalte wegen Sperrbildschirm), `reminder/DailyReminder.kt` (`AlarmManager.setAndAllowWhileIdle`, absichtlich NICHT exakt: exakte Alarme brauchen eine Sonderberechtigung, die Android 14 standardmäßig verweigert, die Nachricht kann im Ruhezustand Minuten später kommen), `ReminderReceiver` (zeigt und plant den nächsten Tag), `BootReceiver` (Neustart/App-Update), `ChatViewModel.saveConfig`/`init` setzen den Alarm, "Nachricht jetzt testen" in den Einstellungen. Geprüft: JVM-Tests (`ReminderTest`), Gerätetest `ReminderOnDeviceTest` auf dem Emulator (Alarm beim System per `dumpsys alarm` registriert und wieder entfernt, Benachrichtigung mit Titel/Text/Antippen-Intent). **Falle:** `AlarmManager.cancel` entfernt das `PendingIntent` nicht, dafür `pending.cancel()`; `dumpsys alarm` führt auf Android 15 abgebrochene Alarme im Verlauf (`alarm_cancelled`), der Test zählt nach dem Abbrechen deshalb nicht mehr. NICHT geprüft: echte Zustellung zur Uhrzeit auf dem S21 (Samsung-Akkuoptimierung), Berechtigungsdialog ab Android 13. Phase 3 (Kalender), 4 (Wecker/Timer) stehen noch aus.
  `Antworten vorlesen` ist standardmäßig aus (Ursache für „Sprachausgabe fehlt“). **Vorwärmen** (`ChatViewModel.warmUp`, nach `reloadTools`/`saveConfig`): ein
  1-Token-Lauf über den bisherigen Verlauf füllt den Präfix-Cache; ohne ihn dauert die erste Antwort nach App-Start ~2-3 min (Prompt ~10 Tokens/s, 4 Threads).
  **Dokumente hochladen** (`core/Documents.kt`, `app/PdfText.kt`, `ChatViewModel.loadDocument/docAsInput/docRead/docSummarize`, `DocCard` in `ChatScreen`): TXT/MD (UTF-8, sonst Windows-1252), DOCX
  (Zip + `word/document.xml`, ohne Bibliothek), PDF per `pdfbox-android` (MIME-Filter bewusst `*/*`: Dateimanager melden .md mit unbekanntem Typ; Format am Namen). Die Rückfrage stellt die App, nicht das
  Modell (4B-Modell unzuverlässig, Text soll nicht unnötig in den Kontext). Budget `ChatSession.documentBudgetTokens` = Fenster − Antwort − System-Prompt − 150; `Documents.fit` kürzt an Absatz/Satz.
  Vorlesen in Abschnitten (`SpeechOutput.readAloud`, 600 Zeichen, zwei Abschnitte Vorlauf, Fortschritt je Abschnitt). **Android-Regex = ICU:** literale `}` müssen als `\}` stehen (Absturz beim HTTP-MCP-Start).
  **Bilder** (`local/VisionLlm.kt`, `cpp/vision_jni.cpp`, `ImagePrep.kt`): Chat-Modell bleibt reiner Text; ein kleines Bildmodell (SmolVLM-256M, `libmtmd` aus llama.cpp, CMake `LLAMA_BUILD_MTMD=ON`)
  beschreibt das Bild, die Beschreibung läuft über dieselbe Rückfrage-Karte (`PendingDoc.image`). Modell + mmproj werden per Auswahldialog in `filesDir/vision/` kopiert (klein; `mtmd` lädt nur über Pfade),
  geladen wird nur pro Beschreibung. Prompt neutral (`IMAGE_PROMPT`: nur Sichtbares, nichts erfinden, keine Listen), `Documents.completeSentences` schneidet abgebrochene Sätze ab. Gemessen: 11 bis 28 s je Bild (256M und 500M fast gleich), Qualität grob.
  Der Bild-Pfad ist nur von Hand auf dem Gerät geprüft (Modelldateien ~280 MB, nicht im Repo), kein automatischer Gerätetest.
  **Vorleser** (`core/Reader.kt` = reine Zustandsmaschine mit Fakes testbar, `ReadPlan`/`Readable.kt` für Seiten/Bilder, `speech/ReaderSupport.kt` Tonfokus + Bildbeschreibung,
  `ChatViewModel` verdrahtet): Abschnitte à 400 Zeichen, nächster Abschnitt wird schon angehängt (keine Lücke), Callback-IDs `r:<Generation>:<Index>` gegen späte Rückmeldungen;
  Pause = Stopp + Position (Android-TTS kann nicht pausieren). Marken `ReadMarks` (`reading_marks.json`), Datei über `takePersistableUriPermission`. EPUB = Zip + OPF-Spine,
  HTML-Parser mit Regex (kein XmlPullParser, JVM-testbar), Bilder nach dem Absatz. Sprache `LanguageDetect` (Kosinus über Buchstabenprofile). Tonfokus `ReaderFocus`
  (`setWillPauseWhenDucked`). **Schlüsselwörter** `core/Keywords.kt` + `ToolSpec.keywords` (nur Android; Eingabe mit Treffer geht NICHT ans Modell; Eintrag = Text oder `{"pattern","args"}` mit festen Argumenten, `Keyword`). **Import aus CSV/XLSX** (`core/KeywordImport.kt`, `Spreadsheet.kt`, Werkzeuge-Bildschirm):
  `Wort;Werkzeug;Parameter…`, Wort klein, doppelte weg (erste Zeile gewinnt), ohne Werkzeug/unbekannter Alias ignoriert, gleiche Werkzeug+Parameter → ein Ausdruck mit Lookarounds statt `\b`;
  ersetzt die Schlüsselwörter der genannten Werkzeuge in `mcp_servers.json` (Vorschau-Dialog), xlsx ohne Bibliothek (Zip + Regex, erstes Blatt laut workbook.xml), `.xls` wird abgelehnt. Gerätetest `DocumentsOnDeviceTest`
  prüft die Regexe unter ICU. Auf dem Gerät von Hand geprüft: Tasten (+1/+10/-10/-1), Pause/Play/Stopp, Marke nach Neustart (Menü), gelöschte Datei (Fehler + Marke weg, andere Marken bleiben), Sprache erkannt
  (Französisch trotz englischem Companion → Dialog), EPUB mit zwei Bildern (Seiten 1/2, Beschreibungen vorgelesen), Schlüsselwort "wetter in" (Werkzeug ohne Modell). NICHT geprüft: Anruf-/Audio-Pause
  (`ReaderFocus`, nur über die Zustandsmaschine getestet), Sprechgeschwindigkeit-Einstellung, HTML auf dem Gerät. **Kotlin-Falle:** `init {}` muss am ENDE von `ChatViewModel` stehen (sonst sind spätere
  Eigenschaften beim Aufruf aus init noch null: Absturz beim Start, kam erst beim Test auf dem Gerät heraus). Bildbeschreibung: "7 Sätze" erzwingen die kleinen Modelle nicht (Gliederung mit Gesicht/Kleidung erfindet Personen: entfernt; ohne Gliederung kommen 1 bis 3 Sätze,
  mit "keine Listen" kein erfundener Text mehr). SmolVLM-500M ist bei gleicher Dauer treffender als 256M (`VisionOnDeviceTest`, nur Protokoll). **Zierbilder** (`core/Decorative.kt`): `role="presentation"`/`aria-hidden`, dasselbe Bild ≥3x im Dokument (schon im `ReadPlan`), beim Laden Dateikopf (<2 KB, Kante <100 px, Seitenverhältnis >6:1) → `ReaderPictures.SKIP`,
  weder Beschreibung noch Ersatztext. Nicht erkannt: Rahmen als CSS-Hintergrund (kein `<img>`, Inline-SVG wird ohnehin verworfen) und große Zierbilder. Der Vorleser bestellt Bilder `Reader.PREFETCH_ITEMS` (5) Elemente vorab, höchstens `PREFETCH_PICTURES` (2) auf einmal;
  wartende Bestellungen prüfen vor dem Laden `cancelled` (sonst rechnen sie nach einem Sprung unnötig weiter).
  Offen: Abbruch beim App-Wechsel gemeldet (nicht reproduzierbar, nur der Stopp-Knopf ruft `ChatEngine.cancel`); `stop()` und Activity-onStop/onDestroy loggen (Tag `AiChat`).
  Eigene Modelle (sherpa-onnx: Whisper tiny/base, Piper) wären der nächste Schritt; `small.pt` müsste für whisper.cpp erst ins ggml-Format.
- **Modell auf dem Handy** (`LlmBackend` im Kern: `ChatClient` = Server, `LocalLlm` = `llama.cpp` per JNI, `Config.backend`). Wichtig:
  Dateien aus dem Auswahldialog kann `llama.cpp` NICHT über `/proc/self/fd/N` öffnen (Permission denied), deshalb `loadFd` mit
  `llama_model_load_from_file_ptr(FILE*)`; `use_extra_bufts=false`, sonst wird das Modell zusätzlich in den RAM umgepackt.
  KV-Cache-Wiederverwendung über den gemeinsamen Anfang der Tokens. Text immer als UTF-8-`byte[]` über JNI (Modified UTF-8
  verstümmelt Emojis). Der Wächter stoppt über `CancelToken.onStop` auch die native Schleife.

## Offene Aufgaben (Reihenfolge)
1. **Echter Test** mit einem Chat-Modell in LM Studio (kein TranslateGemma – das ist reiner Übersetzer);
   Ablauf in `docs/TEST_LMSTUDIO.md`:
   Streaming, Reasoning-Anzeige, Stop-Sequenzen, Du/Ich-Verhalten, Kontext-Anzeige. NEU zu prüfen: wirken
   Sampling + Bereinigung gegen die Wiederholungen, schreibt das 4B-Modell den Marker `[[mcp:…]]` zuverlässig
   (Prompt-Abschnitt in `ToolRegistry.prompt_section`, `hint`/`example` pro Tool), echter MCP-Server (z. B. Websuche).
2. **Kontext-Strategie** in `context.py` ausbauen: alte Runden zusammenfassen (Summary im System-Prompt), feste
   Erinnerungen behalten, Zusammenfassung ggf. durch das Modell selbst.
3. **Profile** für lokal/online umschaltbar (URL, Token, Modell pro Profil; Token nicht im Klartext ins Repo).
4. MCP: Rückfrage nur für nicht schreibgeschützte Tools (`readOnlyHint` wird schon gelesen).
5. Stimme (Sprachausgabe) pro Companion.

## Aufbau
`chat_app/`: `config.py` · `persona.py` · `context.py` · `session.py` (Verlauf, baut Anfrage) · `llm_client.py`
(SSE-Streaming) · `reply.py` · `tools.py` · `mcp_client.py` · `mcp_http.py` · `chatlog.py` · `avatar.py` · `persona_editor.py` ·
`gui.py` (Streaming-/Werkzeug-Thread → Queue → GUI). `servers/`: eigene MCP-Server (`mini_mcp.py`, `weather_server.py`,
`websearch_server.py`). Nicht getestet: echte LM-Studio-Antworten, echte Fernbedienungs-Bridge.
