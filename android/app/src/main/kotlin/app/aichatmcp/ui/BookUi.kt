package app.aichatmcp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aichatmcp.BookStop
import app.aichatmcp.BookViewModel
import app.aichatmcp.core.BookBrief
import app.aichatmcp.core.BookProject
import app.aichatmcp.core.BookStep

/** Autorenmodus: Liste der Bücher mit Formular für ein neues, darunter die Ansicht eines Buches. [onSave] = "markdown" oder "docx" (mit/ohne Planung). */
@Composable
fun BookScreen(vm: BookViewModel, onBack: () -> Unit, onSave: (kind: String, includePlan: Boolean) -> Unit) {
    val project = vm.project
    BackHandler(enabled = project != null && !vm.running) { vm.close() }
    // Während das Modell schreibt, soll der Bildschirm nicht ausgehen (bei dunklem Bildschirm drosselt Samsung die Rechenleistung stark)
    val view = LocalView.current
    DisposableEffect(vm.running) {
        view.keepScreenOn = vm.running
        onDispose { view.keepScreenOn = false }
    }
    if (project == null) BookList(vm, onBack) else BookView(vm, project, onBack = { vm.close() }, onSave = onSave)
}

/** Eine Schreibsprache: Anzeigename, Name im Prompt (die Anweisungen an das Modell sind englisch, nur Deutsch hat deutsche) und Aurelias Begrüßung in dieser Sprache. */
private data class WriteLanguage(val label: String, val brief: String, val greeting: String)

private val WRITE_LANGUAGES = listOf(
    WriteLanguage("Deutsch", "Deutsch", "Ich bin Aurelia und schreibe deine Geschichten. Beschreibe deine Wünsche unten."),
    WriteLanguage("English", "English", "I am Aurelia and I write your stories. Describe your wishes below."),
    WriteLanguage("Français", "French", "Je suis Aurélia et j'écris vos histoires. Décrivez vos souhaits ci-dessous."),
    WriteLanguage("Español", "Spanish", "Soy Aurelia y escribo tus historias. Describe tus deseos abajo."),
    WriteLanguage("Italiano", "Italian", "Sono Aurelia e scrivo le tue storie. Descrivi i tuoi desideri qui sotto."),
)

/** Welches Modell die Planung und welches den Kapiteltext schreibt: wie im Chat, auf dem Handy oder das externe Modell aus den Einstellungen. */
@Composable
private fun ModelChoices(vm: BookViewModel) {
    val same = t("As in the settings", "Wie in den Einstellungen")
    val local = tNow("On this phone", "Auf diesem Handy") + vm.localLabel().let { if (it.isNotBlank()) ": $it" else "" }
    val server = tNow("External model", "Externes Modell") + vm.externalLabel().let { if (it.isNotBlank()) ": $it" else "" }
    val options = listOf("" to same, "local" to local, "server" to server)
    fun label(code: String) = options.firstOrNull { it.first == code }?.second ?: same
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("Models for this book", "Modelle für das Buch"), style = MaterialTheme.typography.titleSmall)
            Text(
                t("Plot, characters, chapter plans and scenes are short texts where a strong model plans much better. The chapter text is long and can be written by a smaller model. The external model is the one entered in the settings.",
                    "Handlung, Figuren, Kapitelpläne und Szenen sind kurze Texte, die ein starkes Modell deutlich besser plant. Der Kapiteltext ist lang und kann von einem kleineren Modell geschrieben werden. Das externe Modell ist das in den Einstellungen eingetragene."),
                style = MaterialTheme.typography.bodySmall,
            )
            DropdownField(
                t("Planning (plot, characters, plan, scenes, summaries)", "Planung (Handlung, Figuren, Plan, Szenen, Zusammenfassungen)"), options.map { it.second }, label(vm.planChoice),
                { chosen -> vm.setModelChoices(options.first { it.second == chosen }.first, vm.writeChoice) }, raw = true,
            )
            DropdownField(
                t("Chapter text", "Kapiteltext"), options.map { it.second }, label(vm.writeChoice),
                { chosen -> vm.setModelChoices(vm.planChoice, options.first { it.second == chosen }.first) }, raw = true,
            )
        }
    }
}

/** Aurelia, die Autorin: ihr Bild (etwa die halbe Höhe eines Hochkant-Bildschirms) und darunter ein Text, der sich langsam aufbaut, als würde ihn gerade eine KI schreiben. */
@Composable
private fun AureliaHeader(vm: BookViewModel, language: WriteLanguage, onLanguage: (WriteLanguage) -> Unit) {
    HeroImage(remember { vm.aureliaImage() }, "Aurelia")
    DropdownField(
        t("Language of the book", "Sprache des Buches"), WRITE_LANGUAGES.map { it.label }, language.label,
        { label -> WRITE_LANGUAGES.firstOrNull { it.label == label }?.let(onLanguage) }, raw = true,
    )
    TypewriterText(language.greeting) // ändert der Nutzer die Sprache, tippt Aurelia den Text neu (in dieser Sprache)
}

@Composable
private fun BookList(vm: BookViewModel, onBack: () -> Unit) {
    var creating by remember { mutableStateOf(vm.projects.isEmpty()) }
    var explain by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(WRITE_LANGUAGES.firstOrNull { it.brief == vm.defaultLanguage() } ?: WRITE_LANGUAGES[0]) }
    ScreenFrame(t("Write a book", "Buch schreiben"), onBack) {
        AureliaHeader(vm, language) { language = it }
        TextButton(onClick = { explain = !explain }) { Text(if (explain) t("Hide how it works", "Ablauf ausblenden") else t("How does it work?", "Wie funktioniert das?")) }
        if (explain) Text(
            t("Give a topic and some conditions. The model first writes the plot, then the characters with their background, then a short plan for every chapter, and only then the chapters. You can check and change each stage. Writing is slow on a phone (a chapter takes several minutes); it continues in the background and you can stop and resume at any time.",
                "Gib ein Thema und Rahmenbedingungen vor. Das Modell schreibt zuerst die Handlung, dann die Figuren mit ihrem Hintergrund, dann einen kurzen Plan für jedes Kapitel und erst danach die Kapitel. Jede Stufe kannst du prüfen und ändern. Das Schreiben ist auf dem Handy langsam (ein Kapitel braucht einige Minuten); es läuft im Hintergrund weiter, und du kannst jederzeit anhalten und später fortsetzen."),
            style = MaterialTheme.typography.bodySmall,
        )
        vm.projects.forEach { p ->
            val (done, total) = p.progress()
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.displayTitle, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (p.finished) t("finished", "fertig") else t("step $done of $total", "Schritt $done von $total"),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    OutlinedButton(onClick = { vm.open(p.id) }) { Text(t("Open", "Öffnen")) }
                    TextButton(onClick = { vm.delete(p.id) }) { Text(t("Delete", "Löschen")) }
                }
            }
        }
        ModelChoices(vm)
        if (!creating) OutlinedButton(onClick = { creating = true }) { Text(t("New book…", "Neues Buch…")) }
        else NewBookForm(vm, language.brief)
    }
}

@Composable
private fun NewBookForm(vm: BookViewModel, language: String) {
    var topic by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var chapters by remember { mutableStateOf("8") }
    var words by remember { mutableStateOf("1000") }
    var perspective by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("") }
    var constraints by remember { mutableStateOf("") }
    SectionTitle(t("New book", "Neues Buch"))
    TextSetting(t("Topic / idea", "Thema / Idee"), topic, { topic = it }, minLines = 3, maxLines = 8)
    TextSetting(t("Genre (optional)", "Genre (optional)"), genre, { genre = it })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextSetting(t("Chapters", "Kapitel"), chapters, { chapters = it }, modifier = Modifier.weight(1f), keyboard = KeyboardType.Number)
        TextSetting(t("Words per chapter", "Wörter je Kapitel"), words, { words = it }, modifier = Modifier.weight(1f), keyboard = KeyboardType.Number)
    }
    TextSetting(t("Point of view (optional, e.g. first person)", "Perspektive (optional, z. B. Ich-Erzähler)"), perspective, { perspective = it })
    TextSetting(t("Style (optional)", "Stil (optional)"), style, { style = it })
    TextSetting(t("Conditions (places, time, characters, taboos)", "Rahmenbedingungen (Orte, Zeit, Figuren, Tabus)"), constraints, { constraints = it }, minLines = 2, maxLines = 6)
    Button(
        onClick = {
            vm.create(
                BookBrief(
                    topic.trim(), genre.trim(), chapters.toIntOrNull() ?: 8, words.toIntOrNull() ?: 1000, perspective.trim(), style.trim(), language, constraints.trim(),
                ),
            )
        },
        enabled = topic.isNotBlank(),
    ) { Text(t("Create book", "Buch anlegen")) }
}

@Composable
private fun BookView(vm: BookViewModel, project: BookProject, onBack: () -> Unit, onSave: (String, Boolean) -> Unit) {
    val (done, total) = project.progress()
    val chaptersExist = project.chapters.isNotEmpty()
    var includePlan by remember { mutableStateOf(false) }
    ScreenFrame(project.displayTitle, onBack) {
        LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        if (vm.status.isNotEmpty()) Text(vm.status, style = MaterialTheme.typography.bodySmall)
        if (vm.error.isNotEmpty()) Text(vm.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (vm.running && vm.live.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) { Text(vm.live, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                vm.running -> Button(onClick = vm::stop) { Text(t("Stop", "Anhalten")) }
                project.finished -> Text(t("The book is finished. Save it below.", "Das Buch ist fertig. Speichere es unten."), style = MaterialTheme.typography.bodyMedium)
                project.premise.isBlank() -> Button(onClick = { vm.start(BookStop.PLOT) }) { Text(t("Write the plot", "Handlung schreiben")) }
                !project.planReady -> Button(onClick = { vm.start(BookStop.PLAN) }) {
                    Text(if (project.characters.isBlank()) t("Create characters and plan", "Figuren und Plan erzeugen") else t("Continue the plan", "Plan fortsetzen"))
                }
                else -> Button(onClick = { vm.start(BookStop.END) }) { Text(if (project.chapters.any { it.parts > 0 }) t("Continue writing", "Weiterschreiben") else t("Write the chapters", "Kapitel schreiben")) }
            }
        }
        if (!project.planReady && !vm.running) {
            Text(
                if (project.premise.isBlank()) t("First only the plot is written. Then you read it, change it or let it be written again, before anything is built on it.",
                    "Zuerst entsteht nur die Handlung. Dann liest du sie, änderst sie oder lässt sie neu schreiben, bevor etwas darauf aufbaut.")
                else t("The plot is ready. If it is right, create the characters and the plan (chapter plans and scenes); you can check and change those too before the chapters are written.",
                    "Die Handlung steht. Wenn sie stimmt, erzeuge Figuren und Plan (Kapitelpläne und Szenen); auch die kannst du prüfen und ändern, bevor die Kapitel geschrieben werden."),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (project.premise.isNotBlank()) {
            SectionTitle(t("Plot", "Handlung"))
            TextSetting("", project.premise, { v -> vm.edit { it.copy(premise = v) } }, minLines = 4, maxLines = 14)
            if (!vm.running) TextButton(onClick = { vm.redo(BookStep.Premise) }) { Text(t("Rewrite (clears everything after it)", "Neu schreiben (leert alles danach)")) }
        }
        if (project.characters.isNotBlank()) {
            SectionTitle(t("Characters (dramatis personae)", "Figuren (Dramatis Personae)"))
            TextSetting("", project.characters, { v -> vm.edit { it.copy(characters = v) } }, minLines = 6, maxLines = 24)
            if (!vm.running) TextButton(onClick = { vm.redo(BookStep.Characters) }) { Text(t("Rewrite (clears the chapters)", "Neu schreiben (leert die Kapitel)")) }
        }
        if (chaptersExist) {
            SectionTitle(t("Chapters", "Kapitel"))
            if (!vm.running && project.chapters.none { it.text.isNotBlank() }) {
                TextButton(onClick = { vm.redo(BookStep.Plan) }) { Text(t("Rewrite all chapter plans and scenes", "Alle Kapitelpläne und Szenen neu schreiben")) }
            }
            project.chapters.forEachIndexed { i, c ->
                var open by remember(i) { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val state = when {
                            c.summary.isNotBlank() -> t("done", "fertig")
                            c.parts > 0 -> t("part ${c.parts}/${project.partsPerChapter}", "Teil ${c.parts}/${project.partsPerChapter}")
                            else -> t("not written yet", "noch nicht geschrieben")
                        }
                        Text("${i + 1}. $state", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        TextSetting(t("Title", "Titel"), c.title, { v -> vm.edit { p -> p.copy(chapters = p.chapters.mapIndexed { j, ch -> if (j == i) ch.copy(title = v) else ch }) } })
                        TextSetting(t("Plan", "Plan"), c.plan, { v -> vm.edit { p -> p.copy(chapters = p.chapters.mapIndexed { j, ch -> if (j == i) ch.copy(plan = v) else ch }) } }, minLines = 2, maxLines = 8)
                        if (c.beats.isNotBlank()) {
                            TextSetting(t("Scenes (beats), each part of the chapter writes its share", "Szenen (Beats), jeder Teil des Kapitels schreibt seinen Anteil"), c.beats, { v -> vm.edit { p -> p.copy(chapters = p.chapters.mapIndexed { j, ch -> if (j == i) ch.copy(beats = v) else ch }) } }, minLines = 3, maxLines = 12)
                        }
                        if (!vm.running && c.plan.isNotBlank() && c.text.isBlank()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { vm.redo(BookStep.ChapterPlan(i)) }) { Text(t("Rewrite plan and scenes", "Plan und Szenen neu")) }
                                if (c.beats.isNotBlank()) TextButton(onClick = { vm.redo(BookStep.Beats(i)) }) { Text(t("Rewrite scenes", "Szenen neu")) }
                            }
                        }
                        if (c.text.isNotBlank()) {
                            TextButton(onClick = { open = !open }) { Text(if (open) t("Hide text", "Text ausblenden") else t("Show text (${c.text.split(Regex("\\s+")).size} words)", "Text anzeigen (${c.text.split(Regex("\\s+")).size} Wörter)")) }
                            if (open) SelectionContainer { Text(c.text, style = MaterialTheme.typography.bodyMedium) }
                            if (!vm.running) TextButton(onClick = { vm.redo(BookStep.Part(i, 1, project.partsPerChapter)) }) { Text(t("Rewrite this chapter", "Dieses Kapitel neu schreiben")) }
                        }
                    }
                }
            }
        }

        if (project.chapters.any { it.text.isNotBlank() }) {
            SectionTitle(t("Save", "Speichern"))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(t("Include plot, characters and chapter plan", "Mit Handlung, Figuren und Kapitelplan"), modifier = Modifier.weight(1f))
                Switch(checked = includePlan, onCheckedChange = { includePlan = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSave("md", includePlan) }) { Text(t("Save as Markdown…", "Als Markdown speichern…")) }
                OutlinedButton(onClick = { onSave("docx", includePlan) }) { Text(t("Save as DOCX…", "Als DOCX speichern…")) }
            }
        }
    }
}
