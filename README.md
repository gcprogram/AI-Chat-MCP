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
Desktop, `mcpServers` oder `servers`; nur stdio-Server). Pro Werkzeug kann ein kurzer deutscher `alias`, `hint`,
`example` und die sichtbaren `args` angegeben werden – je einfacher, desto zuverlässiger das kleine Modell.
Dann in **Companions…** die Werkzeuge pro Companion ankreuzen; sie erscheinen im Prompt mit Beispielzeile. Die zweite
Checkbox **ohne Rückfrage** genehmigt ein Werkzeug für diesen Companion vorab (`approved_tools` in der Persona-Datei):
Es läuft dann ohne Dialog, auch bei vielen Aufrufen hintereinander (z. B. TV-Programme durchschalten). Nicht genehmigte
Werkzeuge fragen weiter nach (globale Einstellung bzw. `confirm` des Alias); die Vorab-Genehmigung gewinnt gegen beides.
`examples/demo_mcp_server.py` (Uhrzeit, Rechnen) dient zum Ausprobieren.

**Namen und Beschreibungen sind englisch** (Alias, `hint`, `example`, Parameter, Prompt-Abschnitt "Tools:"): kleine Modelle folgen englischen Anweisungen zuverlässiger, und der Aufruf bleibt unabhängig von der Chat-Sprache.

### Aliase (Kurznamen)

Je kürzer und einfacher das, was das Modell schreiben muss, desto zuverlässiger. Im Block `aliases` von
`mcp_servers.json` wird ein Kurzname auf einen echten MCP-Aufruf abgebildet; das Modell sieht nur den Kurznamen:

```json
"aliases": {
  "fan-low": {"server": "remote", "tool": "send", "args": {"code": "01"},
              "hint": "Switch the fan on, low speed", "result": "discard", "status": "Fan: low"},
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
| `result` | `model` (Standard): Ergebnis geht ans Modell, das darauf antwortet · `show`: Klartext im Chat, keine zweite Modellrunde (spart Kontext, nichts wird verfälscht) · `discard`: Ergebnis verwerfen, nur Statuszeile `status` |
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
- Fernbedienung: die Bridge aus dem Projekt Broadcast-Remote ist selbst ein stdio-MCP-Server (`status`, `buttons`,
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

## Roadmap

- Kontext-Strategie ausbauen: alte Runden zusammenfassen statt nur abschneiden, feste „Erinnerungen“.
- MCP: HTTP-Transport, keine Rückfrage für schreibgeschützte Tools (`readOnlyHint`).
- Umschalten zwischen lokalem und Online-Modell (Profile).
- Stimme (Sprachausgabe) pro Companion; evtl. Bild oder Video.
