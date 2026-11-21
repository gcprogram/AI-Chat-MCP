package app.aichatmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aichatmcp.ChatViewModel
import app.aichatmcp.PendingDoc
import app.aichatmcp.core.DecisionMode

/**
 * "Buch spielen": Story-Game (Companion mit Spiegel-Bild) macht aus einem geladenen Buch ein Rollenspiel. Hier wird vorbereitet (Buch laden,
 * Figur wählen, Szenenlänge); das Spiel selbst läuft danach im Chat (Leiste mit "Nächste Szene" und "Beenden" oben).
 */
@Composable
fun PlayScreen(vm: ChatViewModel, onBack: () -> Unit, onLoad: () -> Unit, onStarted: () -> Unit) {
    val image = remember { vm.companionImage("story_game") }
    var explain by remember { mutableStateOf(false) }
    val run = vm.roleplay
    val doc = vm.pendingDoc?.takeIf { !it.image }
    ScreenFrame(t("Play a book", "Buch spielen"), onBack) {
        HeroImage(image, "Story-Game")
        TypewriterText(t("Step into the story: choose a book and your character, and we play it together.", "Tritt in die Geschichte ein: Wähle ein Buch und deine Figur, dann spielen wir sie gemeinsam."))
        TextButton(onClick = { explain = !explain }) { Text(if (explain) t("Hide how it works", "Ablauf ausblenden") else t("How does it work?", "Wie funktioniert das?")) }
        if (explain) {
            Text(
                t("1. Load a book (TXT, MD, DOCX, PDF, EPUB or HTML).\n" +
                    "2. Choose your character, or the “I” if the book is told in the first person.\n" +
                    "3. The game master plays the events of the book scene by scene: the narrator and every character except yours. You decide what your character does and says.\n" +
                    "4. If you stray from the plot, the game master reacts in the world and steers you back within one or two replies.\n" +
                    "5. After a number of replies the scene comes to its end and the next one begins (or move on yourself with “Next scene”). “End” leaves the game; your chat with the companion is kept.\n" +
                    "The model never reads the whole book beforehand (that would take hours on a phone): it only sees the current scene and a glimpse of the next one.",
                    "1. Lade ein Buch (TXT, MD, DOCX, PDF, EPUB oder HTML).\n" +
                        "2. Wähle deine Figur, oder das „Ich“, wenn das Buch in der Ich-Form erzählt ist.\n" +
                        "3. Der Spielleiter spielt die Ereignisse des Buches Szene für Szene nach: den Erzähler und jede Figur außer deiner. Du entscheidest, was deine Figur tut und sagt.\n" +
                        "4. Weichst du von der Handlung ab, reagiert der Spielleiter in der Welt und lenkt dich innerhalb von ein bis zwei Antworten zurück.\n" +
                        "5. Nach einer Anzahl von Antworten kommt die Szene zum Ende und die nächste beginnt (oder gehe selbst mit „Nächste Szene“ weiter). „Beenden“ verlässt das Spiel; dein Chat mit dem Companion bleibt erhalten.\n" +
                        "Das Modell liest das Buch nie vorab ganz (das dauert auf dem Handy Stunden): Es sieht nur die aktuelle Szene und einen Blick auf die nächste."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        when {
            run != null -> RunningGame(vm, onStarted)
            vm.docBusy -> Text(t("Reading the file…", "Lese die Datei…"), style = MaterialTheme.typography.bodyMedium)
            doc == null -> {
                Button(onClick = onLoad, modifier = Modifier.fillMaxWidth()) { Text(t("Load a book…", "Buch laden…")) }
                if (vm.status.isNotBlank() && vm.status != "Bereit") Text(vm.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            else -> Setup(vm, doc, onLoad, onStarted)
        }
    }
}

/** Ein Spiel läuft schon: zurück ins Spiel oder beenden. */
@Composable
private fun RunningGame(vm: ChatViewModel, onStarted: () -> Unit) {
    val run = vm.roleplay ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("A game is running: ${run.setup.title}", "Ein Spiel läuft: ${run.setup.title}"), style = MaterialTheme.typography.titleSmall)
            Text(
                t("Scene ${if (run.setup.solo) run.played.size else run.sceneIndex + 1} of ${run.setup.scenes.size}: ${run.setup.scenes[run.sceneIndex].title}", "Szene ${if (run.setup.solo) run.played.size else run.sceneIndex + 1} von ${run.setup.scenes.size}: ${run.setup.scenes[run.sceneIndex].title}"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStarted) { Text(t("Back to the game", "Zurück ins Spiel")) }
                OutlinedButton(onClick = vm::stopRoleplay, enabled = !vm.streaming) { Text(t("End the game", "Spiel beenden")) }
            }
        }
    }
}

/** Das Buch ist geladen: Figur wählen, Länge einer Szene einstellen, starten. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Setup(vm: ChatViewModel, doc: PendingDoc, onLoad: () -> Unit, onStarted: () -> Unit) {
    val choice = rememberRoleplayChoice(doc)
    var turns by remember { mutableStateOf(vm.config.roleplayTurns.toString()) }
    var solo by remember { mutableStateOf(vm.config.soloAdventure) }
    var perDay by remember { mutableStateOf(vm.config.soloScenesPerDay.toString()) }
    var mode by remember { mutableStateOf(DecisionMode.parse(vm.config.soloMode)) }
    Text(t("“${doc.name}” – ${doc.text.length} characters.", "„${doc.name}“ – ${doc.text.length} Zeichen."), style = MaterialTheme.typography.titleSmall)
    if (choice == null) {
        Text(t("Reading the book…", "Lese das Buch…"), style = MaterialTheme.typography.bodySmall)
    } else {
        RoleplayChoiceFields(choice, vm)
        TextSetting(
            t("Replies per scene (0 = move on by hand)", "Antworten je Szene (0 = nur von Hand weiter)"), turns, { turns = it },
            keyboard = KeyboardType.Number,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("Solo adventure", "Solo-Abenteuer"), style = MaterialTheme.typography.titleSmall)
                Text(
                    t("After every scene you choose between 1 and 3 options: one follows the story, one departs from it and can wound or kill your character, one is a variant that leads back to the story. " +
                        "The third wound kills; one wound heals each night. Three wrong decisions in one day lose the story. Good and bad actions towards the main characters earn relationship points.",
                        "Nach jeder Szene wählst du aus 1 bis 3 Möglichkeiten: Eine folgt der Geschichte, eine weicht ab und kann deine Figur verwunden oder töten, eine ist eine Variante, die zur Geschichte zurückführt. " +
                            "Die dritte Verwundung tötet, pro Nacht heilt eine. Drei falsche Entscheidungen an einem Tag verlieren die Geschichte. Gutes und schlechtes Verhalten gegenüber den Hauptfiguren bringt Beziehungspunkte."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
            Switch(checked = solo, onCheckedChange = { solo = it })
        }
        if (solo) {
            Text(
                t("The game master tells each scene as a short passage (a quarter to half a page) from a summary of the book part, then you decide.",
                    "Der Spielleiter erzählt jede Szene als kurzen Abschnitt (eine Viertel- bis halbe Seite) aus einer Zusammenfassung des Buchteils, danach entscheidest du."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
            Text(t("Kind of decisions", "Art der Entscheidungen"), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = mode == DecisionMode.CLASSIC, onClick = { mode = DecisionMode.CLASSIC }, label = { Text(t("Story / risk / detour", "Handlung / Risiko / Umweg")) })
                FilterChip(selected = mode == DecisionMode.STRANDS, onClick = { mode = DecisionMode.STRANDS }, label = { Text(t("Threads", "Stränge")) })
                FilterChip(selected = mode == DecisionMode.MIXED, onClick = { mode = DecisionMode.MIXED }, label = { Text(t("Mixed", "Gemischt")) })
            }
            Text(
                when (mode) {
                    DecisionMode.CLASSIC -> t("One option follows the story, one departs from it (danger), one is a detour back.", "Eine Möglichkeit folgt der Geschichte, eine weicht ab (Gefahr), eine ist ein Umweg zurück.")
                    DecisionMode.STRANDS -> t("You choose which of the next parts of the book comes next, like a different character or an independent adventure. Works best for books with parallel threads or episodes; no danger in these choices.",
                        "Du wählst, welcher der nächsten Buchteile als Nächstes kommt, etwa eine andere Figur oder ein unabhängiges Erlebnis. Passt am besten zu Büchern mit parallelen Strängen oder Episoden; diese Wahl bringt keine Gefahr.")
                    DecisionMode.MIXED -> t("Alternates between the two kinds.", "Wechselt zwischen beiden Arten ab.")
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
            TextSetting(
                t("Scenes per day (then a night passes)", "Szenen je Tag (danach vergeht eine Nacht)"), perDay, { perDay = it },
                keyboard = KeyboardType.Number,
            )
        }
        Button(
            onClick = {
                val n = turns.toIntOrNull()
                val days = perDay.toIntOrNull()?.takeIf { it in 1..20 } ?: vm.config.soloScenesPerDay
                var config = vm.config
                if (n != null && n >= 0) config = config.copy(roleplayTurns = n)
                config = config.copy(soloAdventure = solo, soloScenesPerDay = days, soloMode = mode.name.lowercase())
                if (config != vm.config) vm.saveConfig(config)
                vm.startRoleplay(doc, choice.playerName, choice.asFirstPerson, solo = solo, characters = choice.names)
                onStarted()
            },
            enabled = choice.scenes > 0 && !vm.streaming,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(t("Start the game", "Spiel starten")) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onLoad) { Text(t("Another book…", "Anderes Buch…")) }
        TextButton(onClick = vm::dismissDoc) { Text(t("Discard", "Verwerfen")) }
    }
}
