package app.aichatmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import app.aichatmcp.local.LOCAL_MAX_CONTEXT
import kotlinx.coroutines.launch

private const val SERVER_LABEL = "Server (LM Studio, OpenAI, …)"
private const val LOCAL_LABEL = "Auf dem Gerät (llama.cpp)"

@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit, onPickModel: () -> Unit, onPickVision: () -> Unit, onPickVisionProj: () -> Unit) {
    val c = vm.config
    var backend by remember { mutableStateOf(c.backend) }
    var localPath by remember { mutableStateOf(c.localModel) }
    var threads by remember { mutableStateOf(c.localThreads.toString()) }
    var url by remember { mutableStateOf(c.baseUrl) }
    var key by remember { mutableStateOf(c.apiKey) }
    var model by remember { mutableStateOf(c.model) }
    var models by remember { mutableStateOf(c.models) }
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
    var keywordsEnabled by remember { mutableStateOf(c.keywordsEnabled) }
    var speechRate by remember { mutableStateOf(c.speechRate.toString()) }
    var maxCalls by remember { mutableStateOf(c.maxToolCalls.toString()) }
    var resultChars by remember { mutableStateOf(c.toolResultChars.toString()) }
    var timeout by remember { mutableStateOf(c.toolTimeout.toString()) }
    var modelStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    ScreenFrame("Einstellungen", onBack) {
        SectionTitle("Modell")
        DropdownField(
            "Ausführung", listOf(SERVER_LABEL, LOCAL_LABEL), if (backend == BACKEND_LOCAL) LOCAL_LABEL else SERVER_LABEL,
            { backend = if (it == LOCAL_LABEL) BACKEND_LOCAL else BACKEND_SERVER },
        )
        if (backend == BACKEND_LOCAL) {
            // Datei aus dem Auswahldialog (wird nicht kopiert) oder ein Pfad, den die App lesen darf
            Text(
                if (vm.config.localModel.isEmpty()) "Noch keine Modelldatei gewählt."
                else "Modell: ${vm.config.localModelName.ifEmpty { vm.config.localModel }}",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onPickModel) { Text("Modelldatei (GGUF) wählen…") }
            TextSetting("Threads (Rechenkerne)", threads, { threads = it }, keyboard = KeyboardType.Number)
            Text(
                "Das Modell läuft komplett auf dem Handy (llama.cpp, nur CPU). Der Kontext ist auf $LOCAL_MAX_CONTEXT Tokens " +
                    "begrenzt, der Arbeitsspeicher ist knapp. Die erste Antwort braucht länger, weil das Modell geladen wird.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            TextSetting("Server-URL (inkl. /v1)", url, { url = it }, keyboard = KeyboardType.Uri)
            TextSetting("API-Schlüssel", key, { key = it }, secret = true)
        }
        if (backend != BACKEND_LOCAL) { // Modellliste des Servers
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    modelStatus = "Lade Modelle…"
                    scope.launch {
                        vm.fetchModels(url, key).fold(
                            onSuccess = { list ->
                                models = list
                                if (model !in list && list.isNotEmpty()) model = list.first()
                                modelStatus = "${list.size} Modelle gefunden"
                            },
                            onFailure = { modelStatus = it.message ?: "Fehler" },
                        )
                    }
                }) { Text("Modelle laden") }
                Text(modelStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            DropdownField("Modell", models.ifEmpty { listOfNotNull(model.ifEmpty { null }) }, model, { model = it })
        }

        SectionTitle("Chat")
        TextSetting("Dein Name", userName, { userName = it })
        NumberSetting("Temperatur", temperature) { temperature = it }
        NumberSetting("Max. Antwort-Tokens", maxReply) { maxReply = it }
        NumberSetting("Kontext-Tokens (Fenster des Modells)", contextTokens) { contextTokens = it }

        SectionTitle("Gegen Wiederholungen (kleine Modelle)")
        NumberSetting("Top-P", topP) { topP = it }
        NumberSetting("Frequency-Penalty", frequency) { frequency = it }
        NumberSetting("Presence-Penalty", presence) { presence = it }
        NumberSetting("Repeat-Penalty (0 = nicht senden, nur LM Studio)", repeat) { repeat = it }

        SectionTitle("Sprache")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Antworten vorlesen", modifier = Modifier.weight(1f))
            Switch(checked = speakReplies, onCheckedChange = { speakReplies = it })
        }
        NumberSetting("Sprechgeschwindigkeit (1.0 = normal, Deutsch gern 1.2)", speechRate) { speechRate = it }
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
            OutlinedButton(onClick = vm::refreshPacks) { Text("Status aktualisieren") }
        }

        SectionTitle("Bildmodell (Bilder beschreiben)")
        Text(
            "Das Chat-Modell sieht keine Bilder. Ein kleines Bildmodell (z. B. SmolVLM-256M, GGUF) beschreibt ein hochgeladenes Bild " +
                "als Text; die Beschreibung geht dann an den Companion. Dazu gehören zwei Dateien: das Modell und die passende " +
                "mmproj-Datei. Beide werden in den App-Speicher kopiert, geladen wird nur während einer Beschreibung.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Modell: ${vm.config.visionModelName.ifEmpty { "(keins)" }}")
        Text("mmproj: ${vm.config.visionProjName.ifEmpty { "(keine)" }}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickVision, enabled = !vm.visionBusy) { Text("Modell wählen…") }
            OutlinedButton(onClick = onPickVisionProj, enabled = !vm.visionBusy) { Text("mmproj wählen…") }
        }
        if (vm.visionAvailable) TextButton(onClick = vm::removeVision, enabled = !vm.visionBusy) { Text("Bildmodell entfernen") }
        if (vm.visionNote.isNotEmpty()) Text(vm.visionNote, style = MaterialTheme.typography.bodySmall)

        SectionTitle("Stimmen (Sprachausgabe)")
        LaunchedEffect(Unit) { vm.refreshVoices() }
        Text(
            "Vorgelesen wird mit der Google-Sprachausgabe, wenn oben „Antworten vorlesen“ an ist. Je Sprache wird die " +
                "installierte Stimme verwendet; „Anhören“ spricht einen Beispielsatz.",
            style = MaterialTheme.typography.bodySmall,
        )
        vm.voiceRows.forEach { row ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.name)
                    Text(
                        if (row.state == "installed") "Stimme installiert" else "keine Stimme installiert",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.state == "installed") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                }
                OutlinedButton(onClick = { vm.testVoice(row.tag) }) { Text("Anhören") }
            }
        }
        if (vm.voiceNote.isNotEmpty()) Text(vm.voiceNote, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::installVoices) { Text("Stimmen laden…") }
            OutlinedButton(onClick = vm::refreshVoices) { Text("Status aktualisieren") }
        }

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

        Button(onClick = {
            vm.saveConfig(
                vm.config.copy( // aktueller Stand: die Modelldatei kann gerade erst gewählt worden sein
                    backend = backend, localThreads = threads.toIntOrNull() ?: c.localThreads, baseUrl = url, apiKey = key, model = model, models = models, userName = userName,
                    temperature = temperature.toDoubleOrNull() ?: c.temperature,
                    maxReplyTokens = maxReply.toIntOrNull() ?: c.maxReplyTokens,
                    contextTokens = contextTokens.toIntOrNull() ?: c.contextTokens,
                    topP = topP.toDoubleOrNull() ?: c.topP,
                    frequencyPenalty = frequency.toDoubleOrNull() ?: c.frequencyPenalty,
                    presencePenalty = presence.toDoubleOrNull() ?: c.presencePenalty,
                    repeatPenalty = repeat.toDoubleOrNull() ?: c.repeatPenalty,
                    confirmTools = confirmTools, speakReplies = speakReplies, voiceAutoSend = voiceAutoSend, speechOnline = speechOnline, keywordsEnabled = keywordsEnabled,
                    speechRate = speechRate.toDoubleOrNull() ?: c.speechRate,
                    maxToolCalls = maxCalls.toIntOrNull() ?: c.maxToolCalls,
                    toolResultChars = resultChars.toIntOrNull() ?: c.toolResultChars,
                    toolTimeout = timeout.toIntOrNull() ?: c.toolTimeout,
                ),
            )
            onBack()
        }, modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
    }
}
