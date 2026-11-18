# Veröffentlichung bei Google Play

Stand der Vorbereitung (Android-App, Ordner `android/`). Was hier steht, ist geprüft; was noch zu entscheiden oder auszufüllen ist, steht unter "Noch offen".

## Technischer Stand
- **Zielversion:** `targetSdk 36` (Android 16). Google Play verlangt sie für neue Apps und Updates ab 31.08.2026. Gebaut und auf Android 15 geprüft; das Verhalten unter Android 16 selbst ist nicht geprüft.
- **Seitengröße 16 KB:** Alle nativen Bibliotheken (`libaichat_llama`, `libonnxruntime`, `libsherpa-onnx-jni`, `libandroidx.graphics.path`) haben 16-KB-Ausrichtung (`LOAD`-Segmente 0x4000, mit `llvm-readelf -lW` geprüft). Nach einem Austausch von sherpa-onnx oder dem NDK erneut prüfen.
- **Berechtigung "Zugriff auf alle Dateien" ist entfernt.** Modelle liegen im app-eigenen Ordner oder in einem vom Nutzer gewählten Ordner (Systemdialog), Companions und Chats werden über "Sichern/Zurückholen" in einen gewählten Ordner kopiert.
- **App-Bundle:** `cd android && gradlew :app:bundleRelease` erzeugt `app/build/outputs/bundle/release/app-release.aab` (rund 39 MB, nur `arm64-v8a`). Ohne Signierdatei entsteht ein unsigniertes Bundle (zum Prüfen des Builds). Zum Hochladen muss es signiert sein.
- **Signieren:** Schlüssel und Passwörter stehen in `android/keystore.properties` (Vorlage `android/keystore.properties.example`, die Datei und `*.jks` sind in `.gitignore`). Empfohlen: **Play App Signing** (Google verwaltet den App-Schlüssel, du lädst mit einem Upload-Schlüssel hoch).
  ```bash
  keytool -genkeypair -v -keystore android/upload-keystore.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
  ```
  Die Fragen nach Name und Organisation beantwortest du selbst; Schlüsseldatei und Passwörter sichern (ein verlorener Upload-Schlüssel lässt sich nur über den Play-Support zurücksetzen).
- **Version:** `versionCode` (ganze Zahl, bei jedem Upload höher) und `versionName` stehen in `android/app/build.gradle.kts`.
- **Verkleinern (R8):** ausgeschaltet. Die App nutzt JNI-Rückrufe (llama.cpp, sherpa-onnx), die ohne Schutzregeln beim Verkleinern abstürzen würden; erst mit eigenen Regeln und einem Gerätetest einschalten.

## Berechtigungen und ihre Begründung (für die Play Console)
| Berechtigung | Wofür |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Chat mit eigenem Server oder Online-Anbieter (nur auf Wunsch des Nutzers), Modell-Downloads, Wetter/Websuche-Werkzeuge; Download pausiert beim Wechsel von WLAN auf Mobilfunk |
| `RECORD_AUDIO` | Spracheingabe (Mikrofon-Knopf), erst auf Tippen angefragt |
| `READ_CALENDAR`, `WRITE_CALENDAR` | Organizer: Termine im Tagesupdate lesen, auf Wunsch einen Termin anlegen (mit Vorschau und Rückfrage) |
| `com.android.alarm.permission.SET_ALARM` | Wecker und Timer über die Uhr-App, auf Wunsch (mit Rückfrage) |
| `POST_NOTIFICATIONS` | Fortschritt von Downloads, "Download fertig", tägliche Erinnerung (optional) |
| `FOREGROUND_SERVICE`, `…_DATA_SYNC` | Große Modelle laden, auch wenn die App im Hintergrund ist |
| `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK` | Das lokale Sprachmodell rechnet eine Antwort; bei gesperrtem Bildschirm soll sie fertig werden |
| `RECEIVE_BOOT_COMPLETED` | Tägliche Erinnerung nach einem Neustart wieder einplanen |

**Erklärung für den Vordergrunddienst-Typ `specialUse`** (Feld in der Play Console): "On-device language model inference. The user sends a chat message; the model runs locally for seconds to minutes and the reply must finish even if the screen turns off. The service runs only while a reply is being generated and shows an ongoing notification."

## Datenschutz / Data-Safety-Formular
- Der Entwickler erhebt **keine Daten** und betreibt keinen Server; es gibt keine Analyse- und keine Werbebibliothek.
- Chats, Companions, Todos und Einstellungen bleiben auf dem Gerät (privater Speicher oder ein vom Nutzer gewählter Ordner).
- Nur wenn der Nutzer einen **Online-Anbieter** oder eigenen Server einträgt, gehen seine Nachrichten dorthin (an den von ihm gewählten Dienst, mit seinem Schlüssel). Der Schlüssel liegt im privaten Speicher der App.
- Downloads von Modellen laufen von Hugging Face (und den im Katalog genannten Adressen); dabei wird nichts über den Nutzer übermittelt außer der IP-Adresse und, falls eingetragen, dem Hugging-Face-Zugangsschlüssel (nur an huggingface.co).
- Entwurf der Datenschutzerklärung: `docs/PRIVACY.md`. Sie muss unter einer öffentlichen Adresse stehen; die Kontaktangabe trägst du dort selbst ein.
- Im Data-Safety-Formular: "Keine Daten erhoben", "Keine Daten geteilt" (die Übertragung an den vom Nutzer gewählten Dienst ist nutzerinitiiert und fällt unter die Ausnahme für vom Nutzer veranlasste Übertragungen; im Zweifel als "Nachrichten an einen vom Nutzer gewählten Dienst" angeben).

## Noch offen (Entscheidung oder Eintrag durch dich)
1. **App-Name** (Platzhalter `AI Chat MCP`), Symbol, Screenshots, Kurz- und Langbeschreibung.
2. **Inhalte (KI-Richtlinie von Google Play):** Apps mit KI-erzeugten Inhalten brauchen eine Möglichkeit, **Antworten in der App zu melden**, und dürfen keine anstößigen Inhalte erzeugen. Eine Melden-Funktion gibt es noch nicht (sie bräuchte eine Kontaktadresse, die nicht im Code stehen soll).
3. **Katalogeintrag "Gemmasutra Small 4B (role play) … unfiltered, for adults"** in `core/Catalog.kt`: Für den Play-Store-Build entfernen oder neutral beschreiben, sonst droht eine Ablehnung wegen sexueller Inhalte. Der Download selbst ist eine Datei von Hugging Face; die Richtlinie betrifft, was die App anbietet.
4. **Klartext-Verkehr** (`usesCleartextTraffic="true"`): nötig für `http://`-Adressen im eigenen Netz (LM Studio, Ollama). Die App warnt vor `http://` außerhalb des eigenen Netzes; für Play Console als "lokaler Server des Nutzers" erklären.
5. **Zielgruppe und Altersfreigabe** im Fragebogen (die App ist nicht für Kinder gedacht; Rollenspiel-Companions und frei wählbare Modelle ohne Filter bedeuten: Freigabe ab 16 oder 18 prüfen).
6. **Interner Test** vor der Veröffentlichung: Bundle hochladen, auf einem Gerät mit Android 16 prüfen (Hintergrunddienste, Benachrichtigungen, Ordnerwahl).
