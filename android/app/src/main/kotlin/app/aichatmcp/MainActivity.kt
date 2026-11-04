package app.aichatmcp

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aichatmcp.ui.ChatScreen
import app.aichatmcp.ui.CompanionsScreen
import app.aichatmcp.ui.Screen
import app.aichatmcp.ui.SettingsScreen
import app.aichatmcp.ui.ToolsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) { Root() }
            }
        }
    }
}

@Composable
private fun Root(vm: ChatViewModel = viewModel()) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.CHAT) }
    BackHandler(enabled = screen != Screen.CHAT) { screen = Screen.CHAT }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun read(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }.getOrNull()

    fun nameOf(uri: Uri): String = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "import"

    val importPersona = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u -> read(u)?.let { vm.importPersona(it, nameOf(u)); toast("Companion importiert") } ?: toast("Datei nicht lesbar") }
    }
    val importMcp = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u -> read(u)?.let { text -> vm.saveMcpJson(text)?.let(::toast) ?: toast("Werkzeuge werden neu geladen") } ?: toast("Datei nicht lesbar") }
    }
    val importCert = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u ->
            val pem = read(u)
            if (pem != null && "BEGIN CERTIFICATE" in pem) { vm.saveCert(nameOf(u), pem); toast("Zertifikat gespeichert als ${nameOf(u)}") }
            else toast("Keine PEM-Zertifikatsdatei")
        }
    }
    val importChat = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u -> read(u)?.let { vm.importChat(it)?.let(::toast) } ?: toast("Datei nicht lesbar") }
    }
    val exportChat = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { u ->
            runCatching { context.contentResolver.openOutputStream(u)?.use { it.write(vm.exportChat().toByteArray(Charsets.UTF_8)) } }
                .onSuccess { toast("Chat gespeichert") }.onFailure { toast("Speichern fehlgeschlagen: ${it.message}") }
        }
    }

    when (screen) {
        Screen.CHAT -> ChatScreen(
            vm, open = { screen = it },
            onExport = { exportChat.launch("${vm.persona.id.ifEmpty { "chat" }}.json") },
            onImport = { importChat.launch("*/*") },
        )
        Screen.SETTINGS -> SettingsScreen(vm) { screen = Screen.CHAT }
        Screen.COMPANIONS -> CompanionsScreen(vm, onBack = { screen = Screen.CHAT }, onImport = { importPersona.launch("*/*") })
        Screen.TOOLS -> ToolsScreen(vm, onBack = { screen = Screen.CHAT }, onImportMcp = { importMcp.launch("*/*") }, onImportCert = { importCert.launch("*/*") })
    }
}
