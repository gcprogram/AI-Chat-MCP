package app.aichatmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aichatmcp.ChatViewModel
import app.aichatmcp.PendingDoc
import app.aichatmcp.core.BookCast
import app.aichatmcp.core.BookScenes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Rollenspiel mit der geladenen Datei als Vorlage: Figur wählen (oder "Ich"), dann startet der Spielleiter die erste Szene. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoleplayDialog(doc: PendingDoc, vm: ChatViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val scenes by produceState<Int?>(null, doc) { value = withContext(Dispatchers.Default) { BookScenes.split(doc.text).size } }
    val firstPerson by produceState(false, doc) { value = withContext(Dispatchers.Default) { BookCast.firstPerson(doc.text) } }
    val found by produceState(emptyList<String>(), doc) { value = withContext(Dispatchers.Default) { BookCast.candidates(doc.text) } }
    var names by remember(found) { mutableStateOf(found.take(12)) }
    var player by remember(firstPerson) { mutableStateOf(if (firstPerson) "" else found.firstOrNull().orEmpty()) }
    var custom by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t("Role play with this book", "Rollenspiel mit diesem Buch")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    t("The companion becomes the game master and plays the events of the book scene by scene: the narrator and all characters except yours. If you stray from the plot it steers you back. You decide what your character does.",
                        "Der Companion wird zum Spielleiter und spielt die Ereignisse des Buches Szene für Szene nach: den Erzähler und alle Figuren außer deiner. Weichst du von der Handlung ab, lenkt er dich zurück. Du entscheidest, was deine Figur tut."),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    scenes?.let { t("$it scenes found. ", "$it Szenen gefunden. ") }.orEmpty() +
                        if (firstPerson) t("The book is told in the first person: you can play the “I”.", "Das Buch ist in der Ich-Form erzählt: du kannst das „Ich“ spielen.") else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(t("Your character:", "Deine Figur:"), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = player.isEmpty() && custom.isBlank(), onClick = { player = ""; custom = "" }, label = { Text(t("I (narrator)", "Ich (Erzähler)")) })
                    names.forEach { n -> FilterChip(selected = player == n && custom.isBlank(), onClick = { player = n; custom = "" }, label = { Text(n) }) }
                }
                TextSetting(t("or another name", "oder ein anderer Name"), custom, { custom = it })
                if (found.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { scope.launch { checking = true; names = vm.detectCast(found); if (player !in names && player.isNotEmpty()) player = names.firstOrNull().orEmpty(); checking = false } },
                        enabled = !checking && !vm.streaming,
                    ) { Text(if (checking) t("Checking…", "Prüfe…") else t("Check the names with the model", "Namen mit dem Modell prüfen")) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.startRoleplay(doc, custom.trim().ifEmpty { player }, firstPerson && custom.isBlank() && player.isEmpty()); onClose() },
                enabled = scenes != null && scenes != 0 && !vm.streaming,
            ) { Text(t("Start", "Starten")) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(t("Cancel", "Abbrechen")) } },
    )
}

/** Leiste über dem Chat während eines Buch-Rollenspiels: Szene, nächste Szene, beenden. */
@Composable
fun RoleplayBar(vm: ChatViewModel) {
    val run = vm.roleplay ?: return
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${run.setup.title} · " + t("scene ${run.sceneIndex + 1}/${run.setup.scenes.size}", "Szene ${run.sceneIndex + 1}/${run.setup.scenes.size}") + ": ${run.setup.scenes[run.sceneIndex].title}",
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 2,
            )
            TextButton(onClick = vm::nextScene, enabled = !vm.streaming && run.sceneIndex + 1 < run.setup.scenes.size) { Text(t("Next scene", "Nächste Szene")) }
            TextButton(onClick = vm::stopRoleplay, enabled = !vm.streaming) { Text(t("End", "Beenden")) }
        }
    }
}
