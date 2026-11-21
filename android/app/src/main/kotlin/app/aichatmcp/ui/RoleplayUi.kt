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
import androidx.compose.material3.Button
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
import app.aichatmcp.core.DecisionKind
import app.aichatmcp.core.Solo
import app.aichatmcp.core.SoloEnd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Die Wahl für ein Buch-Rollenspiel: Zahl der Szenen, Ich-Erzählung, Namenskandidaten und die gewählte Spielerfigur. */
class RoleplayChoice(val scenes: Int, val firstPerson: Boolean, val found: List<String>) {
    var names by mutableStateOf(found.take(12))
    var player by mutableStateOf(if (firstPerson) "" else found.firstOrNull().orEmpty())
    var custom by mutableStateOf("")
    var checking by mutableStateOf(false)

    /** Name der Spielerfigur; leer = der Ich-Erzähler. */
    val playerName: String get() = custom.trim().ifEmpty { player }
    val asFirstPerson: Boolean get() = firstPerson && custom.isBlank() && player.isEmpty()
}

/** Untersucht das Buch (Szenen, Erzählform, Namen) im Hintergrund; bis dahin null. */
@Composable
fun rememberRoleplayChoice(doc: PendingDoc): RoleplayChoice? {
    val data by produceState<Triple<Int, Boolean, List<String>>?>(null, doc) {
        value = withContext(Dispatchers.Default) { Triple(BookScenes.split(doc.text).size, BookCast.firstPerson(doc.text), BookCast.candidates(doc.text)) }
    }
    return data?.let { d -> remember(d) { RoleplayChoice(d.first, d.second, d.third) } }
}

/** Die Felder zur Wahl der Spielerfigur (Ich, Namen aus dem Buch, eigener Name) samt Namensprüfung durch das Modell. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoleplayChoiceFields(choice: RoleplayChoice, vm: ChatViewModel) {
    val scope = rememberCoroutineScope()
    Text(
        t("${choice.scenes} scenes found. ", "${choice.scenes} Szenen gefunden. ") +
            if (choice.firstPerson) t("The book is told in the first person: you can play the “I”.", "Das Buch ist in der Ich-Form erzählt: du kannst das „Ich“ spielen.") else "",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(t("Your character:", "Deine Figur:"), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = choice.player.isEmpty() && choice.custom.isBlank(), onClick = { choice.player = ""; choice.custom = "" }, label = { Text(t("I (narrator)", "Ich (Erzähler)")) })
        choice.names.forEach { n -> FilterChip(selected = choice.player == n && choice.custom.isBlank(), onClick = { choice.player = n; choice.custom = "" }, label = { Text(n) }) }
    }
    TextSetting(t("or another name", "oder ein anderer Name"), choice.custom, { choice.custom = it })
    if (choice.found.isNotEmpty()) {
        OutlinedButton(
            onClick = {
                scope.launch {
                    choice.checking = true
                    choice.names = vm.detectCast(choice.found)
                    if (choice.player !in choice.names && choice.player.isNotEmpty()) choice.player = choice.names.firstOrNull().orEmpty()
                    choice.checking = false
                }
            },
            enabled = !choice.checking && !vm.streaming,
        ) { Text(if (choice.checking) t("Checking…", "Prüfe…") else t("Check the names with the model", "Namen mit dem Modell prüfen")) }
    }
}

/** Rollenspiel mit der geladenen Datei als Vorlage: Figur wählen (oder "Ich"), dann startet der Spielleiter die erste Szene. */
@Composable
fun RoleplayDialog(doc: PendingDoc, vm: ChatViewModel, onClose: () -> Unit) {
    val choice = rememberRoleplayChoice(doc)
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
                if (choice == null) WaitText(t("Reading the book", "Lese das Buch"), MaterialTheme.typography.bodySmall) else RoleplayChoiceFields(choice, vm)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { choice?.let { vm.startRoleplay(doc, it.playerName, it.asFirstPerson) }; onClose() },
                enabled = choice != null && choice.scenes != 0 && !vm.streaming,
            ) { Text(t("Start", "Starten")) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(t("Cancel", "Abbrechen")) } },
    )
}

/** Leiste über dem Chat während eines Buch-Rollenspiels: Szene, nächste Szene (im Solo-Abenteuer: Szene beenden und entscheiden), beenden; im Solo-Abenteuer der Spielstand. */
@Composable
fun RoleplayBar(vm: ChatViewModel) {
    val run = vm.roleplay ?: return
    val solo = run.setup.solo
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = if (solo) 0.dp else 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${run.setup.title} · " + t("scene ${if (solo) run.sceneNumber else run.sceneIndex + 1}/${if (solo) run.sceneTotal else run.setup.scenes.size}", "Szene ${if (solo) run.sceneNumber else run.sceneIndex + 1}/${if (solo) run.sceneTotal else run.setup.scenes.size}") + ": ${run.setup.scenes[run.sceneIndex].title}",
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 2,
                )
                if (solo) {
                    TextButton(
                        onClick = vm::nextScene,
                        enabled = !vm.streaming && !run.asking && run.decisions.isEmpty() && run.solo.end == null && run.turns >= 1,
                    ) { Text(t("End scene", "Szene beenden")) }
                } else {
                    TextButton(onClick = vm::nextScene, enabled = !vm.streaming && run.sceneIndex + 1 < run.setup.scenes.size) { Text(t("Next scene", "Nächste Szene")) }
                }
                TextButton(onClick = vm::stopRoleplay, enabled = !vm.streaming) { Text(t("End", "Beenden")) }
            }
            if (solo) {
                Text(
                    Solo.statusLine(run.solo, run.setup.german), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                )
            }
        }
    }
}

/**
 * Solo-Abenteuer: nach jeder Szene die Entscheidungen (ohne Angabe, welche der Handlung folgt, welche riskant ist und welche ein Umweg: die Reihenfolge ist gemischt),
 * am Spielende die Karte mit "Noch einmal" und "Beenden".
 */
@Composable
fun SoloPanel(vm: ChatViewModel) {
    val run = vm.roleplay ?: return
    if (!run.setup.solo) return
    val end = run.solo.end
    when {
        end != null && !vm.streaming -> {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        when (end) {
                            SoloEnd.DEAD -> t("Your character has died. The adventure is over.", "Deine Figur ist gestorben. Das Abenteuer ist zu Ende.")
                            SoloEnd.LOST -> t("The story is lost: you strayed too far from it.", "Die Geschichte ist verloren: Du bist zu weit von ihr abgewichen.")
                            SoloEnd.WON -> t("You played the story to its end.", "Du hast die Geschichte bis zum Ende gespielt.")
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(Solo.statusLine(run.solo, run.setup.german), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::restartGame) { Text(t("Play again", "Noch einmal")) }
                        OutlinedButton(onClick = vm::stopRoleplay) { Text(t("End", "Beenden")) }
                    }
                }
            }
        }
        // nach der Wahl bleibt die Karte stehen: die gewählte Entscheidung ist hervorgehoben, die anderen sind abgeblendet, bis die nächsten Möglichkeiten kommen
        run.answered.isNotEmpty() && !run.asking -> {
            val order = run.order.takeIf { o -> o.size == run.answered.size && o.toSet() == run.answered.indices.toSet() } ?: run.answered.indices.toList()
            Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t("You decided:", "Du hast entschieden:"), style = MaterialTheme.typography.titleSmall)
                    order.forEach { i ->
                        val chosen = i == run.chosen
                        if (chosen) {
                            Button(onClick = {}, enabled = true, modifier = Modifier.fillMaxWidth()) { Text(run.answered[i].text, style = MaterialTheme.typography.bodyMedium) }
                        } else {
                            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(run.answered[i].text, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        }
        run.asking -> Text(
            t("The game master is thinking of your options…", "Der Spielleiter überlegt deine Möglichkeiten…"),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        run.decisions.isNotEmpty() && !vm.streaming -> {
            // gemischt, aber stabil für diese Szene (die App legt die Reihenfolge fest): sonst stünde die Entscheidung der Handlung immer oben
            val order = run.order.takeIf { o -> o.size == run.decisions.size && o.toSet() == run.decisions.indices.toSet() } ?: run.decisions.indices.toList()
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t("What do you do?", "Wie entscheidest du?"), style = MaterialTheme.typography.titleSmall)
                    order.forEach { i ->
                        OutlinedButton(onClick = { vm.chooseDecision(i) }, modifier = Modifier.fillMaxWidth()) {
                            Text(run.decisions[i].text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(
                        if (run.decisions.any { it.kind == DecisionKind.STRAND }) t("Choose which thread continues; the others wait for later. You can also write freely and decide later.", "Wähle, mit welchem Strang es weitergeht; die anderen kommen später dran. Du kannst auch frei schreiben und später entscheiden.")
                        else t("Careful: a decision away from the story can hurt or kill your character. You can also write freely and decide later.", "Vorsicht: Eine Entscheidung abseits der Geschichte kann deine Figur verletzen oder töten. Du kannst auch frei schreiben und später entscheiden."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}
