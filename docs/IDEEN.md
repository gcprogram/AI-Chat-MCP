# Ideen und Machbarkeit (Stand 19.11.2026)

Alle drei Ideen laufen auf den vorhandenen Bausteinen (lokales Modell oder Online-Anbieter, Dokumente, Vorleser, MCP-Werkzeuge, Hintergrunddienst). Gemessen auf dem S21: Qwen3 4B Instruct erzeugt rund 4 Tokens/s, das 1.7B 6 bis 10 Tokens/s (Bildschirm an); der Kontext ist auf 4096 Tokens begrenzt. Das bestimmt, wie die Aufgaben zugeschnitten werden müssen.

## 1. Buch schreiben (Autorenmodus)
**Machbar, Aufwand mittel.** Ein Projekt ist ein Ordner mit `project.json` und je einer Markdown-Datei pro Stufe; jede Stufe ist einzeln wiederholbar und vom Nutzer bearbeitbar, der Ablauf lässt sich anhalten und fortsetzen.

Ablauf:
1. **Eingaben:** Thema, Genre, Länge (Kapitelzahl, Wörter je Kapitel), Perspektive (Ich/Er/Sie), Stil, Sprache, Rahmenbedingungen (Orte, Zeit, Tabus).
2. **Prämisse und grobe Handlung** in einem Gerüst (Dreiakter oder Heldenreise): Anfang, Wendepunkte, Höhepunkt, Ende.
3. **Dramatis Personae:** je wichtiger Figur Rolle, Hintergrund und Vergangenheit, Ziele und Wünsche, Stärken, Schwächen, Traumata, Beziehungen, Sprechweise.
4. **Kapitelplan:** je Kapitel 3 bis 6 Sätze, Ort, beteiligte Figuren, was sich ändert, Ende des Kapitels.
5. **Kapitel schreiben, szenenweise** (500 bis 800 Wörter je Aufruf wegen der Antwortgrenze). Prompt je Aufruf: Stilregeln, Figurenblätter nur der beteiligten Figuren, die Handlung in drei Sätzen, der Plan dieses Kapitels, eine laufende Zusammenfassung der bisherigen Kapitel ("Bisher") und die letzten 150 Wörter davor. Nach jedem Kapitel fasst das Modell es zusammen und führt eine Faktenliste (Namen, Orte, Ereignisse), damit nichts widerspricht.
6. **Export:** Markdown (einfach), DOCX (ZIP mit XML, ohne Bibliothek wie beim Lesen von DOCX), auch als Text für den Vorleser.

Grenzen: Ein Kapitel mit 1.500 Wörtern braucht mit dem 4B etwa 9 Minuten, ein Roman mit 20 Kapiteln mehrere Stunden (Bildschirm an; bei dunklem Bildschirm drosselt Samsung). Das 4B schreibt lesbar, aber mit Wiederholungen und Kontinuitätsfehlern; mit einem Online-Modell (Gemini, OpenAI, Claude) ist die Qualität deutlich besser und es geht schneller. Die Pipeline ist deshalb vom Anbieter unabhängig. Läuft im vorhandenen Hintergrunddienst mit Fortschrittsanzeige.

## 2. Rollenspiel mit Buchvorlage
**Machbar in zwei Stufen, Aufwand groß, setzt Idee 1 (Projektordner, Zusammenfassungen) und den Dokumentimport voraus.**

Vorbereitung (einmal je Buch): Das Buch wird in Kapitel und Abschnitte geteilt (EPUB, DOCX, PDF, TXT sind schon lesbar). Je Kapitel erzeugt das Modell "Handlungsbausteine": 5 bis 10 Szenen mit Ort, Figuren, Geschehen, Ergebnis. Dazu eine Figurenliste mit kurzer Beschreibung. Das Durchlesen eines ganzen Buchs (100.000 Wörter, etwa 150.000 Tokens) dauert lokal Stunden (4B etwa 4 h, 1.7B etwa 1,5 h), deshalb **kapitelweise bei Bedarf**: Das nächste Kapitel wird im Hintergrund vorbereitet, während die aktuelle Szene gespielt wird. Mit einem Online-Modell ist die Vorbereitung in Minuten fertig.

Spiel: Der Nutzer wählt eine Figur ("Ich" bei Ich-Erzählung, dann spricht das Modell den Spieler mit "du" an). Der System-Prompt enthält nur: Rolle des Spielers, aktuelle Szene und die nächsten ein bis zwei Szenen, die Regie ("spiele Erzähler und alle anderen Figuren; halte dich an die Szene; weicht der Spieler ab, reagiere kurz in der Welt und lenke innerhalb von ein bis zwei Zügen zurück zur Handlung"). Szenenwechsel:
- **Stufe 1:** Der Nutzer tippt "Nächste Szene" (zuverlässig, auch mit dem 4B).
- **Stufe 2:** Automatisch: nach jeder Runde fragt ein kurzer Prüfaufruf "Ist das Ziel der Szene erreicht?" (ein Wort als Antwort, wie beim Sprecher-Urteil); mit kleinen Modellen unzuverlässig, deshalb zusätzlich ein Zugzähler als Sicherung.

Grenzen: Kontext 4096 Tokens (deshalb nur Szenenfenster, nicht das Buch), Du/Ich-Verwechslung ist bei Rollenspiel in der Ich-Form das größte Risiko (die Beispiele im Prompt helfen), Stiltreue und das Zurücklenken sind beim 4B mäßig, bei Online-Modellen gut. Urheberrecht: nur Dateien, die der Nutzer selbst besitzt, nur auf dem Gerät.

## 3. Nachrichten von Telegram und WhatsApp vorlesen
**Machbar und klein, aber nicht über die Konten selbst, sondern über den Benachrichtigungszugriff.**

- WhatsApp hat für private Konten keine offizielle Schnittstelle; inoffizielle Clients (whatsapp-web.js, Baileys) verstoßen gegen die Nutzungsbedingungen (Sperrrisiko) und sind für Google Play ungeeignet. Telegram erlaubt mit TDLib eigene Clients, das ist aber viel Code (Anmeldung, Datenbank) für wenig Gewinn.
- **Lösung:** Ein `NotificationListenerService` liest die eingehenden Benachrichtigungen von WhatsApp und Telegram (Absender, Text). Ein eingebauter MCP-Server `messages` bietet `messages_unread` (neue Nachrichten, nach Absender) und `messages_clear`; ein Alias "Nachrichten vorlesen" gibt den Text an den vorhandenen Vorleser. Die Chats werden dabei nie geöffnet, die Nachrichten **bleiben also ungelesen**: das "wieder als ungelesen markieren" entfällt.
- Grenzen: nur Nachrichten, die eine Benachrichtigung erzeugten (nicht stummgeschaltete Chats, nicht bei geöffneter App), Text ist gekürzt, Gruppen werden gebündelt, Sprach- und Bildnachrichten erscheinen nur als Platzhalter. Der Nutzer muss den "Benachrichtigungszugriff" selbst in den Systemeinstellungen erlauben (das kann die App nicht für ihn tun). Speicherung nur im Arbeitsspeicher und kurz, Hinweis in der Datenschutzerklärung. Für Google Play ist der Zugriff erlaubt, wenn er eine Kernfunktion ist und erklärt wird.

## Reihenfolge-Vorschlag
1. Nachrichten vorlesen (klein, etwa ein Tag).
2. Buch schreiben (mittel; liefert die Bausteine für 3).
3. Rollenspiel mit Buchvorlage (groß; Stufe 1 zuerst).
