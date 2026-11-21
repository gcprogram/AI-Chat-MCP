package app.aichatmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aichatmcp.ChatViewModel
import app.aichatmcp.GamebookState
import app.aichatmcp.PendingDoc
import app.aichatmcp.core.DecisionMode
import app.aichatmcp.core.Gamebook
import app.aichatmcp.core.Gamebooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Buch spielen": Story-Game (Companion mit Spiegel-Bild) macht aus einem geladenen Buch ein Rollenspiel. Hier wird vorbereitet (Buch laden,
 * Figur wählen, Szenenlänge); das Spiel selbst läuft danach im Chat (Leiste mit "Nächste Szene" und "Beenden" oben).
 */
@Composable
fun PlayScreen(
    vm: ChatViewModel, onBack: () -> Unit, onLoad: () -> Unit, onStarted: () -> Unit,
    onLoadGamebook: () -> Unit = {}, onSaveGamebook: (fileName: String, json: String) -> Unit = { _, _ -> },
) {
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
            vm.loadedGamebook != null -> GamebookSetup(vm, vm.loadedGamebook!!, onSaveGamebook, onStarted) // auch bei geladenem Buch: ein geladenes Spielbuch geht vor
            vm.docBusy -> WaitText(t("Reading the file", "Lese die Datei"))
            doc == null -> {
                Button(onClick = onLoad, modifier = Modifier.fillMaxWidth()) { Text(t("Load a book…", "Buch laden…")) }
                OutlinedButton(onClick = onLoadGamebook, modifier = Modifier.fillMaxWidth()) { Text(t("Load a saved game book (.json)…", "Gespeichertes Spielbuch laden (.json)…")) }
                if (vm.status.isNotBlank() && vm.status != "Bereit") Text(vm.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            else -> Setup(vm, doc, onLoad, onStarted, onLoadGamebook, onSaveGamebook)
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
                t("Scene ${if (run.setup.solo) run.sceneNumber else run.sceneIndex + 1} of ${if (run.setup.solo) run.sceneTotal else run.setup.scenes.size}: ${run.setup.scenes[run.sceneIndex].title}", "Szene ${if (run.setup.solo) run.sceneNumber else run.sceneIndex + 1} von ${if (run.setup.solo) run.sceneTotal else run.setup.scenes.size}: ${run.setup.scenes[run.sceneIndex].title}"),
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
private fun Setup(
    vm: ChatViewModel, doc: PendingDoc, onLoad: () -> Unit, onStarted: () -> Unit,
    onLoadGamebook: () -> Unit, onSaveGamebook: (fileName: String, json: String) -> Unit,
) {
    val choice = rememberRoleplayChoice(doc)
    var turns by remember { mutableStateOf(vm.config.roleplayTurns.toString()) }
    var solo by remember { mutableStateOf(vm.config.soloAdventure) }
    var perDay by remember { mutableStateOf(vm.config.soloScenesPerDay.toString()) }
    var mode by remember { mutableStateOf(DecisionMode.parse(vm.config.soloMode)) }
    // Während das Spielbuch vorbereitet wird, soll der Bildschirm nicht ausgehen (bei dunklem Bildschirm drosselt Samsung die Rechenleistung stark)
    val view = LocalView.current
    DisposableEffect(vm.prepBusy) {
        view.keepScreenOn = vm.prepBusy
        onDispose { view.keepScreenOn = false }
    }
    Text(t("“${doc.name}” – ${doc.text.length} characters.", "„${doc.name}“ – ${doc.text.length} Zeichen."), style = MaterialTheme.typography.titleSmall)
    if (choice == null) {
        WaitText(t("Reading the book", "Lese das Buch"), MaterialTheme.typography.bodySmall)
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
            ModeChoice(mode) { mode = it }
            TextSetting(
                t("Scenes per day (then a night passes)", "Szenen je Tag (danach vergeht eine Nacht)"), perDay, { perDay = it },
                keyboard = KeyboardType.Number,
            )
            GamebookCard(vm, doc, onLoadGamebook, onSaveGamebook)
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

/** Die Art der Entscheidungen im Solo-Abenteuer wählen (Handlung/Risiko/Umweg, Stränge, gemischt) mit kurzer Erklärung. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeChoice(mode: DecisionMode, onMode: (DecisionMode) -> Unit) {
    Text(t("Kind of decisions", "Art der Entscheidungen"), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = mode == DecisionMode.CLASSIC, onClick = { onMode(DecisionMode.CLASSIC) }, label = { Text(t("Story / risk / detour", "Handlung / Risiko / Umweg")) })
        FilterChip(selected = mode == DecisionMode.STRANDS, onClick = { onMode(DecisionMode.STRANDS) }, label = { Text(t("Threads", "Stränge")) })
        FilterChip(selected = mode == DecisionMode.MIXED, onClick = { onMode(DecisionMode.MIXED) }, label = { Text(t("Mixed", "Gemischt")) })
    }
    Text(
        when (mode) {
            DecisionMode.CLASSIC -> t("One option follows the story, one departs from it (danger), one is a detour back.", "Eine Möglichkeit folgt der Geschichte, eine weicht ab (Gefahr), eine ist ein Umweg zurück.")
            DecisionMode.STRANDS -> t("You choose which story thread continues, like a different character or an independent adventure, within the current chapter. Needs a prepared game book with several threads; no danger in these choices.",
                "Du wählst, welcher Handlungsstrang weitergeht, etwa eine andere Figur oder ein unabhängiges Erlebnis, innerhalb des aktuellen Kapitels. Braucht ein vorbereitetes Spielbuch mit mehreren Strängen; diese Wahl bringt keine Gefahr.")
            DecisionMode.MIXED -> t("Alternates between the two kinds.", "Wechselt zwischen beiden Arten ab.")
        },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
    )
}

/** Eine Dauer in Sekunden als Text ("etwa 12 Min.", "etwa 2 Std. 10 Min."). */
@Composable
private fun durationText(seconds: Long): String {
    val minutes = (seconds + 30) / 60
    return when {
        seconds < 45 -> t("under a minute", "unter einer Minute")
        minutes < 60 -> t("about $minutes min", "etwa $minutes Min.")
        else -> t("about ${minutes / 60} h ${minutes % 60} min", "etwa ${minutes / 60} Std. ${minutes % 60} Min.")
    }
}

/**
 * Das Spielbuch vorbereiten: je Szene Zusammenfassung, Ort und fertige Entscheidungen, dazu die Handlungsstränge, einmal im Voraus (läuft im Hintergrund, Abbruch kostet nichts).
 * Das Ergebnis lässt sich als JSON-Datei speichern, von Hand ändern und wieder einlesen; das Spiel braucht dann keinen Modellaufruf mehr für Entscheidungen.
 */
@Composable
private fun GamebookCard(vm: ChatViewModel, doc: PendingDoc, onLoadGamebook: () -> Unit, onSaveGamebook: (fileName: String, json: String) -> Unit) {
    // Der Zustand liest den Speicher und zerlegt das Buch: im Hintergrund, nicht bei jedem Zeichnen im Hauptthread (sonst ruckelt die Anzeige und die Karte erscheint spät)
    val loaded by produceState<GamebookState?>(null, doc, vm.prepBusy, vm.prepDone) { value = withContext(Dispatchers.Default) { vm.gamebookState(doc) } }
    val state = loaded
    // Ist die Vorbereitung gerade fertig geworden, wird angeboten, das Spielbuch neben dem Buch zu speichern: im App-Speicher kommt man von außen nicht heran
    var wasBusy by remember { mutableStateOf(false) }
    var offerSave by remember { mutableStateOf(false) }
    LaunchedEffect(vm.prepBusy, state?.ready) {
        if (wasBusy && !vm.prepBusy && state?.ready == true) offerSave = true
        wasBusy = vm.prepBusy
    }
    if (offerSave) {
        AlertDialog(
            onDismissRequest = { offerSave = false },
            title = { Text(t("Game book ready", "Spielbuch fertig")) },
            text = {
                Text(t("It is kept inside the app, where you cannot reach it from outside. Save it as a file now? The dialog opens at the folder of the book.",
                    "Es liegt im Speicher der App, den du von außen nicht erreichst. Jetzt als Datei speichern? Der Dialog öffnet sich im Ordner des Buches."))
            },
            confirmButton = { TextButton(onClick = { offerSave = false; vm.gamebookJson(doc)?.let { onSaveGamebook(doc.name.substringBeforeLast('.') + ".gamebook.json", it) } }) { Text(t("Save…", "Speichern…")) } },
            dismissButton = { TextButton(onClick = { offerSave = false }) { Text(t("Later", "Später")) } },
        )
    }
    if (state == null) {
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { WaitText(t("Checking the game book", "Prüfe das Spielbuch"), MaterialTheme.typography.bodyMedium) } }
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t("Prepare the game book", "Spielbuch vorbereiten"), style = MaterialTheme.typography.titleSmall)
            Text(
                t("Prepares the book once: for every scene a summary, the place, ready-made decisions (that do not give away what comes next) and the story threads. The game then needs no model call for decisions and keeps the order. " +
                    "This takes long. You can also start without it; missing scenes are then summarized while you play, and the decisions are asked from the model.",
                    "Bereitet das Buch einmal vor: je Szene eine Zusammenfassung, der Ort, fertige Entscheidungen (die nichts vom Folgenden verraten) und die Handlungsstränge. Das Spiel braucht dann keinen Modellaufruf für Entscheidungen und hält die Reihenfolge ein. " +
                        "Das dauert lange. Du kannst auch ohne starten; fehlende Szenen werden dann beim Spielen zusammengefasst, und die Entscheidungen fragt die App beim Modell ab."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
            when {
                vm.prepBusy -> {
                    LinearProgressIndicator(progress = { if (vm.prepTotal > 0) vm.prepDone.toFloat() / vm.prepTotal else 0f }, modifier = Modifier.fillMaxWidth())
                    if (vm.prepTotal == 0) {
                        // das Buch wird zerlegt und nach Namen durchsucht, bevor die erste Anfrage hinausgeht
                        WaitText(t("Analyzing the book", "Analysiere das Buch"), MaterialTheme.typography.bodySmall)
                    } else {
                        val current = (vm.prepDone + 1).coerceAtMost(vm.prepTotal)
                        Text(
                            t("Request $current of ${vm.prepTotal}", "Anfrage $current von ${vm.prepTotal}") + if (vm.prepWhat.isNotBlank()) " · ${vm.prepWhat}" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        if (vm.prepRemainingSec >= 0) t("Remaining: ${durationText(vm.prepRemainingSec)}", "Noch ${durationText(vm.prepRemainingSec)}") else t("The time is estimated after the first requests…", "Die Zeit wird nach den ersten Anfragen geschätzt…"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = vm::cancelGamebook) { Text(t("Stop", "Anhalten")) }
                }
                state.ready -> {
                    Text(
                        t("Ready: ${state.scenes} scenes, ${state.threads.size} thread(s): ${state.threads.joinToString(", ")}.", "Fertig: ${state.scenes} Szenen, ${state.threads.size} Strang/Stränge: ${state.threads.joinToString(", ")}."),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.threads.size < 2) {
                        Text(
                            t("Only one thread: the thread choice then falls back to the normal decisions.", "Nur ein Strang: die Strang-Wahl fällt dann auf die normalen Entscheidungen zurück."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Text(
                        t("The game book is kept inside the app. Save it as a file next to the book, edit it by hand (summaries, places, decisions) and load it again.", "Das Spielbuch liegt im Speicher der App. Speichere es als Datei neben dem Buch, ändere es von Hand (Zusammenfassungen, Orte, Entscheidungen) und lies es wieder ein."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    )
                    GamebookFileButtons(onLoadGamebook) { vm.gamebookJson(doc)?.let { onSaveGamebook(doc.name.substringBeforeLast('.') + ".gamebook.json", it) } }
                }
                else -> {
                    val perRequest = vm.gamebookSecondsPerRequest()
                    Text(
                        if (perRequest != null) t("Estimated time: ${durationText((perRequest * state.requests).toLong())} (${state.requests} requests, about ${perRequest.toInt()} s each, measured with this model).",
                            "Geschätzte Zeit: ${durationText((perRequest * state.requests).toLong())} (${state.requests} Anfragen, je etwa ${perRequest.toInt()} s, mit diesem Modell gemessen).")
                        else t("${state.requests} requests. The time is measured while it runs and shown then; with the phone's own model expect a long time.",
                            "${state.requests} Anfragen. Die Zeit wird beim Lauf gemessen und dann angezeigt; mit dem Modell auf dem Handy dauert es lange."),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // gleichzeitige Anfragen: bei einem externen Modell oft mehrfach so schnell, auf dem Handy ohne Wirkung
                    Text(t("Requests at the same time:", "Gleichzeitige Anfragen:"), style = MaterialTheme.typography.labelMedium)
                    val selected = if (vm.gamebookParallel in 1..4) vm.gamebookParallel else if (vm.gamebookIsLocal()) 1 else 3
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..4).forEach { n -> FilterChip(selected = selected == n, onClick = { vm.chooseGamebookParallel(n) }, label = { Text(n.toString()) }) }
                    }
                    if (vm.gamebookIsLocal()) {
                        Text(t("The model on this phone answers one request at a time: more does not help.", "Das Modell auf dem Handy beantwortet nur eine Anfrage zugleich: mehr bringt nichts."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    } else {
                        Text(t("If your server answers one request at a time (for example LM Studio without parallel mode), more does not speed it up, but does no harm.", "Beantwortet dein Server nur eine Anfrage zugleich (etwa LM Studio ohne Parallelbetrieb), wird es nicht schneller, schadet aber nicht."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Button(onClick = { vm.prepareGamebook(doc) }, enabled = !vm.streaming && state.scenes > 0) {
                        Text(t("Prepare (${state.requests} requests)", "Vorbereiten (${state.requests} Anfragen)"))
                    }
                    if (vm.prepError.isNotBlank()) Text(vm.prepError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    GamebookFileButtons(onLoadGamebook, null)
                }
            }
        }
    }
}

/** "Speichern…" (nur mit [onSave]) und "Spielbuch laden…". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GamebookFileButtons(onLoad: () -> Unit, onSave: (() -> Unit)?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onSave != null) OutlinedButton(onClick = onSave) { Text(t("Save game book…", "Spielbuch speichern…")) }
        OutlinedButton(onClick = onLoad) { Text(t("Load game book…", "Spielbuch laden…")) }
    }
}

/** Ein geladenes Spielbuch (JSON): Figur wählen, Entscheidungsart, starten; der Buchtext wird nicht gebraucht. */
@Composable
private fun GamebookSetup(vm: ChatViewModel, book: Gamebook, onSaveGamebook: (fileName: String, json: String) -> Unit, onStarted: () -> Unit) {
    val choice = remember(book) { RoleplayChoice(book.scenes.size, book.firstPerson, book.characters) }
    var perDay by remember { mutableStateOf(vm.config.soloScenesPerDay.toString()) }
    var mode by remember { mutableStateOf(DecisionMode.parse(vm.config.soloMode)) }
    Text(t("Game book “${book.title}” – ${book.scenes.size} scenes.", "Spielbuch „${book.title}“ – ${book.scenes.size} Szenen."), style = MaterialTheme.typography.titleSmall)
    Text(
        t("Threads: ${Gamebooks.threadNames(book).joinToString(", ").ifBlank { "–" }}.", "Stränge: ${Gamebooks.threadNames(book).joinToString(", ").ifBlank { "–" }}."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
    )
    RoleplayChoiceFields(choice, vm)
    ModeChoice(mode) { mode = it }
    TextSetting(t("Scenes per day (then a night passes)", "Szenen je Tag (danach vergeht eine Nacht)"), perDay, { perDay = it }, keyboard = KeyboardType.Number)
    Button(
        onClick = {
            val days = perDay.toIntOrNull()?.takeIf { it in 1..20 } ?: vm.config.soloScenesPerDay
            val config = vm.config.copy(soloAdventure = true, soloScenesPerDay = days, soloMode = mode.name.lowercase())
            if (config != vm.config) vm.saveConfig(config)
            vm.startGamebook(book, choice.playerName, choice.asFirstPerson, choice.names)
            onStarted()
        },
        enabled = Gamebooks.playable(book) && !vm.streaming,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(t("Start the game", "Spiel starten")) }
    if (!Gamebooks.playable(book)) {
        Text(
            t("This game book is incomplete: ${Gamebooks.missingScenes(book)} scene(s) have no summary or entry yet. Prepare it first with the book (it continues where it stopped), or fill the fields in the file.",
                "Dieses Spielbuch ist unvollständig: Bei ${Gamebooks.missingScenes(book)} Szene(n) fehlen Zusammenfassung oder Eingang. Bereite es zuerst mit dem Buch weiter vor (es macht dort weiter) oder fülle die Felder in der Datei."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        )
    }
    GamebookFileButtons({ vm.closeLoadedGamebook() }, { onSaveGamebook(book.title.ifBlank { "gamebook" } + ".gamebook.json", vm.gamebookJson(book)) })
    TextButton(onClick = vm::closeLoadedGamebook) { Text(t("Close", "Schließen")) }
}
