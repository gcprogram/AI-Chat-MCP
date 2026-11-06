# AI-Chat-MCP

Offline-Chat-Client mit „Persönlichkeit“ (Companion) für lokale Modelle über LM Studio – oder jeden
OpenAI-kompatiblen Endpunkt (die Server-URL ist frei einstellbar, daher auch Online-Dienste).

## Start

```bash
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python main.py
```

In den Einstellungen die Server-URL (z. B. `http://127.0.0.1:1234/v1`) und den API-Token eintragen (LM Studio verlangt ihn: Developer-Einstellungen),
„Modelle laden“, Modell wählen. Enter sendet, Shift+Enter ist ein Zeilenumbruch.

## Companions (Persönlichkeiten)

Jede Datei in `personas/*.json` ist ein Companion (`name`, `description`, `language`, `greeting`, `rules`, `avatar`,
`tools`). Über **Companions…** werden sie im Programm angelegt, bearbeitet, gespeichert und gelöscht; das
Auswahlfeld in der Werkzeugleiste wechselt den aktiven Companion. Das **Avatar-Bild** (PNG/GIF, mit Pillow auch
JPG/WebP) wird nach `personas/avatars/` kopiert und oben rechts angezeigt; ohne Bild gibt es einen Platzhalter.
Aus den Feldern entsteht der System-Prompt (`chat_app/persona.py`): Er legt fest, wer „ich“ und wer „du“ ist,
verbietet Protokollzeilen, Monologe und Floskeln und wird bei jeder Anfrage mitgesendet, aber nie angezeigt.

## Wiederholungen bei kleinen Modellen

Kleine Modelle (4B) übernehmen ihre eigenen Formulierungen aus dem Verlauf und hängen jeder Antwort dieselbe
Schlussfrage an. Dagegen wirken mehrere Ebenen zusammen:

1. **Sampling** (Einstellungen): `top_p`, `frequency_penalty`, `presence_penalty`, `repeat_penalty`.
   `repeat_penalty` ist eine LM-Studio-Erweiterung – bei Online-Diensten auf 0 setzen (wird dann nicht gesendet).
2. **Prompt**: Regeln gegen Floskeln, immer gleiche Schlusssätze und Markup.
3. **Bereinigung** (`chat_app/reply.py`): Bevor eine Antwort im Verlauf landet, werden Sätze gestrichen, die schon in
   dieser oder den letzten Antworten standen (Wortfolgen-Ähnlichkeit), Markup-/Rollenfragmente (`<h1>User:`) samt
   Rest abgeschnitten, Textschleifen gekürzt und abgebrochene Sätze entfernt. So sieht das Modell seine
   Wiederholungen nicht mehr und macht sie nicht nach. Erkennbare Schleifen brechen schon beim Streamen ab.

## Chats speichern und laden

**Chat speichern…** schreibt JSON (vollständig, inkl. Companion und Werkzeugaufrufen) oder Text („Name: Text“) nach
`chats/`. **Chat laden…** liest beides (auch ältere Text-Exporte); der passende Companion wird gewählt. Geladene
Werkzeugaufrufe sind reiner Verlauf und werden **nie** erneut ausgeführt. Eine unbeantwortete letzte Frage landet
im Eingabefeld.

## Werkzeuge (MCP)

Kleine Modelle beherrschen kein Function-Calling. Stattdessen steht im System-Prompt, dass das Modell für einen
Aufruf **eine Zeile in diesem Format** schreiben soll:

```
[[mcp:NAME|ARGUMENT]]            Ich suche kurz. [[mcp:web-search|KI Nachrichten heute]]
[[mcp:NAME|ARG1|ARG2]]           mehrere Argumente mit | trennen (Reihenfolge wie in der Werkzeugliste)
[[mcp:NAME]]                     ohne Argumente
```

Das Programm beobachtet die Ausgabe: Beim ersten vollständigen Marker wird die Antwort abgebrochen (alles danach
wäre ein vom Modell erfundenes Ergebnis), das MCP-Werkzeug ausgeführt und das Ergebnis als nächste Nachricht
zurückgegeben; das Modell antwortet dann darauf (höchstens „Max. Werkzeugaufrufe“ pro Nachricht). Fehler (unbekanntes
Werkzeug, fehlendes Argument) gehen ebenfalls als Ergebnis zurück, damit das Modell es neu versuchen kann.
Der Parser ist tolerant: Groß-/Kleinschreibung, `_` = `-`, Anführungszeichen, `key=value`, JSON-Objekt,
`[mcp/name:{...}]`. Ergebnisse werden gekürzt (`tool_result_chars`), Marker darin entschärft (kein Aufruf per
Webseiten-Inhalt), und standardmäßig fragt das Programm vor jedem Aufruf nach.

**Einrichtung**: `examples/mcp_servers.example.json` nach `mcp_servers.json` kopieren (Format wie bei Claude
Desktop, `mcpServers` oder `servers`; stdio-Server mit `command`, HTTP-Server mit `url`, siehe unten). Pro Werkzeug kann ein kurzer deutscher `alias`, `hint`,
`example` und die sichtbaren `args` angegeben werden – je einfacher, desto zuverlässiger das kleine Modell.
Dann in **Companions…** die Werkzeuge pro Companion ankreuzen; sie erscheinen im Prompt mit Beispielzeile. Die zweite
Checkbox **ohne Rückfrage** genehmigt ein Werkzeug für diesen Companion vorab (`approved_tools` in der Persona-Datei):
Es läuft dann ohne Dialog, auch bei vielen Aufrufen hintereinander (z. B. TV-Programme durchschalten). Nicht genehmigte
Werkzeuge fragen weiter nach (globale Einstellung bzw. `confirm` des Alias); die Vorab-Genehmigung gewinnt gegen beides.
`examples/demo_mcp_server.py` (Uhrzeit, Rechnen) dient zum Ausprobieren.

**Namen und Beschreibungen sind englisch** (Alias, `hint`, `example`, Parameter, Prompt-Abschnitt "Tools:"): kleine Modelle folgen englischen Anweisungen zuverlässiger, und der Aufruf bleibt unabhängig von der Chat-Sprache.

### HTTP-Server (Streamable HTTP)

Statt eines Unterprozesses (`command`) kann ein Eintrag einen MCP-Server über HTTP ansprechen (`url`) – z. B. im Heimnetz
oder die Fernbedienungs-App auf dem Handy direkt, ohne stdio-Bridge:

```json
"remote": {"url": "https://192.168.1.20:8443/mcp", "headers": {"Authorization": "Bearer ${BROADCAST_REMOTE_TOKEN}"},
           "cert": "~/.broadcast-remote/cert.pem", "raw": false}
```

| Feld | Bedeutung |
|---|---|
| `url` | Endpunkt des Servers (`https://…`; `http://` nur für localhost, Heimnetz-Adressen und `.local`) |
| `headers` | zusätzliche Header; `${NAME}` wird aus der Umgebungsvariable eingesetzt, der Token steht also nie in der Datei. Fehlt die Variable, steht ihr Name in der Fehlermeldung |
| `cert` | Zertifikat (PEM) eines selbst signierten Servers als einziger Vertrauensanker; ohne Angabe gelten die System-Zertifikate |

**Handy per USB** (z. B. im Hotel-WLAN, wo Geräte sich nicht sehen): `adb -s <Seriennummer> forward tcp:8443 tcp:8443`, dann
`"url": "https://127.0.0.1:8443/mcp"`. Das Zertifikat der Fernbedienungs-App enthält `127.0.0.1`. Es wird beim Wechsel
der WLAN-Adresse neu erzeugt; bei „certificate verify failed“ das aktuelle holen (über den Forward
`ssl.get_server_certificate(("127.0.0.1", 8443))`, per Kabel also vertrauenswürdig) und als `cert` eintragen. Die
Forward-Regel gilt nur, solange adb läuft und das Handy angesteckt ist.

`raw`, `tools`, `disabled` und die Aliase funktionieren wie bei stdio. Antworten als JSON oder als SSE-Strom werden
verstanden; läuft die Sitzung auf dem Server ab (Neustart), meldet sich der Client einmal selbst neu an. Weiterleitungen
werden nicht verfolgt, damit der Token nicht an einen fremden Host geht. Verbindungsfehler (Handy aus, falsches WLAN)
erscheinen wie bei stdio in der Statuszeile; die übrigen Server laufen weiter.

### Aliase (Kurznamen)

Je kürzer und einfacher das, was das Modell schreiben muss, desto zuverlässiger. Im Block `aliases` von
`mcp_servers.json` wird ein Kurzname auf einen echten MCP-Aufruf abgebildet; das Modell sieht nur den Kurznamen:

```json
"aliases": {
  "tv-p1": {"server": "remote", "tool": "send", "args": {"code": "0x11"},
            "hint": "TV key P1", "result": "discard", "status": "TV: P1"},
  "weather": {"server": "weather", "tool": "forecast", "params": ["place", "day"], "defaults": {"place": "Berlin"},
              "hint": "Weather forecast for today or tomorrow", "result": "show", "confirm": false}
}
```

| Feld | Bedeutung |
|---|---|
| `server`, `tool` | echtes MCP-Werkzeug (Pflicht) |
| `args` | feste Argumente, die das Modell nicht sieht und nicht ändern kann |
| `params` | Argumente, die das Modell schreibt (Reihenfolge = Reihenfolge im Marker); ohne Angabe alle, die nicht in `args` stehen |
| `defaults` | Ersatz für fehlende Argumente (das Argument wird dadurch optional) |
| `hint`, `example` | Zeile im Prompt (je kürzer, desto weniger Kontext) |
| `result` | `model` (Standard): Ergebnis geht ans Modell, das darauf antwortet · `show`: Klartext im Chat, keine zweite Modellrunde (spart Kontext, nichts wird verfälscht) · `discard`: Ergebnis verwerfen, nur Statuszeile `status` · `both`: Klartext im Chat **und** das Modell bekommt das Ergebnis und antwortet darauf (Folgefragen wie „Brauche ich eine Jacke?“ klappen; bei `show` sieht ein kleines Modell nur eigene Aufrufe im Verlauf und ruft das Werkzeug erneut auf) |
| `remember` | Ergebnis (bei `discard` der Status) dem Modell mit der nächsten Nutzernachricht zeigen; Standard: `true` bei `show`, sonst `false` |
| `confirm` | `true`/`false` überstimmt die globale Rückfrage-Einstellung für diesen Alias |

Fehler eines Werkzeugs gehen immer ans Modell, egal welcher `result`. Pro Server blendet `"raw": false` die rohen
MCP-Werkzeuge aus (nur die Aliase sind dann sichtbar); Aliase auf Server mit `"disabled": true` werden übergangen.
Die Aliase erscheinen im Companion-Editor wie alle anderen Werkzeuge.

### Werkzeug-Modus pro Companion: Textmarker oder nativ

Im Companion-Editor (**Werkzeug-Modus**) wählst du, wie das Modell Werkzeuge aufruft:

- **Textmarker** (Standard, für kleine Modelle): der Prompt erklärt `[[mcp:NAME|ARG]]` (siehe oben).
- **Nativ**: die Werkzeuge gehen als Parameter `tools` der Chat-API (`/v1/chat/completions`) mit; das Chat-Template des
  Modells setzt sie in das Format ein, auf das das Modell trainiert ist. Das funktioniert nur mit Modellen, die
  Tool-Calling können (LM Studio kennzeichnet sie). Die Aliase halten die Beschreibung klein: feste Argumente
  (`args`) sehen Modell und Schema nicht, oft bleibt höchstens ein Parameter.

Alles andere bleibt gleich: Aliase, `result` (`model`/`show`/`discard`), `confirm`, Aufrufgrenze, nie erneut
ausführen beim Laden. Intern werden native Aufrufe im Verlauf und in gespeicherten Chats als lesbarer Marker
abgelegt (`Ich schaue nach. [[mcp:weather|Berlin]]`); für die Anfrage werden sie wieder zu `tool_calls` und
`tool`-Nachrichten, ausgeführt wird mit den echten JSON-Argumenten. Pro Antwort wird nur der erste Aufruf bearbeitet.
Ein Aufruf ohne Ergebnis (abgebrochen) wird beim nächsten Senden zu normalem Text. Lehnt der Server den Parameter
`tools` ab, erscheint seine Fehlermeldung – dann den Companion auf Textmarker stellen.

### Mitgelieferte Server (`servers/`, nur Standardbibliothek, ohne API-Schlüssel)

- `weather_server.py` (Alias `weather`, `weather-past`): `forecast` (today/tomorrow/day after tomorrow, stündlich, wttr.in) und
  `historical` (Open-Meteo-Archiv). Das Datum darf `yesterday`, `day before yesterday`, `2020-11-25`, `25.11.2020` oder
  `25.11.` heißen (deutsche Wörter wie `gestern` gehen auch) – das rechnet der Server, nicht das kleine Modell. Ein Ort
  wie `Langen` kann mehrdeutig sein: `Langen, Hesse` wählt beim Archiv den passenden Treffer. Ausgabe englisch.
- `websearch_server.py` (Alias `websearch`): `search` über die HTML-Ausgabe von DuckDuckGo (Titel, Adresse, Auszug). Das Seitenformat
  ist nicht garantiert; ändert es sich, muss nur `parse_results()` angepasst werden.
- Fernbedienung: entweder direkt per HTTP (siehe oben) oder über die Bridge aus dem Projekt Broadcast-Remote, die selbst ein stdio-MCP-Server (`status`, `buttons`,
  `send(code)`, `off`). Der Token gehört in die Umgebungsvariable `BROADCAST_REMOTE_TOKEN` (wird an den Server
  durchgereicht), nicht in die Datei. Der Beispieleintrag ist deaktiviert und nicht gegen echte Hardware geprüft.

## Kontext

Der Client ist zustandslos: jede Anfrage enthält System-Prompt und Verlauf vollständig. Passt der Verlauf nicht
mehr ins Kontextfenster (Einstellung, Standard 8192), werden die ältesten Runden weggelassen
(`chat_app/context.py`). Die Token-Schätzung kalibriert sich an den Werten, die der Server meldet.

## Aufbau

`chat_app/config.py` · `persona.py` · `context.py` · `session.py` (Verlauf) · `llm_client.py` (SSE-Streaming) ·
`reply.py` (Bereinigung) · `tools.py` + `mcp_client.py` (Werkzeuge) · `chatlog.py` (Speichern/Laden) · `avatar.py` ·
`persona_editor.py` · `gui.py` (Tkinter, Streaming-Thread über Queue). Tests: `python -m unittest discover -s tests` (mit `PYTHONPATH=.`).

## Android-App (`android/`)

Kotlin mit Jetpack Compose, gleiche Logik wie die Python-App: Companions (dieselben JSON-Dateien), Textmarker mit
Aliasen, Antwort-Bereinigung, Kontextverwaltung, Chat speichern/laden (gleiches JSON-Format), HTTP-MCP.
Das Modell läuft entweder in LM Studio (Server-URL und Token in den Einstellungen) oder **direkt auf dem Handy**
(Einstellungen → Ausführung → *Auf dem Gerät*, `llama.cpp` nur CPU, GGUF-Datei über den Dateiauswahldialog).

```bash
cd android
set JAVA_HOME=<JDK 17>            # JDK 25 (z. B. das von Android Studio) kann Kotlin 2.0 nicht lesen
gradlew :core:test :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- `android/core`: reine Kotlin-Logik ohne Android (49 JVM-Tests und 7 Geräte-Tests; Erwartungswerte aus der Python-Version).
- `android/app`: Oberfläche (Chat, Einstellungen, Companions, Werkzeuge) und Speicher im privaten App-Ordner.
- **Server erreichen**: im Heimnetz `http://<PC-IP>:1234/v1`; per USB `adb reverse tcp:1234 tcp:1234` und
  `http://127.0.0.1:1234/v1` (auch im Hotel-WLAN).
- **Werkzeuge**: Gleiches `mcp_servers.json` wie am PC (Menü *Werkzeuge*, auch Import aus Datei). Unterprozesse gibt es auf
  Android nicht: `weather_server.py`/`websearch_server.py` werden automatisch durch die eingebauten Server ersetzt
  (oder `"builtin": "weather"`/`"websearch"`), andere Server müssen per `url` (HTTP) erreichbar sein. `${NAME}` in Headern
  kommt aus den *Geheimnissen* der App, ein selbst signiertes Zertifikat wird dort per *Vom Server holen* (mit
  Fingerabdruck zum Vergleichen) oder aus einer PEM-Datei importiert und über `cert` (Dateiname) angegeben. Die
  Fernbedienungs-App auf demselben Handy ist unter `https://127.0.0.1:8443/mcp` erreichbar.
- **Modell auf dem Handy** (`android/app/src/main/cpp`, JNI zu `llama.cpp`, Version in `android/llama.cpp.version`, beim ersten
  Build per `git` nach `android/third_party/` geholt; NDK 28.2, CMake 3.22.1, nur `arm64-v8a`). Die GGUF-Datei wird nicht
  kopiert, sondern über ihren Dateideskriptor gelesen (gespeicherter Dateizugriff, überlebt Neustarts). Der Kontext ist auf
  4096 Tokens begrenzt. Der Anfang des Verlaufs bleibt im KV-Cache, nur Neues wird berechnet. Gemessen auf einem Galaxy S21
  (Exynos 2100, 4 Threads) mit einem 4B-Modell Q4_K_S: erste Antwort ca. 70 s (Modell wird eingelesen), danach ca. 1 s für
  neue Nachrichten im Cache, 4 bis 5 Tokens/s beim Schreiben, ca. 3 GB Arbeitsspeicher.
- **Sprache** mit den Google-Diensten des Geräts (keine eigene Modelldatei): Mikrofon-Knopf neben „Senden“ (beim ersten Mal wird die
  Mikrofon-Berechtigung abgefragt) und „Antworten vorlesen“ (Sprachsynthese). Die Erkennung nutzt gezielt den Google-Dienst
  (nicht den Hersteller-Erkenner) und versucht zuerst offline. Fehlt das Offline-Sprachpaket (Fehler 13), wird mit der
  Online-Erkennung von Google wiederholt, sofern der Schalter *Online-Erkennung erlauben* an ist (Standard); die Statuszeile zeigt beim Zuhören und nach dem Ergebnis, womit erkannt wurde (`offline` oder `online, Google`). Beides ist in den Einstellungen unter *Sprache* einstellbar; die Sprache folgt der Antwortsprache des
  Companions (`Deutsch` → `de-DE`). Werkzeugaufrufe werden nicht vorgelesen.
- **Offline-Sprachpakete** (Android 13+): Die Einstellungen zeigen unter *Offline-Sprachpakete* je Sprache, ob das Google-Paket
  installiert ist, und laden es per *Laden* herunter (Google fragt mit einem Dialog nach der Größe, ca. 50 bis 60 MB). Den Fortschritt
  meldet der Dienst nicht zuverlässig, die App prüft deshalb regelmäßig, bis das Paket da ist.
- **Stimmen (Sprachausgabe)**: Die Einstellungen zeigen je Sprache, ob eine Google-Stimme installiert ist, und sprechen mit *Anhören* einen
  Beispielsatz. *Stimmen laden…* öffnet die Stimmenverwaltung von Google. Automatisches Vorlesen der Antworten schaltet *Antworten vorlesen* ein (Standard: aus).
  **Stimme pro Companion**: Im Companion-Editor wählt *Stimme* eine der installierten Stimmen der Companion-Sprache (*Anhören* spielt sie vorab),
  gespeichert als `voice` in der Companion-Datei (leer = Standardstimme). Android nennt weder Geschlecht noch Vornamen, die Stimmen heißen
  daher *Stimme 1, 2, …* mit Sprache und offline/online; ausprobieren per *Anhören*. Die Stimme gilt für Antworten und Vorlesen, aber nur,
  wenn die Sprache passt (ein französischer Text wird nicht mit der deutschen Stimme gelesen).
- **Vorwärmen**: Mit dem lokalen Modell werden Modell und bisheriger Verlauf nach dem Start im Hintergrund vorberechnet (Status *Modell wird vorbereitet…*);
  sonst braucht die erste Antwort nach jedem App-Start mehrere Minuten (ca. 10 Prompt-Tokens/s).
- **Dokumente hochladen** (📎 neben dem Nachrichtenfeld): TXT, MD, DOCX, EPUB, HTML und PDF (PDF mit PdfBox für Android; ein Scan ohne Textebene
  liefert keinen Text und wird so gemeldet). Danach fragt die App (nicht das Modell) „Was soll ich damit machen?“: **1 · Als Eingabe
  einlesen** (Text und Dateiname landen im Eingabefeld, die Frage schreibt man dahinter), **2 · Vorlesen** (Abschnitt für Abschnitt mit
  Fortschritt und Stopp, ohne Modell und daher ohne Kontextgrenze) oder **3 · Zusammenfassen** (das Modell fasst zusammen). Das
  Kontextfenster ist klein: Einlesen und Zusammenfassen nehmen nur den Anfang, der hineinpasst (Fenster minus Antwort minus System-Prompt,
  bei 4096 Tokens ca. 2300), und die Karte sagt das. Lange Nutzernachrichten erscheinen im Chat gekürzt, das Modell bekommt den vollen Text.
- **Vorlese-Modus** (Karte → *Vorlesen*): Tasten `<<` `<` `■` `▶` `||` `>` `>>`. `<`/`>` springen einen Abschnitt (ca. 25 s Sprechzeit, das
  ist als Schritt klein genug und kostet keine Wartezeit), `<<`/`>>` eine **Seite** (PDF-Seiten, EPUB-Kapitel), ohne echte Seiten (TXT, MD, DOCX,
  HTML) 10 Abschnitte. Pause hält an der Stelle an, Play beginnt den Abschnitt von vorn (Android kann nicht mitten im Abschnitt anhalten).
  - **Vorlese-Marke**: Die Stelle wird je Text gespeichert (`reading_marks.json`, der Text über dauerhaften Dateizugriff wiedergefunden) und überlebt
    einen Neustart. Fortsetzen über die Karte (*Weiterlesen*, wenn dieselbe Datei wieder gewählt wird) oder im Menü ⋮ → *Weiterlesen: …*. Ist die
    Datei weg, gibt es eine Fehlermeldung und die Marke wird gelöscht. Marken anderer Texte bleiben. Ganz am Anfang oder am Ende gibt es keine Marke.
  - **Geschwindigkeit**: Einstellungen → *Sprechgeschwindigkeit* (Standard 1.0). Gemessen auf dem Galaxy S21 spricht die deutsche Google-Stimme etwa 18 % langsamer
    pro Zeichen als die englische (15,3 gegenüber 18,6 Zeichen/s; pro Wort ca. 35 %, deutsche Wörter sind länger). Für Deutsch passt oft 1.2.
  - **Anruf oder anderes Audio**: Das Vorlesen pausiert (Tonfokus) und geht danach von selbst weiter. Übernimmt eine andere App den Ton dauerhaft
    (Musik), bleibt es pausiert, bis du Play drückst. Läuft der Bildschirm aus, hängt es vom System ab, ob das Vorlesen weitergeht (kein Hintergrunddienst).
  - **Sprache des Textes**: wird über die Buchstabenhäufigkeit erkannt (Deutsch, Englisch, Französisch, Spanisch, Italienisch; Japanisch an der Schrift),
    nicht nach dem Companion; zu kurze oder unklare Texte nehmen die Sprache des Companions. Fehlt die Stimme, fragt ein Dialog (*Stimmen laden…* oder
    *Trotzdem vorlesen*).
  - **Bilder im Text** (EPUB, HTML mit eingebetteten Bildern): nach dem Absatz beschreibt das Bildmodell das Bild (schon beim Abschnitt davor
    bestellt, damit keine lange Pause entsteht), die Beschreibung (englisch) wird vorgelesen, dann geht der Text weiter. Ohne Bildmodell wird der
    Ersatztext (`alt`) gelesen. Relative Bilder einer einzelnen HTML-Datei sind nicht ladbar.
- **Bilder beschreiben** (📎, Bilddatei wählen): Das Chat-Modell kann keine Bilder sehen, deshalb beschreibt ein kleines **Bildmodell** (getestet mit
  SmolVLM-256M-Instruct Q8_0, ca. 175 MB, plus `mmproj-SmolVLM-256M-Instruct-Q8_0.gguf`, ca. 104 MB, beide von `ggml-org/SmolVLM-256M-Instruct-GGUF`)
  das Bild als Text. Beide Dateien in den Einstellungen unter *Bildmodell* wählen (werden in den App-Speicher kopiert; geladen wird nur
  während einer Beschreibung). Danach gilt dieselbe Rückfrage wie bei Dokumenten: *Als Eingabe einlesen* (die Frage dazu an den Companion
  schreibt man dahinter) oder *Vorlesen*. Die Beschreibung ist englisch und kurz; das Bild wird vorher auf 768 Pixel verkleinert.
  Gemessen auf dem Galaxy S21: ca. 12 bis 35 s je Bild (das erste Mal nach dem Installieren langsamer). Das Bildmodell ersetzt keine Texterkennung
  (Schrift im Bild liest es nur grob) und halluziniert Details. Der Prompt ist neutral (nur Sichtbares, nichts erfinden, keine Listen). Eine feste Gliederung
  (Gesicht, Kleidung, ...) hat bei Bildern ohne Person Dinge erfunden und wurde entfernt. 7 Sätze lassen sich mit den kleinen Modellen nicht erzwingen: Gemessen
  an 6 Bildern liefert SmolVLM-**500M** (`ggml-org/SmolVLM-500M-Instruct-GGUF`, Q8_0 437 MB + mmproj 109 MB) meist 1 bis 3 treffende Sätze in 11 bis 28 s
  (kaum langsamer als 256M) und liest einfachen Text im Bild korrekt ab; das 256M erfand dort Orte und Zahlen. Beide Modelle können Diagramme falsch deuten.
  **Zierbilder** beim Vorlesen (Rahmenteile, Trennlinien, Symbole) werden übersprungen, nicht beschrieben und ihr Ersatztext nicht vorgelesen: als Dekoration
  markiert (`role="presentation"`, `aria-hidden`), dasselbe Bild mindestens dreimal im Dokument, Datei unter 2 KB, kürzere Kante unter 100 Pixel oder
  Streifen über 6:1. Es werden höchstens zwei Bilder im Voraus berechnet. Nicht erkannt: Rahmen als CSS-Hintergrund (steht nicht als Bild im Text, wird also auch nie
  beschrieben) und große reine Zierbilder.
  Vergleich auf dem Gerät: `VisionOnDeviceTest` (Bilder und Modelle per `adb`/`run-as` nach `cache/vlm/`, siehe Kopf der Datei).
- **Schlüsselwörter** (Einstellungen → *Schlüsselwörter lösen Werkzeuge aus*, Standard aus): Ein Alias in `mcp_servers.json` kann
  `"keywords": ["\bprogramm\s*1\b", "channel one"]` haben (reguläre Ausdrücke, Groß-/Kleinschreibung egal; Gruppen werden zu Argumenten). Kommt ein
  Ausdruck in der Eingabe, in der Antwort des Modells, im vorgelesenen Text oder in einer Bildbeschreibung vor, läuft das Werkzeug ohne das Modell.
  Eine Eingabe mit Treffer geht nicht an das Modell. Es gelten Freigabe des Companions und Rückfrage; dasselbe Werkzeug löst höchstens alle 30 s
  aus. **Vorsicht:** Auch fremde Texte (Dateien, Bilder) können so Werkzeuge auslösen. Nur in der Android-App, die Python-App ignoriert `keywords`.
  Ein Eintrag kann auch feste Argumente tragen: `{"pattern": "wetter", "args": ["Paderborn", "morgen"]}` (ohne `args` werden die Gruppen des Ausdrucks zu Argumenten).
  **Liste importieren** (Werkzeuge → *Schlüsselwörter aus Liste*, CSV oder Excel `.xlsx`, erstes Blatt; Beispiel `examples/keywords.csv`): Eine Zeile je Wort,
  `Wort;Werkzeug;Parameter1;Parameter2;…` (Trennzeichen `;`, auch `,` oder Tabulator werden erkannt; eine Kopfzeile `Wort;Werkzeug` wird übersprungen).
  Das Wort wird klein geschrieben, ein doppeltes Wort fällt weg (bei verschiedenen Werkzeugen oder Parametern gilt die erste Zeile, die App meldet es),
  Zeilen ohne Werkzeug und Werkzeuge ohne Alias in `mcp_servers.json` werden ignoriert. Wörter mit demselben Werkzeug und denselben Parametern ergeben einen
  Ausdruck `(?<![\p{L}\p{N}])(?:wort1|wort2)(?![\p{L}\p{N}])` (nur ganze Wörter, Sonderzeichen entschärft, Leerraum beliebig lang); die Parameter sind feste Argumente,
  eine leere Zelle dazwischen bleibt ein leerer Parameter. Vor dem Übernehmen zeigt die App eine Vorschau; die bisherigen Schlüsselwörter **der Werkzeuge in der Liste**
  werden ersetzt (andere bleiben). Ein Werkzeug löst je Text höchstens einmal aus, bei mehreren passenden Zeilen gewinnt die erste.
- **Avatare**: Der Avatar des Companions erscheint oben im Chat und im Companion-Editor. Die App kann `AIChat/avatars` nicht direkt
  lesen; unter *Companions* kopiert *Avatare aus Ordner holen…* alle Bilder eines gewählten Ordners in den App-Speicher (Dateinamen wie am
  PC, damit importierte Companion-Dateien ihr Bild finden; Android fragt dabei einmal nach dem Ordnerzugriff). *Bild wählen…* setzt ein
  Einzelbild für einen gespeicherten Companion.
- Noch nicht in der App: nativer Werkzeug-Modus, Chat-Export als Text, eigene Sprachmodelle (Whisper/Piper).

## Roadmap

- Kontext-Strategie ausbauen: alte Runden zusammenfassen statt nur abschneiden, feste „Erinnerungen“.
- MCP: keine Rückfrage für schreibgeschützte Tools (`readOnlyHint`).
- Umschalten zwischen lokalem und Online-Modell (Profile).
- Stimme (Sprachausgabe) pro Companion; evtl. Bild oder Video.
