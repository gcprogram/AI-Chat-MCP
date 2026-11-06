package app.aichatmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aichatmcp.ChatViewModel
import app.aichatmcp.core.Persona
import app.aichatmcp.core.Speech
import app.aichatmcp.core.normalizeName
import app.aichatmcp.speech.SpeechOutput

private const val NEW_ENTRY = "(Neu)"

@Composable
fun CompanionsScreen(
    vm: ChatViewModel, onBack: () -> Unit, onImport: () -> Unit, onImportAvatars: () -> Unit, onPickAvatar: (String) -> Unit,
) {
    var selected by remember { mutableStateOf(vm.persona.id) }
    ScreenFrame("Companions", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DropdownField(
                "Companion", vm.personaIds + NEW_ENTRY, if (selected.isEmpty()) NEW_ENTRY else selected,
                { selected = if (it == NEW_ENTRY) "" else it }, Modifier.weight(1f),
            )
            OutlinedButton(onClick = onImport) { Text("Importieren") }
        }
        OutlinedButton(onClick = onImportAvatars) { Text("Avatare aus Ordner holen…") }
        key(selected) {
            PersonaForm(
                initial = if (selected.isEmpty()) Persona(name = "", description = "Du bist eine freundliche, neugierige Gesprächspartnerin.", greeting = "Hallo! Schön, dass du da bist.")
                else vm.loadPersona(selected),
                available = vm.tools.available,
                avatarFile = vm.avatarFile(if (selected.isEmpty()) Persona() else vm.loadPersona(selected)).also { vm.avatarVersion },
                personaId = selected,
                onPickAvatar = { onPickAvatar(selected) },
                onRemoveAvatar = { vm.removeAvatar(selected) },
                canDelete = selected.isNotEmpty() && vm.personaIds.size > 1,
                onSave = { selected = vm.savePersona(it) },
                onDelete = { vm.deletePersona(selected); selected = vm.persona.id },
                loadVoices = vm::loadVoiceOptions,
                onTestVoice = vm::testCompanionVoice,
            )
        }
    }
}

@Composable
private fun PersonaForm(
    initial: Persona, available: List<String>, avatarFile: java.io.File?, personaId: String, onPickAvatar: () -> Unit,
    onRemoveAvatar: () -> Unit, canDelete: Boolean, onSave: (Persona) -> Unit, onDelete: () -> Unit,
    loadVoices: (String, (List<SpeechOutput.VoiceOption>) -> Unit) -> Unit, onTestVoice: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }
    var language by remember { mutableStateOf(initial.language) }
    var greeting by remember { mutableStateOf(initial.greeting) }
    var rules by remember { mutableStateOf(initial.rules.joinToString("\n")) }
    var tools by remember { mutableStateOf(initial.tools.map { normalizeName(it) }.toSet()) }
    var approved by remember { mutableStateOf(initial.approvedTools.map { normalizeName(it) }.toSet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var voice by remember { mutableStateOf(initial.voice) }
    var voiceOptions by remember { mutableStateOf<List<SpeechOutput.VoiceOption>?>(null) } // null = wird noch geladen
    LaunchedEffect(language) {
        voiceOptions = null
        loadVoices(language) { voiceOptions = it }
    }
    // Freigeschaltete, aber gerade nicht verfügbare Werkzeuge nicht verlieren (z. B. Server vorübergehend aus)
    val hidden = initial.tools.filter { normalizeName(it) !in available }
    val hiddenApproved = initial.approvedTools.filter { normalizeName(it) !in available }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AvatarView(avatarFile, name.ifBlank { "?" }, 72.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = onPickAvatar, enabled = personaId.isNotEmpty()) { Text("Bild wählen…") }
            if (initial.avatar.isNotEmpty()) TextButton(onClick = onRemoveAvatar) { Text("Bild entfernen") }
        }
    }
    when {
        personaId.isEmpty() -> Text("Zum Wählen eines Bildes den Companion zuerst speichern.", style = MaterialTheme.typography.bodySmall)
        initial.avatar.isNotEmpty() && avatarFile == null -> Text(
            "Das Bild '${initial.avatar}' fehlt: \"Avatare aus Ordner holen…\" oder ein Bild wählen.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        )
    }
    TextSetting("Name", name, { name = it })
    TextSetting("Beschreibung", description, { description = it }, minLines = 3, maxLines = 8)
    // Bereits gespeicherte freie Werte (z. B. "Plattdeutsch") bleiben als Eintrag erhalten
    val languages = Speech.RESPONSE_LANGUAGES + listOfNotNull(language.takeIf { it.isNotBlank() && it !in Speech.RESPONSE_LANGUAGES })
    DropdownField("Sprache (Antworten, Erkennung, Vorlesen)", languages, language, { language = it })
    val options = voiceOptions.orEmpty()
    val defaultLabel = "(Standardstimme)"
    val voiceLabel = options.firstOrNull { it.name == voice }?.label
        ?: if (voice.isEmpty() || voiceOptions == null) defaultLabel else "(nicht verfügbar: Standardstimme)"
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DropdownField(
            "Stimme", listOf(defaultLabel) + options.map { it.label }, voiceLabel,
            { picked -> voice = options.firstOrNull { it.label == picked }?.name.orEmpty() }, Modifier.weight(1f),
        )
        OutlinedButton(onClick = { onTestVoice(language, voice) }) { Text("Anhören") }
    }
    if (voiceOptions?.isEmpty() == true) {
        Text(
            "Für diese Sprache ist keine Stimme installiert (Einstellungen → Stimmen laden).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
    } else if (options.size == 1) {
        Text("Nur eine Stimme für diese Sprache installiert (Einstellungen → Stimmen laden).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
    TextSetting("Begrüßung", greeting, { greeting = it }, minLines = 2, maxLines = 5)
    TextSetting("Regeln (eine pro Zeile)", rules, { rules = it }, minLines = 2, maxLines = 8)

    SectionTitle("Werkzeuge")
    if (available.isEmpty()) {
        Text("Keine Werkzeuge verfügbar (siehe Werkzeuge im Menü).", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("Freischalten  ·  ohne Rückfrage (vorab genehmigt)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
    available.forEach { alias ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = alias in tools, onCheckedChange = { on -> tools = if (on) tools + alias else tools - alias })
            Text(alias, modifier = Modifier.weight(1f))
            Text("ohne Rückfrage", style = MaterialTheme.typography.bodySmall)
            Checkbox(checked = alias in approved, onCheckedChange = { on -> approved = if (on) approved + alias else approved - alias })
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = {
            onSave(
                initial.copy(
                    name = name.trim(), description = description.trim(), language = language.trim().ifEmpty { "Deutsch" },
                    voice = voice, greeting = greeting.trim(), rules = rules.lines().map { it.trim() }.filter { it.isNotEmpty() },
                    tools = available.filter { it in tools } + hidden,
                    approvedTools = available.filter { it in approved && it in tools } + hiddenApproved,
                ),
            )
        }, enabled = name.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Speichern") }
        if (canDelete) OutlinedButton(onClick = { confirmDelete = true }) { Text("Löschen") }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Companion löschen") },
            text = { Text("'$name' samt laufendem Chat endgültig löschen?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}
