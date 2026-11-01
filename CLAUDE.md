# AI-Chat-MCP – Projektkontext für Claude

Offline-Chat-Client (Tkinter) mit Persönlichkeit/Avatar-Companion gegen LM Studio oder einen anderen
OpenAI-kompatiblen Endpunkt. Lokale Modelle haben nur ca. 8192 Token Kontext. Abgespalten aus einem früheren
kombinierten Translator/Chat-Client (Original zuletzt im Repo `gcprogram/Python`, Ordner `AIChat-Client`,
Commit `217b144`). Der Übersetzer lebt jetzt in `gcprogram/AI-Translator`.

## Regeln des Besitzers
- **Kein Name, keine E-Mail im Code, in Commits, READMEs oder Kommentaren.** Git-Identität ist die
  GitHub-noreply-Adresse (global gesetzt) – nicht ändern. Vor jedem Push `git log --format='%an %ae'` prüfen.
- Commits nur auf Wunsch; Commit-Nachrichten englisch, Code-Kommentare/UI deutsch.
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
set PYTHONPATH=. && python -m unittest discover -s tests      # 14 Tests inkl. GUI-End-to-End mit Fake-Server
python main.py
```

## Offene Aufgaben (Reihenfolge)
1. **Echter Test** mit einem Chat-Modell in LM Studio (kein TranslateGemma – das ist reiner Übersetzer):
   Streaming, Reasoning-Anzeige, Stop-Sequenzen, Du/Ich-Verhalten der Persona, Kontext-Anzeige. Prompt in
   `persona.py` nach echten Ergebnissen nachschärfen.
2. **Kontext-Strategie** in `context.py` ausbauen: aktuell nur „älteste Runden weglassen“ mit kalibrierter
   Token-Schätzung. Ziel: alte Runden zusammenfassen (Summary-Nachricht im System-Prompt), feste Erinnerungen
   (Fakten über den Nutzer) behalten, Zusammenfassung ggf. durch das Modell selbst.
3. **MCP**: Modell soll Tools auslösen können – MCP-Client im Programm (Tool-Liste → `tools` in der Anfrage,
   `tool_calls` auswerten, Ergebnis zurückspielen).
4. **Profile** für lokal/online umschaltbar (URL, Token, Modell pro Profil; Token nicht im Klartext ins Repo).
5. Stimme (Sprachausgabe) und Profilbild/Avatar pro Persona (Felder in der Persona-JSON), evtl. Bild/Video.

## Aufbau
`chat_app/`: `config.py` · `persona.py` · `context.py` · `session.py` (Verlauf, baut Anfrage) · `llm_client.py`
(SSE-Streaming) · `gui.py` (Streaming-Thread → Queue → GUI). Nicht getestet: echte LM-Studio-Antworten.
