package app.aichatmcp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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

/** Aurelia, die Autorin: ihr Bild (etwa die halbe Höhe eines Hochkant-Bildschirms) und darunter ein Text, der sich langsam aufbaut, als würde ihn gerade eine KI schreiben. */
@Composable
private fun AureliaHeader(vm: BookViewModel) {
    val image by produceState<ImageBitmap?>(null) { value = withContext(Dispatchers.IO) { vm.aureliaImage()?.let { decodeAvatar(it, target = 1024) } } }
    image?.let {
        Image(it, contentDescription = "Aurelia", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)))
    }
    val full = tNow("I am Aurelia and I write your stories. Describe your wishes below.", "Ich bin Aurelia und schreibe deine Geschichten. Beschreibe deine Wünsche unten.")
    var shown by remember(full) { mutableIntStateOf(0) }
    var cursor by remember { mutableStateOf(true) }
    LaunchedEffect(full) {
        delay(600)
        while (shown < full.length) { // unregelmäßig wie ein erzeugender Text: nach Satzzeichen und Wortenden etwas länger
            shown++
            val c = full[shown - 1]
            delay(if (c == '.' ) 450L else if (c == ' ') 90L else 45L)
        }
        repeat(6) { cursor = !cursor; delay(500) }
        cursor = false
    }
    Text(
        full.take(shown) + if (cursor) "▍" else "",
        style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun BookList(vm: BookViewModel, onBack: () -> Unit) {
    var creating by remember { mutableStateOf(vm.projects.isEmpty()) }
    var explain by remember { mutableStateOf(false) }
    ScreenFrame(t("Write a book", "Buch schreiben"), onBack) {
        AureliaHeader(vm)
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
        if (!creating) OutlinedButton(onClick = { creating = true }) { Text(t("New book…", "Neues Buch…")) }
        else NewBookForm(vm)
    }
}

@Composable
private fun NewBookForm(vm: BookViewModel) {
    var topic by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var chapters by remember { mutableStateOf("8") }
    var words by remember { mutableStateOf("1000") }
    var perspective by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("") }
    var constraints by remember { mutableStateOf("") }
    var language by remember { mutableStateOf(vm.defaultLanguage()) }
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
    TextSetting(t("Language", "Sprache"), language, { language = it })
    Button(
        onClick = {
            vm.create(
                BookBrief(
                    topic.trim(), genre.trim(), chapters.toIntOrNull() ?: 8, words.toIntOrNull() ?: 1000, perspective.trim(), style.trim(), language.trim().ifEmpty { "Deutsch" }, constraints.trim(),
                ),
            )
        },
        enabled = topic.isNotBlank(),
    ) { Text(t("Create book", "Buch anlegen")) }
}

@Composable
private fun BookView(vm: BookViewModel, project: BookProject, onBack: () -> Unit, onSave: (String, Boolean) -> Unit) {
    val (done, total) = project.progress()
    val planReady = project.chapters.isNotEmpty()
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
                !planReady -> Button(onClick = { vm.start(true) }) { Text(t("Create the plan", "Planung erzeugen")) }
                else -> Button(onClick = { vm.start(false) }) { Text(if (project.chapters.any { it.parts > 0 }) t("Continue writing", "Weiterschreiben") else t("Write the chapters", "Kapitel schreiben")) }
            }
        }
        if (!planReady && !vm.running) {
            Text(
                t("First the plot, the characters and the chapter plan are written. Then you can check and change them before the chapters are written.",
                    "Zuerst entstehen Handlung, Figuren und Kapitelplan. Dann kannst du sie prüfen und ändern, bevor die Kapitel geschrieben werden."),
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
        if (planReady) {
            SectionTitle(t("Chapters", "Kapitel"))
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
