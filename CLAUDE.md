# AI-Chat-MCP – Projektkontext für Claude

Offline-Chat-Client (Tkinter) mit Persönlichkeit/Avatar-Companion gegen LM Studio oder einen anderen
OpenAI-kompatiblen Endpunkt. Lokale Modelle haben nur ca. 8192 Token Kontext. Abgespalten aus einem früheren
kombinierten Translator/Chat-Client (Original zuletzt im Repo `gcprogram/Python`, Ordner `AIChat-Client`,
Commit `217b144`). Der Übersetzer lebt jetzt in `gcprogram/AI-Translator`.

## Regeln des Besitzers
- **Kein Name, keine E-Mail im Code, in Commits, READMEs oder Kommentaren.** Git-Identität ist die
  GitHub-noreply-Adresse (global gesetzt) – nicht ändern. Vor jedem Push `git log --format='%an %ae'` prüfen.
- Bei grünen Tests sofort committen (ohne Rückfrage), bei roten nicht; sonst nur auf Wunsch. Nie pushen ohne
  Auftrag. Commit-Nachrichten englisch, Code-Kommentare/UI deutsch.
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
set PYTHONPATH=. && python -m unittest discover -s tests      # 42 Tests inkl. GUI-End-to-End mit Fake-LLM-Server und echtem MCP-Demo-Server
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
  (`tools`). Chats: `chatlog.py` (JSON vollständig, TXT lesbar/ladbar), Ordner `chats/` (gitignored).
- `mcp_servers.json` ist gitignored (kann Schlüssel enthalten); Vorlage in `examples/`.

## Offene Aufgaben (Reihenfolge)
1. **Echter Test** mit einem Chat-Modell in LM Studio (kein TranslateGemma – das ist reiner Übersetzer):
   Streaming, Reasoning-Anzeige, Stop-Sequenzen, Du/Ich-Verhalten, Kontext-Anzeige. NEU zu prüfen: wirken
   Sampling + Bereinigung gegen die Wiederholungen, schreibt das 4B-Modell den Marker `[[mcp:…]]` zuverlässig
   (Prompt-Abschnitt in `ToolRegistry.prompt_section`, `hint`/`example` pro Tool), echter MCP-Server (z. B. Websuche).
2. **Kontext-Strategie** in `context.py` ausbauen: alte Runden zusammenfassen (Summary im System-Prompt), feste
   Erinnerungen behalten, Zusammenfassung ggf. durch das Modell selbst.
3. **Profile** für lokal/online umschaltbar (URL, Token, Modell pro Profil; Token nicht im Klartext ins Repo).
4. MCP: HTTP-Transport; Rückfrage nur für nicht schreibgeschützte Tools (`readOnlyHint` wird schon gelesen).
5. Stimme (Sprachausgabe) pro Companion.

## Aufbau
`chat_app/`: `config.py` · `persona.py` · `context.py` · `session.py` (Verlauf, baut Anfrage) · `llm_client.py`
(SSE-Streaming) · `reply.py` · `tools.py` · `mcp_client.py` · `chatlog.py` · `avatar.py` · `persona_editor.py` ·
`gui.py` (Streaming-/Werkzeug-Thread → Queue → GUI). Nicht getestet: echte LM-Studio-Antworten.
