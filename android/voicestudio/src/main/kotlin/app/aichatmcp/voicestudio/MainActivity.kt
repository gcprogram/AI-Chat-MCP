package app.aichatmcp.voicestudio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aichatmcp.voicestudio.ui.StudioApp

class MainActivity : ComponentActivity() {
    private var model: StudioViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme(primary = Color(0xFFFFB1C4)) else lightColorScheme(primary = Color(0xFF7A1E3A)),
            ) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) { Root { model = it } }
            }
        }
    }

    override fun onStop() {
        model?.onAppStopped()
        super.onStop()
    }
}

@Composable
private fun Root(onModel: (StudioViewModel) -> Unit) {
    val vm: StudioViewModel = viewModel()
    onModel(vm)
    val context = LocalContext.current
    BackHandler(enabled = vm.screen != Screen.PROJECTS) { vm.back() }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording() else Toast.makeText(context, "Ohne Mikrofon-Berechtigung keine Aufnahme.", Toast.LENGTH_LONG).show()
    }
    val pickText = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importFile) }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importZip) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(vm::export) }

    StudioApp(
        vm,
        onRecordClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startRecording()
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        },
        // Kein MIME-Filter: Dateimanager melden .txt/.md mit uneinheitlichem Typ
        onPickText = { pickText.launch(arrayOf("*/*")) },
        onPickZip = { pickZip.launch(arrayOf("*/*")) },
        onExport = { name -> export.launch("$name.zip") },
    )
}
