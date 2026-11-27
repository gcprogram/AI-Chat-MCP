package app.aichatmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aichatmcp.TranslateViewModel
import app.aichatmcp.core.StoredFile
import app.aichatmcp.core.translate.Languages
import app.aichatmcp.translate.ExportFormat

/**
 * "Translate": ein Dokument (TXT, MD, DOCX, PDF, EPUB) Abschnitt für Abschnitt übersetzen, offline mit TranslateGemma auf dem Handy oder
 * über den Server. Absätze, Überschriften und Bilder bleiben erhalten; das Ergebnis lässt sich in alle Formate speichern oder vorlesen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslateScreen(
    vm: TranslateViewModel, onBack: () -> Unit, onPick: () -> Unit, onExport: (ExportFormat) -> Unit, onRead: () -> Unit, onOpenModels: () -> Unit,
) {
    val localFiles by produceState<List<StoredFile>>(emptyList(), vm.onDevice) { value = vm.localFiles() }
    var showGlossary by remember { mutableStateOf(vm.glossary.isNotBlank()) }
    val hasResult = vm.sections.isNotEmpty()
    ScreenFrame(t("Translate", "Übersetzen"), onBack) {
        // 1. Dokument
        SectionTitle(t("Document", "Dokument"))
        val input = vm.input
        Text(
            input?.name ?: t("No file chosen. Pick a TXT, MD, DOCX, PDF or EPUB file, or use “Translate” on a document in the chat.", "Keine Datei gewählt. Wähle eine TXT-, MD-, DOCX-, PDF- oder EPUB-Datei oder nimm „Übersetzen“ an einem Dokument im Chat."),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPick, enabled = !vm.running) { Text(t("Choose file…", "Datei wählen…")) }
            if (input != null) TextButton(onClick = vm::clearInput, enabled = !vm.running) { Text(t("Remove", "Entfernen")) }
        }

        // 2. Sprachen
        SectionTitle(t("Languages", "Sprachen"))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            DropdownField(
                t("From", "Von"), Languages.displayList, Languages.displayName(vm.langSource),
                { vm.setLanguages(Languages.codeFromDisplay(it), vm.langTarget) }, Modifier.weight(1f), raw = true,
            )
            TextButton(onClick = vm::swapLanguages, enabled = !vm.running) { Text("⇄") }
            DropdownField(
                t("To", "Nach"), Languages.displayList, Languages.displayName(vm.langTarget),
                { vm.setLanguages(vm.langSource, Languages.codeFromDisplay(it)) }, Modifier.weight(1f), raw = true,
            )
        }

        // 3. Modell
        SectionTitle(t("Model", "Modell"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = vm.onDevice, onClick = { vm.chooseOnDevice(true) }, enabled = !vm.running, label = { Text(t("On this phone (offline)", "Auf diesem Handy (offline)")) })
            FilterChip(selected = !vm.onDevice, onClick = { vm.chooseOnDevice(false) }, enabled = !vm.running, label = { Text(t("Server", "Server")) })
        }
        if (vm.onDevice) {
            if (localFiles.isEmpty()) {
                Text(
                    t("No model on this phone yet. TranslateGemma 4B (2.4 GB) translates best; download it in Setup → Models.", "Noch kein Modell auf dem Handy. TranslateGemma 4B (2,4 GB) übersetzt am besten; lade es unter Setup → Modelle."),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onOpenModels) { Text(t("Open models…", "Modelle öffnen…")) }
            } else {
                DropdownField(
                    t("Model file", "Modelldatei"), localFiles.map { it.name }, vm.localName,
                    { name -> localFiles.firstOrNull { it.name == name }?.let(vm::setLocalModel) }, raw = true,
                )
                if ("translategemma" !in vm.localName.lowercase()) {
                    Text(
                        t("Only TranslateGemma models translate on the phone. Download it in Setup → Models.", "Auf dem Handy übersetzen nur TranslateGemma-Modelle. Lade es unter Setup → Modelle."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        } else {
            val models = remember(vm.serverModel) { vm.serverModels() }
            if (models.isEmpty()) {
                Text(t("No server models known. Load the list in Setup first.", "Keine Server-Modelle bekannt. Lade zuerst die Liste im Setup."), style = MaterialTheme.typography.bodySmall)
            } else {
                DropdownField(t("Server model", "Server-Modell"), models, vm.serverModel, vm::chooseServerModel, raw = true)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = vm.useContext, onCheckedChange = vm::changeUseContext, enabled = !vm.running)
            Text(t("Use the previous section as context (consistent names and tone)", "Vorgängerabschnitt als Kontext (einheitliche Namen und Ton)"), style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = vm.readWhenDone, onCheckedChange = vm::changeReadWhenDone)
            Text(t("Read the translation aloud when it is finished", "Übersetzung gleich vorlesen, sobald sie fertig ist"), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { showGlossary = !showGlossary }) { Text(if (showGlossary) t("Hide glossary", "Glossar ausblenden") else t("Glossary…", "Glossar…")) }
        if (showGlossary) {
            TextSetting(
                t("Glossary (one “term: translation” per line)", "Glossar (je Zeile „Begriff: Übersetzung“)"), vm.glossary, vm::changeGlossary,
                minLines = 3, maxLines = 8,
            )
        }

        // 4. Start
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (vm.running) {
                Button(onClick = vm::cancel) { Text(t("Cancel", "Abbrechen")) }
            } else {
                Button(onClick = vm::start, enabled = input != null) { Text(t("Translate", "Übersetzen")) }
                if (vm.pending.isNotEmpty()) {
                    OutlinedButton(onClick = vm::resume) { Text(t("Continue (${vm.pending.size} sections left)", "Fortsetzen (noch ${vm.pending.size} Abschnitte)")) }
                }
                if (hasResult || vm.pending.isNotEmpty()) TextButton(onClick = vm::discard) { Text(t("Discard", "Verwerfen")) }
            }
        }
        if (vm.total > 0) LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
        if (vm.status.isNotBlank()) Text(vm.status, style = MaterialTheme.typography.bodySmall)
        if (vm.error.isNotBlank() && !vm.running) {
            Text(vm.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        // 5. Ergebnis
        if (hasResult) {
            SectionTitle(if (vm.documentName.isNotBlank()) t("Result: ${vm.documentName}", "Ergebnis: ${vm.documentName}") else t("Result", "Ergebnis"))
            val shown = vm.sections.takeLast(2)
            val hidden = vm.sections.size - shown.size
            if (hidden > 0) Text(t("… $hidden earlier sections not shown here; they are in the export.", "… $hidden frühere Abschnitte sind hier nicht eingeblendet; sie stehen im Export."), style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEach { s ->
                        Text(s.translation.take(PREVIEW_CHARS) + if (s.translation.length > PREVIEW_CHARS) " …" else "", style = MaterialTheme.typography.bodyMedium)
                        s.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            val warned = vm.sections.count { it.warnings.isNotEmpty() }
            if (warned > 0) Text(t("$warned sections have notes (e.g. glossary not followed).", "$warned Abschnitte haben Hinweise (z. B. Glossar nicht eingehalten)."), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onRead, enabled = !vm.running) { Text(t("🔊 Read aloud", "🔊 Vorlesen")) }
            Text(t("Save as", "Speichern als"), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ExportFormat.entries.forEach { format ->
                    OutlinedButton(onClick = { onExport(format) }, enabled = !vm.running) { Text(format.label) }
                }
            }
        }
    }
}

private const val PREVIEW_CHARS = 1500
