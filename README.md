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

In den Einstellungen die Server-URL (z. B. `http://127.0.0.1:1234/v1`), ggf. API-Schlüssel eintragen,
„Modelle laden“, Modell wählen. Enter sendet, Shift+Enter ist ein Zeilenumbruch.

## Persönlichkeit

Jede Datei in `personas/*.json` ist eine Persönlichkeit (`name`, `description`, `language`, `greeting`, `rules`).
Daraus entsteht der System-Prompt (`chat_app/persona.py`): Er legt fest, wer „ich“ und wer „du“ ist,
verbietet Protokollzeilen („Nutzer: …“) und Monologe und wird bei jeder Anfrage mitgesendet, aber nie angezeigt.
Zusätzlich werden Stop-Sequenzen und ein Antwort-Limit gesetzt, damit das Modell nicht das ganze Gespräch
selbst weiterschreibt.

## Kontext

Der Client ist zustandslos: jede Anfrage enthält System-Prompt und Verlauf vollständig. Passt der Verlauf nicht
mehr ins Kontextfenster (Einstellung, Standard 8192), werden die ältesten Runden weggelassen
(`chat_app/context.py`). Die Token-Schätzung kalibriert sich an den Werten, die der Server meldet.

## Aufbau

`chat_app/config.py` · `persona.py` · `context.py` · `session.py` (Verlauf) · `llm_client.py` (SSE-Streaming) ·
`gui.py` (Tkinter, Streaming-Thread über Queue). Tests: `python -m unittest discover -s tests` (mit `PYTHONPATH=.`).

## Roadmap

- Kontext-Strategie ausbauen: alte Runden zusammenfassen statt nur abschneiden, feste „Erinnerungen“.
- MCP: Das Modell soll Funktionen (Tools) auslösen können; MCP-Client im Programm.
- Umschalten zwischen lokalem und Online-Modell (Profile).
- Stimme (Sprachausgabe) und Profilbild/Avatar für die Persönlichkeit; evtl. Bild oder Video.
