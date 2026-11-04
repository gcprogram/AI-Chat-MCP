package app.aichatmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import app.aichatmcp.ChatViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val c = vm.config
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
    var maxCalls by remember { mutableStateOf(c.maxToolCalls.toString()) }
    var resultChars by remember { mutableStateOf(c.toolResultChars.toString()) }
    var timeout by remember { mutableStateOf(c.toolTimeout.toString()) }
    var modelStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    ScreenFrame("Einstellungen", onBack) {
        SectionTitle("Server")
        TextSetting("Server-URL (inkl. /v1)", url, { url = it }, keyboard = KeyboardType.Uri)
        TextSetting("API-Schlüssel", key, { key = it }, secret = true)
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

        SectionTitle("Werkzeuge")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Vor jedem Werkzeugaufruf nachfragen", modifier = Modifier.weight(1f))
            Switch(checked = confirmTools, onCheckedChange = { confirmTools = it })
        }
        NumberSetting("Max. Werkzeugaufrufe pro Nachricht (0 = aus)", maxCalls) { maxCalls = it }
        NumberSetting("Ergebnis kürzen auf (Zeichen)", resultChars) { resultChars = it }
        NumberSetting("Werkzeug-Zeitlimit (Sekunden)", timeout) { timeout = it }

        Button(onClick = {
            vm.saveConfig(
                c.copy(
                    baseUrl = url, apiKey = key, model = model, models = models, userName = userName,
                    temperature = temperature.toDoubleOrNull() ?: c.temperature,
                    maxReplyTokens = maxReply.toIntOrNull() ?: c.maxReplyTokens,
                    contextTokens = contextTokens.toIntOrNull() ?: c.contextTokens,
                    topP = topP.toDoubleOrNull() ?: c.topP,
                    frequencyPenalty = frequency.toDoubleOrNull() ?: c.frequencyPenalty,
                    presencePenalty = presence.toDoubleOrNull() ?: c.presencePenalty,
                    repeatPenalty = repeat.toDoubleOrNull() ?: c.repeatPenalty,
                    confirmTools = confirmTools,
                    maxToolCalls = maxCalls.toIntOrNull() ?: c.maxToolCalls,
                    toolResultChars = resultChars.toIntOrNull() ?: c.toolResultChars,
                    toolTimeout = timeout.toIntOrNull() ?: c.toolTimeout,
                ),
            )
            onBack()
        }, modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
    }
}
