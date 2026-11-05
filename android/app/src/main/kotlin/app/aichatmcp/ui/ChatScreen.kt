package app.aichatmcp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import app.aichatmcp.UiKind
import app.aichatmcp.UiMessage
import app.aichatmcp.core.ChatEvent
import app.aichatmcp.core.splitSegments

private val ToolGreen = Color(0xFF0B6B3A)
private val ErrorRed = Color(0xFFC0392B)
private val InfoBlue = Color(0xFF1A56C4)

enum class Screen { CHAT, SETTINGS, COMPANIONS, TOOLS }

@Composable
fun ChatScreen(vm: ChatViewModel, open: (Screen) -> Unit, onExport: () -> Unit, onImport: () -> Unit, onMic: () -> Unit) {
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
            OutlinedButton(onClick = onMic, enabled = !vm.streaming) { Text(if (vm.listening) "■" else "🎤") }
            Spacer(Modifier.padding(2.dp))
            if (vm.streaming) Button(onClick = vm::stop) { Text("Stopp") }
            else Button(onClick = vm::send, enabled = vm.input.isNotBlank()) { Text("Senden") }
        }
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
