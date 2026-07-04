package com.carbaxo.torrentbox

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.ui.platform.LocalContext
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

        Prefs.init(this)
        WatchStore.init(this)
        TorrentEngine.start()
        TorrentEngine.setLimits(Prefs.downLimitKB.value, Prefs.upLimitKB.value)
        DownloadService.start(this)
        StreamServer.ensureStarted()
        RealDebrid.init(this)
        Update.check()
        // Limpia el buffer de la sesión anterior (lo visto ya no sirve al reiniciar)
        Thread {
            runCatching {
                if (Prefs.autoCleanBuffer.value) {
                    val b = Prefs.bufferDirFile(); val dl = Prefs.downloadDirFile()
                    if (b.absolutePath != dl.absolutePath) b.listFiles()?.forEach { it.deleteRecursively() }
                }
            }
        }.start()
        // El token de Real-Debrid guardado en la nube (cuenta) se adopta aquí
        Sync.onRdToken = { t -> RealDebrid.adoptToken(t) }
        Sync.init(this)

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
                        onPlay = { infoHash, c -> bringToFront(); startActivity(playerIntent(c).putExtra("infoHash", infoHash)) },
                        onPlayUrl = { url, c -> bringToFront(); startActivity(playerIntent(c).putExtra("url", url)) }
                    )
                }
            }
        }
    }

    /**
     * Vuelve a traer NUESTRA app al frente si otra (p.ej. AceStream, que se
     * abre para arrancar su engine) se quedó por delante al llegar el stream.
     */
    private fun bringToFront() {
        runCatching {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            am.appTasks.firstOrNull()?.moveToFront()
        }
    }

    private fun playerIntent(c: PlayCtx) = Intent(this, PlayerActivity::class.java).apply {
        putExtra("tmdbId", c.tmdbId); putExtra("type", c.type)
        putExtra("season", c.season); putExtra("episode", c.episode)
        putExtra("name", c.name); putExtra("poster", c.poster); putExtra("resumeMs", c.resumeMs)
    }

    private fun magnetFromIntent(i: Intent?): String? {
        val data = i?.data?.toString()
        return if (data != null && data.startsWith("magnet:")) data else null
    }
}

/** Contexto del título que se está reproduciendo (para marcar visto / reanudar). */
data class PlayCtx(
    val tmdbId: Int = -1, val type: String = "movie",
    val season: Int = -1, val episode: Int = -1,
    val name: String = "", val poster: String? = null, val resumeMs: Long = 0L
)

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    DISCOVER("Descubrir", Icons.Filled.Explore),
    SEARCH("Buscar", Icons.Filled.Search),
    TV("TV", Icons.Filled.LiveTv),
    DOWNLOADS("Descargas", Icons.Filled.Download),
    SETTINGS("Ajustes", Icons.Filled.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(saveRoot: File, initialMagnet: String?, onPlay: (String, PlayCtx) -> Unit, onPlayUrl: (String, PlayCtx) -> Unit) {
    var tab by remember { mutableStateOf(Tab.DISCOVER) }
    var detail by remember { mutableStateOf<Tmdb.Title?>(null) }
    var catalogType by remember { mutableStateOf("movie") }
    val downloads = remember { mutableStateListOf<TorrentEngine.Snapshot>() }
    val rdDownloads = remember { mutableStateListOf<RdDownloads.Snap>() }
    var pendingPlay by remember { mutableStateOf<Pair<String, PlayCtx>?>(null) }
    val ctx = LocalContext.current

    // Refresco de descargas (torrent + Real-Debrid) + auto-reproducción.
    // El trabajo bloqueante (JNI/DownloadManager) va en IO; el estado se
    // actualiza al volver al hilo principal (fin de withContext).
    LaunchedEffect(Unit) {
        while (true) {
            val snaps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { TorrentEngine.snapshots() }
            val rd = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { RdDownloads.snapshots(ctx) } catch (_: Throwable) { emptyList() }
            }
            downloads.clear(); downloads.addAll(snaps)
            rdDownloads.clear(); rdDownloads.addAll(rd)
            val p = pendingPlay
            if (p != null) {
                val s = snaps.find { it.infoHash == p.first && it.hasVideo }
                if (s != null) { pendingPlay = null; onPlay(p.first, p.second) }
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    // buffer=true (Ver) descarga a la carpeta temporal; false (Descargar) a la permanente
    fun addMagnet(m: String, autoplay: Boolean, playCtx: PlayCtx = PlayCtx()) {
        if (m.isBlank()) return
        val dir = if (autoplay) Prefs.bufferDirFile() else Prefs.downloadDirFile()
        TorrentEngine.addMagnet(m.trim(), dir) { d, _ ->
            if (autoplay && d != null) onMain { pendingPlay = d.infoHash to playCtx }
        }
    }

    LaunchedEffect(initialMagnet) { if (!initialMagnet.isNullOrBlank()) addMagnet(initialMagnet, false) }

    // Avisos de episodios nuevos cuando llegan los favoritos de la nube
    LaunchedEffect(Sync.favorites.size) { if (Sync.favorites.isNotEmpty()) EpisodeAlerts.check(ctx) }

    // Ficha de detalle a pantalla completa
    val d = detail
    if (d != null) {
        DetailScreen(
            title = d,
            onBack = { detail = null },
            onWatch = { magnet, c -> addMagnet(magnet, true, c); tab = Tab.DOWNLOADS; detail = null },
            onDownload = { magnet -> addMagnet(magnet, false); tab = Tab.DOWNLOADS; detail = null },
            onPlayUrl = { url, c -> onPlayUrl(url, c) },
            onOpenDownloads = { tab = Tab.DOWNLOADS; detail = null }
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
                Tab.TV -> AceScreen(onPlayUrl)
                Tab.DOWNLOADS -> DownloadsScreen(downloads, rdDownloads, { h -> onPlay(h, PlayCtx()) }, { u -> onPlayUrl(u, PlayCtx()) })
                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

@Composable
fun PosterCard(t: Tmdb.Title, width: Int = 120, onClick: () -> Unit) {
    val watched = WatchStore.isWatchedTitle(t.type, t.tmdbId)
    Column(Modifier.width(width.dp).clickable { onClick() }) {
        Box {
            AsyncImage(
                model = t.poster,
                contentDescription = t.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp))
            )
            if (watched) Text(
                "✓ Visto",
                style = MaterialTheme.typography.labelSmall, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color(0xCC34D399)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(t.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            t.year + (if (t.rating > 0) "  ⭐ ${t.rating}" else ""),
            style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1
        )
    }
}

@Composable
fun ContinueCard(p: WatchStore.Prog, onClick: () -> Unit) {
    val pct = if (p.duration > 0) (p.position / p.duration).coerceIn(0.0, 1.0).toFloat() else 0f
    Column(Modifier.width(120.dp).clickable { onClick() }) {
        AsyncImage(
            model = p.poster, contentDescription = p.name, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))
        )
        LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        Text(p.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val ep = if (p.season != null && p.episode != null) "T${p.season} · E${p.episode}" else ""
        if (ep.isNotBlank()) Text(ep, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(type: String, onType: (String) -> Unit, onOpen: (Tmdb.Title) -> Unit) {
    var rows by remember { mutableStateOf<List<Tmdb.Row>>(emptyList()) }
    var status by remember { mutableStateOf(if (Tmdb.hasKey) "Cargando catálogos…" else "") }
    // Explorar una plataforma en modo rejilla paginada ("Ver más")
    var browse by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Recomendados según lo visto + favoritos
    val recs = remember { mutableStateListOf<Tmdb.Title>() }
    LaunchedEffect(WatchStore.list.size, Sync.favorites.size) {
        if (!Tmdb.hasKey) return@LaunchedEffect
        val seeds = (WatchStore.seeds() + Sync.favorites.map { it.tmdbId to it.type }).distinctBy { it.first }.take(6)
        if (seeds.isEmpty()) { recs.clear(); return@LaunchedEffect }
        Tmdb.recommendations(seeds) { list -> onMain { recs.clear(); recs.addAll(list) } }
    }

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

    browse?.let { (prov, nm) ->
        BrowseScreen(prov, nm, type, onOpen = onOpen, onBack = { browse = null })
        return
    }

    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        // Aviso de nueva versión (auto-actualización)
        Update.available?.let { up ->
            item {
                Card(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { Update.downloadAndInstall(ctx) },
                    colors = CardDefaults.cardColors(containerColor = Accent.copy(alpha = 0.18f))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("⬆️ Nueva versión disponible (build ${up.build}) — toca para instalar", fontWeight = FontWeight.Bold)
                        if (Update.status.isNotBlank()) Text(Update.status, style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                }
            }
        }
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
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(selected = type == "movie", onClick = { onType("movie") },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Películas") }
                SegmentedButton(selected = type == "series", onClick = { onType("series") },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Series") }
            }
            // Explorar por género
            if (Tmdb.hasKey) {
                Spacer(Modifier.height(10.dp))
                Text("Géneros", style = MaterialTheme.typography.labelMedium, color = Muted)
                Spacer(Modifier.height(6.dp))
                val genres = if (type == "movie") listOf(
                    28 to "Acción", 35 to "Comedia", 18 to "Drama", 27 to "Terror",
                    878 to "Ciencia ficción", 16 to "Animación", 53 to "Thriller",
                    10749 to "Romance", 12 to "Aventura", 80 to "Crimen", 99 to "Documental", 14 to "Fantasía"
                ) else listOf(
                    10759 to "Acción y aventura", 35 to "Comedia", 18 to "Drama", 16 to "Animación",
                    80 to "Crimen", 9648 to "Misterio", 10765 to "Ciencia ficción y fantasía",
                    99 to "Documental", 10751 to "Familia"
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(genres.size) { i ->
                        val (gid, gname) = genres[i]
                        AssistChip(onClick = { browse = "genre:$gid" to gname }, label = { Text(gname) })
                    }
                }
            }
            if (!Tmdb.hasKey) {
                Spacer(Modifier.height(12.dp))
                Text("Catálogos no disponibles en esta compilación.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (status.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(status, color = Muted, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(8.dp))
        }
        // Continuar viendo (series/películas a medias)
        val cont = WatchStore.continueWatching()
        if (cont.isNotEmpty()) item {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("Continuar viendo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(cont.size) { i ->
                        val p = cont[i]
                        ContinueCard(p) { onOpen(Tmdb.Title(p.tmdbId, p.name, p.name, "", p.poster, 0.0, p.type)) }
                    }
                }
            }
        }
        // Mi lista (favoritos sincronizados con la cuenta)
        if (Sync.favorites.isNotEmpty()) item {
            Column(Modifier.padding(vertical = 8.dp)) {
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
        }
        // Recomendado para ti
        if (recs.isNotEmpty()) item {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("Recomendado para ti", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recs.size) { i -> PosterCard(recs[i]) { onOpen(recs[i]) } }
                }
            }
        }
        items(rows.size) { idx ->
            val row = rows[idx]
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(row.items.size) { i -> PosterCard(row.items[i]) { onOpen(row.items[i]) } }
                    val prov = Tmdb.PLATFORMS.firstOrNull { it.name == row.name }?.providers
                    if (prov != null) item {
                        Box(
                            Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))
                                .background(Surface1).clickable { browse = prov to row.name },
                            contentAlignment = Alignment.Center
                        ) { Text("Ver más ›", color = Accent, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
fun BrowseScreen(provider: String, name: String, type: String, onOpen: (Tmdb.Title) -> Unit, onBack: () -> Unit) {
    val items = remember { mutableStateListOf<Tmdb.Title>() }
    var page by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var end by remember { mutableStateOf(false) }

    fun loadNext() {
        if (loading || end) return
        loading = true
        val next = page + 1
        // provider puede ser una plataforma ("8") o un género ("genre:28")
        val genreId = provider.substringAfter("genre:", "").toIntOrNull()
        val prov = if (genreId == null) provider else null
        Tmdb.discover(type, prov, genreId, next) { list, _ ->
            onMain {
                loading = false
                if (list.isNullOrEmpty()) end = true else { page = next; items.addAll(list) }
            }
        }
    }
    LaunchedEffect(provider, type) { items.clear(); page = 0; end = false; loadNext() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Volver") }
            Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items.forEach { t -> item { PosterCard(t, width = 110) { onOpen(t) } } }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (!end) Button(onClick = { loadNext() }, enabled = !loading) {
                        Text(if (loading) "Cargando…" else "Ver más")
                    } else Text("No hay más resultados", color = Muted, style = MaterialTheme.typography.bodySmall)
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

/**
 * Pestaña TV: AceStream a pantalla completa (canales en directo y eventos).
 */
@Composable
fun AceScreen(onPlayUrl: (String, PlayCtx) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        item {
            Text("TV en directo", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            IptvPanel(onPlayUrl)
            Spacer(Modifier.height(12.dp))
            AceStreamPanel(onPlayUrl)
        }
    }
}

/**
 * Reproductor IPTV genérico (M3U o Xtream Codes). El usuario aporta la fuente
 * (una lista o unas credenciales de su proveedor); TorrentBox solo reproduce,
 * directo en el reproductor, sin restricciones. No incluye ningún canal.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun IptvPanel(onPlayUrl: (String, PlayCtx) -> Unit) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf(0) } // 0 = M3U, 1 = Xtream
    var m3u by remember { mutableStateOf(Iptv.savedM3u(ctx)) }
    val savedXt = remember { Iptv.savedXtream(ctx) }
    var host by remember { mutableStateOf(savedXt?.host ?: "") }
    var user by remember { mutableStateOf(savedXt?.user ?: "") }
    var pass by remember { mutableStateOf(savedXt?.pass ?: "") }
    var status by remember { mutableStateOf("") }
    var channels by remember { mutableStateOf<List<Iptv.Channel>>(emptyList()) }
    var group by remember { mutableStateOf<String?>(null) }   // categoría seleccionada
    var filter by remember { mutableStateOf("") }

    fun onLoaded(list: List<Iptv.Channel>?, err: String?) = onMain {
        if (list == null) { status = err ?: "Error" }
        else { channels = list; group = null; status = "${list.size} canales" }
    }

    fun loadM3u() {
        if (m3u.isBlank()) { status = "Pon la URL de tu lista M3U"; return }
        Iptv.saveM3u(ctx, m3u); status = "Cargando lista…"
        Iptv.loadM3u(m3u) { l, e -> onLoaded(l, e) }
    }
    fun loadXtream() {
        if (host.isBlank() || user.isBlank()) { status = "Pon host, usuario y contraseña"; return }
        val x = Iptv.Xtream(host, user, pass)
        Iptv.saveXtream(ctx, x); status = "Conectando…"
        Iptv.loadXtream(x) { l, e -> onLoaded(l, e) }
    }

    // Carga automática al abrir si ya hay fuente guardada
    LaunchedEffect(Unit) {
        when {
            Iptv.savedXtream(ctx) != null -> loadXtream()
            Iptv.savedM3u(ctx).isNotBlank() -> loadM3u()
        }
    }

    val groups = remember(channels) {
        channels.map { it.group }.distinct()
            .sortedWith(compareBy({ it == "Otros" || it == "General" }, { it }))
    }
    val shown = channels.filter {
        (group == null || it.group == group) &&
            (filter.isBlank() || it.name.contains(filter, ignoreCase = true))
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text("📺 IPTV (tu lista M3U o Xtream)", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            if (expanded) {
                Text("Reproduce tu propia fuente IPTV (proveedor que pagues, lista pública…). No se incluye ningún canal.",
                    style = MaterialTheme.typography.labelSmall, color = Muted)
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(selected = mode == 0, onClick = { mode = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Lista M3U") }
                    SegmentedButton(selected = mode == 1, onClick = { mode = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Xtream Codes") }
                }
                if (mode == 0) {
                    OutlinedTextField(value = m3u, onValueChange = { m3u = it },
                        label = { Text("URL de la lista (.m3u / .m3u8)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { loadM3u() }, enabled = m3u.isNotBlank()) { Text("Cargar lista") }
                        OutlinedButton(onClick = { m3u = Iptv.ACE_PLAYLIST; loadM3u() }) { Text("Canales AceStream (search-ace.stream)") }
                    }
                    Text("Consejo: la lista de search-ace.stream son canales AceStream; al pulsar un canal se abre en la app de AceStream (gratis).",
                        style = MaterialTheme.typography.labelSmall, color = Muted)
                } else {
                    OutlinedTextField(value = host, onValueChange = { host = it },
                        label = { Text("Host (http://servidor:puerto)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Usuario") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Contraseña") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Button(onClick = { loadXtream() }, enabled = host.isNotBlank() && user.isNotBlank()) { Text("Conectar") }
                }
                if (channels.isNotEmpty()) {
                    TextButton(onClick = { Iptv.clear(ctx); channels = emptyList(); m3u = ""; host = ""; user = ""; pass = ""; status = "Fuente borrada" }) {
                        Text("Borrar fuente guardada")
                    }
                }
                if (status.isNotBlank()) Text(status, color = Muted, style = MaterialTheme.typography.bodySmall)

                if (channels.isNotEmpty()) {
                    OutlinedTextField(value = filter, onValueChange = { filter = it },
                        label = { Text("Filtrar canal…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    // Categorías
                    if (groups.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { FilterChip(selected = group == null, onClick = { group = null }, label = { Text("Todos") }) }
                            items(groups.size) { i ->
                                FilterChip(selected = group == groups[i], onClick = { group = groups[i] }, label = { Text(groups[i]) })
                            }
                        }
                    }
                    Text("${shown.size} canales", style = MaterialTheme.typography.labelSmall, color = Muted)
                    // Lista (limitada para no petar la UI; el filtro reduce)
                    shown.take(300).forEach { ch ->
                        val aceId = ch.aceId
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                // Canal AceStream -> se abre en la app de AceStream (gratis);
                                // canal IPTV normal -> se reproduce en TorrentBox.
                                if (aceId != null) {
                                    if (!AceStream.openExternal(ctx, aceId)) status = "Instala «Ace Stream Media» para ver este canal."
                                } else onPlayUrl(ch.url, PlayCtx(name = ch.name))
                            }.padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (ch.logo != null) AsyncImage(model = ch.logo, contentDescription = null,
                                modifier = Modifier.width(44.dp).height(44.dp).clip(RoundedCornerShape(6.dp)))
                            Column(Modifier.weight(1f)) {
                                Text(ch.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (group == null) Text(ch.group, style = MaterialTheme.typography.labelSmall, color = Muted)
                            }
                            Text(if (aceId != null) "📡" else "▶", color = Accent)
                        }
                    }
                    if (shown.size > 300) Text("Mostrando 300 de ${shown.size}. Usa el filtro o una categoría.", style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
        }
    }
}

/**
 * Panel de AceStream: busca en acestreamid.com (scraping), reproduce vía el
 * AceStream Engine sin salir de la app, permite pegar un enlace a mano y abre
 * la página del contenido como alternativa si el engine no resuelve.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AceStreamPanel(onPlayUrl: (String, PlayCtx) -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AceStream.Result>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf(1) }

    // Reproducir GRATIS: se lo pasamos a la app de AceStream (su propio reproductor).
    fun openInAce(contentId: String) {
        if (!AceStream.openExternal(ctx, contentId))
            status = "No hay ninguna app que abra enlaces acestream://. Instala «Ace Stream Media»."
    }

    // Reproducir DENTRO de TorrentBox: requiere AceStream Premium (el motor gratis
    // bloquea la reproducción en reproductores externos como el nuestro).
    fun playInApp(name: String, contentId: String) {
        status = "Comprobando AceStream Engine…"
        AceStream.ensureEngine(ctx, onStatus = { s -> onMain { status = s } }) { ok, err ->
            onMain {
                if (!ok) {
                    status = (err ?: "Engine no disponible") + " Usa «Abrir en AceStream» (gratis)."
                    return@onMain
                }
                status = "⚡ Resolviendo en el engine…"
                AceStream.resolve(contentId) { url, rerr ->
                    onMain {
                        if (url != null) { status = ""; onPlayUrl(url, PlayCtx(name = name)) }
                        else status = (rerr ?: "Reproducción integrada no disponible (requiere Premium).") + " Usa «Abrir en AceStream» (gratis)."
                    }
                }
            }
        }
    }

    // Con query vacía lista TODO el directorio; p es la "hoja" (página) pedida
    fun load(p: Int) {
        status = if (query.isBlank()) "Cargando directorio (página $p)…" else "Buscando en AceStream…"
        AceStream.search(query.trim(), p) { list, err ->
            onMain {
                when {
                    list == null -> status = err ?: "Error"
                    list.isEmpty() -> status = err ?: "Sin resultados"
                    else -> { results = list; page = p; status = "${list.size} canales/eventos · página $p" }
                }
            }
        }
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text("📡 AceStream (canales y eventos)", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            if (expanded) {
                // Canales favoritos (acceso rápido)
                if (Prefs.aceFavs.isNotEmpty()) {
                    Text("⭐ Favoritos", style = MaterialTheme.typography.labelMedium, color = Muted)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(Prefs.aceFavs.size) { i ->
                            val parts = Prefs.aceFavs[i].split("|", limit = 2)
                            val fid = parts[0]; val fname = parts.getOrElse(1) { "Canal" }
                            AssistChip(onClick = { openInAce(fid) }, label = { Text("▶ $fname") })
                        }
                    }
                }
                Text("Al pulsar ▶ se abre en la app de AceStream (gratis). La reproducción dentro de TorrentBox requiere AceStream Premium.",
                    style = MaterialTheme.typography.labelSmall, color = Muted)
                if (!AceStream.engineInstalled(ctx)) {
                    Text("Requiere la app «Ace Stream Media» (gratis) — esa app YA incluye el engine. La reproducción es P2P dentro de tu app.",
                        style = MaterialTheme.typography.labelSmall, color = Muted)
                    OutlinedButton(onClick = { AceStream.openEngineInstall(ctx) }) { Text("Instalar Ace Stream Media") }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Buscar canal / evento… (vacío = ver todos)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { results = emptyList(); load(1) }) {
                    Text(if (query.isBlank()) "Ver todos los enlaces" else "Buscar en AceStream")
                }

                // Enlace / content-id manual
                OutlinedTextField(
                    value = manual, onValueChange = { manual = it },
                    label = { Text("O pega un enlace acestream:// o content-id") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = AceStream.extractContentId(manual) != null,
                        onClick = {
                            val id = AceStream.extractContentId(manual)
                            if (id != null) openInAce(id) else status = "Enlace no válido."
                        }
                    ) { Text("▶ Reproducir enlace") }
                    AceStream.extractContentId(manual)?.let { id ->
                        OutlinedButton(onClick = { playInApp("AceStream", id) }) { Text("Aquí (Premium)") }
                        OutlinedButton(onClick = { AceStream.openPage(ctx, id) }) { Text("Abrir página") }
                    }
                }

                if (status.isNotBlank()) Text(status, color = Muted, style = MaterialTheme.typography.bodySmall)

                results.forEach { r ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Bg)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                IconButton(onClick = { Prefs.toggleAceFav(r.contentId, r.name) }) {
                                    Icon(
                                        if (Prefs.isAceFav(r.contentId)) Icons.Filled.Star else Icons.Filled.StarBorder,
                                        contentDescription = "Favorito",
                                        tint = if (Prefs.isAceFav(r.contentId)) Color(0xFFFBBF24) else Muted
                                    )
                                }
                            }
                            val votes = if (r.likes >= 0 || r.dislikes >= 0)
                                "👍 ${r.likes.coerceAtLeast(0)} · 👎 ${r.dislikes.coerceAtLeast(0)} · " else ""
                            Text(votes + r.contentId.take(12) + "…", style = MaterialTheme.typography.labelSmall, color = Muted)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { openInAce(r.contentId) }) { Text("▶ Reproducir") }
                                OutlinedButton(onClick = { playInApp(r.name, r.contentId) }) { Text("Aquí (Premium)") }
                                OutlinedButton(onClick = { AceStream.openPage(ctx, r.pageUrl) }) { Text("Página") }
                            }
                        }
                    }
                }
                // Hojas (paginación) del directorio / búsqueda
                if (results.isNotEmpty() || page > 1) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { load(page - 1) }, enabled = page > 1) { Text("◀ Anterior") }
                        Text("Página $page", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Muted)
                        OutlinedButton(onClick = { load(page + 1) }, enabled = results.isNotEmpty()) { Text("Siguiente ▶") }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        Sync.onSignInResult(res.data) { _, _ -> }
    }
    var rdInput by remember { mutableStateOf("") }
    var rdStatus by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Ajustes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // --- Cuenta (Google / sincronización) + selección de perfil ---
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cuenta", fontWeight = FontWeight.Bold)
                if (!Sync.enabled) {
                    Text("El login con Google no está disponible en esta compilación.", color = Muted, style = MaterialTheme.typography.bodySmall)
                } else if (Sync.email == null) {
                    Text("Inicia sesión con tu cuenta de Google para ver tus perfiles, favoritos y ajustes de la app del PC.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { Sync.signInIntent()?.let { launcher.launch(it) } }) { Text("Entrar con Google") }
                } else {
                    Text("👤 ${Sync.email}", style = MaterialTheme.typography.bodyMedium)
                    if (Sync.loading) Text("Sincronizando…", color = Muted, style = MaterialTheme.typography.labelSmall)
                    if (Sync.profiles.isNotEmpty()) {
                        Text("Perfil", style = MaterialTheme.typography.labelMedium, color = Muted)
                        FlowRowSimple {
                            Sync.profiles.forEach { p ->
                                val active = Sync.activeProfile?.id == p.id
                                FilterChip(
                                    selected = active,
                                    onClick = { Sync.selectProfile(p.id) },
                                    label = { Text("${p.avatar} ${p.name}${if (p.kids) " 🧒" else ""}") }
                                )
                            }
                        }
                    } else if (!Sync.loading) {
                        Text("No hay perfiles en la nube todavía. Crea uno en la app del PC.", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onClick = { Sync.signOut() }) { Text("Cerrar sesión") }
                }
            }
        }

        // --- Idiomas (filtro y orden de preferencia) ---
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Idiomas", fontWeight = FontWeight.Bold)
                Text("Orden de preferencia: al buscar, las fuentes salen primero en el idioma de arriba.", color = Muted, style = MaterialTheme.typography.bodySmall)
                val order = Prefs.languageOrder
                order.forEachIndexed { i, code ->
                    val info = Lang.byCode(code)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${info?.flag ?: "🏳️"}  ${info?.label ?: code}", modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            if (i > 0) { val l = order.toMutableList(); l.add(i - 1, l.removeAt(i)); Prefs.setLanguageOrder(l) }
                        }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Subir") }
                        IconButton(onClick = {
                            if (i < order.size - 1) { val l = order.toMutableList(); l.add(i + 1, l.removeAt(i)); Prefs.setLanguageOrder(l) }
                        }, enabled = i < order.size - 1) { Icon(Icons.Filled.KeyboardArrowDown, "Bajar") }
                        IconButton(onClick = {
                            if (order.size > 1) Prefs.setLanguageOrder(order.filter { it != code })
                        }, enabled = order.size > 1) { Icon(Icons.Filled.Close, "Quitar") }
                    }
                }
                val notAdded = Lang.ALL.filter { it.code !in order }
                if (notAdded.isNotEmpty()) {
                    Text("Añadir:", style = MaterialTheme.typography.labelMedium, color = Muted)
                    FlowRowSimple {
                        notAdded.forEach { info ->
                            AssistChip(onClick = { Prefs.setLanguageOrder(order + info.code) },
                                label = { Text("${info.flag} ${info.label}") })
                        }
                    }
                }
            }
        }

        // --- Carpetas de descarga ---
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Carpetas", fontWeight = FontWeight.Bold)
                var folderStatus by remember { mutableStateOf("") }
                // Selector de carpeta del sistema (SAF). Convertimos el árbol elegido
                // a una ruta real; si Android no deja escribir ahí sin permisos, avisamos.
                val pickDownload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                    val path = Prefs.resolveTreeUri(uri)
                    if (path != null) { Prefs.setDownloadDir(path); folderStatus = "Descargas → ${Prefs.shortLabel(path)}" }
                    else folderStatus = "Android no permite escribir en esa carpeta sin permisos especiales. Elige otra o usa las de la app."
                }
                val pickBuffer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                    val path = Prefs.resolveTreeUri(uri)
                    if (path != null) { Prefs.setBufferDir(path); folderStatus = "Buffer → ${Prefs.shortLabel(path)}" }
                    else folderStatus = "Android no permite escribir en esa carpeta sin permisos especiales. Elige otra o usa las de la app."
                }

                val vols = remember { Prefs.availableVolumes() }
                fun volLabel(i: Int) = if (i == 0) "Memoria interna (app)" else "Tarjeta SD / externa (app)"

                Text("Descargas (permanente):", style = MaterialTheme.typography.labelMedium)
                Text("Actual: ${Prefs.shortLabel(Prefs.downloadDir.value)}", style = MaterialTheme.typography.labelSmall, color = Muted)
                vols.forEachIndexed { i, f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = Prefs.downloadDir.value == f.absolutePath, onClick = { Prefs.setDownloadDir(f.absolutePath); folderStatus = "" })
                        Text(volLabel(i), style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(onClick = { pickDownload.launch(null) }) { Text("Elegir otra carpeta…") }

                Spacer(Modifier.height(4.dp))
                Text("Buffer (al pulsar “Ver”):", style = MaterialTheme.typography.labelMedium)
                Text("Actual: ${Prefs.shortLabel(Prefs.bufferDir.value)}", style = MaterialTheme.typography.labelSmall, color = Muted)
                vols.forEachIndexed { i, f ->
                    val bf = File(f.parentFile, "buffer")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = Prefs.bufferDir.value == bf.absolutePath, onClick = { Prefs.setBufferDir(bf.absolutePath); folderStatus = "" })
                        Text(volLabel(i), style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(onClick = { pickBuffer.launch(null) }) { Text("Elegir otra carpeta…") }

                if (folderStatus.isNotBlank()) Text(folderStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
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
                    Text("Pega tu token para reproducir por streaming directo (sin descargar en el móvil). Se comparte con tu cuenta si has entrado con Google.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = rdInput, onValueChange = { rdInput = it }, label = { Text("Token de Real-Debrid") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        rdStatus = "Validando…"
                        val tk = rdInput.trim()
                        RealDebrid.connect(tk) { ok, msg ->
                            onMain {
                                rdStatus = if (ok) "Conectado como $msg" else (msg ?: "Error")
                                if (ok && Sync.email != null) Sync.saveAccountRdToken(tk)
                            }
                        }
                    }, enabled = rdInput.isNotBlank()) { Text("Conectar") }
                    Text("Consíguelo en real-debrid.com/apitoken", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                if (rdStatus.isNotBlank()) Text(rdStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        // --- Velocidad y buffer ---
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Velocidad y buffer", fontWeight = FontWeight.Bold)
                var down by remember { mutableStateOf(Prefs.downLimitKB.value.takeIf { it > 0 }?.toString() ?: "") }
                var up by remember { mutableStateOf(Prefs.upLimitKB.value.takeIf { it > 0 }?.toString() ?: "") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = down, onValueChange = { down = it.filter { c -> c.isDigit() } },
                        label = { Text("Bajada KB/s (vacío = ∞)") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = up, onValueChange = { up = it.filter { c -> c.isDigit() } },
                        label = { Text("Subida KB/s") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Button(onClick = { Prefs.setLimits(down.toIntOrNull() ?: 0, up.toIntOrNull() ?: 0) }) { Text("Aplicar límites") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = Prefs.autoCleanBuffer.value, onCheckedChange = { Prefs.setAutoCleanBuffer(it) })
                    Text("Vaciar el buffer al abrir la app", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // --- Actualizaciones ---
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Actualizaciones", fontWeight = FontWeight.Bold)
                Text("Versión instalada: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.CI_BUILD})",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                val up = Update.available
                if (up != null) {
                    Text("⬆️ Hay una versión nueva: build ${up.build}", color = Color(0xFF34D399), fontWeight = FontWeight.Bold)
                    Button(onClick = { Update.downloadAndInstall(ctx) }) { Text("Descargar e instalar") }
                } else {
                    Text(if (Update.checked) "Estás en la última versión." else "…",
                        style = MaterialTheme.typography.bodySmall, color = Muted)
                    OutlinedButton(onClick = { Update.check() }) { Text("Buscar actualización") }
                }
                if (Update.status.isNotBlank()) Text(Update.status, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        // --- Salir ---
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Aplicación", fontWeight = FontWeight.Bold)
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF87171)),
                    onClick = {
                        runCatching { TorrentEngine.stop() }
                        runCatching { StreamServer.stop() }
                        runCatching { ctx.stopService(Intent(ctx, DownloadService::class.java)) }
                        (ctx as? android.app.Activity)?.finishAffinity()
                        kotlin.system.exitProcess(0)
                    }
                ) { Text("Salir y cerrar la app") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// FlowRow simple (evita depender de la API experimental en algunos sitios)
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowSimple(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
fun DownloadsScreen(
    downloads: List<TorrentEngine.Snapshot>,
    rdDownloads: List<RdDownloads.Snap>,
    onPlay: (String) -> Unit,
    onPlayUrl: (String) -> Unit
) {
    val ctx = LocalContext.current
    // Separa la ACTIVIDAD (descargando) de lo que ya está LISTO PARA VER
    val torrentsReady = downloads.filter { it.progress >= 0.999f }
    val torrentsActive = downloads.filter { it.progress < 0.999f }
    val rdReady = rdDownloads.filter { it.done }
    val rdActive = rdDownloads.filter { !it.done }
    val nothing = downloads.isEmpty() && rdDownloads.isEmpty()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        item {
            Text("Descargas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            // Espacio libre y tamaño del buffer (se recalcula al cambiar las descargas)
            var space by remember { mutableStateOf("") }
            LaunchedEffect(downloads.size, downloads.sumOf { (it.progress * 100).toInt() } / 25) {
                space = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val free = Prefs.downloadDirFile().usableSpace
                        val buf = Prefs.bufferDirFile().walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        "Libre: ${Search.humanSize(free)} · Buffer: ${Search.humanSize(buf)}"
                    }.getOrDefault("")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(space, style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    Thread { runCatching { TorrentEngine.clearDir(Prefs.bufferDirFile()) } }.start()
                }) { Text("Vaciar buffer") }
            }
            if (nothing) Text("Aún no hay descargas. Abre un título y pulsa Ver o Descargar.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }

        // --- Listas para ver ---
        if (torrentsReady.isNotEmpty() || rdReady.isNotEmpty()) {
            item { Text("▶ Listas para ver", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF34D399), modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
            items(rdReady.size) { i -> RdDownloadCard(rdReady[i], onPlayUrl = { onPlayUrl(RdDownloads.playUri(ctx, rdReady[i].id) ?: rdReady[i].localUri ?: "") }, onRemove = { RdDownloads.remove(ctx, rdReady[i].id) }) }
            items(torrentsReady.size) { i -> DownloadCard(torrentsReady[i], onPlay) }
        }

        // --- Descargando (actividad) ---
        if (torrentsActive.isNotEmpty() || rdActive.isNotEmpty()) {
            item { Text("⏳ Descargando", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)) }
            items(rdActive.size) { i -> RdDownloadCard(rdActive[i], onPlayUrl = { onPlayUrl(RdDownloads.playUri(ctx, rdActive[i].id) ?: rdActive[i].localUri ?: "") }, onRemove = { RdDownloads.remove(ctx, rdActive[i].id) }) }
            items(torrentsActive.size) { i -> DownloadCard(torrentsActive[i], onPlay) }
        }
    }
}

@Composable
fun RdDownloadCard(d: RdDownloads.Snap, onPlayUrl: () -> Unit, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = Surface1)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(d.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val known = d.total > 0
            val prog = if (known) (d.bytes.toFloat() / d.total).coerceIn(0f, 1f) else 0f
            // Barra indeterminada mientras no se conoce el tamaño (arrancando)
            if (d.done || known) LinearProgressIndicator(progress = { if (d.done) 1f else prog }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                when {
                    d.failed -> "Error en la descarga"
                    d.done -> "✓ Disponible sin conexión · ${Search.humanSize(if (known) d.total else d.bytes)}"
                    known -> "${(prog * 100).toInt()}% · ${Search.humanSize(d.bytes)} / ${Search.humanSize(d.total)}"
                    d.bytes > 0 -> "Descargando… ${Search.humanSize(d.bytes)}"
                    else -> "En cola…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (d.done) Color(0xFF34D399) else Muted
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.done) Button(onClick = onPlayUrl) { Text("▶ Ver") }
                OutlinedButton(onClick = onRemove) { Text("Borrar") }
            }
        }
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

// Lista de enlaces (fuentes) reutilizable: se muestra bajo un episodio, bajo
// el botón de temporada completa, o (en películas) bajo "Buscar fuentes".
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourcesSection(
    sources: List<Search.Result>,
    loading: Boolean,
    label: String,
    title: Tmdb.Title,
    ctx: android.content.Context,
    buildCtx: () -> PlayCtx,
    onWatch: (String, PlayCtx) -> Unit,
    onDownload: (String) -> Unit,
    onPlayUrl: (String, PlayCtx) -> Unit,
    onOpenDownloads: () -> Unit
) {
    var linksExpanded by remember { mutableStateOf(true) }
    var rdStatus by remember { mutableStateOf("") }

    Column(Modifier.padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (loading) Text("Buscando fuentes…", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (sources.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().clickable { linksExpanded = !linksExpanded }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Enlaces (${sources.size})" + if (label.isNotBlank()) " · $label" else "",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                Icon(if (linksExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (linksExpanded) "Plegar" else "Desplegar")
            }
        }
        if (linksExpanded) sources.forEach { r ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(r.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${Lang.flag(r.lang)} ${Lang.label(r.lang)}  ·  ▲ ${r.seeders} seeders · ${Search.humanSize(r.sizeBytes)}", style = MaterialTheme.typography.labelSmall, color = Muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onWatch(r.magnet, buildCtx()) }) { Text("▶ Ver") }
                        OutlinedButton(onClick = { onDownload(r.magnet) }) { Text("⬇ Descargar") }
                        if (RealDebrid.configured) {
                            Button(
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34D399)),
                                onClick = {
                                    rdStatus = "⚡ Preparando en Real-Debrid…"
                                    RealDebrid.streamMagnet(r.magnet) { url, _, err, progress ->
                                        onMain {
                                            when {
                                                url != null -> { rdStatus = ""; onPlayUrl(url, buildCtx()) }
                                                progress != null -> rdStatus = "Real-Debrid preparando… ${progress}% (reintenta en un momento)"
                                                else -> rdStatus = err ?: "Error de Real-Debrid"
                                            }
                                        }
                                    }
                                }
                            ) { Text("⚡ Ver RD") }
                            OutlinedButton(onClick = {
                                rdStatus = "⚡ Preparando descarga con Real-Debrid…"
                                RealDebrid.streamMagnet(r.magnet) { url, fname, err, progress ->
                                    onMain {
                                        when {
                                            url != null -> {
                                                RdDownloads.enqueue(ctx, url, fname ?: title.title)
                                                onOpenDownloads()
                                            }
                                            progress != null -> rdStatus = "Real-Debrid preparando… ${progress}% (reintenta en un momento)"
                                            else -> rdStatus = err ?: "Error de Real-Debrid"
                                        }
                                    }
                                }
                            }) { Text("⚡ Descargar RD") }
                        }
                    }
                }
            }
        }
        if (rdStatus.isNotBlank()) Text(rdStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(title: Tmdb.Title, onBack: () -> Unit, onWatch: (String, PlayCtx) -> Unit, onDownload: (String) -> Unit, onPlayUrl: (String, PlayCtx) -> Unit, onOpenDownloads: () -> Unit) {
    val ctx = LocalContext.current
    var detail by remember { mutableStateOf<Tmdb.Detail?>(null) }
    var sources by remember { mutableStateOf<List<Search.Result>>(emptyList()) }
    var status by remember { mutableStateOf("Cargando…") }
    var loadingSources by remember { mutableStateOf(false) }

    // Series: temporada/episodio seleccionados y lista de episodios
    var selSeason by remember { mutableStateOf<Int?>(null) }
    var episodes by remember { mutableStateOf<List<Tmdb.Episode>>(emptyList()) }
    var sourcesLabel by remember { mutableStateOf("") }
    var imdbId by remember { mutableStateOf<String?>(null) }
    var trailerKey by remember { mutableStateOf<String?>(null) }
    // temporada/episodio a los que corresponden las fuentes mostradas
    var ctxSeason by remember { mutableStateOf(-1) }
    var ctxEpisode by remember { mutableStateOf(-1) }
    // qué bloque muestra sus enlaces: -1 nada, 0 temporada completa, >0 ese episodio
    var expandedEpisode by remember { mutableStateOf(-1) }

    LaunchedEffect(title.tmdbId) {
        Tmdb.detail(title.type, title.tmdbId) { d, _ ->
            onMain {
                detail = d; status = ""
                if (d != null && d.type == "series" && d.seasons.isNotEmpty()) selSeason = d.seasons.first().season
            }
        }
        Tmdb.imdbId(title.type, title.tmdbId) { id -> onMain { imdbId = id } }
        Tmdb.trailer(title.type, title.tmdbId) { k -> onMain { trailerKey = k } }
    }

    fun openTrailer(key: String) {
        // Abre la app de YouTube; si no está, el navegador
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$key"))) }
            .onFailure { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key"))) } }
    }

    // Al cambiar de temporada, carga sus episodios
    LaunchedEffect(selSeason) {
        val s = selSeason ?: return@LaunchedEffect
        episodes = emptyList()
        Tmdb.episodes(title.tmdbId, s) { list, _ -> onMain { episodes = list ?: emptyList() } }
    }

    // Busca fuentes en Torrentio (banderas de idioma) + Peerflix (apibay) a la
    // vez, combina, deduplica por infoHash (gana más seeders) y ordena por el
    // idioma preferido. La combinación se hace en el hilo principal (onMain).
    fun runSearch(query: String, label: String, season: Int? = null, episode: Int? = null) {
        loadingSources = true; sources = emptyList(); sourcesLabel = label
        ctxSeason = season ?: -1; ctxEpisode = episode ?: -1
        val id = imdbId
        val useTorrentio = id != null && (title.type == "movie" || episode != null)
        val acc = mutableListOf<Search.Result>()
        var remaining = (if (useTorrentio) 1 else 0) + 1 // +1 = Peerflix (apibay)
        var lastErr: String? = null
        fun part(list: List<Search.Result>?, err: String?) = onMain {
            if (list != null) acc.addAll(list) else lastErr = err
            if (--remaining <= 0) {
                val byHash = LinkedHashMap<String, Search.Result>()
                for (r in acc) {
                    val prev = byHash[r.infoHash]
                    if (prev == null || r.seeders > prev.seeders) byHash[r.infoHash] = r
                }
                loadingSources = false
                sources = Search.sortByLang(byHash.values.toList(), Prefs.languageOrder)
                if (sources.isEmpty()) status = lastErr ?: "Sin fuentes"
            }
        }
        if (useTorrentio) Torrentio.streams(title.type, id!!, season, episode) { l, e -> part(l, e) }
        Search.search(query) { l, e -> part(l, e) }
    }
    fun loadSources(dt: Tmdb.Detail) = runSearch(dt.originalTitle, dt.title)

    // Contexto para el reproductor (marcar visto + reanudar) según lo buscado
    fun buildCtx(): PlayCtx {
        val s = ctxSeason.takeIf { it > 0 }
        val e = ctxEpisode.takeIf { it > 0 }
        val key = if (title.type == "series" && s != null) "series:${title.tmdbId}:$s:${e ?: 1}" else "movie:${title.tmdbId}"
        val resumeMs = WatchStore.progressFor(key)?.let { if (!it.watched) (it.position * 1000).toLong() else 0L } ?: 0L
        return PlayCtx(title.tmdbId, title.type, s ?: -1, e ?: -1, title.title, title.poster, resumeMs)
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

            // Tráiler + favorito
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                trailerKey?.let { k ->
                    Button(colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF87171)),
                        onClick = { openTrailer(k) }) { Text("🎬 Tráiler") }
                }
                if (Sync.enabled && Sync.email != null) {
                    val fid = "tmdb:${title.tmdbId}"
                    OutlinedButton(onClick = { Sync.toggleFavorite(title) }) {
                        Text(if (Sync.isFav(fid)) "❤ En Mi lista" else "🤍 Añadir a Mi lista")
                    }
                }
            }

            // --- Fuentes: película, o serie organizada por temporadas/episodios ---
            if (dt != null && dt.type == "series" && dt.seasons.isNotEmpty()) {
                Text("Temporadas", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dt.seasons.forEach { s ->
                        FilterChip(selected = selSeason == s.season, onClick = { selSeason = s.season; expandedEpisode = -1 },
                            label = { Text("T${s.season} · ${s.episodes} ep.") })
                    }
                }
                selSeason?.let { sn ->
                    OutlinedButton(
                        onClick = {
                            expandedEpisode = 0
                            runSearch("${dt.originalTitle} " + "S%02d".format(sn), "${dt.title} · Temporada $sn completa", sn, null)
                        },
                        enabled = !loadingSources, modifier = Modifier.fillMaxWidth()
                    ) { Text("Buscar temporada $sn completa") }
                    if (expandedEpisode == 0) {
                        SourcesSection(sources, loadingSources, sourcesLabel, title, ctx, { buildCtx() }, onWatch, onDownload, onPlayUrl, onOpenDownloads)
                    }
                    episodes.forEach { ep ->
                        Card(
                            Modifier.fillMaxWidth().clickable {
                                expandedEpisode = ep.episode
                                runSearch(Search.episodeQuery(dt.originalTitle, sn, ep.episode), "${dt.title} · T${sn}E${ep.episode} · ${ep.name}", sn, ep.episode)
                            },
                            colors = CardDefaults.cardColors(containerColor = Surface1)
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                val seen = WatchStore.isWatchedEpisode(title.tmdbId, sn, ep.episode)
                                Text(
                                    (if (seen) "✓ " else "") + "${ep.episode}. ${ep.name}" + (if (expandedEpisode == ep.episode) "  ▾" else ""),
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (seen) Color(0xFF34D399) else MaterialTheme.colorScheme.onSurface
                                )
                                if (ep.overview.isNotBlank()) Text(ep.overview, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        // Enlaces JUSTO debajo del episodio seleccionado
                        if (expandedEpisode == ep.episode) {
                            SourcesSection(sources, loadingSources, sourcesLabel, title, ctx, { buildCtx() }, onWatch, onDownload, onPlayUrl, onOpenDownloads)
                        }
                    }
                }
            } else {
                Button(onClick = { expandedEpisode = -1; dt?.let { loadSources(it) } }, enabled = dt != null && !loadingSources, modifier = Modifier.fillMaxWidth()) {
                    Text(if (loadingSources) "Buscando fuentes…" else "Buscar fuentes")
                }
                SourcesSection(sources, loadingSources, sourcesLabel, title, ctx, { buildCtx() }, onWatch, onDownload, onPlayUrl, onOpenDownloads)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
