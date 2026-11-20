package app.aichatmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aichatmcp.ChatViewModel
import app.aichatmcp.PendingDoc

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
                t("Scene ${run.sceneIndex + 1} of ${run.setup.scenes.size}: ${run.setup.scenes[run.sceneIndex].title}", "Szene ${run.sceneIndex + 1} von ${run.setup.scenes.size}: ${run.setup.scenes[run.sceneIndex].title}"),
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
@Composable
private fun Setup(vm: ChatViewModel, doc: PendingDoc, onLoad: () -> Unit, onStarted: () -> Unit) {
    val choice = rememberRoleplayChoice(doc)
    var turns by remember { mutableStateOf(vm.config.roleplayTurns.toString()) }
    Text(t("“${doc.name}” – ${doc.text.length} characters.", "„${doc.name}“ – ${doc.text.length} Zeichen."), style = MaterialTheme.typography.titleSmall)
    if (choice == null) {
        Text(t("Reading the book…", "Lese das Buch…"), style = MaterialTheme.typography.bodySmall)
    } else {
        RoleplayChoiceFields(choice, vm)
        TextSetting(
            t("Replies per scene (0 = move on by hand)", "Antworten je Szene (0 = nur von Hand weiter)"), turns, { turns = it },
            keyboard = KeyboardType.Number,
        )
        Button(
            onClick = {
                val n = turns.toIntOrNull()
                if (n != null && n >= 0 && n != vm.config.roleplayTurns) vm.saveConfig(vm.config.copy(roleplayTurns = n))
                vm.startRoleplay(doc, choice.playerName, choice.asFirstPerson)
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
