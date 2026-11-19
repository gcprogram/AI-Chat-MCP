@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.aichatmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.saveable.rememberSaveable
import app.aichatmcp.core.LANGUAGE_DE
import app.aichatmcp.core.LANGUAGE_EN
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import app.aichatmcp.ChatViewModel
import app.aichatmcp.core.BACKEND_LOCAL
import app.aichatmcp.core.BACKEND_SERVER
import app.aichatmcp.core.ENGINE_GOOGLE
import app.aichatmcp.core.ENGINE_WHISPER
import app.aichatmcp.local.LOCAL_MAX_CONTEXT
import kotlinx.coroutines.launch
private const val SERVER_LABEL = "Server (LM Studio, OpenAI, …)"
private const val LOCAL_LABEL = "Auf dem Gerät (llama.cpp)"

@Composable
fun SettingsScreen(
    vm: ChatViewModel, onBack: () -> Unit, onPickModel: () -> Unit, onPickVision: () -> Unit, onPickVisionProj: () -> Unit,
    onPickPiper: () -> Unit, onPickWhisper: () -> Unit, onRequestNotifications: () -> Unit, onRequestCalendar: () -> Unit, onToast: (String) -> Unit,
    onOpenModels: () -> Unit, folders: FolderActions, onReplayIntro: () -> Unit,
) {
    val c = vm.config
    var backend by remember { mutableStateOf(c.backend) }
    var localPath by remember { mutableStateOf(c.localModel) }
    var threads by remember { mutableStateOf(c.localThreads.toString()) }
    var thinking by remember { mutableStateOf(c.localThinking) }
    val server = remember { ServerDraft(c) }
    var userName by remember { mutableStateOf(c.userName) }
    var temperature by remember { mutableStateOf(c.temperature.toString()) }
    var maxReply by remember { mutableStateOf(c.maxReplyTokens.toString()) }
    var contextTokens by remember { mutableStateOf(c.contextTokens.toString()) }
    var topP by remember { mutableStateOf(c.topP.toString()) }
    var frequency by remember { mutableStateOf(c.frequencyPenalty.toString()) }
    var presence by remember { mutableStateOf(c.presencePenalty.toString()) }
    var repeat by remember { mutableStateOf(c.repeatPenalty.toString()) }
    var confirmTools by remember { mutableStateOf(c.confirmTools) }
    var speakReplies by remember { mutableStateOf(c.speakReplies) }
    var voiceAutoSend by remember { mutableStateOf(c.voiceAutoSend) }
    var speechOnline by remember { mutableStateOf(c.speechOnline) }
    var speechEngine by remember { mutableStateOf(c.speechEngine) }
    var whisperModel by remember { mutableStateOf(c.whisperModel) }
    var whisperLanguage by remember { mutableStateOf(c.whisperLanguage) }
    var dailyReminder by remember { mutableStateOf(c.dailyReminder) }
    var catchMessages by remember { mutableStateOf(c.catchMessages) }
    var roleplayTurns by remember { mutableStateOf(c.roleplayTurns.toString()) }
    var dailyReminderTime by remember { mutableStateOf(c.dailyReminderTime) }
    var keywordsEnabled by remember { mutableStateOf(c.keywordsEnabled) }
    var dialogueJudge by remember { mutableStateOf(c.dialogueJudge) }
    var speechRate by remember { mutableStateOf(c.speechRate.toString()) }
    var piperNoise by remember { mutableStateOf(c.piperNoise.toString()) }
    var piperNoiseW by remember { mutableStateOf(c.piperNoiseW.toString()) }
    var maxCalls by remember { mutableStateOf(c.maxToolCalls.toString()) }
    var resultChars by remember { mutableStateOf(c.toolResultChars.toString()) }
    var timeout by remember { mutableStateOf(c.toolTimeout.toString()) }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf(
        t("Chat model", "Chat-Modell"), t("Chat", "Chat"), t("Voice input", "Spracheingabe"), t("Voices", "Stimmen"),
        t("Image model", "Bildmodell"), t("Organizer", "Organizer"), t("Tools", "Werkzeuge"), t("App", "App"),
    )

    TabbedFrame(
        t("Settings", "Einstellungen"), onBack, tabs, tab, { tab = it },
        footer = {
            Button(onClick = {
                vm.rememberProviderKey(server.provider, server.key)
                vm.saveConfig(
                    server.apply(vm.config).copy( // aktueller Stand: die Modelldatei kann gerade erst gewählt worden sein
                        backend = backend, localThreads = threads.toIntOrNull() ?: c.localThreads, localThinking = thinking, userName = userName,
                        temperature = temperature.toDoubleOrNull() ?: c.temperature,
                        maxReplyTokens = maxReply.toIntOrNull() ?: c.maxReplyTokens,
                        contextTokens = contextTokens.toIntOrNull() ?: c.contextTokens,
                        topP = topP.toDoubleOrNull() ?: c.topP,
                        frequencyPenalty = frequency.toDoubleOrNull() ?: c.frequencyPenalty,
                        presencePenalty = presence.toDoubleOrNull() ?: c.presencePenalty,
                        repeatPenalty = repeat.toDoubleOrNull() ?: c.repeatPenalty,
                        confirmTools = confirmTools, speakReplies = speakReplies, voiceAutoSend = voiceAutoSend, speechOnline = speechOnline, keywordsEnabled = keywordsEnabled, dialogueJudge = dialogueJudge,
                        dailyReminder = dailyReminder, catchMessages = catchMessages, roleplayTurns = roleplayTurns.toIntOrNull() ?: c.roleplayTurns, dailyReminderTime = dailyReminderTime.trim(), speechEngine = speechEngine, whisperModel = whisperModel, whisperLanguage = whisperLanguage.trim().lowercase(),
                        speechRate = speechRate.toDoubleOrNull() ?: c.speechRate,
                        piperNoise = piperNoise.toDoubleOrNull() ?: c.piperNoise,
                        piperNoiseW = piperNoiseW.toDoubleOrNull() ?: c.piperNoiseW,
                        maxToolCalls = maxCalls.toIntOrNull() ?: c.maxToolCalls,
                        toolResultChars = resultChars.toIntOrNull() ?: c.toolResultChars,
                        toolTimeout = timeout.toIntOrNull() ?: c.toolTimeout,
                    ),
                )
                onBack()
            }, modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
        },
    ) {
        when (tab) {
            0 -> {
                DropdownField(
                    t("Run the model", "Modell ausführen"), listOf(LOCAL_LABEL, SERVER_LABEL), if (backend == BACKEND_LOCAL) LOCAL_LABEL else SERVER_LABEL,
                    { backend = if (it == LOCAL_LABEL) BACKEND_LOCAL else BACKEND_SERVER },
                )
                if (backend == BACKEND_LOCAL) {
                    LaunchedEffect(Unit) { vm.refreshModelFiles() }
                    Text(
                        if (vm.config.localModel.isEmpty()) "Noch keine Modelldatei gewählt."
                        else "Modell: ${vm.config.localModelName.ifEmpty { vm.config.localModel }}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (vm.modelFiles.isNotEmpty()) {
                        DropdownField(
                            t("Models in the models folder", "Modelle im Modellordner"), vm.modelFiles.map { it.name },
                            vm.modelFiles.firstOrNull { vm.isCurrentModel(it) }?.name.orEmpty(),
                            { name -> vm.modelFiles.firstOrNull { it.name == name }?.let { backend = BACKEND_LOCAL; vm.useModelFile(it) } },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenModels) { Text(t("Download models…", "Modelle herunterladen…")) }
                        OutlinedButton(onClick = onPickModel) { Text("Modelldatei (GGUF) wählen…") }
                    }
                    TextSetting("Threads (Rechenkerne)", threads, { threads = it }, keyboard = KeyboardType.Number)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(t("Let the model think first (Qwen3, slower)", "Modell erst nachdenken lassen (Qwen3, langsamer)"), modifier = Modifier.weight(1f))
                        Switch(checked = thinking, onCheckedChange = { thinking = it })
                    }
                    Text(
                        t("Qwen3 models can write a long train of thought before they answer. It costs a lot of time on a phone and rarely improves chat answers, so it is off by default. The thoughts are shown but never stored. Qwen3 Instruct models do not think at all.",
                            "Qwen3-Modelle können vor der Antwort lange nachdenken. Das kostet auf dem Handy viel Zeit und verbessert Chat-Antworten selten, deshalb ist es standardmäßig aus. Die Gedanken werden angezeigt, aber nie gespeichert. Qwen3-Instruct-Modelle denken gar nicht."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Das Modell läuft komplett auf dem Handy (llama.cpp, nur CPU). Der Kontext ist auf $LOCAL_MAX_CONTEXT Tokens " +
                            "begrenzt, der Arbeitsspeicher ist knapp. Die erste Antwort braucht länger, weil das Modell geladen wird.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    ServerFields(vm, server)
                }
            }
            1 -> {
            TextSetting("Dein Name", userName, { userName = it })
            NumberSetting("Temperatur", temperature) { temperature = it }
            NumberSetting("Max. Antwort-Tokens", maxReply) { maxReply = it }
            NumberSetting("Kontext-Tokens (Fenster des Modells)", contextTokens) { contextTokens = it }
            TextSetting(t("Replies per scene in a book role play (0 = move on by hand)", "Antworten je Szene im Buch-Rollenspiel (0 = nur von Hand weiter)"), roleplayTurns, { roleplayTurns = it }, keyboard = KeyboardType.Number)
            SectionTitle("Gegen Wiederholungen (kleine Modelle)")
            NumberSetting("Top-P", topP) { topP = it }
            NumberSetting("Frequency-Penalty", frequency) { frequency = it }
            NumberSetting("Presence-Penalty", presence) { presence = it }
            NumberSetting("Repeat-Penalty (0 = nicht senden, nur LM Studio)", repeat) { repeat = it }
            }
            2 -> {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Nach Spracheingabe sofort senden", modifier = Modifier.weight(1f))
                Switch(checked = voiceAutoSend, onCheckedChange = { voiceAutoSend = it })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Online-Erkennung erlauben, wenn das Offline-Sprachpaket fehlt", modifier = Modifier.weight(1f))
                Switch(checked = speechOnline, onCheckedChange = { speechOnline = it })
            }
            Text(
                "Erkennung und Stimme sind die von Google (Spracherkennung und -synthese von Google), die Sprache folgt der " +
                    "Antwortsprache des Companions. Es wird zuerst offline erkannt (Audio bleibt auf dem Handy). Fehlt das " +
                    "Sprachpaket (Google-Einstellungen → Sprache → Offline-Spracherkennung), geht das Audio bei erlaubter " +
                    "Online-Erkennung an Google.",
                style = MaterialTheme.typography.bodySmall,
            )
            SectionTitle("Spracheingabe mit Whisper (offline)")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Whisper statt Google für das Mikrofon", modifier = Modifier.weight(1f))
                Switch(checked = speechEngine == ENGINE_WHISPER, onCheckedChange = { speechEngine = if (it) ENGINE_WHISPER else ENGINE_GOOGLE })
            }
            Text(
                "Whisper läuft komplett auf dem Gerät, ohne Google und ohne Netz, und erkennt viele Sprachen. Es erkennt erst nach dem Sprechen " +
                    "(kein Mitlesen), die Aufnahme endet nach einer Pause oder beim erneuten Tippen auf das Mikrofon. Größere Modelle erkennen besser, " +
                    "brauchen aber länger: „small“ ist ein guter Anfang, „medium“ ist auf dem Handy meist zu langsam. Modellpakete: sherpa-onnx " +
                    "(Pakete „sherpa-onnx-whisper-…“, Ordner mit Encoder, Decoder und tokens.txt).",
                style = MaterialTheme.typography.bodySmall,
            )
            TextSetting("Sprache für Whisper (leer = Sprache des Companions, auto = erkennen, sonst z. B. de)", whisperLanguage, { whisperLanguage = it })
            Text(
                "Das Feld legt fest, in welcher Sprache Whisper schreibt. Leer: in der Antwortsprache des Companions; sprichst du Deutsch zu einem englischen " +
                    "Companion, kommt der Text schon auf Englisch heraus (Whisper übersetzt). „auto“ schreibt in der gesprochenen Sprache, ein Code wie „de“ " +
                    "immer in dieser Sprache.",
                style = MaterialTheme.typography.bodySmall,
            )
            vm.whisperModels.forEach { m ->
                val selected = (vm.whisperModels.firstOrNull { it.name == whisperModel } ?: vm.whisperModels.first()).name == m.name
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected, onClick = { whisperModel = m.name })
                    Column(Modifier.weight(1f)) {
                        Text(m.title)
                        Text(
                            m.name + if (m.englishOnly) " (nur Englisch)" else "", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    TextButton(onClick = { vm.deleteWhisper(m) }) { Text("Löschen") }
                }
            }
            if (vm.whisperModels.isEmpty()) {
                Text("Noch kein Whisper-Modell installiert.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            if (vm.whisperNote.isNotEmpty()) Text(vm.whisperNote, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onPickWhisper, enabled = !vm.whisperBusy) { Text(if (vm.whisperBusy) "Kopiere…" else "Whisper-Ordner wählen…") }
            SectionTitle("Offline-Sprachpakete (Spracherkennung)")
            if (!vm.packsSupported) {
                Text("Das Verwalten der Sprachpakete braucht Android 13 oder neuer.", style = MaterialTheme.typography.bodySmall)
            } else {
                LaunchedEffect(Unit) { vm.refreshPacks() }
                vm.packRows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.name)
                            Text(
                                when (row.state) {
                                    "installed" -> "offline installiert"
                                    "downloading" -> if (row.progress > 0) "wird geladen… ${row.progress} %" else "wird geladen…"
                                    "available" -> "nicht installiert (es wird online erkannt)"
                                    else -> "für Offline-Erkennung nicht angeboten"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (row.state == "installed") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            )
                        }
                        if (row.state == "available") OutlinedButton(onClick = { vm.downloadPack(row.tag) }) { Text("Laden") }
                    }
                }
                if (vm.packNote.isNotEmpty()) Text(vm.packNote, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = vm::refreshPacks) { Text("Aktualisieren") }
            }
                OutlinedButton(onClick = onOpenModels) { Text(t("Download Whisper…", "Whisper herunterladen…")) }
            }
            3 -> {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Antworten vorlesen", modifier = Modifier.weight(1f))
                Switch(checked = speakReplies, onCheckedChange = { speakReplies = it })
            }
            NumberSetting("Sprechgeschwindigkeit (1.0 = normal, Deutsch gern 1.2)", speechRate) { speechRate = it }
                SectionTitle(t("System voices", "Systemstimmen"))
            LaunchedEffect(Unit) { vm.refreshVoices() }
            Text(
                "Vorgelesen wird, wenn oben „Antworten vorlesen“ an ist. Die Stimme wählst du je Companion im Companion-Editor: entweder eine " +
                    "Systemstimme (Google-Sprachausgabe, braucht installierte Stimmen) oder eine Piper-Stimme (läuft ohne Google auf dem Gerät, " +
                    "siehe unten). Ohne Auswahl gilt die Systemstimme der Sprache.",
                style = MaterialTheme.typography.bodySmall,
            )
            vm.voiceRows.forEach { row ->
                val system = row.state == "installed"
                val piperCount = vm.piperVoices.count { it.language == row.tag.substringBefore('-') }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.name)
                        Text(
                            t("System voice: ", "Systemstimme: ") + (if (system) t("installed", "installiert") else t("none", "keine")) + "  ·  Piper: " + (if (piperCount > 0) "$piperCount" else t("none", "keine")),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (system || piperCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                    }
                    OutlinedButton(onClick = { vm.testVoice(row.tag) }, enabled = system) { Text("Anhören") }
                }
            }
            if (vm.voiceNote.isNotEmpty()) Text(vm.voiceNote, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::installVoices) { Text("Systemstimmen laden…") }
                OutlinedButton(onClick = vm::refreshVoices) { Text("Aktualisieren") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Unklare wörtliche Rede vom Server zuordnen lassen (KI)", modifier = Modifier.weight(1f))
                Switch(checked = dialogueJudge, onCheckedChange = { dialogueJudge = it })
            }
            Text(
                "Gilt beim Vorlesen mit Stimmen für männliche und weibliche Rede (Companion-Editor). Die Regeln erkennen den Sprecher am Begleitsatz, an " +
                    "Vornamen und am Absatzwechsel. Für den Rest fragt die App den Server (Chat-Modell, z. B. LM Studio) im Hintergrund, während schon " +
                    "gelesen wird; bis die Antwort da ist, spricht der Erzähler. Dabei gehen kurze Textausschnitte (etwa 300 Zeichen je Rede) an den Server. " +
                    "Nur bei Server-Betrieb, nicht mit dem Modell auf dem Handy. Standard: aus.",
                style = MaterialTheme.typography.bodySmall,
            )
            SectionTitle("Piper-Stimmen (offline, auch eigene)")
            NumberSetting("Schwankung der Stimme (0.667 = Vorgabe, höher = lebendiger)", piperNoise) { piperNoise = it }
            NumberSetting("Schwankung der Betonung/Rhythmik (0.8 = Vorgabe, höher = abwechslungsreicher)", piperNoiseW) { piperNoiseW = it }
            Text(
                "Gilt nach dem Speichern (die Stimmen werden neu geladen). Zum Probieren z. B. 0.9 und 1.1 eintragen, speichern und " +
                    "eine Stimme anhören; zu hohe Werte lassen sie stolpern oder rauschen (zurück auf 0.667 und 0.8).",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Piper-Stimmen sprechen komplett auf dem Gerät, auch ohne Google-Sprachausgabe. „Stimmenordner wählen…“ übernimmt einen Ordner " +
                    "im Format der sherpa-onnx-Pakete (vits-piper-…: .onnx, tokens.txt, einmalig espeak-ng-data). Eine eigene Stimme entsteht durch " +
                    "Training am PC, Anleitung: docs/EIGENE_STIMME.md. Gewählt wird die Stimme im Companion-Editor unter „Stimme“; dort erscheinen " +
                    "nur Stimmen der Sprache des Companions. Qualität low/medium läuft flüssig, high ist auf älteren Handys zu langsam.",
                style = MaterialTheme.typography.bodySmall,
            )
            var openLanguages by remember { mutableStateOf(setOf<String>()) }
            vm.piperVoices.groupBy { it.language.orEmpty() }.entries.sortedBy { if (it.key.isEmpty()) "~" else languageLabel(it.key) }.forEach { (code, voices) ->
                val open = code in openLanguages
                TextButton(
                    onClick = { openLanguages = if (open) openLanguages - code else openLanguages + code },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        (if (open) "▾  " else "▸  ") + languageLabel(code.ifEmpty { null }) + " (${voices.size})",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (open) voices.forEach { voice ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(voice.title)
                            Text(voice.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        if (voice.speakers.size < 2) OutlinedButton(onClick = { vm.testPiper(voice) }) { Text("Anhören") }
                        TextButton(onClick = { vm.deletePiper(voice) }) { Text("Löschen") }
                    }
                    if (voice.speakers.size >= 2) {
                        Text("Mehrere Sprecher/Stimmungen, im Companion-Editor einzeln wählbar. Anhören:", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            voice.speakers.forEach { (speaker, sid) -> OutlinedButton(onClick = { vm.testPiper(voice, sid) }) { Text(speaker) } }
                        }
                    }
                }
            }
            if (vm.piperVoices.isEmpty()) Text("Noch keine Piper-Stimme installiert.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            if (vm.piperNote.isNotEmpty()) Text(vm.piperNote, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onPickPiper, enabled = !vm.piperBusy) { Text(if (vm.piperBusy) "Kopiere…" else "Stimmenordner wählen…") }
                OutlinedButton(onClick = onOpenModels) { Text(t("Download Piper voices…", "Piper-Stimmen herunterladen…")) }
            }
            4 -> {
            SectionTitle("Bildmodell (Bilder beschreiben)")
            Text(
                "Das Chat-Modell sieht keine Bilder. Ein kleines Bildmodell (z. B. SmolVLM-256M, GGUF) beschreibt ein hochgeladenes Bild " +
                    "als Text; die Beschreibung geht dann an den Companion. Dazu gehören zwei Dateien: das Modell und die passende " +
                    "mmproj-Datei. Beide werden in den App-Speicher kopiert, geladen wird nur während einer Beschreibung.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(t("Model: ", "Modell: ") + vm.config.visionModelName.ifEmpty { t("(none)", "(keins)") })
            Text("mmproj: " + vm.config.visionProjName.ifEmpty { t("(none)", "(keine)") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickVision, enabled = !vm.visionBusy) { Text("Modell wählen…") }
                OutlinedButton(onClick = onPickVisionProj, enabled = !vm.visionBusy) { Text("mmproj wählen…") }
            }
            if (vm.visionAvailable) TextButton(onClick = vm::removeVision, enabled = !vm.visionBusy) { Text("Bildmodell entfernen") }
            if (vm.visionNote.isNotEmpty()) Text(vm.visionNote, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onOpenModels) { Text(t("Download the image model…", "Bildmodell herunterladen…")) }
            }
            5 -> {
            SectionTitle("Tägliche Erinnerung (Push)")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Täglich an das Update erinnern", modifier = Modifier.weight(1f))
                Switch(checked = dailyReminder, onCheckedChange = { dailyReminder = it; if (it) onRequestNotifications() })
            }
            TextSetting("Uhrzeit (z. B. 08:00)", dailyReminderTime, { dailyReminderTime = it })
            Text(
                "Die Nachricht nennt nur eine Kurzübersicht (Zahl der offenen Todos), nicht ihren Inhalt. Antippen öffnet den Chat. Der Alarm ist nicht " +
                    "sekundengenau: Im Ruhezustand des Handys kann die Nachricht einige Minuten später kommen. Er gilt erst nach „Speichern“.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { onToast(vm.testReminder()) }) { Text("Nachricht jetzt testen") }
            SectionTitle("Kalender")
            Text(
                if (vm.calendarAllowed) "Zugriff erlaubt: Das Tagesupdate und die Werkzeuge cal-add und cal-list nutzen den Kalender des Telefons. " +
                    "Neue Termine fragen vorher nach (mit Wochentag und Datum)."
                else "Noch kein Zugriff. Ohne ihn zeigt das Tagesupdate keine Termine und cal-add/cal-list melden dem Companion einen Fehler.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!vm.calendarAllowed) OutlinedButton(onClick = onRequestCalendar) { Text("Kalender-Zugriff erlauben") }
            SectionTitle(t("Messages (WhatsApp, Telegram)", "Nachrichten (WhatsApp, Telegram)"))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(t("Read incoming messages (for the companion to read out)", "Eingehende Nachrichten mitlesen (damit der Companion sie vorlesen kann)"), modifier = Modifier.weight(1f))
                Switch(checked = catchMessages, onCheckedChange = { catchMessages = it })
            }
            val context = androidx.compose.ui.platform.LocalContext.current
            val access = vm.messagesAccess()
            Text(
                t("The app reads the notifications of WhatsApp and Telegram (sender and text), keeps them only in memory and forgets them when you read the chat. It never opens the chats, so the messages stay unread in the messenger. Add the tool “messages” to a companion, then say “Read my messages”. Nothing is sent anywhere. Applies after “Save”.",
                    "Die App liest die Benachrichtigungen von WhatsApp und Telegram (Absender und Text), behält sie nur im Arbeitsspeicher und vergisst sie, sobald du den Chat gelesen hast. Sie öffnet die Chats nie, die Nachrichten bleiben im Messenger also ungelesen. Gib einem Companion das Werkzeug „messages“ und sage dann „Lies meine Nachrichten vor“. Es wird nichts weitergeschickt. Gilt nach „Speichern“."),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (access) t("System access: allowed.", "Systemzugriff: erlaubt.") else t("System access: not allowed yet. You have to allow it yourself in the system settings.", "Systemzugriff: noch nicht erlaubt. Das musst du selbst in den Systemeinstellungen tun."),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }) {
                Text(t("Open notification access settings…", "Einstellungen für den Benachrichtigungszugriff öffnen…"))
            }
            }
            6 -> {
            SectionTitle("Werkzeuge")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Vor jedem Werkzeugaufruf nachfragen", modifier = Modifier.weight(1f))
                Switch(checked = confirmTools, onCheckedChange = { confirmTools = it })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Schlüsselwörter lösen Werkzeuge aus", modifier = Modifier.weight(1f))
                Switch(checked = keywordsEnabled, onCheckedChange = { keywordsEnabled = it })
            }
            Text(
                "In mcp_servers.json kann ein Alias \"keywords\": [\"regulärer Ausdruck\", …] haben. Kommt ein Ausdruck in deiner Eingabe, " +
                    "in der Antwort des Modells, im vorgelesenen Text oder in einer Bildbeschreibung vor, läuft das Werkzeug ohne das " +
                    "Modell. Eine Eingabe mit Treffer geht nicht an das Modell. Es gelten die Freigaben des Companions und die Rückfrage " +
                    "vor Werkzeugaufrufen. Vorsicht: Auch fremde Texte (Dateien, Bilder) können so Werkzeuge auslösen. Standard: aus.",
                style = MaterialTheme.typography.bodySmall,
            )
            NumberSetting("Max. Werkzeugaufrufe pro Nachricht (0 = aus)", maxCalls) { maxCalls = it }
            NumberSetting("Ergebnis kürzen auf (Zeichen)", resultChars) { resultChars = it }
            NumberSetting("Werkzeug-Zeitlimit (Sekunden)", timeout) { timeout = it }
            }
            else -> {
                SectionTitle(t("Language of the app", "Sprache der App"))
                DropdownField(
                    t("Interface language", "Sprache der Oberfläche"), listOf("English", "Deutsch"), if (vm.config.uiLanguage == LANGUAGE_DE) "Deutsch" else "English",
                    { vm.saveConfig(vm.config.copy(uiLanguage = if (it == "Deutsch") LANGUAGE_DE else LANGUAGE_EN)) },
                    raw = true,
                )
                Text(
                    t("This is the language of menus and buttons. The language a companion answers in is set per companion.",
                        "Das ist die Sprache der Menüs und Knöpfe. In welcher Sprache ein Companion antwortet, stellst du je Companion ein."),
                    style = MaterialTheme.typography.bodySmall,
                )
                SectionTitle(t("Models and folders", "Modelle und Ordner"))
                Button(onClick = onOpenModels) { Text(t("Models and downloads…", "Modelle und Downloads…")) }
                FolderInfo(vm, folders)
                BackupInfo(folders)
                SectionTitle(t("Report a problem", "Problem melden"))
                Text(
                    t("Sends a short message to the developer (your mail app opens with the text ready; nothing is sent before you press send). You can also report a single reply with “Report” under it.",
                        "Schickt eine kurze Nachricht an den Entwickler (dein Mailprogramm öffnet sich mit fertigem Text; erst mit „Senden“ geht etwas ab). Eine einzelne Antwort meldest du mit „Melden“ unter der Antwort."),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { vm.reportProblem()?.let(onToast) }) { Text(t("Report a problem…", "Problem melden…")) }
                SectionTitle(t("Introduction", "Einführung"))
                OutlinedButton(onClick = onReplayIntro) { Text(t("Show the introduction again", "Einführung noch einmal zeigen")) }
            }
        }
    }
}
