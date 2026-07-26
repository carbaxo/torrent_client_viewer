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

private val mainHandler = Handler(Looper.getMainLooper())
private fun onMain(block: () -> Unit) = mainHandler.post(block)

// Paleta al estilo de la web (morado Stremio)
private val Accent = Color(0xFF7B5BF5)
private val Bg = Color(0xFF0C0B11)
private val Surface1 = Color(0xFF15141D)
private val Muted = Color(0xFF8F8BA1)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Prefs.init(this)
        WatchStore.init(this)
        RealDebrid.init(this)
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
                        onPlayUrl = { url, c -> startActivity(playerIntent(c).putExtra("url", url)) }
                    )
                }
            }
        }
    }

    private fun playerIntent(c: PlayCtx) = Intent(this, PlayerActivity::class.java).apply {
        putExtra("tmdbId", c.tmdbId); putExtra("type", c.type)
        putExtra("season", c.season); putExtra("episode", c.episode)
        putExtra("name", c.name); putExtra("poster", c.poster); putExtra("resumeMs", c.resumeMs)
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
    DOWNLOADS("Descargas", Icons.Filled.Download),
    SETTINGS("Ajustes", Icons.Filled.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(onPlayUrl: (String, PlayCtx) -> Unit) {
    var tab by remember { mutableStateOf(Tab.DISCOVER) }
    var detail by remember { mutableStateOf<Tmdb.Title?>(null) }
    var catalogType by remember { mutableStateOf("movie") }
    val rdDownloads = remember { mutableStateListOf<RdDownloads.Snap>() }
    val ctx = LocalContext.current

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

    // Ficha de detalle a pantalla completa
    val d = detail
    if (d != null) {
        DetailScreen(
            title = d,
            onBack = { detail = null },
            onPlayUrl = { url, c -> onPlayUrl(url, c) },
            onOpenDownloads = { tab = Tab.DOWNLOADS; detail = null }
        )
        return
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
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
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                Tab.DISCOVER -> DiscoverScreen(catalogType, { catalogType = it }, kids = kids, onOpen = { detail = it })
                Tab.SEARCH -> if (kids) DiscoverScreen(catalogType, { catalogType = it }, kids = true, onOpen = { detail = it }) else SearchScreen(onOpen = { detail = it })
                Tab.DOWNLOADS -> DownloadsScreen(rdDownloads) { u -> onPlayUrl(u, PlayCtx()) }
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
    onOpenDownloads: () -> Unit
) {
    var linksExpanded by remember { mutableStateOf(true) }
    var rdStatus by remember { mutableStateOf("") }
    var qualityFilter by remember { mutableStateOf("all") }

    // Calidades presentes en los resultados (para los chips de filtro)
    val qualities = remember(sources) {
        listOf("4K", "1080p", "720p", "480p", "SD").filter { q -> sources.any { it.quality == q } }
    }
    val shown = if (qualityFilter == "all") sources else sources.filter { it.quality == qualityFilter }

    Column(Modifier.padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (loading) Text("Buscando fuentes…", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (sources.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().clickable { linksExpanded = !linksExpanded }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Enlaces (${shown.size})" + if (label.isNotBlank()) " · $label" else "",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                Icon(if (linksExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (linksExpanded) "Plegar" else "Desplegar")
            }
            // Filtros de calidad (como la web)
            if (linksExpanded && qualities.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = qualityFilter == "all", onClick = { qualityFilter = "all" }, label = { Text("Todas") })
                    qualities.forEach { q ->
                        FilterChip(selected = qualityFilter == q, onClick = { qualityFilter = q }, label = { Text(q) })
                    }
                }
            }
        }
        if (linksExpanded) shown.forEach { r ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(r.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${Lang.flag(r.lang)} ${Lang.label(r.lang)}" + (if (r.quality != "Unknown") "  ·  ${r.quality}" else "") + "  ·  ▲ ${r.seeders} seeders · ${Search.humanSize(r.sizeBytes)}", style = MaterialTheme.typography.labelSmall, color = Muted)
                    if (RealDebrid.configured) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                rdStatus = "⚡ Preparando en Real-Debrid…"
                                RealDebrid.streamMagnet(r.magnet) { url, _, err, progress ->
                                    onMain {
                                        when {
                                            url != null -> { rdStatus = ""; onPlayUrl(url, buildCtx()) }
                                            progress != null -> rdStatus = "Real-Debrid lo está preparando en sus servidores… ${progress}%. Vuelve a pulsar en un momento."
                                            else -> rdStatus = err ?: "Error de Real-Debrid"
                                        }
                                    }
                                }
                            }) { Text("▶ Ver") }
                            OutlinedButton(onClick = {
                                rdStatus = "⚡ Preparando la descarga…"
                                RealDebrid.streamMagnet(r.magnet) { url, fname, err, progress ->
                                    onMain {
                                        when {
                                            url != null -> {
                                                RdDownloads.enqueue(ctx, url, fname ?: title.title)
                                                rdStatus = ""
                                                onOpenDownloads()
                                            }
                                            progress != null -> rdStatus = "Real-Debrid lo está preparando en sus servidores… ${progress}%. Vuelve a pulsar en un momento."
                                            else -> rdStatus = err ?: "Error de Real-Debrid"
                                        }
                                    }
                                }
                            }) { Text("⬇ Descargar") }
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
        if (rdStatus.isNotBlank()) Text(rdStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(title: Tmdb.Title, onBack: () -> Unit, onPlayUrl: (String, PlayCtx) -> Unit, onOpenDownloads: () -> Unit) {
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
                        SourcesSection(sources, loadingSources, sourcesLabel, title, ctx, { buildCtx() }, onPlayUrl, onOpenDownloads)
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
                            SourcesSection(sources, loadingSources, sourcesLabel, title, ctx, { buildCtx() }, onPlayUrl, onOpenDownloads)
                        }
                    }
                }
            } else {
                Button(onClick = { expandedEpisode = -1; dt?.let { loadSources(it) } }, enabled = dt != null && !loadingSources, modifier = Modifier.fillMaxWidth()) {
                    Text(if (loadingSources) "Buscando fuentes…" else "Buscar fuentes")
                }
                SourcesSection(sources, loadingSources, sourcesLabel, title, ctx, { buildCtx() }, onPlayUrl, onOpenDownloads)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
