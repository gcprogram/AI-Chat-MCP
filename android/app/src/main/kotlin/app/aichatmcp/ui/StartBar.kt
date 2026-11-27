package app.aichatmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aichatmcp.ChatViewModel
import app.aichatmcp.core.Persona
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Das Start-Menü unten: die Funktionen der App als Symbole: Companions (Übersichtsseite), Buch schreiben, Buch vorlesen, Buch spielen und Dokumente übersetzen, dazu der Chat.
 * Es steht auf den Hauptbildschirmen; Einstellungen und Werkzeuge (Setup, im Menü ⋮) haben es nicht.
 */
@Composable
fun StartBar(current: Screen, open: (Screen) -> Unit) {
    val items = listOf(
        Triple(Screen.CHAT, "💬", t("Chat", "Chat")),
        Triple(Screen.GALLERY, "👥", t("Companions", "Companions")),
        Triple(Screen.BOOK, "✍️", t("Write", "Schreiben")),
        Triple(Screen.READ, "📖", t("Read", "Vorlesen")),
        Triple(Screen.PLAY, "🎲", t("Play", "Spielen")),
        Triple(Screen.TRANSLATE, "🌐", t("Translate", "Übersetzen")),
    )
    Column {
        HorizontalDivider()
        NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0), modifier = Modifier.height(64.dp)) {
            items.forEach { (screen, icon, label) ->
                NavigationBarItem(
                    selected = current == screen, onClick = { open(screen) },
                    icon = { Text(icon, fontSize = 20.sp) },
                    label = { Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible, fontSize = 10.sp) },
                    alwaysShowLabel = true,
                )
            }
        }
    }
}

/** Passt [p] auf die Suche? Gesucht wird in Name, Beschreibung, Begrüßung, Regeln (den Anweisungen) und Sprache; alle Wörter müssen vorkommen. */
internal fun personaMatches(p: Persona, query: String): Boolean {
    val words = query.trim().lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val text = (listOf(p.name, p.description, p.greeting, p.language, p.id) + p.rules).joinToString(" ").lowercase()
    return words.all { it in text }
}

/**
 * Die Übersichtsseite der Companions: Bilder mit Namen in einem Raster, oben die Suche (sie findet auch Wörter in der Beschreibung und den Anweisungen).
 * Antippen wählt den Companion und öffnet den Chat; "✎" öffnet den Editor.
 */
@Composable
fun GalleryScreen(vm: ChatViewModel, open: (Screen) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val personas by produceState<List<Persona>>(emptyList(), vm.personaIds, vm.avatarVersion) { value = withContext(Dispatchers.IO) { vm.allPersonas() } }
    val shown = personas.filter { personaMatches(it, query) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Companions", "Companions"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { open(Screen.COMPANIONS) }) { Text(t("Manage / new…", "Verwalten / neu…")) }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(t("Search name, description, instructions…", "Suche in Name, Beschreibung, Anweisungen…")) },
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("✕") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        if (shown.isEmpty()) {
            Text(
                if (personas.isEmpty()) t("Loading…", "Lade…") else t("No companion matches “$query”.", "Kein Companion passt zu „$query“."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp), modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shown, key = { it.id }) { p -> PersonaCard(vm, p, p.id == vm.persona.id, open) }
        }
    }
}

@Composable
private fun PersonaCard(vm: ChatViewModel, p: Persona, active: Boolean, open: (Screen) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Card(
        modifier = Modifier.fillMaxWidth().let { if (active) it.border(2.dp, MaterialTheme.colorScheme.primary, shape) else it }
            .clickable(enabled = !vm.streaming) { vm.selectPersona(p.id); open(Screen.CHAT) },
        shape = shape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box {
            Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AvatarView(vm.avatarFile(p), p.name.ifBlank { p.id }, 96.dp)
                Spacer(Modifier.height(6.dp))
                Text(p.name.ifBlank { p.id }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(
                    p.description.removePrefix("Du bist ").trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
            TextButton(
                onClick = { if (!vm.streaming) { vm.selectPersona(p.id); open(Screen.COMPANIONS) } },
                modifier = Modifier.align(Alignment.TopEnd), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) { Text("✎", fontSize = 18.sp) }
        }
    }
}
