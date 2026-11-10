# Organizer: Todos, Tagesupdate, Erinnerungen, Kalender, Wecker

Planung (gemeinsam mit dem Besitzer entschieden). Nur Android; die PC-App bleibt unverändert.

## Entscheidungen
- **Ein eingebauter MCP-Server `organizer`** (läuft im Prozess wie `weather`/`websearch`, `BuiltinServers.kt`). Das kleine Modell sieht nur kurze englische Aliase
  mit höchstens zwei Argumenten (`[[mcp:todo-add|Buy milk|tomorrow]]`). **Datum und Uhrzeit rechnet der Server**, nicht das Modell.
- **Erinnern ist keine Modell-Aufgabe:** Tagesupdate und Push sind App-Funktionen auf demselben Speicher (`todos.json`); ein 4B-Modell entscheidet nicht, ob du erinnert wirst.
- **Kalender:** direkt in den Gerätekalender schreiben (`CalendarContract`, Berechtigung `WRITE_CALENDAR`/`READ_CALENDAR`), **immer mit Rückfrage** (Titel und Zeit), weil ein
  falsch verstandenes Datum bei kleinen Modellen das größte Risiko ist.
- **Tagesupdate beim App-Start:** einmal pro Tag und Companion, **von der App zusammengestellt, ohne Modell** (offene Todos, heutige Termine). Es erscheint als angezeigtes
  Ergebnis (`tool_shown`, Name `daily-update`) und geht mit der nächsten Nutzernachricht als Kontext ans Modell. Schalter am Companion (`daily_update`).
- **Push:** tägliche Benachrichtigung zur gewählten Uhrzeit, Inhalt = Kurzübersicht (Zahl offener Todos, heutige Termine); Antippen öffnet den Chat. Braucht ab Android 13 die
  Berechtigung `POST_NOTIFICATIONS`.

## Phasen
1. **Todos + Tagesupdate** (`todo-add|Text|Datum`, `todo-list`, `todo-done|Nr oder Text`; `Config.briefingDays`, `Persona.dailyUpdate`).
2. Tägliche Push-Nachricht (**fertig**: `AlarmManager.setAndAllowWhileIdle`, Uhrzeit und Schalter in den Einstellungen, Berechtigung `POST_NOTIFICATIONS`, Neuplanung nach Neustart).
3. Kalender (`cal-add|Titel|Datum|Uhrzeit`, `cal-list|Datum`), Tagesupdate um Termine erweitern.
4. Wecker und Timer (`alarm|HH:mm|Name`, `timer|Minuten`, `AlarmClock`-Intents; niedrige Priorität).

## Grenzen
- Wie zuverlässig das 4B-Modell die Marker schreibt, ist erst am echten Modell zu sehen (Fake-Server-Tests ersetzen das nicht).
- Unbekannt: Verhalten der Benachrichtigungen unter Akku-Optimierung von Samsung (Push zur Uhrzeit kann verzögert kommen, wenn nicht exakt geplant wird).
