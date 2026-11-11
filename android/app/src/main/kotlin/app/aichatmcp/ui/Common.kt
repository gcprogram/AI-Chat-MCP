package app.aichatmcp.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Rahmen für Nebenbildschirme: Titelzeile mit Zurück-Knopf, darunter scrollbarer Inhalt. */
@Composable
fun ScreenFrame(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Zurück") }
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider()
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun TextSetting(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(),
    secret: Boolean = false, minLines: Int = 1, maxLines: Int = 1, keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, modifier = modifier,
        singleLine = maxLines == 1 && minLines == 1, minLines = minLines, maxLines = maxLines,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
    )
}

@Composable
fun NumberSetting(label: String, value: String, onChange: (String) -> Unit) =
    TextSetting(label, value, onChange, keyboard = KeyboardType.Decimal)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected, onValueChange = {}, readOnly = true, label = { Text(label) }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

/** Das Bild eines Companions als Kreis; ohne Bild der Anfangsbuchstabe auf einer aus dem Namen abgeleiteten Farbe. [onClick]: nur mit Bild wirksam (Vollbild). */
@Composable
fun AvatarView(file: File?, name: String, size: Dp = 40.dp, onClick: (() -> Unit)? = null) {
    val bitmap by produceState<ImageBitmap?>(null, file?.path, file?.lastModified()) {
        value = file?.let { withContext(Dispatchers.IO) { decodeAvatar(it) } }
    }
    val image = bitmap
    if (image != null) {
        val shape = Modifier.size(size).clip(CircleShape)
        Image(
            image, contentDescription = name, contentScale = ContentScale.Crop,
            modifier = if (onClick != null) shape.clickable(onClick = onClick) else shape,
        )
    } else {
        val hue = (name.fold(0) { acc, c -> acc * 31 + c.code }.mod(360)).toFloat()
        Box(Modifier.size(size).clip(CircleShape).background(Color.hsv(hue, 0.45f, 0.75f)), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Vollbildansicht des Companion-Bildes: schwarzer Grund, Bild eingepasst, Name darunter; ein Tippen irgendwo schließt. */
@Composable
fun AvatarFullscreen(file: File, name: String, onClose: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) { decodeAvatar(file, target = 2048) } // das eingebettete Bild ist höchstens 768 Pixel: nie verkleinert
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            Text(
                name, color = Color.White, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            )
        }
    }
}

/** Lädt das Bild verkleinert (die Dateien sind oft mehrere Megapixel groß, angezeigt werden wenige Dutzend dp). */
private fun decodeAvatar(file: File, target: Int = 256): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/** Deutscher Sprachname zu einem Kürzel ("de" -> "Deutsch"); null oder unbekannt -> "Unbekannt". */
fun languageLabel(code: String?): String {
    if (code.isNullOrBlank()) return "Unbekannt"
    val name = java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.GERMAN)
    return name.ifBlank { code }.replaceFirstChar { it.titlecase() }
}
