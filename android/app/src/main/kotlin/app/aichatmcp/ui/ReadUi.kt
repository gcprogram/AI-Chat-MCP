package app.aichatmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aichatmcp.ChatViewModel
import app.aichatmcp.PendingDoc
import app.aichatmcp.core.PIPER_PREFIX
import app.aichatmcp.core.ReadMark
import app.aichatmcp.core.ReaderState
import app.aichatmcp.core.SummaryLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.aichatmcp.speech.SpeechOutput

/**
 * "Buch vorlesen": Der Vorleser (Companion mit Mondlicht-Bild) liest ein Buch ab, Abschnitt für Abschnitt, mit Play, Pause, Stopp und Sprüngen direkt
 * unter dem Bild. Darunter die Stimmen: Erzähler, Männer und Frauen in wörtlicher Rede. Die Stimmen gehören zum Companion Vorleser.
 */
@Composable
fun ReadScreen(vm: ChatViewModel, onBack: () -> Unit, onLoad: () -> Unit, onSaveText: (fileName: String, text: String) -> Unit) {
    LaunchedEffect(Unit) { vm.openReadBook() }
    // Während das Modell zusammenfasst, soll der Bildschirm nicht ausgehen (bei dunklem Bildschirm drosselt Samsung die Rechenleistung stark)
    val view = LocalView.current
    DisposableEffect(vm.summary.busy) {
        view.keepScreenOn = vm.summary.busy
        onDispose { view.keepScreenOn = false }
    }
    val doc = vm.pendingDoc?.takeIf { !it.image }
    val mark = doc?.let { vm.markFor(it) }
    val image = remember { vm.companionImage("vorleser") }
    var explain by remember { mutableStateOf(false) }
    ScreenFrame(t("Read a book", "Buch vorlesen"), onBack) {
        HeroImage(image, "Vorleser")
        ReadTransport(vm, doc, mark)
        BookSection(vm, doc, mark, onLoad)
        if (doc != null) SummarySection(vm, doc, onSaveText)
        TypewriterText(t("Load a book and I will read it to you, with a voice for every role.", "Lade ein Buch, und ich lese es dir vor, mit einer Stimme für jede Rolle."))
        VoiceSection(vm)
        TextButton(onClick = { explain = !explain }) { Text(if (explain) t("Hide how it works", "Ablauf ausblenden") else t("How does it work?", "Wie funktioniert das?")) }
        if (explain) {
            Text(
                t("Load a book (TXT, MD, DOCX, PDF, EPUB or HTML). Play reads it section by section; pause and stop remember the place, so next time you can continue where you stopped. " +
                    "The language is recognised from the text. With a Piper voice as narrator you can give men and women their own voices: the app recognises who speaks from the accompanying sentence (“said she”), first names and the change of paragraph. Where it cannot tell, the narrator speaks. " +
                    "Piper voices are installed in Settings → Voices.",
                    "Lade ein Buch (TXT, MD, DOCX, PDF, EPUB oder HTML). Play liest es Abschnitt für Abschnitt vor; Pause und Stopp merken sich die Stelle, so kannst du später dort weiterlesen. " +
                        "Die Sprache wird am Text erkannt. Mit einer Piper-Stimme als Erzähler können Männer und Frauen eigene Stimmen bekommen: Die App erkennt, wer spricht, am Begleitsatz („sagte sie“), an Vornamen und am Absatzwechsel. Wo das nicht klappt, spricht der Erzähler. " +
                        "Piper-Stimmen installierst du unter Einstellungen → Stimmen."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    VoicePromptDialog(vm)
}

/** Die Tasten direkt unter dem Bild: << < ■ ▶/|| > >> (Sprünge, Stopp, Play und Pause in einer Taste) mit der Anzeige der Stelle darüber. */
@Composable
private fun ReadTransport(vm: ChatViewModel, doc: PendingDoc?, mark: ReadMark?) {
    val reading = vm.docReading
    val playing = vm.readerState == ReaderState.PLAYING
    Text(
        when {
            reading -> buildString {
                append(t("Section ${(vm.readerIndex + 1).coerceAtMost(vm.readerTotal)} of ${vm.readerTotal}", "Abschnitt ${(vm.readerIndex + 1).coerceAtMost(vm.readerTotal)} von ${vm.readerTotal}"))
                if (vm.readerPages > 1) append(t(" · page ${vm.readerPage} of ${vm.readerPages}", " · Seite ${vm.readerPage} von ${vm.readerPages}"))
                if (vm.readerState == ReaderState.PAUSED) append(t(" · paused", " · pausiert"))
                if (vm.readerNote.isNotEmpty()) append(" · ${vm.readerNote}")
            }
            doc != null -> t("Ready: ${doc.name}", "Bereit: ${doc.name}")
            else -> t("No book loaded yet.", "Noch kein Buch geladen.")
        },
        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TransportKey("<<", Modifier.weight(1f), reading, vm::readerJumpBack)
        TransportKey("<", Modifier.weight(1f), reading, vm::readerPrevious)
        TransportKey("■", Modifier.weight(1f), reading, vm::readerStop)
        TransportKey(if (playing) "||" else "▶", Modifier.weight(1.4f), reading || doc != null) {
            if (playing) vm.readerPause() else if (reading) vm.readerPlay() else vm.docRead(fromMark = mark != null)
        }
        TransportKey(">", Modifier.weight(1f), reading, vm::readerNext)
        TransportKey(">>", Modifier.weight(1f), reading, vm::readerJumpForward)
    }
}

@Composable
private fun TransportKey(label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.height(56.dp), contentPadding = PaddingValues(horizontal = 0.dp)) {
        Text(label, fontSize = 20.sp, maxLines = 1)
    }
}

/** Das Buch: laden, zuletzt gelesene Bücher fortsetzen, von vorn beginnen oder verwerfen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookSection(vm: ChatViewModel, doc: PendingDoc?, mark: ReadMark?, onLoad: () -> Unit) {
    if (vm.status.isNotBlank() && (doc != null || vm.docReading)) Text(vm.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    if (doc == null) {
        if (vm.docBusy) {
            Text(t("Reading the file…", "Lese die Datei…"), style = MaterialTheme.typography.bodyMedium)
            return
        }
        if (!vm.docReading) Button(onClick = onLoad, modifier = Modifier.fillMaxWidth()) { Text(t("Load a book…", "Buch laden…")) }
        vm.markList.take(5).forEach { m ->
            OutlinedButton(onClick = { vm.resumeMark(m) }, modifier = Modifier.fillMaxWidth(), enabled = !vm.docReading) {
                Text(t("Continue: ${m.name} (${m.chunk + 1}/${m.total})", "Weiterlesen: ${m.name} (${m.chunk + 1}/${m.total})"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }
    Text(
        t("“${doc.name}” – ${doc.text.length} characters.", "„${doc.name}“ – ${doc.text.length} Zeichen.") +
            (if (mark != null) t(" Last read up to section ${mark.chunk + 1} of ${mark.total}: ▶ continues there.", " Zuletzt gelesen bis Abschnitt ${mark.chunk + 1} von ${mark.total}: ▶ setzt dort fort.") else ""),
        style = MaterialTheme.typography.bodyMedium,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (mark != null && !vm.docReading) OutlinedButton(onClick = { vm.docRead() }) { Text(t("From the beginning", "Von vorn")) }
        OutlinedButton(onClick = onLoad, enabled = !vm.docReading) { Text(t("Another book…", "Anderes Buch…")) }
        TextButton(onClick = vm::dismissDoc) { Text(t("Discard", "Verwerfen")) }
    }
}

/**
 * Zusammenfassung des geladenen Buches in drei Stufen: 6 Sätze, ausführlich (ein bis zwei Seiten) und Kapitel für Kapitel mit den wesentlichen Elementen.
 * Lange Bücher brauchen viele Anfragen (Teil für Teil); der Stand wird gespeichert, ein Abbruch kostet nichts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummarySection(vm: ChatViewModel, doc: PendingDoc, onSaveText: (String, String) -> Unit) {
    val runner = vm.summary
    LaunchedEffect(doc.name, doc.text.length) { runner.show(doc.name, doc.text) }
    val short = t("6 sentences", "6 Sätze")
    val detailed = t("Detailed (1–2 pages)", "Ausführlich (1–2 Seiten)")
    val chapters = t("Chapter by chapter", "Kapitel für Kapitel")
    SectionTitle(t("Summary", "Zusammenfassung"))
    val estimate by produceState<Int?>(null, doc.name, doc.text.length, runner.data, runner.busy) {
        value = withContext(Dispatchers.Default) { runner.requests(doc.name, doc.text, SummaryLevel.DETAILED) }
    }
    estimate?.let { n ->
        Text(
            if (n == 0) t("Everything needed is already summarised; the levels appear at once.", "Alles Nötige ist schon zusammengefasst; die Stufen erscheinen sofort.")
            else t("About $n requests to the model for the whole book (part by part). The model for planning from “Write a book” is used; a long book takes long on a phone, a strong model on the PC is much faster. What is done is kept.",
                "Etwa $n Anfragen an das Modell für das ganze Buch (Teil für Teil). Es wird das Modell für die Planung aus „Buch schreiben“ benutzt; ein langes Buch dauert auf dem Handy lange, ein starkes Modell am PC geht viel schneller. Was fertig ist, bleibt erhalten."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = { vm.summarize(SummaryLevel.SHORT) }, enabled = !runner.busy) { Text(short) }
        Button(onClick = { vm.summarize(SummaryLevel.DETAILED) }, enabled = !runner.busy) { Text(detailed) }
        Button(onClick = { vm.summarize(SummaryLevel.CHAPTERS) }, enabled = !runner.busy) { Text(chapters) }
    }
    if (runner.busy) {
        LinearProgressIndicator(progress = { if (runner.total == 0) 0f else runner.done.toFloat() / runner.total }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                t("Request ${runner.done.coerceAtMost(runner.total) + 1} of ${runner.total.coerceAtLeast(1)}", "Anfrage ${runner.done.coerceAtMost(runner.total) + 1} von ${runner.total.coerceAtLeast(1)}") +
                    (if (runner.what.isNotBlank()) ": ${runner.what}" else ""),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = runner::cancel) { Text(t("Stop", "Anhalten")) }
        }
    }
    if (runner.error.isNotEmpty()) Text(runner.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    val d = runner.data
    val title = doc.name.substringBeforeLast('.')
    if (d != null) {
        SummaryResult(short, d.short, vm, "$title – ${short}.md", onSaveText)
        SummaryResult(detailed, d.detailed, vm, "$title – ${detailed}.md", onSaveText)
        SummaryResult(chapters, d.perChapter, vm, "$title – ${chapters}.md", onSaveText)
    }
}

@Composable
private fun SummaryResult(title: String, text: String, vm: ChatViewModel, fileName: String, onSaveText: (String, String) -> Unit) {
    if (text.isBlank()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSaveText(fileName.replace(Regex("""[\\/:*?"<>|]"""), "_"), "# ${fileName.removeSuffix(".md")}\n\n$text\n") }) { Text(t("Save…", "Speichern…")) }
                OutlinedButton(onClick = { vm.speakText(text) }, enabled = !vm.docReading) { Text(t("Read aloud", "Vorlesen")) }
            }
        }
    }
}

/** Stimmen für das Vorlesen: Erzähler, männliche und weibliche Rede (nur Piper) und der Schalter für den Stimmenwechsel. Gespeichert wird im Companion Vorleser. */
@Composable
private fun VoiceSection(vm: ChatViewModel) {
    val persona = vm.persona
    val languageName = remember(vm.pendingDoc, persona.language) { vm.readerLanguageName() }
    var options by remember { mutableStateOf<List<SpeechOutput.VoiceOption>?>(null) } // null = wird noch geladen
    LaunchedEffect(languageName) {
        options = null
        vm.loadVoiceOptions(languageName) { options = it }
    }
    val all = options.orEmpty()
    val piper = all.filter { it.name.startsWith(PIPER_PREFIX) }
    SectionTitle(t("Voices", "Stimmen"))
    Text(
        t("For the language: $languageName · saved in: ${persona.name}", "Für die Sprache: $languageName · gespeichert bei: ${persona.name}"),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
    )
    val defaultLabel = t("(default voice)", "(Standardstimme)")
    val narratorLabel = all.firstOrNull { it.name == persona.voice }?.label ?: if (persona.voice.isEmpty() || options == null) defaultLabel else t("(not available: default voice)", "(nicht verfügbar: Standardstimme)")
    VoiceRow(
        t("Narrator", "Erzähler"), listOf(defaultLabel) + all.map { it.label }, narratorLabel,
        { picked -> vm.savePersona(persona.copy(voice = all.firstOrNull { it.label == picked }?.name.orEmpty())) },
        onTest = { vm.testCompanionVoice(languageName, persona.voice) },
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(t("Change the voice for direct speech", "Stimme bei wörtlicher Rede wechseln"), modifier = Modifier.weight(1f))
        Switch(checked = persona.dialogueVoices, onCheckedChange = { vm.savePersona(persona.copy(dialogueVoices = it)) })
    }
    if (persona.dialogueVoices) {
        if (!persona.voice.startsWith(PIPER_PREFIX)) {
            Text(
                t("The voice change needs a Piper voice as narrator (the system voice cannot be switched in the middle of a text). Install Piper voices in Settings → Voices.",
                    "Der Stimmenwechsel braucht eine Piper-Stimme als Erzähler (die Systemstimme lässt sich mitten im Text nicht wechseln). Piper-Stimmen installierst du unter Einstellungen → Stimmen."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
        }
        val none = t("(like the narrator)", "(wie Erzähler)")
        listOf(
            Triple(t("Voice for male speech", "Stimme für männliche Rede"), persona.voiceMale) { name: String -> vm.savePersona(persona.copy(voiceMale = name)) },
            Triple(t("Voice for female speech", "Stimme für weibliche Rede"), persona.voiceFemale) { name: String -> vm.savePersona(persona.copy(voiceFemale = name)) },
        ).forEach { (label, current, save) ->
            val shown = piper.firstOrNull { it.name == current }?.label ?: if (current.isEmpty()) none else t("(not available: narrator)", "(nicht verfügbar: Erzähler)")
            VoiceRow(label, listOf(none) + piper.map { it.label }, shown, { picked -> save(piper.firstOrNull { it.label == picked }?.name.orEmpty()) }, onTest = { vm.testCompanionVoice(languageName, current) }, canTest = current.isNotEmpty())
        }
        if (piper.isEmpty() && options != null) {
            Text(
                t("There is no Piper voice for “$languageName” yet.", "Für „$languageName“ ist noch keine Piper-Stimme installiert."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun VoiceRow(label: String, options: List<String>, selected: String, onPick: (String) -> Unit, onTest: () -> Unit, canTest: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DropdownField(label, options, selected, onPick, Modifier.weight(1f), raw = true)
        OutlinedButton(onClick = onTest, enabled = canTest) { Text(t("Listen", "Anhören")) }
    }
}
