@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.aichatmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aichatmcp.ChatViewModel
import app.aichatmcp.core.CatalogEntry
import app.aichatmcp.core.ModelCatalog
import app.aichatmcp.core.ModelKind

/** "2,5 GB" / "430 MB" für die Anzeige von Dateigrößen. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000L -> "${bytes / 1_000_000} MB"
    else -> "${(bytes / 1000).coerceAtLeast(1)} KB"
}

/** Deutsche Kurzbeschreibungen der Katalogeinträge (die englischen stehen im Katalog selbst). */
private fun noteDe(id: String): String? = when (id) {
    "qwen3-4b-instruct" -> "Bester Allrounder für Werkzeuge (Todos, Kalender, Wecker). Braucht ein neueres Handy, etwa 3 GB Arbeitsspeicher."
    "qwen3-1.7b" -> "Klein und schnell, für ältere Handys. Einfachere Antworten, bei Werkzeugen weniger zuverlässig; zeigt manchmal „Denk“-Text."
    "gemmasutra-4b" -> "Für Rollenspiel und Geschichten feinabgestimmt; schreibt Werkzeugaufrufe schlecht. Ungefiltert, für Erwachsene."
    "gemma-3n-e2b" -> "Googles kleines Modell für Handys: gute Sprache, bei Werkzeugen schwächer."
    "whisper-small" -> "Spracheingabe offline in vielen Sprachen. Gute Genauigkeit, auf dem Handy schnell genug."
    "smolvlm-500m" -> "Beschreibt Bilder als Text, damit ein Chat-Modell darüber sprechen kann. Zwei Dateien, geladen nur während der Beschreibung."
    "piper-de_DE-thorsten-medium" -> "Natürliche deutsche Männerstimme."
    "piper-de_DE-thorsten_emotional-medium" -> "Deutsche Männerstimme mit mehreren Stimmungen."
    "piper-en_US-lessac-medium" -> "Klare amerikanische Stimme."
    "piper-en_GB-cori-medium" -> "Britische Frauenstimme."
    else -> null
}

/** Bestätigungsdialog (Größe, freier Platz, WLAN-Hinweis) und Fortschrittsbalken des laufenden Downloads. Kommt oben in jeden Bildschirm mit Downloads. */
@Composable
fun DownloadHost(vm: ChatViewModel) {
    val d = vm.downloads
    d.pending?.let { p ->
        AlertDialog(
            onDismissRequest = d::dismiss,
            title = { Text(t("Download: ${p.title}", "Herunterladen: ${p.title}")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (p.bytes > 0) t("Size: ${formatBytes(p.bytes)}. Free space: ${formatBytes(p.check.freeBytes)}.", "Größe: ${formatBytes(p.bytes)}. Freier Platz: ${formatBytes(p.check.freeBytes)}.")
                        else t("Already downloaded.", "Schon geladen."),
                    )
                    if (!p.check.ok) {
                        Text(
                            t("Not enough space: about ${formatBytes(p.check.missingBytes)} more are needed (a reserve of ${formatBytes(p.check.reserveBytes)} stays free).",
                                "Nicht genug Platz: etwa ${formatBytes(p.check.missingBytes)} fehlen (${formatBytes(p.check.reserveBytes)} bleiben als Reserve frei)."),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!p.onWifi) {
                        Text(
                            t("You are not on Wi-Fi. Model files are large and can use up your mobile data. Connect to Wi-Fi first.",
                                "Du bist nicht im WLAN. Modelldateien sind groß und können dein mobiles Datenvolumen aufbrauchen. Verbinde dich besser vorher mit dem WLAN."),
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(t("You are on Wi-Fi. Keep the app open while it downloads; an interrupted download continues where it stopped.",
                            "Du bist im WLAN. Lass die App während des Downloads offen; ein unterbrochener Download macht dort weiter, wo er stand."))
                    }
                }
            },
            confirmButton = { Button(onClick = d::confirm, enabled = p.check.ok) { Text(t("Download", "Herunterladen")) } },
            dismissButton = { TextButton(onClick = d::dismiss) { Text(t("Cancel", "Abbrechen")) } },
        )
    }
    d.active?.let { a ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t("Downloading: ${a.title}", "Lade: ${a.title}"), style = MaterialTheme.typography.titleSmall)
                val fraction = if (a.totalBytes > 0) (a.doneBytes.toFloat() / a.totalBytes).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${formatBytes(a.doneBytes)} / ${formatBytes(a.totalBytes)} (${(fraction * 100).toInt()} %)  ·  ${a.file.substringAfterLast('/')}",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = d::cancel) { Text(t("Cancel", "Abbrechen")) }
            }
        }
    }
    if (d.note.isNotEmpty() && d.active == null) Text(d.note, style = MaterialTheme.typography.bodySmall)
}

/** Eine Karte je Katalogeintrag: Titel, Beschreibung, Größe, Knopf zum Laden (oder "installiert"). */
@Composable
fun CatalogCard(vm: ChatViewModel, entry: CatalogEntry) {
    val installed = vm.downloads.isInstalled(entry)
    val busy = vm.downloads.active != null
    val german = LocalUiLanguage.current == app.aichatmcp.core.LANGUAGE_DE
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (entry.recommended) Text(t("recommended", "empfohlen"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text((if (german) noteDe(entry.id) else null) ?: entry.note, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatBytes(entry.bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
                if (installed) {
                    Text(t("Installed", "Installiert"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    if (entry.kind == ModelKind.CHAT) {
                        val file = java.io.File(vm.modelFiles.firstOrNull { it.name == entry.files.first().name }?.path ?: "")
                        if (file.isFile && !vm.isCurrentModel(file)) OutlinedButton(onClick = { vm.useModelFile(file) }) { Text(t("Use", "Verwenden")) }
                        else if (file.isFile) Text(t("in use", "in Benutzung"), style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Button(onClick = { vm.downloads.ask(entry) }, enabled = !busy) { Text(t("Download", "Herunterladen")) }
                }
            }
        }
    }
}

/** Die Karten einer Art (Chat, Whisper, Bild, Piper); bei Piper optional nur Stimmen einer Sprache zuerst. */
@Composable
fun CatalogList(vm: ChatViewModel, entries: List<CatalogEntry>) {
    entries.forEach { CatalogCard(vm, it) }
}

/** Suche auf Hugging Face (Name, Adresse oder besitzer/repo) und Auswahl einer GGUF-Datei. */
@Composable
fun HuggingFaceSection(vm: ChatViewModel) {
    var query by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val hf = vm.downloads.hf
    Text(
        t("Find any chat model on Hugging Face: type a name (for example qwen3 4b gguf), paste a link or write owner/name. Choose a GGUF file; Q4_K_M is a good size/quality balance for phones.",
            "Finde ein beliebiges Chat-Modell auf Hugging Face: Namen eintippen (z. B. qwen3 4b gguf), einen Link einfügen oder besitzer/name schreiben. Dann eine GGUF-Datei wählen; Q4_K_M ist für Handys ein guter Kompromiss aus Größe und Qualität."),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextSetting(t("Name or link", "Name oder Link"), query, { query = it }, modifier = Modifier.weight(1f))
        Button(onClick = { vm.downloads.hfFind(query) }, enabled = !hf.busy && query.isNotBlank()) { Text(t("Search", "Suchen")) }
    }
    if (hf.busy) Text(t("Searching…", "Suche…"), style = MaterialTheme.typography.bodySmall)
    if (hf.error.isNotEmpty()) Text(hf.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    hf.hits.forEach { hit ->
        TextButton(onClick = { vm.downloads.hfOpen(hit.id) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(hit.id, style = MaterialTheme.typography.bodyMedium)
                Text("↓ ${hit.downloads}  ♥ ${hit.likes}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
    if (hf.repo.isNotEmpty()) {
        Text(hf.repo, style = MaterialTheme.typography.titleSmall)
        val suggested = app.aichatmcp.core.HuggingFace.suggested(hf.files)
        hf.files.filter { !it.isProjector }.sortedByDescending { it == suggested }.forEach { g -> // der Vorschlag steht oben
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(g.file.name.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall)
                    Text(
                        listOf(g.quant, formatBytes(g.file.bytes), if (g == suggested) t("suggested", "Vorschlag") else "").filter { it.isNotEmpty() }.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall, color = if (g == suggested) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                }
                OutlinedButton(onClick = { vm.downloads.askGguf(g) }, enabled = vm.downloads.active == null) { Text(t("Download", "Herunterladen")) }
            }
        }
        if (hf.files.any { it.isProjector }) {
            Text(
                t("This repository also has image projector files (mmproj). They are for image models and are not needed for chat.",
                    "Dieses Repository hat auch Bild-Hilfsdateien (mmproj). Sie gehören zu Bildmodellen und werden für den Chat nicht gebraucht."),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
    Text(
        t("Protected (gated) models need a free Hugging Face access token (huggingface.co/settings/tokens). It is stored on this phone only and sent only to huggingface.co.",
            "Geschützte Modelle brauchen einen kostenlosen Hugging-Face-Zugangsschlüssel (huggingface.co/settings/tokens). Er bleibt auf diesem Handy und geht nur an huggingface.co."),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextSetting(
            if (vm.hasHuggingFaceToken()) t("Access token (saved)", "Zugangsschlüssel (gespeichert)") else t("Access token (optional)", "Zugangsschlüssel (optional)"),
            token, { token = it }, modifier = Modifier.weight(1f), secret = true,
        )
        OutlinedButton(onClick = { vm.saveHuggingFaceToken(token); token = "" }) { Text(t("Save", "Speichern")) }
    }
}

/** Alles zum Laden und Verwalten von Modellen auf einem Bildschirm (Einstellungen > App > Modelle, auch aus der Einführung erreichbar). */
@Composable
fun ModelsScreen(vm: ChatViewModel, onBack: () -> Unit, onRequestAllFiles: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshModelFiles(); vm.refreshVoices() }
    ScreenFrame(t("Models and downloads", "Modelle und Downloads"), onBack, pinned = { DownloadHost(vm) }) {
        Text(
            t("Everything runs offline on this phone once it is downloaded. Use Wi-Fi: the files are large (from 100 MB to about 3 GB).",
                "Alles läuft offline auf diesem Handy, sobald es geladen ist. Nutze das WLAN: die Dateien sind groß (von 100 MB bis etwa 3 GB)."),
            style = MaterialTheme.typography.bodySmall,
        )
        SectionTitle(t("Chat models", "Chat-Modelle"))
        CatalogList(vm, ModelCatalog.chat)

        SectionTitle(t("Your model files", "Deine Modelldateien"))
        FolderInfo(vm, onRequestAllFiles)
        if (vm.modelFiles.isEmpty()) Text(t("No model files in the models folder yet.", "Noch keine Modelldateien im Modellordner."), style = MaterialTheme.typography.bodySmall)
        vm.modelFiles.forEach { f ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.bodyMedium)
                    Text(formatBytes(f.length()) + if (vm.isCurrentModel(f)) "  ·  " + t("in use", "in Benutzung") else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                if (!vm.isCurrentModel(f)) OutlinedButton(onClick = { vm.useModelFile(f) }) { Text(t("Use", "Verwenden")) }
                TextButton(onClick = { vm.deleteModelFile(f) }) { Text(t("Delete", "Löschen")) }
            }
        }

        SectionTitle(t("More models from Hugging Face", "Weitere Modelle von Hugging Face"))
        HuggingFaceSection(vm)

        SectionTitle(t("Voice input (Whisper)", "Spracheingabe (Whisper)"))
        CatalogList(vm, ModelCatalog.whisper)
        SectionTitle(t("Image model", "Bildmodell"))
        CatalogList(vm, ModelCatalog.vision)
        SectionTitle(t("Voices (Piper)", "Stimmen (Piper)"))
        CatalogList(vm, ModelCatalog.piper)
        OutlinedButton(onClick = vm::installVoices) { Text(t("Install system voices…", "Systemstimmen installieren…")) }
        Text(
            t("System voices come from the Google text-to-speech engine on your phone; the button opens its voice installer.",
                "Systemstimmen kommen von der Google-Sprachausgabe deines Handys; der Knopf öffnet deren Stimmen-Installation."),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Wo die Ordner liegen und wie man den gemeinsamen Ordner freischaltet. */
@Composable
fun FolderInfo(vm: ChatViewModel, onRequestAllFiles: () -> Unit) {
    Text(
        t("Folder: ${vm.foldersLabel}", "Ordner: ${vm.foldersLabel}") + "\n" +
            if (vm.sharedFolders) t("Models, companions and chats are stored in AIChatMCP/models, /companions and /chats, visible to file managers and your PC.",
                "Modelle, Companions und Chats liegen in AIChatMCP/models, /companions und /chats, sichtbar für Dateimanager und PC.")
            else t("Models are stored in this app folder. To use the shared folder AIChatMCP (models, companions and chats; visible to file managers, survives reinstalling), allow “All files access”. Companions and chats are kept private until then.",
                "Modelle liegen in diesem App-Ordner. Für den gemeinsamen Ordner AIChatMCP (Modelle, Companions und Chats; sichtbar für Dateimanager, übersteht eine Neuinstallation) erlaube „Zugriff auf alle Dateien“. Companions und Chats bleiben bis dahin privat."),
        style = MaterialTheme.typography.bodySmall,
    )
    if (!vm.sharedFolders) OutlinedButton(onClick = onRequestAllFiles) { Text(t("Allow all files access…", "Zugriff auf alle Dateien erlauben…")) }
}

