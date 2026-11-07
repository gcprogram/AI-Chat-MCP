package app.aichatmcp.voicestudio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aichatmcp.voicestudio.GOAL_MINUTES
import app.aichatmcp.voicestudio.MIN_MINUTES
import app.aichatmcp.voicestudio.Phase
import app.aichatmcp.voicestudio.Recorder
import app.aichatmcp.voicestudio.Screen
import app.aichatmcp.voicestudio.StudioViewModel
import app.aichatmcp.voicestudio.formatMinutes
import app.aichatmcp.voicestudio.recordingsLabel
import java.util.Locale

@Composable
fun Frame(title: String, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextButton(onClick = onBack) { Text("‹ Zurück") }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = if (onBack == null) 8.dp else 0.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

@Composable
fun StudioApp(vm: StudioViewModel, onRecordClick: () -> Unit, onPickText: () -> Unit, onPickZip: () -> Unit, onExport: (String) -> Unit) {
    when (vm.screen) {
        Screen.PROJECTS -> ProjectsScreen(vm)
        Screen.PROJECT -> ProjectScreen(vm, onPickText, onPickZip, onExport)
        Screen.RECORD -> RecordScreen(vm, onRecordClick)
        Screen.RECORDINGS -> RecordingsScreen(vm)
        Screen.CHECK -> CheckScreen(vm)
    }
}

// --- Projekte ------------------------------------------------------------------------------------------------------

@Composable
private fun ProjectsScreen(vm: StudioViewModel) {
    var creating by remember { mutableStateOf(false) }
    Frame("Stimmen-Studio", null) {
        Text(
            "Hier nimmst du deine Stimme auf, um daraus eine eigene Stimme für den Chat zu trainieren. Ein Projekt ist eine Stimme: " +
                "Sätze einsprechen, prüfen und als ZIP auf den PC bringen.",
            style = MaterialTheme.typography.bodyMedium,
        )
        vm.projects.forEach { p ->
            Card(Modifier.fillMaxWidth().clickable { vm.openProject(p.name) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${recordingsLabel(p.count)} · ${formatMinutes(p.minutes)} von $GOAL_MINUTES Minuten · ${p.pending} Sätze offen",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(progress = { (p.minutes / GOAL_MINUTES).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (vm.projects.isEmpty()) Text("Noch kein Projekt. Lege eins an und fang mit den Beispielsätzen an.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("Neues Projekt") }
    }
    if (creating) {
        var name by remember { mutableStateOf("meine-stimme") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Neues Projekt") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name der Stimme") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { creating = false; vm.createProject(name) }, enabled = name.isNotBlank()) { Text("Anlegen") } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun ProjectScreen(vm: StudioViewModel, onPickText: () -> Unit, onPickZip: () -> Unit, onExport: (String) -> Unit) {
    val info = vm.current
    var pasting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Frame(info?.name ?: "Projekt", vm::back) {
        if (info == null) return@Frame
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${formatMinutes(info.minutes)} von $GOAL_MINUTES Minuten", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(progress = { (info.minutes / GOAL_MINUTES).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("${recordingsLabel(info.count)} · ${info.pending} Sätze offen", style = MaterialTheme.typography.bodySmall)
                Text(
                    when {
                        info.minutes < 15 -> "Das ist noch zu wenig. Ab etwa $MIN_MINUTES Minuten lässt sich eine vorhandene Stimme feinabstimmen."
                        info.minutes < MIN_MINUTES -> "Fast geschafft: noch etwa ${(MIN_MINUTES - info.minutes).toInt() + 1} Minuten bis zur Mindestmenge."
                        info.minutes < GOAL_MINUTES -> "Mindestmenge erreicht. Mehr Aufnahmen (bis $GOAL_MINUTES Minuten) machen die Stimme besser."
                        else -> "Genug für ein gutes Ergebnis. Jetzt prüfen und exportieren."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Button(onClick = vm::startSession, enabled = info.pending > 0, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (info.pending > 0) "Aufnehmen" else "Keine offenen Sätze: unten Sätze hinzufügen")
        }
        if (vm.message.isNotEmpty()) Text(vm.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)

        Text("Sätze hinzufügen", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "Nimm immer neue Sätze auf, nicht dieselben noch einmal: Mehr Abwechslung macht die Stimme besser. Die 126 Beispielsätze ergeben nur " +
                "etwa 7 Minuten; für $MIN_MINUTES Minuten brauchst du rund 500 Sätze, für $GOAL_MINUTES rund 1000. Am schnellsten geht das mit einem " +
                "Buch oder eigenen Texten (Text einfügen oder Datei wählen): Sie werden automatisch in Sätze zerlegt.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { vm.addBuiltIn("sentences_de.txt", "deutsche Beispielsätze") }, modifier = Modifier.weight(1f)) { Text("Deutsch (126)") }
            OutlinedButton(onClick = { vm.addBuiltIn("sentences_en.txt", "englische Beispielsätze") }, modifier = Modifier.weight(1f)) { Text("English (126)") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { pasting = true }, modifier = Modifier.weight(1f)) { Text("Text einfügen…") }
            OutlinedButton(onClick = onPickText, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Buch/Datei…") }
        }

        Text("Aufnahmen", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        OutlinedButton(onClick = onPickZip, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Aufnahmen vom PC übernehmen (ZIP)…") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = vm::openRecordings, modifier = Modifier.weight(1f)) { Text("Anhören / löschen") }
            OutlinedButton(onClick = vm::openCheck, modifier = Modifier.weight(1f)) { Text("Prüfen") }
        }
        Button(onClick = { onExport(info.name) }, enabled = info.count > 0 && !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Als ZIP exportieren…") }
        Text(
            "Die ZIP-Datei (Ordner wavs/ und metadata.csv) kopierst du auf den PC. Dort folgt das Training mit Piper, die Anleitung steht in " +
                "tools/voice/README.md im Projekt.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("Tipps für gute Aufnahmen", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "• Ruhiger Raum ohne Hall (Teppich, Vorhänge, Kleiderschrank helfen). Fenster zu, Geräte aus.\n" +
                "• Immer gleicher Abstand zum Telefon (eine Handbreit) und gleiche Haltung.\n" +
                "• Normal sprechen, mit der Betonung, die der Companion später haben soll. Nicht flüstern, nicht schreien.\n" +
                "• Bei einem Versprecher einfach „Wiederholen“.\n" +
                "• Lieber an mehreren Tagen je 20 Minuten als alles auf einmal.\n" +
                "• Ziel: $MIN_MINUTES bis $GOAL_MINUTES Minuten.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { deleting = true }) { Text("Projekt löschen", color = MaterialTheme.colorScheme.error) }
    }
    if (pasting) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text("Text einfügen") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text("Text oder Buchseiten") },
                    minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { pasting = false; vm.addText(text) }, enabled = text.isNotBlank()) { Text("Sätze erzeugen") } },
            dismissButton = { TextButton(onClick = { pasting = false }) { Text("Abbrechen") } },
        )
    }
    if (deleting && info != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Projekt löschen?") },
            text = { Text("„${info.name}“ mit allen ${info.count} Aufnahmen wird gelöscht. Das lässt sich nicht rückgängig machen.") },
            confirmButton = { TextButton(onClick = { deleting = false; vm.deleteProject(info.name) }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Abbrechen") } },
        )
    }
}

// --- Aufnehmen -----------------------------------------------------------------------------------------------------

@Composable
private fun RecordScreen(vm: StudioViewModel, onRecordClick: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) { // Bildschirm anlassen, solange man aufnimmt
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val info = vm.current
    Frame("Aufnehmen", vm::back) {
        if (info != null) {
            Text(
                "${formatMinutes(info.minutes)} von $GOAL_MINUTES Minuten · ${info.pending} Sätze offen",
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(progress = { (info.minutes / GOAL_MINUTES).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
        }
        val sentence = vm.sentence
        if (sentence == null) {
            Text(vm.message.ifEmpty { "Keine offenen Sätze." }, style = MaterialTheme.typography.titleMedium)
            Button(onClick = vm::back, modifier = Modifier.fillMaxWidth()) { Text("Zurück zum Projekt") }
            return@Frame
        }
        Card(Modifier.fillMaxWidth()) {
            Text(sentence, fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(20.dp))
        }
        if (vm.message.isNotEmpty()) Text(vm.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

        when (vm.phase) {
            Phase.READY -> {
                Button(
                    onClick = onRecordClick, modifier = Modifier.fillMaxWidth().height(72.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                ) { Text("●  Aufnahme starten", fontSize = 20.sp) }
                OutlinedButton(onClick = vm::skip, modifier = Modifier.fillMaxWidth()) { Text("Satz überspringen") }
                Text(
                    "Tippe auf „Aufnahme starten“, sprich den Satz in deinem normalen Ton und tippe dann auf „Beenden“.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Phase.RECORDING -> {
                LevelMeter(vm.level)
                Text(String.format(Locale.GERMAN, "Aufnahme läuft … %.1f s", vm.seconds), style = MaterialTheme.typography.titleMedium)
                Button(onClick = vm::stopRecording, modifier = Modifier.fillMaxWidth().height(72.dp)) { Text("■  Beenden", fontSize = 20.sp) }
                if (vm.seconds > Recorder.MAX_SECONDS - 8) Text("Gleich zu Ende: nach ${Recorder.MAX_SECONDS} Sekunden bricht die Aufnahme ab.", style = MaterialTheme.typography.bodySmall)
            }
            Phase.REVIEW -> {
                if (vm.processing) {
                    Text("Verarbeite …", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    Text(String.format(Locale.GERMAN, "Aufnahme: %.1f Sekunden", vm.reviewSeconds), style = MaterialTheme.typography.titleMedium)
                    vm.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    OutlinedButton(onClick = if (vm.playing) vm::stopPlaying else vm::preview, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(if (vm.playing) "■  Anhören beenden" else "▶  Anhören")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = vm::retry, modifier = Modifier.weight(1f).height(56.dp)) { Text("Wiederholen") }
                        Button(onClick = vm::keep, modifier = Modifier.weight(1f).height(56.dp)) { Text("Behalten ✓") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelMeter(level: Float) {
    // Die Spitze springt stark: Wurzel macht leise Sprache sichtbar; rot ab nahe Vollaussteuerung
    val shown = kotlin.math.sqrt(level.coerceIn(0f, 1f))
    LinearProgressIndicator(
        progress = { shown },
        modifier = Modifier.fillMaxWidth().height(14.dp),
        color = if (level > 0.95f) Color(0xFFB3261E) else MaterialTheme.colorScheme.primary,
    )
    Text(
        if (level > 0.95f) "Zu laut: weiter weg oder leiser sprechen" else if (level < 0.03f) "Nichts zu hören … lauter oder näher heran" else "Pegel in Ordnung",
        style = MaterialTheme.typography.bodySmall,
    )
}

// --- Aufnahmen ansehen und prüfen ----------------------------------------------------------------------------------

@Composable
private fun RecordingsScreen(vm: StudioViewModel) {
    var asking by remember { mutableStateOf<app.aichatmcp.voicestudio.RecordingItem?>(null) }
    Frame("Aufnahmen (${vm.recordings.size})", vm::back) {
        if (vm.message.isNotEmpty()) Text(vm.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (vm.recordings.isEmpty()) Text("Noch keine Aufnahmen.", style = MaterialTheme.typography.bodyMedium)
        vm.recordings.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.text, style = MaterialTheme.typography.bodyLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            String.format(Locale.GERMAN, "%s · %.1f s", item.name, item.seconds), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { vm.play(item) }) { Text("▶") }
                        TextButton(onClick = { asking = item }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        if (vm.playing) OutlinedButton(onClick = vm::stopPlaying, modifier = Modifier.fillMaxWidth()) { Text("■  Wiedergabe beenden") }
    }
    asking?.let { item ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Aufnahme löschen?") },
            text = { Text("„${item.text}“ wird gelöscht und kommt wieder in die Liste der offenen Sätze, damit du ihn neu einsprechen kannst.") },
            confirmButton = { TextButton(onClick = { asking = null; vm.deleteRecording(item) }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun CheckScreen(vm: StudioViewModel) {
    Frame("Prüfen", vm::back) {
        val r = vm.report
        if (r == null) {
            Text("Prüfe …", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            return@Frame
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${recordingsLabel(r.count)} · ${formatMinutes(r.minutes)} Minuten", style = MaterialTheme.typography.titleMedium)
                Text(
                    String.format(Locale.GERMAN, "Median %.1f s je Satz · mittlere Lautstärke %.0f dBFS (Streuung %.1f dB)", r.medianSeconds, r.meanLevelDb, r.spreadDb),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (r.errors.isEmpty()) Text("Keine Fehler gefunden.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
        r.errors.forEach { Text("Fehler: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        r.notes.take(40).forEach { Text("Hinweis: $it", style = MaterialTheme.typography.bodyMedium) }
        if (r.notes.size > 40) Text("… und ${r.notes.size - 40} weitere Hinweise", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Fehler solltest du vor dem Training beheben (betroffene Aufnahmen unter „Anhören / löschen“ löschen und neu einsprechen). " +
                "Hinweise sind Empfehlungen.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
