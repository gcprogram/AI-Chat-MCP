# Erster echter Test mit LM Studio

Bisher getestet wurde nur gegen einen Fake-Server. Diese Anleitung prüft, was damit nicht prüfbar ist: wie sich ein
echtes 4B-Modell verhält. Dauer: etwa 30 Minuten. Reihenfolge einhalten – jeder Schritt baut auf dem vorigen auf.

## 1. Vorbereitung

**LM Studio**
1. Ein **Chat-Modell** laden (kein TranslateGemma, das ist nur ein Übersetzer). Für Schritt 6 zusätzlich ein Modell mit
   Tool-Unterstützung (in LM Studio mit einem Hammer-Symbol gekennzeichnet; z. B. Qwen3 oder Llama 3.x Instruct).
2. Beim Laden das **Kontextfenster auf 8192** stellen (so rechnet der Client).
3. Im Reiter *Developer* den Server starten und einen **API-Token** anlegen.

**Programm**
```bash
python -m venv .venv && .venv\Scripts\activate && pip install -r requirements.txt
python main.py
```
*Einstellungen*: Server-URL `http://localhost:1234/v1`, API-Schlüssel = der Token, **Modelle laden**, Modell wählen,
Speichern. Den Token nie in eine Datei im Repo oder in einen Chat schreiben.

## 2. Einfacher Chat (ohne Werkzeuge)

Companion `companion`, Werkzeuge noch nicht aktiv. Schreibe 8 bis 10 Nachrichten, darunter eine offene Frage wie
„Wie stellst du dir die Zukunft mit KI vor?“, und beobachte:

| Prüfpunkt | In Ordnung, wenn … |
|---|---|
| Streaming | Text erscheint schrittweise, **Stopp** bricht ab und die Teilantwort bleibt im Verlauf |
| Rollen | Das Modell spricht dich mit „du“ an und schreibt nie Zeilen für dich (`Alex: …`) |
| Länge | meist 1 bis 4 Sätze, kein Monolog |
| Wiederholungen | keine immer gleiche Schlussfrage, keine doppelten Sätze; im Chat steht die **bereinigte** Fassung |
| Reasoning | bei Denk-Modellen erscheint „Denkt nach…“, der Verlauf enthält es nicht |
| Kontext | die Anzeige unten rechts wächst plausibel; ab etwa 7000 Tokens meldet sie „ältere Nachrichten ausgeblendet“ |

Auffälligkeiten notieren und bei Bedarf in den *Einstellungen* nachstellen: mehr Wiederholungen → Frequency-Penalty
0,5 und Repeat-Penalty 1,2; zu steif → Temperatur etwas höher.

## 3. Werkzeuge einrichten

```bash
copy examples\mcp_servers.example.json mcp_servers.json
```
Programm neu starten; unten steht „Werkzeuge bereit: …“ (Websuche und Wetter brauchen Internet). In **Companions…**
beim Companion die Werkzeuge `weather`, `weather-past`, `websearch`, `time`, `calc` ankreuzen, Werkzeug-Modus
**Textmarker**, Speichern. Neuer Chat.

In `mcp_servers.json` unter `defaults` statt „Berlin“ deinen eigenen Ort eintragen (die Datei ist gitignored).

## 4. Textmarker-Modus (der eigentliche Test für das 4B-Modell)

Jeweils eine frische Nachricht senden und im Chat nach der grünen Zeile `» Werkzeug …` suchen:

| Eingabe | Erwartet | Modus |
|---|---|---|
| „Wie spät ist es?“ | `» Werkzeug time`, dann Antwort mit Uhrzeit | model |
| „Was ist 17 mal 23?“ | `» Werkzeug calc: 17*23`, Antwort mit 391 | model |
| „Wie wird das Wetter morgen?“ | `» Werkzeug weather: …`, danach die Tabelle im Klartext, **keine** zweite Modellantwort | show |
| „Wie war das Wetter am 25.11.2020?“ | `» Werkzeug weather-past: …`, Wetterblock | show |
| „Und gestern?“ direkt danach | das Modell nutzt Ort und Zusammenhang der angezeigten Tabelle | show + Verlauf |
| „Such mal nach aktuellen KI-Nachrichten.“ | `» Werkzeug websearch: …`, Modell fasst Treffer zusammen | model |
| „Erzähl mir einen Witz.“ | **kein** Werkzeug | Gegenprobe |

Pro Zeile je 3 Versuche und zählen, wie oft es klappt. Typische Fehler und was man tut:

| Beobachtung | Wahrscheinliche Ursache | Gegenmittel |
|---|---|---|
| Modell schreibt das Ergebnis selbst („Es sind 391“) ohne Aufruf | Prompt-Abschnitt wird ignoriert | `hint`/`example` des Werkzeugs kürzer und konkreter; Temperatur senken |
| Falscher Name oder falsche Argument-Reihenfolge | zu viele Werkzeuge im Prompt | pro Companion nur 2 bis 4 Werkzeuge freischalten |
| `[Fehler bei …] Es fehlt: …` | Argument vergessen | `defaults` im Alias setzen (macht das Argument optional) |
| Ruft bei jeder Nachricht ein Werkzeug | Beispiele wirken zu stark | `example` weglassen oder neutraler formulieren |
| Aufruf mit Markdown/Anführungszeichen | Parser ist tolerant, meist unproblematisch | nur melden, wenn ein Aufruf nicht erkannt wird |
| Bestätigungsdialog nervt | globale Einstellung | pro Alias `"confirm": false` (Wetter/Suche sind lesend) |

Danach: **Chat speichern…**, **Neuer Chat**, **Chat laden…** – die Werkzeuge dürfen dabei **nicht** erneut laufen
(kein Dialog, keine neue Anfrage).

## 5. Rückfrage und Limit

- „Vor jedem Werkzeugaufruf nachfragen“ in den *Einstellungen* anschalten, „Nein“ klicken → das Modell bekommt die
  Ablehnung und soll sich entsprechend äußern.
- „Max. Werkzeugaufrufe pro Nachricht“ auf 1 stellen und eine Frage stellen, die zwei Aufrufe nahelegt → Meldung
  „Limit für Werkzeugaufrufe erreicht“, keine Endlosschleife.

## 6. Nativer Modus

Nur mit einem Modell mit Tool-Unterstützung. Im Companion-Editor den **Werkzeug-Modus** auf *Nativ* stellen,
Speichern, Neuer Chat, dieselbe Tabelle wie in Schritt 4 durchgehen. Erwartet: dieselben `» Werkzeug …`-Zeilen.

| Beobachtung | Ursache / Gegenmittel |
|---|---|
| Fehlermeldung vom Server beim Senden | Modell/Template unterstützt `tools` nicht → anderes Modell oder Textmarker |
| Keine Zeile `» Werkzeug`, Modell antwortet nur mit Text | Modell nutzt die Funktionen nicht → Beschreibung (`hint`) präziser; anderes Modell testen |
| Aufruf erscheint, aber „Es fehlt: …“ | Modell lässt Pflichtparameter weg → `defaults` setzen |
| Antwort bricht nach dem Aufruf ab oder ist leer | Template-Problem mit `tool`-Nachrichten → in LM Studio Prompt-Template des Modells prüfen |

Beide Modi mit demselben Modell vergleichen und die Trefferquote notieren – das entscheidet, welcher Modus der
Standard für kleine Modelle wird.

## 7. Fernbedienung (optional, nur mit Hardware)

Der Beispieleintrag `remote` ist deaktiviert und ungeprüft. Zum Testen: App auf dem Handy mit eingeschalteter
Fernsteuerung, Umgebungsvariable `BROADCAST_REMOTE_TOKEN` setzen (nicht in die Datei), in `mcp_servers.json`
`"disabled": true` entfernen und die Pfade/URL anpassen, Companion die `tv-…`-Aliase freischalten. Prüfen:
Statuszeile `✔ TV: …` erscheint, das Gerät reagiert, ein Bestätigungsdialog kommt (Standard).
Ob Tool- und Parametername (`send`/`code`) zur Bridge passen, steht erst nach diesem Test fest.

## 8. Ergebnis festhalten

Notiere pro Modell: Trefferquote Marker (Schritt 4) und nativ (Schritt 6), auffällige Wiederholungen, Länge der
Antworten, Kontextverbrauch. Die Ergebnisse gehören in `CLAUDE.md` unter „Offene Aufgaben 1“, damit die nächsten
Schritte (Prompt-Feinschliff, Standardmodus, Kontext-Zusammenfassung) darauf aufbauen.
