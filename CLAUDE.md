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
- Reasoning (`reasoning_content`) wird angezeigt, aber nie in den Verlauf gespeichert.
- LM Studio verlangt einen **API-Token** (Developer-Einstellungen) → in den Einstellungen eintragen
  (`config.json`, gitignored). Token niemals ins Repo oder in Chat-Ausgaben schreiben.
- Server-URL ist frei (inkl. `/v1`), damit Online-Dienste ohne Codeänderung gehen.

## Tests / Start
```bash
python -m venv .venv && .venv\Scripts\activate && pip install -r requirements.txt
set PYTHONPATH=. && python -m unittest discover -s tests      # 88 Tests inkl. GUI-End-to-End mit Fake-LLM-Server und echtem MCP-Demo-Server
python main.py
```

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
  (`tools`), davon vorab genehmigte ohne Rückfrage (`approved_tools`, schlägt globale Einstellung und Alias-`confirm`). Chats: `chatlog.py` (JSON vollständig, TXT lesbar/ladbar), Ordner `chats/` (gitignored).
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
- **Nativer Modus** (`Persona.tool_mode = "native"`, Schalter im Companion-Editor): Aliase gehen als `tools`-Parameter
  (`ToolRegistry.native_tools`, klein dank fester `args`). `llm_client` setzt `delta.tool_calls` zusammen und liefert
  `StreamEvent("tool_call")`; die GUI macht daraus per `ToolRegistry.native_call` einen Marker (Anzeige/Verlauf/Speichern
  bleiben wie im Marker-Modus) und führt mit den echten JSON-Argumenten aus (`ToolCall.arguments`, `ChatApp.native_args`).
  Erst beim Senden wandelt `ChatSession._wire_native` den Verlauf in `tool_calls`/`tool`-Nachrichten um (Aufruf ohne
  Ergebnis → Text; `tool_shown` → `tool`-Nachricht). Werkzeugbeschreibungen zählen in die Kontext-Schätzung. Nur der
  erste Aufruf pro Antwort. Ungeprüft: echtes Modell/LM Studio (nur Fake-Server-Tests).
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
  `Antworten vorlesen` ist standardmäßig aus (Ursache für „Sprachausgabe fehlt“). **Vorwärmen** (`ChatViewModel.warmUp`, nach `reloadTools`/`saveConfig`): ein
  1-Token-Lauf über den bisherigen Verlauf füllt den Präfix-Cache; ohne ihn dauert die erste Antwort nach App-Start ~2-3 min (Prompt ~10 Tokens/s, 4 Threads).
  **Dokumente hochladen** (`core/Documents.kt`, `app/PdfText.kt`, `ChatViewModel.loadDocument/docAsInput/docRead/docSummarize`, `DocCard` in `ChatScreen`): TXT/MD (UTF-8, sonst Windows-1252), DOCX
  (Zip + `word/document.xml`, ohne Bibliothek), PDF per `pdfbox-android` (MIME-Filter bewusst `*/*`: Dateimanager melden .md mit unbekanntem Typ; Format am Namen). Die Rückfrage stellt die App, nicht das
  Modell (4B-Modell unzuverlässig, Text soll nicht unnötig in den Kontext). Budget `ChatSession.documentBudgetTokens` = Fenster − Antwort − System-Prompt − 150; `Documents.fit` kürzt an Absatz/Satz.
  Vorlesen in Abschnitten (`SpeechOutput.readAloud`, 600 Zeichen, zwei Abschnitte Vorlauf, Fortschritt je Abschnitt). **Android-Regex = ICU:** literale `}` müssen als `\}` stehen (Absturz beim HTTP-MCP-Start).
  **Bilder** (`local/VisionLlm.kt`, `cpp/vision_jni.cpp`, `ImagePrep.kt`): Chat-Modell bleibt reiner Text; ein kleines Bildmodell (SmolVLM-256M, `libmtmd` aus llama.cpp, CMake `LLAMA_BUILD_MTMD=ON`)
  beschreibt das Bild, die Beschreibung läuft über dieselbe Rückfrage-Karte (`PendingDoc.image`). Modell + mmproj werden per Auswahldialog in `filesDir/vision/` kopiert (klein; `mtmd` lädt nur über Pfade),
  geladen wird nur pro Beschreibung. Prompt bewusst kurz (3 bis 5 Sätze), `Documents.completeSentences` schneidet abgebrochene Sätze ab. Gemessen: 12 bis 35 s je Bild, Qualität grob.
  Der Bild-Pfad ist nur von Hand auf dem Gerät geprüft (Modelldateien ~280 MB, nicht im Repo), kein automatischer Gerätetest.
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
