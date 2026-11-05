package app.aichatmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aichatmcp.ChatViewModel
import app.aichatmcp.FetchedCert
import kotlinx.coroutines.launch

@Composable
fun ToolsScreen(vm: ChatViewModel, onBack: () -> Unit, onImportMcp: () -> Unit, onImportCert: () -> Unit) {
    var text by remember { mutableStateOf(vm.mcpText) }
    LaunchedEffect(vm.mcpText) { text = vm.mcpText } // nach Import oder Zurücksetzen
    var jsonError by remember { mutableStateOf("") }
    var secretName by remember { mutableStateOf("") }
    var secretValue by remember { mutableStateOf("") }
    var hostPort by remember { mutableStateOf("127.0.0.1:8443") }
    var certName by remember { mutableStateOf("remote.pem") }
    var certStatus by remember { mutableStateOf("") }
    var fetched by remember { mutableStateOf<FetchedCert?>(null) }
    val scope = rememberCoroutineScope()

    ScreenFrame("Werkzeuge", onBack) {
        SectionTitle("Stand")
        Text(
            if (vm.tools.loading) "Werkzeuge werden gestartet…"
            else "Verfügbar: ${vm.tools.available.joinToString(", ").ifEmpty { "keine" }}",
            style = MaterialTheme.typography.bodyMedium,
        )
        vm.tools.errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        SectionTitle("mcp_servers.json")
        Text(
            "Gleiches Format wie am PC. 'builtin': weather oder websearch läuft in der App, 'url' spricht einen MCP-Server " +
                "über HTTP an. Die Python-Server des PCs werden automatisch durch die eingebauten ersetzt.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextSetting("Konfiguration", text, { text = it; jsonError = "" }, minLines = 10, maxLines = 24)
        if (jsonError.isNotEmpty()) Text(jsonError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { jsonError = vm.saveMcpJson(text).orEmpty() }) { Text("Speichern & neu laden") }
            OutlinedButton(onClick = onImportMcp) { Text("Aus Datei") }
            OutlinedButton(onClick = vm::resetMcpJson) { Text("Standard") }
        }

        SectionTitle("Geheimnisse (für \${NAME} in Headern)")
        vm.secretNames.forEach { name ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$name = ••••••••", fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.deleteSecret(name) }) { Text("Löschen") }
            }
        }
        // Fehlende Geheimnisse stehen als ${NAME} in der Konfiguration: den ersten davon als Namen vorschlagen
        val missing = Regex("\\$\\{(\\w+)\\}").findAll(vm.mcpText).map { it.groupValues[1] }.distinct().filter { it !in vm.secretNames }.toList()
        LaunchedEffect(missing) { if (secretName.isBlank()) missing.firstOrNull()?.let { secretName = it } }
        if (missing.isNotEmpty()) {
            Text("Es fehlt noch: ${missing.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        TextSetting("Name (z. B. BROADCAST_REMOTE_TOKEN)", secretName, { secretName = it })
        TextSetting("Wert", secretValue, { secretValue = it }, secret = true)
        OutlinedButton(onClick = { vm.setSecret(secretName, secretValue); secretValue = "" }, enabled = secretName.isNotBlank() && secretValue.isNotEmpty()) {
            Text("Geheimnis speichern")
        }
        if (secretName.isBlank() && secretValue.isNotEmpty()) {
            Text("Der Name fehlt: oben bei „Name“ eintragen (z. B. BROADCAST_REMOTE_TOKEN).", style = MaterialTheme.typography.bodySmall)
        }

        SectionTitle("Zertifikate (selbst signierte Server)")
        vm.certNames.forEach { name ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(name, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.deleteCert(name) }) { Text("Löschen") }
            }
        }
        TextSetting("Server (Host:Port)", hostPort, { hostPort = it }, keyboard = KeyboardType.Uri)
        TextSetting("Speichern als (Name aus 'cert' in der Konfiguration)", certName, { certName = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                val host = hostPort.substringBeforeLast(':', hostPort).trim()
                val port = hostPort.substringAfterLast(':', "443").trim().toIntOrNull() ?: 443
                certStatus = "Hole Zertifikat…"
                scope.launch {
                    vm.fetchCert(host, port).fold(
                        onSuccess = { fetched = it; certStatus = "" },
                        onFailure = { certStatus = "Nicht erreichbar: ${it.message}" },
                    )
                }
            }) { Text("Vom Server holen") }
            OutlinedButton(onClick = onImportCert) { Text("Aus Datei") }
        }
        if (certStatus.isNotEmpty()) Text(certStatus, style = MaterialTheme.typography.bodySmall)
    }

    fetched?.let { cert ->
        AlertDialog(
            onDismissRequest = { fetched = null },
            title = { Text("Zertifikat vertrauen?") },
            text = {
                Text(
                    "Server: $hostPort\nSubjekt: ${cert.subject}\n\nSHA-256-Fingerabdruck:\n${cert.fingerprint}\n\n" +
                        "Vergleiche ihn mit der Anzeige der Fernbedienungs-App. Gespeichert wird es als einziger " +
                        "Vertrauensanker für Server, die dieses Zertifikat in 'cert' angeben.",
                    fontFamily = FontFamily.Default,
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.saveCert(certName, cert.pem); fetched = null; certStatus = "Gespeichert als $certName" }) {
                    Text("Vertrauen & speichern")
                }
            },
            dismissButton = { TextButton(onClick = { fetched = null }) { Text("Abbrechen") } },
        )
    }
}
