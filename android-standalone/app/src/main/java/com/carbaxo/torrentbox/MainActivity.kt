package com.carbaxo.torrentbox

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.io.File

private val mainHandler = Handler(Looper.getMainLooper())
private fun onMain(block: () -> Unit) = mainHandler.post(block)

class MainActivity : ComponentActivity() {

    private lateinit var saveRoot: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saveRoot = File(getExternalFilesDir(null) ?: filesDir, "torrents").apply { mkdirs() }

        TorrentEngine.start()
        DownloadService.start(this)
        StreamServer.ensureStarted()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    AppScreen(
                        saveRoot = saveRoot,
                        initialMagnet = magnetFromIntent(intent),
                        onPlay = { infoHash ->
                            startActivity(Intent(this, PlayerActivity::class.java).putExtra("infoHash", infoHash))
                        }
                    )
                }
            }
        }
    }

    private fun magnetFromIntent(i: Intent?): String? {
        val data = i?.data?.toString()
        return if (data != null && data.startsWith("magnet:")) data else null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(saveRoot: File, initialMagnet: String?, onPlay: (String) -> Unit) {
    var magnet by remember { mutableStateOf(initialMagnet ?: "") }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<Search.Result>() }
    val downloads = remember { mutableStateListOf<TorrentEngine.Snapshot>() }

    // Refresco del estado de descargas cada segundo
    LaunchedEffect(Unit) {
        while (true) {
            val snaps = TorrentEngine.snapshots()
            onMain { downloads.clear(); downloads.addAll(snaps) }
            kotlinx.coroutines.delay(1000)
        }
    }

    fun add(m: String) {
        if (m.isBlank()) return
        status = "Añadiendo… (obteniendo metadatos)"
        TorrentEngine.addMagnet(m.trim(), saveRoot) { d, err ->
            onMain { status = if (d != null) "Añadido: ${d.name}" else "Error: $err" }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("🎬 TorrentBox", style = MaterialTheme.typography.headlineSmall)
        Text("Descarga y reproduce en el propio móvil", style = MaterialTheme.typography.bodySmall)

        // Añadir magnet
        OutlinedTextField(
            value = magnet, onValueChange = { magnet = it },
            label = { Text("Enlace magnet") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { add(magnet); magnet = "" }, modifier = Modifier.fillMaxWidth()) {
            Text("Añadir y descargar")
        }

        HorizontalDivider()

        // Buscar
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Buscar película/serie") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                if (query.isBlank()) return@Button
                searching = true; results.clear()
                Search.search(query.trim()) { list, err ->
                    onMain {
                        searching = false
                        if (list != null) { results.addAll(list); status = "${list.size} resultados" }
                        else status = "Error: $err"
                    }
                }
            },
            enabled = !searching, modifier = Modifier.fillMaxWidth()
        ) { Text(if (searching) "Buscando…" else "Buscar") }

        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)

        results.forEach { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(r.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    Text("▲ ${r.seeders} seeders · ${Search.humanSize(r.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { add(r.magnet) }) { Text("Descargar") }
                }
            }
        }

        HorizontalDivider()
        Text("Mis descargas", style = MaterialTheme.typography.titleMedium)

        if (downloads.isEmpty()) {
            Text("Aún no hay descargas.", style = MaterialTheme.typography.bodySmall)
        }
        downloads.forEach { d ->
            DownloadCard(d, onPlay)
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun DownloadCard(d: TorrentEngine.Snapshot, onPlay: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(d.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
            Text(
                "${(d.progress * 100).toInt()}%  ·  ↓ ${Search.humanSize(d.downloadRate.toLong())}/s  ·  " +
                    "${d.numPeers} peers  ·  ${d.state}",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (d.hasVideo) {
                    Button(onClick = { onPlay(d.infoHash) }) { Text("▶ Ver") }
                }
                OutlinedButton(onClick = {
                    if (d.paused) TorrentEngine.resume(d.infoHash) else TorrentEngine.pause(d.infoHash)
                }) { Text(if (d.paused) "Reanudar" else "Pausar") }
                OutlinedButton(onClick = { TorrentEngine.remove(d.infoHash, deleteFiles = true) }) { Text("Borrar") }
            }
        }
    }
}
