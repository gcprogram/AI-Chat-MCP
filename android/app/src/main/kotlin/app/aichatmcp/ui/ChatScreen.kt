package app.aichatmcp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aichatmcp.ChatViewModel
import app.aichatmcp.PendingDoc
import app.aichatmcp.PendingImage
import app.aichatmcp.UiKind
import app.aichatmcp.UiMessage
import app.aichatmcp.core.ChatEvent
import app.aichatmcp.core.ReaderState
import app.aichatmcp.core.splitSegments

private val ToolGreen = Color(0xFF0B6B3A)
private val ErrorRed = Color(0xFFC0392B)
private val InfoBlue = Color(0xFF1A56C4)

enum class Screen { CHAT, SETTINGS, COMPANIONS, TOOLS }

@Composable
fun ChatScreen(vm: ChatViewModel, open: (Screen) -> Unit, onExport: () -> Unit, onImport: () -> Unit, onMic: () -> Unit, onUpload: () -> Unit) {
    val listState = rememberLazyListState()
    val liveLength = vm.live?.let { it.content.length + it.reasoning.length } ?: 0
    LaunchedEffect(vm.messages.size, liveLength) {
        listState.scrollToItem(0) // neueste Nachricht unten sichtbar
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, open, onExport, onImport)
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 10.dp), state = listState, reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // reverseLayout: Eintrag 0 ist unten, die Liste bleibt am Ende verankert (wie bei Chat-Apps)
                    item { Spacer(Modifier.padding(2.dp)) }
                    vm.live?.let { live -> item { LiveItem(live, vm.persona.name) } }
                    items(vm.messages.asReversed()) { MessageItem(it, vm.persona.name, vm.config.userName) }
                    item { Spacer(Modifier.padding(2.dp)) }
                }
            }
        }
        HorizontalDivider()
        vm.pendingImage?.let { ImageCard(it, vm) }
        vm.pendingDoc?.let { DocCard(it, vm) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(vm.status, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(vm.contextLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = vm.input, onValueChange = { vm.input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Nachricht") }, maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Spacer(Modifier.padding(4.dp))
            OutlinedButton(onClick = onUpload, enabled = !vm.streaming && !vm.docBusy, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(if (vm.docBusy) "…" else "📎")
            }
            Spacer(Modifier.padding(2.dp))
            OutlinedButton(onClick = onMic, enabled = !vm.streaming && !vm.recognizing, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(if (vm.listening) "■" else "🎤") }
            Spacer(Modifier.padding(2.dp))
            if (vm.streaming) Button(onClick = vm::stop) { Text("Stopp") }
            else Button(onClick = vm::send, enabled = vm.input.isNotBlank()) { Text("Senden") }
        }
    }
    vm.voicePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = vm::dismissVoicePrompt,
            title = { Text("Stimme fehlt") },
            text = {
                Text(
                    "Der Text ist vermutlich ${prompt.name}, aber dafür ist keine Sprachstimme installiert. Du kannst die Stimmen laden " +
                        "(danach noch einmal auf Vorlesen tippen) oder jetzt trotzdem mit der vorhandenen Stimme vorlesen lassen.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.dismissVoicePrompt(); vm.installVoices() }) { Text("Stimmen laden…") } },
            dismissButton = {
                Row {
                    TextButton(onClick = vm::confirmVoicePrompt) { Text("Trotzdem vorlesen") }
                    TextButton(onClick = vm::dismissVoicePrompt) { Text("Abbrechen") }
                }
            },
        )
    }
    vm.pendingConfirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { pending.answer(false) },
            title = { Text("Werkzeug ausführen?") },
            text = {
                Text(
                    "${pending.persona} möchte das Werkzeug '${pending.call.name}' ausführen.\n\n" +
                        "Argumente: ${pending.call.args.ifEmpty { "(keine)" }}",
                )
            },
            confirmButton = { TextButton(onClick = { pending.answer(true) }) { Text("Ausführen") } },
            dismissButton = { TextButton(onClick = { pending.answer(false) }) { Text("Ablehnen") } },
        )
    }
}

@Composable
private fun TopBar(vm: ChatViewModel, open: (Screen) -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    var personaMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val avatar = remember(vm.persona.avatar, vm.avatarVersion) { vm.avatarFile(vm.persona) }
        AvatarView(avatar, vm.persona.name)
        Spacer(Modifier.padding(start = 4.dp))
        Box {
            TextButton(onClick = { personaMenu = true }, enabled = !vm.streaming) {
                Text(vm.persona.name.ifEmpty { vm.persona.id }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                Text(" ▾")
            }
            DropdownMenu(expanded = personaMenu, onDismissRequest = { personaMenu = false }) {
                vm.personaIds.forEach { id ->
                    DropdownMenuItem(text = { Text(id) }, onClick = { personaMenu = false; vm.selectPersona(id) })
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { if (vm.messages.size > 1) confirmNew = true else vm.newChat() }, enabled = !vm.streaming) { Text("Neuer Chat") }
        Box {
            TextButton(onClick = { moreMenu = true }) { Text("⋮") }
            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                DropdownMenuItem(text = { Text("Companions") }, onClick = { moreMenu = false; open(Screen.COMPANIONS) })
                DropdownMenuItem(text = { Text("Werkzeuge") }, onClick = { moreMenu = false; open(Screen.TOOLS) })
                DropdownMenuItem(text = { Text("Einstellungen") }, onClick = { moreMenu = false; open(Screen.SETTINGS) })
                HorizontalDivider()
                vm.markList.take(5).forEach { mark ->
                    DropdownMenuItem(
                        text = { Text("Weiterlesen: ${mark.name} (${mark.chunk + 1}/${mark.total})", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { moreMenu = false; vm.resumeMark(mark) },
                    )
                }
                if (vm.markList.isNotEmpty()) HorizontalDivider()
                DropdownMenuItem(text = { Text("Chat exportieren…") }, onClick = { moreMenu = false; onExport() })
                DropdownMenuItem(text = { Text("Chat laden…") }, onClick = { moreMenu = false; onImport() })
            }
        }
    }
    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text("Neuer Chat") },
            text = { Text("Aktuellen Chat verwerfen?") },
            confirmButton = { TextButton(onClick = { confirmNew = false; vm.newChat() }) { Text("Verwerfen") } },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun MessageItem(message: UiMessage, personaName: String, userName: String) {
    when (message.kind) {
        UiKind.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Bubble(userName, message.text, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        }
        UiKind.ASSISTANT -> Row(Modifier.fillMaxWidth()) {
            Bubble(personaName, message.text, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        UiKind.RESULT -> Text(message.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        UiKind.RESULT_ERROR -> Text(message.text, style = MaterialTheme.typography.bodySmall, color = ErrorRed)
        UiKind.SHOWN -> Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
            Text(message.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
        }
        UiKind.INFO -> Text(message.text, color = InfoBlue, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium)
        UiKind.ERROR -> Text(message.text, color = ErrorRed, style = MaterialTheme.typography.bodyMedium)
        UiKind.TOOL -> Text(message.text, color = ToolGreen, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Bubble(name: String, text: String, background: Color, foreground: Color) {
    Surface(shape = RoundedCornerShape(14.dp), color = background, contentColor = foreground, modifier = Modifier.widthIn(max = 520.dp, min = 0.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(name, style = MaterialTheme.typography.labelMedium, color = foreground.copy(alpha = 0.7f))
            Text(withToolCalls(text), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Werkzeugaufruf-Marker erscheinen als eigene, grüne Zeile. */
private fun withToolCalls(text: String) = buildAnnotatedString {
    val segments = splitSegments(text)
    segments.forEachIndexed { i, (kind, part) ->
        if (kind == "call") {
            withStyle(SpanStyle(color = ToolGreen, fontStyle = FontStyle.Italic)) { append("» Werkzeug $part") }
            if (i < segments.lastIndex) append("\n")
        } else {
            append(part)
        }
    }
}

/**
 * Rückfrage zu einem hochgeladenen Dokument: "Was soll ich damit machen?" mit den drei Möglichkeiten. Die Frage stellt die
 * App, nicht das Modell (ein kleines Modell würde sie unzuverlässig stellen und den Text unnötig in den Kontext holen).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocCard(doc: PendingDoc, vm: ChatViewModel) {
    Surface(
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(vm.persona.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            if (doc.image) {
                Text("${doc.name}: So sieht das Bildmodell es (englisch):", style = MaterialTheme.typography.bodyMedium)
                // Die Karte darf den Chat nicht verdrängen: lange Beschreibungen erscheinen gekürzt (die Eingabe bekommt den ganzen Text)
                Text(doc.text, style = MaterialTheme.typography.bodyLarge, maxLines = 10, overflow = TextOverflow.Ellipsis)
                Text("Was soll ich damit machen?", style = MaterialTheme.typography.bodyLarge)
            } else {
                Text("Die Datei „${doc.name}“ ist da (${doc.text.length} Zeichen, ca. ${doc.tokens} Tokens). Was soll ich damit machen?", style = MaterialTheme.typography.bodyLarge)
            }
            if (doc.tokens > doc.budget) {
                Text(
                    "Der Text ist länger als mein Kontext (Platz für ca. ${doc.budget} Tokens): Beim Einlesen und Zusammenfassen berücksichtige ich nur den Anfang. Vorlesen geht vollständig.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (vm.docReading) {
                ReaderControls(vm)
            } else {
                val mark = vm.markFor(doc)
                if (mark != null) {
                    Text(
                        "Zuletzt gelesen bis Abschnitt ${mark.chunk + 1} von ${mark.total}. Dort kann das Vorlesen weitergehen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = vm::docAsInput) { Text("1 · Als Eingabe einlesen") }
                    if (mark != null) Button(onClick = { vm.docRead(fromMark = true) }) { Text("2 · Weiterlesen") }
                    if (mark != null) OutlinedButton(onClick = { vm.docRead() }) { Text("Von vorn vorlesen") }
                    else Button(onClick = { vm.docRead() }) { Text("2 · Vorlesen") }
                    if (!doc.image) Button(onClick = vm::docSummarize, enabled = !vm.streaming) { Text("3 · Zusammenfassen") }
                    TextButton(onClick = vm::dismissDoc) { Text("Verwerfen") }
                }
            }
        }
    }
}

/**
 * Tasten im Vorlese-Modus: << Seite zurück (ohne Seiten 10 Abschnitte), < ein Abschnitt zurück, ■ Stopp, ▶/|| Play und Pause in einer Taste,
 * > ein Abschnitt vor, >> Seite vor (ohne Seiten 10 Abschnitte). Ein Abschnitt dauert etwa 25 Sekunden zum Hören: Das ist als
 * Schritt klein genug zum Wiederholen und kostet keine Wartezeit (nur bei Bildern, die erst beschrieben werden müssen).
 */
@Composable
private fun ReaderControls(vm: ChatViewModel) {
    val playing = vm.readerState == ReaderState.PLAYING
    Text(
        buildString {
            append("Abschnitt ${(vm.readerIndex + 1).coerceAtMost(vm.readerTotal)} von ${vm.readerTotal}")
            if (vm.readerPages > 1) append(" · Seite ${vm.readerPage} von ${vm.readerPages}")
            if (vm.readerState == ReaderState.PAUSED) append(" · pausiert")
            if (vm.readerNote.isNotEmpty()) append(" · ${vm.readerNote}")
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ReaderKey("<<", Modifier.weight(1f), onClick = vm::readerJumpBack)
        ReaderKey("<", Modifier.weight(1f), onClick = vm::readerPrevious)
        ReaderKey("■", Modifier.weight(1f), onClick = vm::readerStop)
        // Ein Knopf für Play und Pause: während des Vorlesens "||" (pausiert), sonst "▶" (setzt fort)
        ReaderKey(if (playing) "||" else "▶", Modifier.weight(1f), onClick = if (playing) vm::readerPause else vm::readerPlay)
        ReaderKey(">", Modifier.weight(1f), onClick = vm::readerNext)
        ReaderKey(">>", Modifier.weight(1f), onClick = vm::readerJumpForward)
    }
    Text(
        if (vm.readerPages > 1) "<< und >> springen eine Seite, < und > einen Abschnitt." else "<< und >> springen 10 Abschnitte, < und > einen.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ReaderKey(label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier, contentPadding = PaddingValues(horizontal = 0.dp)) {
        Text(label, maxLines = 1)
    }
}

/** Rückfrage zu einem gewählten Bild: Das kleine Bildmodell beschreibt es als Text (das Chat-Modell kann keine Bilder sehen). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageCard(image: PendingImage, vm: ChatViewModel) {
    Surface(
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(vm.persona.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            Text("Das Bild „${image.name}“ ist da (${image.jpeg.size / 1000} KB). Soll ich es beschreiben lassen?", style = MaterialTheme.typography.bodyLarge)
            if (!vm.visionAvailable) {
                Text(
                    "Dafür fehlt ein Bildmodell: Einstellungen → Bildmodell (ein kleines Modell wie SmolVLM, GGUF und mmproj-Datei).",
                    style = MaterialTheme.typography.bodySmall, color = ErrorRed,
                )
            }
            if (vm.imageBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Beschreibe das Bild…", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = vm::cancelImage) { Text("Abbrechen") }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = vm::describeImage, enabled = vm.visionAvailable && !vm.streaming) { Text("Beschreiben") }
                    TextButton(onClick = vm::dismissImage) { Text("Verwerfen") }
                }
            }
        }
    }
}

@Composable
private fun LiveItem(live: ChatEvent.Live, personaName: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (live.reasoning.isNotEmpty() && live.content.isEmpty()) {
            Text(
                "Denkt nach…\n" + live.reasoning.takeLast(400), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline, fontStyle = FontStyle.Italic,
            )
        }
        if (live.content.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                Bubble(personaName, live.content, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
