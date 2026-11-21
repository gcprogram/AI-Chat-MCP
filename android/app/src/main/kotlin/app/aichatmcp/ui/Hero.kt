package app.aichatmcp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Das große Kopfbild der Autoren-, Vorlese- und Spiel-Bildschirme: quadratisch in voller Breite, auf einem Hochkant-Bildschirm etwa die halbe Höhe. */
@Composable
fun HeroImage(file: File?, name: String) {
    val image by produceState<ImageBitmap?>(null, file?.path) { value = file?.let { withContext(Dispatchers.IO) { decodeAvatar(it, target = 1024) } } }
    image?.let {
        Image(it, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)))
    }
}

/** Ein Text, der sich Zeichen für Zeichen aufbaut, als würde er gerade von einer KI erzeugt; ändert sich [full], beginnt das Tippen von vorn. */
@Composable
fun TypewriterText(full: String) {
    var shown by remember(full) { mutableIntStateOf(0) }
    var cursor by remember { mutableStateOf(true) }
    LaunchedEffect(full) {
        delay(600)
        while (shown < full.length) { // unregelmäßig wie ein erzeugender Text: nach Satzzeichen und Wortenden etwas länger
            shown++
            val c = full[shown - 1]
            delay(if (c == '.') 450L else if (c == ' ') 90L else 45L)
        }
        repeat(6) { cursor = !cursor; delay(500) }
        cursor = false
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // der fertige Text reserviert unsichtbar die Höhe, damit die Felder darunter beim Tippen nicht springen
        Text(full + " ", style = MaterialTheme.typography.titleMedium, color = Color.Transparent)
        Text(full.take(shown) + if (cursor) "▍" else "", style = MaterialTheme.typography.titleMedium)
    }
}

/** Ein Wartetext mit bewegten Punkten ("Lese das Buch" -> "Lese das Buch." / ".." / "..."), die Breite bleibt gleich, nichts springt. */
@Composable
fun WaitText(text: String, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium, color: Color = Color.Unspecified) {
    var dots by remember { mutableIntStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            dots = dots % 3 + 1
        }
    }
    Text(
        buildAnnotatedString {
            append(text)
            append(".".repeat(dots))
            withStyle(SpanStyle(color = Color.Transparent)) { append(".".repeat(3 - dots)) }
        },
        style = style, color = color,
    )
}
