package app.aichatmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aichatmcp.ChatViewModel
import app.aichatmcp.PendingDoc
import app.aichatmcp.core.PIPER_PREFIX
import app.aichatmcp.core.ReadMark
import app.aichatmcp.core.ReaderState
import app.aichatmcp.speech.SpeechOutput

/**
 * "Buch vorlesen": Der Vorleser (Companion mit Mondlicht-Bild) liest ein Buch ab, Abschnitt für Abschnitt, mit Play, Pause, Stopp und Sprüngen direkt
 * unter dem Bild. Darunter die Stimmen: Erzähler, Männer und Frauen in wörtlicher Rede. Die Stimmen gehören zum Companion Vorleser.
 */
@Composable
fun ReadScreen(vm: ChatViewModel, onBack: () -> Unit, onLoad: () -> Unit) {
    LaunchedEffect(Unit) { vm.openReadBook() }
    val doc = vm.pendingDoc?.takeIf { !it.image }
    val mark = doc?.let { vm.markFor(it) }
    val image = remember { vm.companionImage("vorleser") }
    var explain by remember { mutableStateOf(false) }
    ScreenFrame(t("Read a book", "Buch vorlesen"), onBack) {
        HeroImage(image, "Vorleser")
        ReadTransport(vm, doc, mark)
        BookSection(vm, doc, mark, onLoad)
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
