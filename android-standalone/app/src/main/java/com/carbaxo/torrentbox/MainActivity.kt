package com.carbaxo.torrentbox

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

private val mainHandler = Handler(Looper.getMainLooper())
private fun onMain(block: () -> Unit) = mainHandler.post(block)

// Paleta al estilo de la web (morado Stremio)
private val Accent = Color(0xFF7B5BF5)
private val Bg = Color(0xFF0C0B11)
private val Surface1 = Color(0xFF15141D)
private val Muted = Color(0xFF8F8BA1)

class MainActivity : ComponentActivity() {
    private lateinit var saveRoot: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saveRoot = File(getExternalFilesDir(null) ?: filesDir, "torrents").apply { mkdirs() }

        TorrentEngine.start()
        DownloadService.start(this)
        StreamServer.ensureStarted()
        Sync.init(this)
        RealDebrid.init(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Surface1)
            ) {
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    AppScreen(
                        saveRoot = saveRoot,
                        initialMagnet = magnetFromIntent(intent),
                        onPlay = { infoHash ->
                            startActivity(Intent(this, PlayerActivity::class.java).putExtra("infoHash", infoHash))
                        },
                        onPlayUrl = { url ->
                            startActivity(Intent(this, PlayerActivity::class.java).putExtra("url", url))
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

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    DISCOVER("Descubrir", Icons.Filled.Explore),
    SEARCH("Buscar", Icons.Filled.Search),
    DOWNLOADS("Descargas", Icons.Filled.Download),
    SETTINGS("Ajustes", Icons.Filled.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(saveRoot: File, initialMagnet: String?, onPlay: (String) -> Unit, onPlayUrl: (String) -> Unit) {
    var tab by remember { mutableStateOf(Tab.DISCOVER) }
    var detail by remember { mutableStateOf<Tmdb.Title?>(null) }
    var catalogType by remember { mutableStateOf("movie") }
    val downloads = remember { mutableStateListOf<TorrentEngine.Snapshot>() }
    var pendingPlay by remember { mutableStateOf<String?>(null) }

    // Refresco de descargas + auto-reproducción cuando el vídeo está listo
    LaunchedEffect(Unit) {
        while (true) {
            val snaps = TorrentEngine.snapshots()
            onMain {
                downloads.clear(); downloads.addAll(snaps)
                val p = pendingPlay
                if (p != null) {
                    val s = snaps.find { it.infoHash == p && it.hasVideo }
                    if (s != null) { pendingPlay = null; onPlay(p) }
                }
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    fun addMagnet(m: String, autoplay: Boolean) {
        if (m.isBlank()) return
        TorrentEngine.addMagnet(m.trim(), saveRoot) { d, _ ->
            if (autoplay && d != null) onMain { pendingPlay = d.infoHash }
        }
    }

    LaunchedEffect(initialMagnet) { if (!initialMagnet.isNullOrBlank()) addMagnet(initialMagnet, false) }

    // Ficha de detalle a pantalla completa
    val d = detail
    if (d != null) {
        DetailScreen(
            title = d,
            onBack = { detail = null },
            onWatch = { magnet -> addMagnet(magnet, true); tab = Tab.DOWNLOADS; detail = null },
            onDownload = { magnet -> addMagnet(magnet, false) },
            onPlayUrl = onPlayUrl
        )
        return
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Surface1) {
                Tab.values().forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Accent, selectedTextColor = Accent, indicatorColor = Surface1
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                Tab.DISCOVER -> DiscoverScreen(catalogType, { catalogType = it }, onOpen = { detail = it })
                Tab.SEARCH -> SearchScreen(onOpen = { detail = it })
                Tab.DOWNLOADS -> DownloadsScreen(downloads, onPlay)
                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

@Composable
fun PosterCard(t: Tmdb.Title, width: Int = 120, onClick: () -> Unit) {
    Column(Modifier.width(width.dp).clickable { onClick() }) {
        AsyncImage(
            model = t.poster,
            contentDescription = t.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.height(6.dp))
        Text(t.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            t.year + (if (t.rating > 0) "  ⭐ ${t.rating}" else ""),
            style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(type: String, onType: (String) -> Unit, onOpen: (Tmdb.Title) -> Unit) {
    var rows by remember { mutableStateOf<List<Tmdb.Row>>(emptyList()) }
    var status by remember { mutableStateOf(if (Tmdb.hasKey) "Cargando catálogos…" else "") }

    LaunchedEffect(type) {
        if (!Tmdb.hasKey) { status = "" ; return@LaunchedEffect }
        status = "Cargando catálogos…"; rows = emptyList()
        Tmdb.catalogs(type) { list, err ->
            onMain { rows = list ?: emptyList(); status = if (list == null) (err ?: "Error") else "" }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        Sync.onSignInResult(res.data) { _, _ -> }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Descubrir", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (Sync.enabled) {
                    if (Sync.email == null) {
                        OutlinedButton(onClick = { Sync.signInIntent()?.let { launcher.launch(it) } }) { Text("Entrar con Google") }
                    } else {
                        TextButton(onClick = { Sync.signOut() }) { Text("👤 Salir") }
                    }
                }
            }
            if (Sync.enabled && Sync.email != null) {
                Text("Sincronizado: ${Sync.email}", style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            // Mi lista (favoritos sincronizados con la cuenta)
            if (Sync.favorites.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Mi lista", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(Sync.favorites.size) { i ->
                        val f = Sync.favorites[i]
                        val t = Tmdb.Title(f.tmdbId, f.title, f.title, f.year, f.poster, f.rating, f.type)
                        PosterCard(t) { onOpen(t) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(selected = type == "movie", onClick = { onType("movie") },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Películas") }
                SegmentedButton(selected = type == "series", onClick = { onType("series") },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Series") }
            }
            if (!Tmdb.hasKey) {
                Spacer(Modifier.height(12.dp))
                Text("Catálogos no disponibles en esta compilación.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (status.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(status, color = Muted, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(8.dp))
        }
        items(rows.size) { idx ->
            val row = rows[idx]
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(row.items.size) { i -> PosterCard(row.items[i]) { onOpen(row.items[i]) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpen: (Tmdb.Title) -> Unit) {
    var query by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("movie") }
    var results by remember { mutableStateOf<List<Tmdb.Title>>(emptyList()) }
    var status by remember { mutableStateOf("") }

    fun go() {
        if (query.isBlank() || !Tmdb.hasKey) return
        status = "Buscando…"; results = emptyList()
        Tmdb.searchText(query.trim(), type) { list, err ->
            onMain { results = list ?: emptyList(); status = if (list == null) (err ?: "Error") else "${results.size} resultados" }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        item {
            Text("Buscar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Película o serie…") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(selected = type == "movie", onClick = { type = "movie" },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Películas") }
                    SegmentedButton(selected = type == "series", onClick = { type = "series" },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Series") }
                }
                Button(onClick = { go() }) { Text("Buscar") }
            }
            if (status.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(status, color = Muted, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(10.dp))
        }
        items(results.size) { i ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(results[i]) }.padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(model = results[i].poster, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.width(70.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.weight(1f).align(Alignment.CenterVertically)) {
                    Text(results[i].title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(results[i].year + (if (results[i].rating > 0) "  ⭐ ${results[i].rating}" else ""),
                        style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        Sync.onSignInResult(res.data) { _, _ -> }
    }
    var rdInput by remember { mutableStateOf("") }
    var rdStatus by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Ajustes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // --- Cuenta (Google / sincronización) ---
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cuenta", fontWeight = FontWeight.Bold)
                if (!Sync.enabled) {
                    Text("El login con Google no está disponible en esta compilación.", color = Muted, style = MaterialTheme.typography.bodySmall)
                } else if (Sync.email == null) {
                    Text("Inicia sesión para sincronizar tu lista con la app del PC.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { Sync.signInIntent()?.let { launcher.launch(it) } }) { Text("Entrar con Google") }
                } else {
                    Text("👤 ${Sync.email}", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { Sync.signOut() }) { Text("Cerrar sesión") }
                }
            }
        }

        // --- Real-Debrid ---
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Real-Debrid", fontWeight = FontWeight.Bold)
                if (RealDebrid.configured) {
                    Text("⚡ Conectado${RealDebrid.account?.let { " · $it" } ?: ""}", color = Color(0xFF34D399), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { RealDebrid.disconnect() }) { Text("Desconectar") }
                } else {
                    Text("Pega tu token para reproducir por streaming directo (sin descargar en el móvil).", color = Muted, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = rdInput, onValueChange = { rdInput = it }, label = { Text("Token de Real-Debrid") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        rdStatus = "Validando…"
                        RealDebrid.connect(rdInput) { ok, msg -> onMain { rdStatus = if (ok) "Conectado como $msg" else (msg ?: "Error") } }
                    }, enabled = rdInput.isNotBlank()) { Text("Conectar") }
                    Text("Consíguelo en real-debrid.com/apitoken", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                if (rdStatus.isNotBlank()) Text(rdStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun DownloadsScreen(downloads: List<TorrentEngine.Snapshot>, onPlay: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        item {
            Text("Descargas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            if (downloads.isEmpty()) Text("Aún no hay descargas. Abre un título y pulsa Ver o Descargar.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        items(downloads.size) { i -> DownloadCard(downloads[i], onPlay) }
    }
}

@Composable
fun DownloadCard(d: TorrentEngine.Snapshot, onPlay: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = Surface1)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(d.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
            Text(
                "${(d.progress * 100).toInt()}%  ·  ↓ ${Search.humanSize(d.downloadRate.toLong())}/s  ·  ${d.numPeers} peers",
                style = MaterialTheme.typography.bodySmall, color = Muted
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (d.hasVideo) Button(onClick = { onPlay(d.infoHash) }) { Text("▶ Ver") }
                OutlinedButton(onClick = {
                    if (d.paused) TorrentEngine.resume(d.infoHash) else TorrentEngine.pause(d.infoHash)
                }) { Text(if (d.paused) "Reanudar" else "Pausar") }
                OutlinedButton(onClick = { TorrentEngine.remove(d.infoHash, deleteFiles = true) }) { Text("Borrar") }
            }
        }
    }
}

@Composable
fun DetailScreen(title: Tmdb.Title, onBack: () -> Unit, onWatch: (String) -> Unit, onDownload: (String) -> Unit, onPlayUrl: (String) -> Unit) {
    var detail by remember { mutableStateOf<Tmdb.Detail?>(null) }
    var sources by remember { mutableStateOf<List<Search.Result>>(emptyList()) }
    var status by remember { mutableStateOf("Cargando…") }
    var loadingSources by remember { mutableStateOf(false) }
    var rdStatus by remember { mutableStateOf("") }

    LaunchedEffect(title.tmdbId) {
        Tmdb.detail(title.type, title.tmdbId) { d, _ -> onMain { detail = d; status = "" } }
    }

    fun loadSources(dt: Tmdb.Detail) {
        loadingSources = true; sources = emptyList()
        Search.search(dt.originalTitle) { list, err ->
            onMain { loadingSources = false; sources = list ?: emptyList(); if (list == null) status = err ?: "Sin fuentes" }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val dt = detail
        Box {
            AsyncImage(
                model = dt?.backdrop ?: title.poster,
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(220.dp)
            )
            TextButton(onClick = onBack, modifier = Modifier.padding(8.dp)) { Text("← Volver", color = Color.White) }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                listOfNotNull(
                    if (title.type == "series") "Serie" else "Película",
                    title.year.ifBlank { null },
                    if (title.rating > 0) "⭐ ${title.rating}" else null,
                    dt?.genres?.joinToString(" · ")?.ifBlank { null }
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall, color = Muted
            )
            if (dt != null && dt.overview.isNotBlank()) Text(dt.overview, style = MaterialTheme.typography.bodyMedium)
            if (status.isNotBlank()) Text(status, color = Muted, style = MaterialTheme.typography.bodySmall)

            // Favorito (sincroniza con la cuenta)
            if (Sync.enabled && Sync.email != null) {
                val fid = "tmdb:${title.tmdbId}"
                OutlinedButton(onClick = { Sync.toggleFavorite(title) }) {
                    Text(if (Sync.isFav(fid)) "❤ En Mi lista" else "🤍 Añadir a Mi lista")
                }
            }

            Button(onClick = { dt?.let { loadSources(it) } }, enabled = dt != null && !loadingSources, modifier = Modifier.fillMaxWidth()) {
                Text(if (loadingSources) "Buscando fuentes…" else "Buscar fuentes")
            }

            sources.forEach { r ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(r.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("▲ ${r.seeders} seeders · ${Search.humanSize(r.sizeBytes)}", style = MaterialTheme.typography.labelSmall, color = Muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onWatch(r.magnet) }) { Text("▶ Ver") }
                            OutlinedButton(onClick = { onDownload(r.magnet) }) { Text("⬇ Descargar") }
                            if (RealDebrid.configured) {
                                OutlinedButton(onClick = {
                                    rdStatus = "⚡ Preparando en Real-Debrid…"
                                    RealDebrid.streamMagnet(r.magnet) { url, err, progress ->
                                        onMain {
                                            when {
                                                url != null -> { rdStatus = ""; onPlayUrl(url) }
                                                progress != null -> rdStatus = "Real-Debrid descargando… ${progress}% (vuelve a pulsar ⚡ en un rato)"
                                                else -> rdStatus = err ?: "Error de Real-Debrid"
                                            }
                                        }
                                    }
                                }) { Text("⚡ RD") }
                            }
                        }
                    }
                }
            }
            if (rdStatus.isNotBlank()) Text(rdStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
        }
    }
}
