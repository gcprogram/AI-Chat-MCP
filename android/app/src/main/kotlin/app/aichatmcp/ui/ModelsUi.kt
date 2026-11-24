@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.aichatmcp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.aichatmcp.ChatViewModel
import app.aichatmcp.InterruptReason
import app.aichatmcp.Interruption
import app.aichatmcp.ModelManager
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
    "gemmasutra-4b" -> "Für Rollenspiel und Geschichten feinabgestimmt; schreibt Werkzeugaufrufe schlecht. Rollenspiel-Modell."
    "gemma-3n-e2b" -> "Googles kleines Modell für Handys: gute Sprache, bei Werkzeugen schwächer."
    "gemma-4-e4b" -> "Googles neueres Gemma für Handys: gute Sprache, Werkzeugaufrufe ungetestet. Großer Download (5,3 GB), braucht ein Handy mit 12 GB Arbeitsspeicher."
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
    val context = LocalContext.current
    // Ab Android 13 braucht die Fortschritts-Benachrichtigung eine Erlaubnis; der Download startet auch bei "Nein"
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { d.confirm() }
    val startDownload = {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else d.confirm()
    }
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
                        Text(t("You are on Wi-Fi. The download keeps running in the background (a notification shows the progress). If Wi-Fi drops, you are asked what to do, and it continues where it stopped.",
                            "Du bist im WLAN. Der Download läuft im Hintergrund weiter (eine Benachrichtigung zeigt den Fortschritt). Fällt das WLAN weg, wirst du gefragt, und er macht dort weiter, wo er stand."))
                    }
                }
            },
            confirmButton = { Button(onClick = { startDownload() }, enabled = p.check.ok) { Text(t("Download", "Herunterladen")) } },
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
                val i = d.interruption
                if (i == null) {
                    OutlinedButton(onClick = d::cancel) { Text(t("Cancel", "Abbrechen")) }
                } else {
                    Text(interruptionText(i), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    InterruptionButtons(d, i)
                }
            }
        }
    }
    if (d.note.isNotEmpty() && d.active == null) Text(d.note, style = MaterialTheme.typography.bodySmall)
}

/** Was gerade los ist, wenn ein Download angehalten wurde. */
private fun interruptionText(i: Interruption): String {
    val left = formatBytes(i.remainingBytes)
    return when (i.reason) {
        InterruptReason.WIFI_LOST ->
            if (i.ask) tNow("Wi-Fi was lost. The download is paused ($left left). Continue over mobile data (uses your data plan) or wait for Wi-Fi?",
                "Das WLAN ist weg. Der Download ist angehalten (noch $left). Über Mobilfunk fortsetzen (verbraucht Datenvolumen) oder auf WLAN warten?")
            else tNow("Waiting for Wi-Fi ($left left). It continues by itself when Wi-Fi is back.", "Warte auf WLAN (noch $left). Es geht von selbst weiter, sobald das WLAN zurück ist.")
        InterruptReason.OFFLINE -> tNow("No connection. It continues by itself when a network is back ($left left).", "Keine Verbindung. Es geht von selbst weiter, sobald ein Netz da ist (noch $left).")
        InterruptReason.FAILED -> tNow("The connection was interrupted ($left left). ${i.detail}", "Die Verbindung ist abgerissen (noch $left). ${i.detail}")
        InterruptReason.SYSTEM -> tNow("Android paused the background service after its time limit ($left left). Continue?", "Android hat den Hintergrunddienst nach seinem Zeitlimit angehalten (noch $left). Fortsetzen?")
    }
}

@Composable
private fun InterruptionButtons(d: ModelManager, i: Interruption) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (i.reason) {
            InterruptReason.WIFI_LOST -> {
                Button(onClick = d::continueOnMobile) { Text(t("Use mobile data", "Über Mobilfunk")) }
                if (i.ask) OutlinedButton(onClick = d::waitForWifi) { Text(t("Wait for Wi-Fi", "Auf WLAN warten")) }
            }
            InterruptReason.FAILED, InterruptReason.SYSTEM -> Button(onClick = d::retry) { Text(if (i.reason == InterruptReason.SYSTEM) t("Continue", "Fortsetzen") else t("Try again", "Erneut versuchen")) }
            InterruptReason.OFFLINE -> {}
        }
        OutlinedButton(onClick = d::cancel) { Text(t("Cancel", "Abbrechen")) }
    }
}

/**
 * Fragt nach, wenn ein Download angehalten wurde und der Nutzer entscheiden muss (WLAN weg, Verbindung abgerissen). Gehört in die Wurzel der App,
 * damit die Frage auf jedem Bildschirm erscheint; dieselben Knöpfe stehen in der Benachrichtigung.
 */
@Composable
fun DownloadQuestion(vm: ChatViewModel) {
    val d = vm.downloads
    val i = d.interruption ?: return
    if (!i.ask) return
    AlertDialog(
        onDismissRequest = { if (i.reason == InterruptReason.WIFI_LOST) d.waitForWifi() },
        title = { Text(d.active?.title?.let { t("Download paused: $it", "Download angehalten: $it") } ?: t("Download paused", "Download angehalten")) },
        text = { Text(interruptionText(i)) },
        confirmButton = { Column(horizontalAlignment = Alignment.End) { InterruptionButtons(d, i) } },
    )
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
                        val file = vm.modelFiles.firstOrNull { it.name == entry.files.first().name }
                        if (file != null && !vm.isCurrentModel(file)) OutlinedButton(onClick = { vm.useModelFile(file) }) { Text(t("Use", "Verwenden")) }
                        else if (file != null) Text(t("in use", "in Benutzung"), style = MaterialTheme.typography.labelSmall)
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
                Text(hitDetails(hit), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
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
fun ModelsScreen(vm: ChatViewModel, onBack: () -> Unit, folders: FolderActions) {
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
        FolderInfo(vm, folders)
        if (vm.modelFiles.isEmpty()) Text(t("No model files in the models folder yet.", "Noch keine Modelldateien im Modellordner."), style = MaterialTheme.typography.bodySmall)
        vm.modelFiles.forEach { f ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.bodyMedium)
                    Text(formatBytes(f.bytes) + if (vm.isCurrentModel(f)) "  ·  " + t("in use", "in Benutzung") else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
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

/** Die Knöpfe für Ordner, die die Hauptaktivität bedient (Systemdialoge zur Ordnerwahl). */
class FolderActions(val pickModels: () -> Unit, val backup: () -> Unit, val restore: () -> Unit)

/** Wo die Modelle liegen, mit Wahl eines eigenen Ordners (z. B. um sie mit anderen Apps zu teilen). */
@Composable
fun FolderInfo(vm: ChatViewModel, folders: FolderActions) {
    Text(
        t("Model folder: ${vm.modelFolderLabel}", "Modellordner: ${vm.modelFolderLabel}") + "\n" +
            if (vm.modelFolderChosen) t("Your own folder: it survives reinstalling, file managers and your PC can see it, and other apps can use the same models from it.",
                "Dein eigener Ordner: Er übersteht eine Neuinstallation, Dateimanager und PC sehen ihn, und andere Apps können dieselben Modelle daraus nutzen.")
            else t("Models are stored privately for this app and are lost when it is uninstalled. Choose a folder to keep them, and to share them with your other offline AI apps.",
                "Modelle liegen privat für diese App und gehen bei der Deinstallation verloren. Wähle einen Ordner, um sie zu behalten und mit deinen anderen Offline-KI-Apps zu teilen."),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = folders.pickModels) { Text(t("Choose folder…", "Ordner wählen…")) }
        if (vm.modelFolderChosen) TextButton(onClick = { vm.setModelFolder(null) }) { Text(t("Use app folder", "App-Ordner nutzen")) }
    }
}

/** Companions und Chats in einen Ordner sichern und von dort zurückholen (z. B. beim Wechsel des Handys). */
@Composable
fun BackupInfo(folders: FolderActions) {
    SectionTitle(t("Back up companions and chats", "Companions und Chats sichern"))
    Text(
        t("Copies all companions and chats into a folder you choose (subfolders companions and chats), for example on the memory card or in a synced folder. Restoring adds what is missing and replaces files that are newer in the folder.",
            "Kopiert alle Companions und Chats in einen Ordner deiner Wahl (Unterordner companions und chats), z. B. auf die Speicherkarte oder in einen synchronisierten Ordner. Beim Zurückholen wird Fehlendes ergänzt und ersetzt, was im Ordner neuer ist."),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = folders.backup) { Text(t("Back up…", "Sichern…")) }
        OutlinedButton(onClick = folders.restore) { Text(t("Restore…", "Zurückholen…")) }
    }
}

/** Kurzzahl: 1633531 -> "1.6M", 12400 -> "12k". */
fun compactCount(n: Long): String = when {
    n >= 1_000_000L -> String.format(java.util.Locale.ROOT, "%.1fM", n / 1e6)
    n >= 10_000L -> "${n / 1000}k"
    n >= 1_000L -> String.format(java.util.Locale.ROOT, "%.1fk", n / 1e3)
    else -> n.toString()
}

/** Eine Zeile unter einem Suchtreffer: Größe (Parameter, grobe 4-Bit-Dateigröße), Alter der letzten Änderung, Beliebtheit. */
@Composable
fun hitDetails(hit: app.aichatmcp.core.ModelHit): String {
    val parts = mutableListOf<String>()
    val params = app.aichatmcp.core.HuggingFace.paramsLabel(hit.params)
    if (params.isNotEmpty()) parts += t("$params parameters, about ${formatBytes(hit.estimatedQ4Bytes)} (Q4)", "$params Parameter, etwa ${formatBytes(hit.estimatedQ4Bytes)} (Q4)")
    val days = app.aichatmcp.core.HuggingFace.ageDays(hit.updated)
    if (days != null) {
        parts += when {
            days < 1 -> t("updated today", "heute aktualisiert")
            days < 30 -> t("updated $days days ago", "aktualisiert vor $days Tagen")
            days < 365 -> (days / 30).let { m -> t("updated $m months ago", "aktualisiert vor $m Monaten") }
            else -> (days / 365).let { y -> t("updated $y years ago", "aktualisiert vor $y Jahren") }
        }
    }
    parts += "↓ ${compactCount(hit.downloads)}  ♥ ${compactCount(hit.likes)}"
    return parts.joinToString("  ·  ")
}
