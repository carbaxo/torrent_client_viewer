package com.carbaxo.torrentbox

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage

private val mainHandler = Handler(Looper.getMainLooper())
private fun onMain(block: () -> Unit) = mainHandler.post(block)
private fun onMainDelayed(ms: Long, block: () -> Unit) = mainHandler.postDelayed(block, ms)

/** Estado de la ventana flotante mientras Real-Debrid prepara un enlace. */
data class Prep(val download: Boolean, val msg: String, val error: String? = null)

// Paleta al estilo de la web (morado Stremio)
private val Accent = Color(0xFF7B5BF5)
private val Bg = Color(0xFF0C0B11)
private val Surface1 = Color(0xFF15141D)
private val Muted = Color(0xFF8F8BA1)

// AppCompatActivity: el diálogo "emitir a…" de Chromecast lo exige
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Prefs.init(this)
        WatchStore.init(this)
        RealDebrid.init(this)
        // Chromecast: sesión global, se elige la TV antes de abrir nada
        CastManager.init(this)
        Update.check()
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
                        onPlayUrl = { url, c -> startActivity(playerIntent(c).putExtra("url", url)) },
                        // "Ver" con TV conectada: el reproductor no se abre, se
                        // resuelve el enlace y se manda a la TV desde el momento
                        // en que se pulsa (con su estado en pantalla).
                        onCastMagnet = { magnet, c -> CastManager.castMagnet(magnet, c) }
                    )
                }
            }
        }
    }

    private fun playerIntent(c: PlayCtx) = Intent(this, PlayerActivity::class.java).apply {
        putExtra("tmdbId", c.tmdbId); putExtra("type", c.type)
        putExtra("season", c.season); putExtra("episode", c.episode)
        putExtra("name", c.name); putExtra("poster", c.poster); putExtra("resumeMs", c.resumeMs)
        putExtra("engine", c.engine); putExtra("quality", c.quality)
        putExtra("lang", c.lang); putExtra("query", c.query)
    }
}

/** Contexto del título que se está reproduciendo (para marcar visto / reanudar). */
data class PlayCtx(
    val tmdbId: Int = -1, val type: String = "movie",
    val season: Int = -1, val episode: Int = -1,
    val name: String = "", val poster: String? = null, val resumeMs: Long = 0L,
    // Con que se esta viendo, para que el SIGUIENTE EPISODIO use lo mismo
    // (motor, calidad e idioma), como hace Stremio.
    val engine: String = "", val quality: String = "", val lang: String? = null,
    /** Titulo original: hace falta para buscar por texto en Peerflix. */
    val query: String = ""
) {
    /** Anota de qué enlace viene la reproducción (motor, calidad, idioma). */
    fun withSource(r: Search.Result) = copy(engine = r.engine, quality = r.quality, lang = r.lang)
}

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    DISCOVER("Descubrir", Icons.Filled.Explore),
    SEARCH("Buscar", Icons.Filled.Search),
    DOWNLOADS("Descargas", Icons.Filled.Download),
    SETTINGS("Ajustes", Icons.Filled.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(onPlayUrl: (String, PlayCtx) -> Unit, onCastMagnet: (String, PlayCtx) -> Unit) {
    var tab by remember { mutableStateOf(Tab.DISCOVER) }
    var detail by remember { mutableStateOf<Tmdb.Title?>(null) }
    var catalogType by remember { mutableStateOf("movie") }
    var showCastScreen by remember { mutableStateOf(false) }
    // "Preguntar cada vez" con qué reproductor abrir, y errores al lanzarlo
    var askPlayer by remember { mutableStateOf<Pair<String, PlayCtx>?>(null) }
    var playerError by remember { mutableStateOf<String?>(null) }
    val rdDownloads = remember { mutableStateListOf<RdDownloads.Snap>() }
    val ctx = LocalContext.current

    // Si Play Services no estaba listo al arrancar, se reintenta al pintar
    LaunchedEffect(Unit) { CastManager.init(ctx) }

    // Con TV conectada todo va a la TV; si no, al reproductor del móvil
    fun openExternal(url: String, c: PlayCtx) {
        val err = ExternalPlayer.open(ctx, url, c.name, c.resumeMs)
        if (err != null) playerError = err else detail = null
    }

    fun play(url: String, c: PlayCtx) {
        when {
            CastManager.connected -> { CastManager.castUrl(url, c); showCastScreen = true; detail = null }
            // Reproductor externo (VLC, MX Player…) según Ajustes → Reproducción
            Prefs.playerMode == Prefs.PLAYER_EXTERNAL -> openExternal(url, c)
            Prefs.playerMode == Prefs.PLAYER_ASK -> askPlayer = url to c
            else -> onPlayUrl(url, c)
        }
    }
    fun cast(magnet: String, c: PlayCtx) {
        onCastMagnet(magnet, c); showCastScreen = true; detail = null
    }

    // Modo infantil: el perfil activo marca kids. Oculta Buscar (búsqueda libre);
    // los catálogos se filtran a géneros familiares.
    val kids = Sync.activeProfile?.kids == true
    val visibleTabs = if (kids) listOf(Tab.DISCOVER, Tab.DOWNLOADS, Tab.SETTINGS) else Tab.values().toList()
    LaunchedEffect(kids) { if (kids && tab !in visibleTabs) tab = Tab.DISCOVER }

    // Refresco del progreso de las descargas del DownloadManager. La consulta
    // es bloqueante, así que va en IO; el estado se actualiza al volver al hilo
    // principal (fin de withContext).
    LaunchedEffect(Unit) {
        while (true) {
            val rd = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { RdDownloads.snapshots(ctx) } catch (_: Throwable) { emptyList() }
            }
            rdDownloads.clear(); rdDownloads.addAll(rd)
            kotlinx.coroutines.delay(1000)
        }
    }

    // Avisos de episodios nuevos cuando llegan los favoritos de la nube
    LaunchedEffect(Sync.favorites.size) { if (Sync.favorites.isNotEmpty()) EpisodeAlerts.check(ctx) }

    // --- ¿Con qué reproductor? (modo "preguntar cada vez") ---
    askPlayer?.let { (url, c) ->
        AlertDialog(
            onDismissRequest = { askPlayer = null },
            title = { Text("¿Con qué lo abrimos?") },
            text = { Text("Otra app (VLC, MX Player…) suele manejar mejor los MKV con audio DTS o TrueHD.") },
            confirmButton = {
                TextButton(onClick = { askPlayer = null; onPlayUrl(url, c) }) { Text("En la app") }
            },
            dismissButton = {
                TextButton(onClick = { askPlayer = null; openExternal(url, c) }) { Text("Otra app…") }
            }
        )
    }
    playerError?.let { msg ->
        AlertDialog(
            onDismissRequest = { playerError = null },
            title = { Text("Reproductor externo") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { playerError = null }) { Text("Cerrar") } }
        )
    }

    // Mando de la TV a pantalla completa (mientras se emite)
    if (showCastScreen && CastManager.connected) {
        CastScreen(onClose = { showCastScreen = false })
        return
    }

    // Ficha de detalle a pantalla completa
    val d = detail
    if (d != null) {
        DetailScreen(
            title = d,
            onBack = { detail = null },
            onPlayUrl = { url, c -> play(url, c) },
            onCastMagnet = { magnet, c -> cast(magnet, c) },
            onOpenDownloads = { tab = Tab.DOWNLOADS; detail = null }
        )
        return
    }

    Scaffold(
        containerColor = Bg,
        // Botón de Chromecast siempre visible: se elige la TV ANTES de abrir
        // ningún título, igual que en HBO o Netflix.
        topBar = {
            Row(
                Modifier.fillMaxWidth().background(Bg).padding(start = 14.dp, end = 6.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "TorrentBox", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = Accent, modifier = Modifier.weight(1f)
                )
                if (CastManager.connected) {
                    Text(
                        CastManager.deviceName ?: "TV", color = Color(0xFF34D399),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp)
                    )
                }
                CastIconButton()
            }
        },
        bottomBar = {
            Column {
                // Barra "emitiendo": abre el mando de la TV
                if (CastManager.connected && CastManager.title.isNotBlank()) {
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFF1B2A25))
                            .clickable { showCastScreen = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Cast, contentDescription = null, tint = Color(0xFF34D399))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                CastManager.title, style = MaterialTheme.typography.labelLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                CastManager.status.ifBlank { "Emitiendo en ${CastManager.deviceName ?: "la TV"}" },
                                style = MaterialTheme.typography.labelSmall, color = Muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text("Abrir ›", color = Accent, style = MaterialTheme.typography.labelMedium)
                    }
                }
                NavigationBar(containerColor = Surface1) {
                    visibleTabs.forEach { t ->
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
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                Tab.DISCOVER -> DiscoverScreen(catalogType, { catalogType = it }, kids = kids, onOpen = { detail = it })
                Tab.SEARCH -> if (kids) DiscoverScreen(catalogType, { catalogType = it }, kids = true, onOpen = { detail = it }) else SearchScreen(onOpen = { detail = it })
                Tab.DOWNLOADS -> DownloadsScreen(rdDownloads) { u -> play(u, PlayCtx()) }
                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

/** Botón nativo de Chromecast (abre el diálogo "emitir a…" del sistema). */
@Composable
fun CastIconButton() {
    AndroidView(
        modifier = Modifier.size(44.dp),
        factory = { c ->
            // Tema propio para que el icono salga blanco sobre el fondo oscuro
            val themed = androidx.appcompat.view.ContextThemeWrapper(c, R.style.Theme_TorrentBox_CastButton)
            androidx.mediarouter.app.MediaRouteButton(themed).apply {
                runCatching {
                    com.google.android.gms.cast.framework.CastButtonFactory
                        .setUpMediaRouteButton(c.applicationContext, this)
                }
            }
        }
    )
}

/**
 * Mando de la TV: qué se está emitiendo, con play/pausa, saltos y barra de
 * progreso. Se puede volver a la app y elegir otra película sin desconectar.
 */
@Composable
fun CastScreen(onClose: () -> Unit) {
    val cp = CastManager.player
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        var ticks = 0
        while (true) {
            pos = runCatching { cp?.currentPosition ?: 0L }.getOrDefault(0L)
            dur = runCatching { cp?.duration ?: 0L }.getOrDefault(0L)
            playing = runCatching { cp?.isPlaying == true }.getOrDefault(false)
            // Guarda "continuar viendo" cada ~10 s mientras se emite
            ticks++
            val c = CastManager.playCtx
            if (ticks % 10 == 0 && c.tmdbId > 0 && pos > 5000) {
                WatchStore.record(
                    tmdbId = c.tmdbId, type = c.type,
                    season = c.season.takeIf { it > 0 }, episode = c.episode.takeIf { it > 0 },
                    name = c.name, poster = c.poster,
                    position = pos / 1000.0, duration = if (dur > 0) dur / 1000.0 else 0.0
                )
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    fun fmt(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format("%d:%02d", s / 60, s % 60)
    }

    Column(
        Modifier.fillMaxSize().background(Bg).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Volver a la app", color = Accent, modifier = Modifier.clickable { onClose() })
            Spacer(Modifier.weight(1f))
            CastIconButton()
        }

        CastManager.poster?.let { p ->
            AsyncImage(
                model = p, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp))
            )
        }

        Text(
            CastManager.title.ifBlank { "Nada en emisión" },
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
        )
        Text(
            "📺 ${CastManager.deviceName ?: "TV"}" +
                if (CastManager.playingConverted) "  ·  audio convertido a AAC" else "",
            style = MaterialTheme.typography.labelMedium, color = Color(0xFF34D399)
        )
        if (CastManager.status.isNotBlank()) {
            Text(CastManager.status, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (CastManager.warning.isNotBlank()) {
            Text(CastManager.warning, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFBBF24))
        }

        // Progreso
        Slider(
            value = if (dur > 0) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f,
            onValueChange = { f -> if (dur > 0) runCatching { cp?.seekTo((f * dur.toFloat()).toLong()) } },
            enabled = dur > 0
        )
        Row(Modifier.fillMaxWidth()) {
            Text(fmt(pos), style = MaterialTheme.typography.labelSmall, color = Muted)
            Spacer(Modifier.weight(1f))
            Text(fmt(dur), style = MaterialTheme.typography.labelSmall, color = Muted)
        }

        // Controles
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                cp?.let { p -> runCatching { p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)) } }
            }) { Text("⏪ 10s") }
            Button(onClick = {
                cp?.let { p -> runCatching { if (p.isPlaying) p.pause() else p.play() } }
            }) { Text(if (playing) "⏸ Pausa" else "▶ Reproducir") }
            OutlinedButton(onClick = {
                cp?.let { p -> runCatching { p.seekTo(p.currentPosition + 10_000) } }
            }) { Text("10s ⏩") }
        }

        Text(
            "Puedes volver a la app y elegir otra película: se enviará a esta misma TV.",
            style = MaterialTheme.typography.labelSmall, color = Muted
        )

        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { CastManager.stop(); onClose() }) { Text("⏹ Parar") }
            OutlinedButton(onClick = { CastManager.disconnect(); onClose() }) { Text("Desconectar TV") }
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
fun DiscoverScreen(type: String, onType: (String) -> Unit, kids: Boolean = false, onOpen: (Tmdb.Title) -> Unit) {
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

    LaunchedEffect(type, kids) {
        if (!Tmdb.hasKey) { status = "" ; return@LaunchedEffect }
        status = "Cargando catálogos…"; rows = emptyList()
        Tmdb.catalogs(type, kids) { list, err ->
            onMain { rows = list ?: emptyList(); status = if (list == null) (err ?: "Error") else "" }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        Sync.onSignInResult(res.data) { _, _ -> }
    }

    browse?.let { (prov, nm) ->
        BrowseScreen(prov, nm, type, kids = kids, onOpen = onOpen, onBack = { browse = null })
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
            // Explorar por género (oculto en modo infantil: solo catálogos familiares)
            if (Tmdb.hasKey && !kids) {
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
        // Recomendado para ti (oculto en modo infantil)
        if (recs.isNotEmpty() && !kids) item {
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
fun BrowseScreen(provider: String, name: String, type: String, kids: Boolean = false, onOpen: (Tmdb.Title) -> Unit, onBack: () -> Unit) {
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
        Tmdb.discover(type, prov, genreId, next, kids) { list, _ ->
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

@OptIn(ExperimentalLayoutApi::class)   // FlowRow (chips de reproductor)
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
                    // Estado del formulario de crear/editar perfil
                    var editingId by remember { mutableStateOf<String?>(null) }
                    var showForm by remember { mutableStateOf(false) }
                    var pName by remember { mutableStateOf("") }
                    var pKids by remember { mutableStateOf(false) }
                    var pAvatar by remember { mutableStateOf("") }
                    var pMsg by remember { mutableStateOf("") }
                    fun resetForm() { editingId = null; showForm = false; pName = ""; pKids = false; pAvatar = ""; pMsg = "" }

                    if (Sync.profiles.isNotEmpty()) {
                        Text("Perfil", style = MaterialTheme.typography.labelMedium, color = Muted)
                        Sync.profiles.forEach { p ->
                            val active = Sync.activeProfile?.id == p.id
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(
                                    selected = active,
                                    onClick = { Sync.selectProfile(p.id) },
                                    label = { Text("${p.avatar} ${p.name}${if (p.kids) " 🧒" else ""}") },
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    editingId = p.id; pName = p.name; pKids = p.kids; pAvatar = p.avatar; showForm = true; pMsg = ""
                                }) { Icon(Icons.Filled.Edit, "Editar perfil") }
                                if (Sync.profiles.size > 1) IconButton(onClick = {
                                    Sync.removeProfile(p.id) { ok, err -> onMain { if (!ok) pMsg = err ?: "Error" } }
                                }) { Icon(Icons.Filled.Close, "Borrar perfil") }
                            }
                        }
                    } else if (!Sync.loading) {
                        Text("No hay perfiles todavía. Crea el primero aquí abajo.", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }

                    if (showForm) {
                        Text(if (editingId == null) "Nuevo perfil" else "Editar perfil", style = MaterialTheme.typography.labelMedium, color = Muted)
                        OutlinedTextField(value = pName, onValueChange = { pName = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = pAvatar, onValueChange = { pAvatar = it.take(2) }, label = { Text("Emoji (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = pKids, onCheckedChange = { pKids = it })
                            Text("Modo infantil (solo catálogos familiares)")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val cb: (Boolean, String?) -> Unit = { ok, err -> onMain { if (ok) resetForm() else pMsg = err ?: "Error" } }
                                if (editingId == null) Sync.addProfile(pName, pKids, pAvatar, cb)
                                else Sync.updateProfile(editingId!!, pName, pKids, pAvatar, cb)
                            }) { Text("Guardar") }
                            OutlinedButton(onClick = { resetForm() }) { Text("Cancelar") }
                        }
                    } else if (Sync.profiles.size < 5) {
                        OutlinedButton(onClick = { showForm = true; editingId = null; pName = ""; pKids = false; pAvatar = "" }) { Text("＋ Nuevo perfil") }
                    }
                    if (pMsg.isNotBlank()) Text(pMsg, color = Muted, style = MaterialTheme.typography.labelSmall)

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

        // --- Real-Debrid ---
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Real-Debrid", fontWeight = FontWeight.Bold)
                if (RealDebrid.configured) {
                    Text("⚡ Conectado${RealDebrid.account?.let { " · $it" } ?: ""}", color = Color(0xFF34D399), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { RealDebrid.disconnect() }) { Text("Desconectar") }
                } else {
                    Text("⚠️ Real-Debrid es imprescindible: la app no descarga por BitTorrent, todo el vídeo llega por streaming directo desde los servidores de RD. Pega tu token para empezar. Se comparte con tu cuenta si has entrado con Google.", color = Color(0xFFFBBF24), style = MaterialTheme.typography.bodySmall)
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

        // --- Reproducción ---
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reproducción", fontWeight = FontWeight.Bold)
                Text("¿Con qué se abre el vídeo al pulsar Ver?", color = Muted, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Prefs.PLAYER_APP to "Reproductor de la app",
                        Prefs.PLAYER_ASK to "Preguntar",
                        Prefs.PLAYER_EXTERNAL to "Otra app"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = Prefs.playerMode == mode,
                            onClick = { Prefs.savePlayerMode(mode) },
                            label = { Text(label) }
                        )
                    }
                }
                Text(
                    "Con «Otra app» se abre en VLC, MX Player o el que elijas: van mejor con MKV " +
                        "y audio DTS/TrueHD. Se pierden el «continuar viendo» y el siguiente " +
                        "episodio automático, que son del reproductor de la app. Al emitir a una " +
                        "TV esto no aplica: manda el Chromecast.",
                    color = Muted, style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // --- Descargas ---
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Descargas", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Descargar con datos móviles", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (Prefs.downloadOverMobile) "Activado: baja también sin WiFi (y en roaming)."
                            else "Desactivado: las descargas esperan a tener WiFi.",
                            color = Muted, style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Switch(
                        checked = Prefs.downloadOverMobile,
                        onCheckedChange = { Prefs.saveDownloadOverMobile(it) }
                    )
                }
                if (Prefs.downloadOverMobile && RdDownloads.dataSaverBlocks(ctx)) {
                    Text(
                        "⚠️ El «Ahorro de datos» de Android está activo y bloquea las descargas " +
                            "con datos móviles aunque la app las permita. Desactívalo, o excluye " +
                            "TorrentBox en Ajustes de Android → Red → Ahorro de datos → Datos sin restricción.",
                        color = Color(0xFFFBBF24), style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(
                    "El cambio afecta a las descargas NUEVAS: el sistema conserva la condición " +
                        "de red con la que se encoló cada una.",
                    color = Muted, style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // --- Motores de búsqueda ---
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Buscadores", fontWeight = FontWeight.Bold)
                Text(
                    "Se consultan Peerflix (el mismo addon que Stremio: Dontorrent, " +
                        "MejorTorrent, Wolfmax4k, Popcorntime…) y Torrentio.",
                    color = Muted, style = MaterialTheme.typography.bodySmall
                )
                var pf by remember { mutableStateOf(Prefs.peerflixUrl) }
                OutlinedTextField(
                    value = pf, onValueChange = { pf = it },
                    label = { Text("URL propia de Peerflix (opcional)") },
                    placeholder = { Text(Peerflix.DEFAULT_BASE) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Prefs.savePeerflixUrl(pf) }) { Text("Guardar") }
                    if (Prefs.peerflixUrl.isNotBlank()) {
                        OutlinedButton(onClick = { Prefs.savePeerflixUrl(""); pf = "" }) { Text("Usar la pública") }
                    }
                }
                Text(
                    "Si configuras tu Peerflix en config.peerflix.mov (por ejemplo con tu " +
                        "Real-Debrid), pega aquí la URL que te dé. Vacío = la pública.",
                    color = Muted, style = MaterialTheme.typography.labelSmall
                )
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
                    // Solo se puede afirmar que está al día si la consulta salió bien
                    Text(
                        when {
                            !Update.checked -> "Comprobando…"
                            Update.status.isBlank() -> "Estás en la última versión."
                            else -> "No se pudo comprobar."
                        },
                        style = MaterialTheme.typography.bodySmall, color = Muted
                    )
                    OutlinedButton(onClick = { Update.check() }) { Text("Buscar actualización") }
                }
                if (Update.status.isNotBlank()) Text(
                    Update.status, color = Color(0xFFFBBF24), style = MaterialTheme.typography.bodySmall
                )

                // Token de GitHub: el repositorio es privado y sin él la API de
                // Releases responde 404, así que la app no podía saber si había
                // versión nueva ni descargarla.
                var gh by remember { mutableStateOf(Prefs.githubToken) }
                OutlinedTextField(
                    value = gh, onValueChange = { gh = it },
                    label = { Text("Token de GitHub (para actualizar)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Prefs.saveGithubToken(gh); Update.check() }) { Text("Guardar y comprobar") }
                    if (Prefs.githubToken.isNotBlank()) {
                        OutlinedButton(onClick = { Prefs.saveGithubToken(""); gh = "" }) { Text("Borrar") }
                    }
                }
                Text(
                    "Créalo en github.com/settings/personal-access-tokens con acceso solo a " +
                        "este repositorio y permiso «Contents: Read-only». Se guarda cifrado en el móvil.",
                    color = Muted, style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // --- Aplicación ---
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Aplicación", fontWeight = FontWeight.Bold)
                Text(
                    "Al salir no queda nada corriendo: la app no tiene servicio en segundo plano ni motor de torrents. " +
                        "Las descargas las gestiona el sistema, así que siguen aunque cierres la app.",
                    color = Muted, style = MaterialTheme.typography.bodySmall
                )
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
fun DownloadsScreen(rdDownloads: List<RdDownloads.Snap>, onPlayUrl: (String) -> Unit) {
    val ctx = LocalContext.current
    // Separa la ACTIVIDAD (descargando) de lo que ya está LISTO PARA VER
    val ready = rdDownloads.filter { it.done }
    val active = rdDownloads.filter { !it.done }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        item {
            Text("Descargas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            // Espacio libre en la carpeta donde escribe el DownloadManager
            var space by remember { mutableStateOf("") }
            LaunchedEffect(rdDownloads.size) {
                space = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
                        "Libre: ${Search.humanSize(dir.usableSpace)}"
                    }.getOrDefault("")
                }
            }
            Text(space, style = MaterialTheme.typography.labelSmall, color = Muted)
            // Si el sistema bloquea los datos en segundo plano, la descarga se
            // queda parada sin explicacion: mejor decirlo aqui.
            if (active.isNotEmpty() && RdDownloads.dataSaverBlocks(ctx)) {
                Text(
                    "⚠️ El «Ahorro de datos» de Android puede tener parada la descarga con " +
                        "datos móviles. Excluye TorrentBox en Ajustes de Android → Red → " +
                        "Ahorro de datos, o conéctate a WiFi.",
                    color = Color(0xFFFBBF24), style = MaterialTheme.typography.labelSmall
                )
            }
            if (rdDownloads.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Aún no hay descargas. Abre un título, busca fuentes y pulsa ⬇ Descargar.\n" +
                        "Las gestiona el sistema: continúan aunque cierres la app.",
                    color = Muted, style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // --- Listas para ver ---
        if (ready.isNotEmpty()) {
            item { Text("▶ Listas para ver", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF34D399), modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
            items(ready.size) { i -> RdDownloadCard(ready[i], onPlayUrl = { onPlayUrl(RdDownloads.playUri(ctx, ready[i].id) ?: ready[i].localUri ?: "") }, onRemove = { RdDownloads.remove(ctx, ready[i].id) }) }
        }

        // --- Descargando (actividad) ---
        if (active.isNotEmpty()) {
            item { Text("⏳ Descargando", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)) }
            items(active.size) { i -> RdDownloadCard(active[i], onPlayUrl = { onPlayUrl(RdDownloads.playUri(ctx, active[i].id) ?: active[i].localUri ?: "") }, onRemove = { RdDownloads.remove(ctx, active[i].id) }) }
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
    onPlayUrl: (String, PlayCtx) -> Unit,
    onCastMagnet: (String, PlayCtx) -> Unit,
    onOpenDownloads: () -> Unit
) {
    var linksExpanded by remember { mutableStateOf(true) }
    // Motor elegido (Todos / Torrentio / Peerflix), recordado entre titulos.
    // El ÚNICO filtro es el motor: filtrar por calidad escondía enlaces (los
    // que no llevan la etiqueta en el nombre, muchos mkv, quedaban fuera).
    val engineFilter = Prefs.engine
    // Filtro de calidad, local a esta pantalla. Las que no se identifican van al
    // chip "Otras": así se pueden ver siempre (antes desaparecían sin más).
    var qualityFilter by remember { mutableStateOf("all") }
    val byEngine = sources.filter { it.fromEngine(engineFilter) }
    val shown = when (qualityFilter) {
        "all" -> byEngine
        Search.QUALITY_OTHER -> byEngine.filter { it.quality == Search.QUALITY_OTHER }
        else -> byEngine.filter { it.quality == qualityFilter }
    }

    // Estado de la ventana flotante "Cargando…" / "Preparando la descarga"
    var prep by remember { mutableStateOf<Prep?>(null) }

    /**
     * Pide el enlace a Real-Debrid mostrando un diálogo con el progreso. Si RD
     * aún está bajando el torrent a sus servidores, reintenta solo (antes había
     * que volver a pulsar el botón, y parecía que no respondía).
     */
    fun prepare(r: Search.Result, download: Boolean, attempt: Int = 0) {
        if (attempt == 0) {
            prep = Prep(download, if (download) "Pidiendo el enlace a Real-Debrid…" else "Preparando el vídeo…")
        }
        RealDebrid.streamMagnet(r.magnet) { url, fname, err, progress ->
            onMain {
                val cur = prep ?: return@onMain     // cancelado por el usuario
                when {
                    url != null -> {
                        prep = null
                        if (download) {
                            RdDownloads.enqueue(ctx, url, fname ?: title.title)
                            onOpenDownloads()
                        } else onPlayUrl(url, buildCtx().withSource(r))
                    }
                    progress != null && attempt < 25 -> {
                        prep = cur.copy(msg = "Real-Debrid lo está preparando… ${progress}%")
                        onMainDelayed(4000) { prepare(r, download, attempt + 1) }
                    }
                    progress != null -> prep = cur.copy(
                        error = "Real-Debrid sigue preparándolo (${progress}%). Inténtalo dentro de un rato."
                    )
                    else -> prep = cur.copy(error = err ?: "Error de Real-Debrid")
                }
            }
        }
    }

    Column(Modifier.padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Un chip por motor, como las pestañas de addons de Stremio
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val engines = listOf(Search.ENGINE_PEERFLIX, Search.ENGINE_TORRENTIO)
            (listOf(Search.ENGINE_ALL) + engines).forEach { key ->
                val n = if (key == Search.ENGINE_ALL) sources.size else sources.count { it.fromEngine(key) }
                // Los motores sin resultados no se muestran (salvo el elegido)
                if (key == Search.ENGINE_ALL || n > 0 || engineFilter == key) {
                    FilterChip(
                        selected = engineFilter == key,
                        onClick = { Prefs.selectEngine(key) },
                        label = {
                            Text(
                                Search.engineName(key) + if (sources.isEmpty()) "" else " ($n)"
                            )
                        }
                    )
                }
            }
        }
        // Chips de calidad (solo las presentes en los enlaces de este motor)
        if (byEngine.isNotEmpty()) {
            val present = Search.QUALITIES.filter { q -> byEngine.any { it.quality == q } } +
                (if (byEngine.any { it.quality == Search.QUALITY_OTHER }) listOf(Search.QUALITY_OTHER) else emptyList())
            if (present.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = qualityFilter == "all",
                        onClick = { qualityFilter = "all" },
                        label = { Text("Todas") }
                    )
                    present.forEach { q ->
                        FilterChip(
                            selected = qualityFilter == q,
                            onClick = { qualityFilter = q },
                            label = {
                                Text(
                                    (if (q == Search.QUALITY_OTHER) "Otras" else q) +
                                        " (${byEngine.count { it.quality == q }})"
                                )
                            }
                        )
                    }
                }
            }
        }
        if (loading) Text("Buscando fuentes…", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (sources.isNotEmpty() && shown.isEmpty()) Text(
            if (byEngine.isEmpty()) "Sin enlaces de este motor para este título; prueba \"Todos\"."
            else "Ningún enlace con esa calidad; prueba \"Todas\".",
            color = Color(0xFFFBBF24), style = MaterialTheme.typography.bodySmall
        )
        if (sources.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().clickable { linksExpanded = !linksExpanded }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Enlaces (${shown.size})" + if (label.isNotBlank()) " · $label" else "",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                Icon(if (linksExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (linksExpanded) "Plegar" else "Desplegar")
            }
        }
        if (linksExpanded) shown.forEach { r ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(r.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildList {
                            if (r.engineLabel.isNotBlank()) add("⚙ ${r.engineLabel}")
                            add("${Lang.flag(r.lang)} ${Lang.label(r.lang)}")
                            if (r.quality != Search.QUALITY_OTHER) add(r.quality)
                            add("▲ ${r.seeders} seeders")
                            // El tamaño no siempre lo da el addon: si no, no se pone
                            if (r.sizeBytes > 0) add("💾 ${Search.humanSize(r.sizeBytes)}")
                        }.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall, color = Muted
                    )
                    if (RealDebrid.configured) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                if (CastManager.connected) {
                                    // Con TV conectada va directo a la TV; el
                                    // CastManager muestra el progreso y elige la
                                    // versión con audio compatible.
                                    onCastMagnet(r.magnet, buildCtx().withSource(r))
                                } else prepare(r, download = false)
                            }) { Text(if (CastManager.connected) "📺 Ver en la TV" else "▶ Ver") }
                            OutlinedButton(onClick = { prepare(r, download = true) }) { Text("⬇ Descargar") }
                        }
                    } else {
                        Text(
                            "Conecta Real-Debrid en Ajustes para ver o descargar.",
                            color = Color(0xFFFBBF24), style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }

    // --- Ventana flotante mientras Real-Debrid prepara el enlace ---
    val p = prep
    if (p != null) {
        AlertDialog(
            onDismissRequest = { prep = null },
            title = { Text(if (p.download) "Preparando la descarga" else "Cargando…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p.error == null) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(14.dp))
                    }
                    Text(
                        p.error ?: p.msg,
                        color = if (p.error != null) Color(0xFFFBBF24) else MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { prep = null }) {
                    Text(if (p.error != null) "Cerrar" else "Cancelar")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    title: Tmdb.Title,
    onBack: () -> Unit,
    onPlayUrl: (String, PlayCtx) -> Unit,
    onCastMagnet: (String, PlayCtx) -> Unit,
    onOpenDownloads: () -> Unit
) {
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

    // Busca en Peerflix (addon de Stremio) y en Torrentio a la vez, por IMDb id;
    // combina, deduplica por infoHash (gana el de más seeders) y ordena por
    // motor e idioma. La combinación se hace en el hilo principal (onMain).
    fun runSearch(label: String, season: Int? = null, episode: Int? = null) {
        loadingSources = true; sources = emptyList(); sourcesLabel = label
        ctxSeason = season ?: -1; ctxEpisode = episode ?: -1
        val id = imdbId
        // Los dos motores buscan por IMDb id: hace falta tenerlo, y en series
        // hace falta el episodio concreto.
        val usable = id != null && (title.type == "movie" || episode != null)
        if (!usable) {
            loadingSources = false
            status = if (id == null) "No se pudo identificar el título (sin IMDb id)"
            else "Elige un episodio para ver sus enlaces"
            return
        }
        val acc = mutableListOf<Search.Result>()
        var remaining = 2   // Peerflix + Torrentio
        var lastErr: String? = null
        fun part(list: List<Search.Result>?, err: String?) = onMain {
            if (list != null) acc.addAll(list) else lastErr = err
            if (--remaining <= 0) {
                // Un mismo torrent puede venir de los dos motores: se queda el
                // que trae mas seeders, pero recordando que lo dieron ambos (asi
                // sigue apareciendo en las dos pestanas).
                val byHash = LinkedHashMap<String, Search.Result>()
                for (r in acc) {
                    val prev = byHash[r.infoHash]
                    byHash[r.infoHash] = if (prev == null) r
                    else (if (r.seeders > prev.seeders) r else prev).copy(
                        engine = Search.mergeEngines(prev.engine, r.engine),
                        // si uno de los dos trae el tamaño, se conserva
                        sizeBytes = maxOf(prev.sizeBytes, r.sizeBytes),
                        // y la calidad que se haya podido identificar
                        quality = if (prev.quality != Search.QUALITY_OTHER) prev.quality else r.quality
                    )
                }
                loadingSources = false
                sources = Search.sortByEngineAndLang(byHash.values.toList(), Prefs.languageOrder)
                if (sources.isEmpty()) status = lastErr ?: "Sin fuentes"
            }
        }
        Peerflix.streams(title.type, id!!, season, episode) { l, e -> part(l, e) }
        Torrentio.streams(title.type, id, season, episode) { l, e -> part(l, e) }
    }
    fun loadSources(dt: Tmdb.Detail) = runSearch(dt.title)

    // Los enlaces salen SOLOS al abrir la ficha (como Stremio). Se espera un
    // momento al id de IMDb: sin el, Torrentio no se puede consultar.
    var autoSearched by remember { mutableStateOf(false) }
    LaunchedEffect(detail?.tmdbId, imdbId) {
        val dt = detail ?: return@LaunchedEffect
        if (dt.type == "series" || autoSearched) return@LaunchedEffect
        if (imdbId == null) kotlinx.coroutines.delay(1500)
        if (autoSearched) return@LaunchedEffect
        autoSearched = true
        loadSources(dt)
    }

    // Serie: al entrar en una temporada se abre solo el primer episodio sin ver
    // y se cargan sus enlaces.
    LaunchedEffect(selSeason, episodes.size, imdbId) {
        val dt = detail ?: return@LaunchedEffect
        val sn = selSeason ?: return@LaunchedEffect
        if (dt.type != "series" || episodes.isEmpty() || expandedEpisode != -1) return@LaunchedEffect
        if (imdbId == null) kotlinx.coroutines.delay(1500)
        if (expandedEpisode != -1) return@LaunchedEffect
        val ep = episodes.firstOrNull { !WatchStore.isWatchedEpisode(title.tmdbId, sn, it.episode) }
            ?: episodes.first()
        expandedEpisode = ep.episode
        runSearch("${dt.title} · T${sn}E${ep.episode} · ${ep.name}", sn, ep.episode)
    }

    // Contexto para el reproductor (marcar visto + reanudar) según lo buscado
    fun buildCtx(): PlayCtx {
        val s = ctxSeason.takeIf { it > 0 }
        val e = ctxEpisode.takeIf { it > 0 }
        val key = if (title.type == "series" && s != null) "series:${title.tmdbId}:$s:${e ?: 1}" else "movie:${title.tmdbId}"
        val resumeMs = WatchStore.progressFor(key)?.let { if (!it.watched) (it.position * 1000).toLong() else 0L } ?: 0L
        return PlayCtx(
            title.tmdbId, title.type, s ?: -1, e ?: -1, title.title, title.poster, resumeMs,
            // El título original es el que busca Peerflix (por texto)
            query = detail?.originalTitle ?: title.title
        )
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
                    // Sin "temporada completa": Peerflix y Torrentio dan enlaces
                    // por episodio (los packs de temporada salen entre ellos).
                    episodes.forEach { ep ->
                        Card(
                            Modifier.fillMaxWidth().clickable {
                                expandedEpisode = ep.episode
                                runSearch("${dt.title} · T${sn}E${ep.episode} · ${ep.name}", sn, ep.episode)
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
                            SourcesSection(sources, loadingSources, sourcesLabel, title, ctx, { buildCtx() }, onPlayUrl, onCastMagnet, onOpenDownloads)
                        }
                    }
                }
            } else {
                // Los enlaces se cargan solos; esto es solo para reintentar
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (loadingSources) "Buscando fuentes…" else "Enlaces",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (!loadingSources && dt != null) Text(
                        "🔄 Recargar", color = Accent, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable { expandedEpisode = -1; loadSources(dt) }
                    )
                }
                SourcesSection(sources, loadingSources, sourcesLabel, title, ctx, { buildCtx() }, onPlayUrl, onCastMagnet, onOpenDownloads)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
