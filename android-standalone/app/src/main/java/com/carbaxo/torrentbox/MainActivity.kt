package com.carbaxo.torrentbox

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage

private val mainHandler = Handler(Looper.getMainLooper())
private fun onMain(block: () -> Unit) = mainHandler.post(block)
private fun onMainDelayed(ms: Long, block: () -> Unit) = mainHandler.postDelayed(block, ms)

/** Estado de la ventana flotante mientras Real-Debrid prepara un enlace. */
data class Prep(
    val download: Boolean,
    val msg: String,
    val error: String? = null,
    /** Magnet del enlace: si RD falla, se puede añadir a mano a la cuenta. */
    val magnet: String = "",
    /** Real-Debrid está bajándolo a sus servidores (no lo tenía en caché). */
    val atRd: Boolean = false
)

// Paleta al estilo de la web (morado Stremio)
// Paleta granate. El acento se usa tanto de relleno de boton (con texto blanco
// encima) como de texto sobre el fondo oscuro, asi que no puede ser un granate
// apagado: dejaria de leerse. Los fondos llevan el tinte hacia el rojo.
private val Accent = Color(0xFFC62B45)
private val Bg = Color(0xFF0E0A0C)
private val Surface1 = Color(0xFF1B1317)
private val Muted = Color(0xFF9C8E92)

// AppCompatActivity: el diálogo "emitir a…" de Chromecast lo exige
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Tv.init(this)          // ¿estamos en una tele? cambia foco y navegación
        Prefs.init(this)
        Downloads.init(this)   // descargas propias (pausar/continuar)
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

        // Magnets que llegan de fuera: al pulsar uno en el navegador o al
        // compartir un texto con CaViPlay. Se usa el listener de androidx en
        // vez de sobrescribir onNewIntent (la app es singleTop).
        handleIncoming(intent)
        addOnNewIntentListener { handleIncoming(it) }

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

    /** Deja el magnet/enlace recibido en el buzón; Descargas lo recoge. */
    private fun handleIncoming(i: Intent?) {
        when (i?.action) {
            Intent.ACTION_VIEW -> MagnetInbox.offer(i.dataString)
            Intent.ACTION_SEND -> MagnetInbox.offer(i.getStringExtra(Intent.EXTRA_TEXT))
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
    var showProfiles by remember { mutableStateOf(false) }
    // "Preguntar cada vez" con qué reproductor abrir, y errores al lanzarlo
    var askPlayer by remember { mutableStateOf<Pair<String, PlayCtx>?>(null) }
    // (título, mensaje, ¿ofrecer instalar VLC?)
    var playerMsg by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    val ctx = LocalContext.current

    // Si Play Services no estaba listo al arrancar, se reintenta al pintar
    LaunchedEffect(Unit) { CastManager.init(ctx) }

    // Con TV conectada todo va a la TV; si no, al reproductor del móvil
    fun openExternal(url: String, c: PlayCtx, pkg: String? = null) {
        val err = ExternalPlayer.open(ctx, url, c.name, c.resumeMs, pkg)
        if (err == null) { detail = null; return }
        playerMsg = Triple(
            "Reproductor externo", err,
            pkg == ExternalPlayer.VLC && !ExternalPlayer.vlcInstalled(ctx)
        )
    }

    /**
     * Pasa el vídeo a VLC para que sea VLC quien lo emita a la TV. VLC
     * transcodifica en el móvil, así que se traga cualquier MKV con DTS; a
     * cambio el vídeo pasa por el teléfono y el último paso lo tiene que dar el
     * usuario dentro de VLC: Android no permite elegirle el dispositivo desde
     * fuera.
     */
    fun castViaVlc(url: String, c: PlayCtx) {
        // El receptor de la TV solo atiende a una app: si nuestra sesión sigue
        // abierta, VLC no podría conectarse. Se cierra antes de pasar el relevo.
        if (CastManager.connected) CastManager.disconnect()
        val err = ExternalPlayer.open(ctx, url, c.name, c.resumeMs, ExternalPlayer.VLC)
        if (err != null) {
            playerMsg = Triple("Emitir con VLC", err, !ExternalPlayer.vlcInstalled(ctx))
            return
        }
        detail = null
        playerMsg = Triple(
            "Emitir con VLC",
            "Ya está abierto en VLC. Ahora pulsa el icono de emitir (📺) arriba en VLC y " +
                "elige tu TV. Ese último paso hay que darlo ahí: Android no deja que otra " +
                "app le diga a VLC a qué dispositivo emitir.",
            false
        )
    }

    fun play(url: String, c: PlayCtx) {
        when {
            // TV elegida arriba + "emitir con VLC" en Ajustes
            CastManager.connected && Prefs.castWithVlc -> castViaVlc(url, c)
            CastManager.connected -> { CastManager.castUrl(url, c); showCastScreen = true; detail = null }
            // Reproductor externo (VLC, MX Player…) según Ajustes → Reproducción
            Prefs.playerMode == Prefs.PLAYER_VLC -> openExternal(url, c, ExternalPlayer.VLC)
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

    // Avisos de episodios nuevos cuando llegan los favoritos de la nube
    LaunchedEffect(Sync.favorites.size) { if (Sync.favorites.isNotEmpty()) EpisodeAlerts.check(ctx) }

    // Un magnet compartido desde fuera (o el de un enlace que falló) se añade en
    // Descargas: nos vamos allí para que se vea el campo ya relleno.
    LaunchedEffect(MagnetInbox.pending) {
        if (MagnetInbox.pending != null) { detail = null; showCastScreen = false; tab = Tab.DOWNLOADS }
    }

    // --- ¿Con qué reproductor? (modo "preguntar cada vez") ---
    askPlayer?.let { (url, c) ->
        val hasVlc = ExternalPlayer.vlcInstalled(ctx)
        AlertDialog(
            onDismissRequest = { askPlayer = null },
            title = { Text("¿Con qué lo abrimos?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "VLC maneja mejor los MKV con audio DTS o TrueHD; el de la app guarda " +
                            "el «continuar viendo» y pasa al siguiente episodio.",
                        style = MaterialTheme.typography.bodySmall, color = Muted
                    )
                    Button(
                        onClick = { askPlayer = null; onPlayUrl(url, c) },
                        modifier = Modifier.fillMaxWidth().tvFocusRing(RoundedCornerShape(20.dp))
                    ) { Text("Reproductor de la app") }
                    if (hasVlc) OutlinedButton(
                        onClick = { askPlayer = null; openExternal(url, c, ExternalPlayer.VLC) },
                        modifier = Modifier.fillMaxWidth().tvFocusRing(RoundedCornerShape(20.dp))
                    ) { Text("VLC") }
                    OutlinedButton(
                        onClick = { askPlayer = null; openExternal(url, c) },
                        modifier = Modifier.fillMaxWidth().tvFocusRing(RoundedCornerShape(20.dp))
                    ) { Text("Otra app…") }
                }
            },
            confirmButton = {
                TextButton(onClick = { askPlayer = null }, modifier = Modifier.tvFocusRing()) { Text("Cancelar") }
            }
        )
    }
    playerMsg?.let { (mTitle, mText, offerVlc) ->
        AlertDialog(
            onDismissRequest = { playerMsg = null },
            title = { Text(mTitle) },
            text = { Text(mText) },
            confirmButton = {
                TextButton(onClick = { playerMsg = null }, modifier = Modifier.tvFocusRing()) { Text("Cerrar") }
            },
            dismissButton = {
                if (offerVlc) TextButton(
                    onClick = { playerMsg = null; ExternalPlayer.installVlc(ctx) },
                    modifier = Modifier.tvFocusRing()
                ) { Text("Instalar VLC") }
            }
        )
    }

    if (showProfiles) ProfilePicker(
        onClose = { showProfiles = false },
        onManage = { showProfiles = false; detail = null; tab = Tab.SETTINGS }
    )

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

    // Con el mando, "atrás" vuelve a Descubrir en vez de cerrar la app
    BackHandler(enabled = tab != Tab.DISCOVER) { tab = Tab.DISCOVER }

    // Botón de Chromecast siempre visible: se elige la TV ANTES de abrir
    // ningún título, igual que en HBO o Netflix.
    val topBar: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().background(Bg).padding(start = 14.dp, end = 6.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                // En la tele, el título dice también en qué sección estás
                if (Tv.isTv) "CaViPlay · ${tab.label}" else "CaViPlay",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = Accent, modifier = Modifier.weight(1f)
            )
            if (CastManager.connected) {
                Text(
                    CastManager.deviceName ?: "TV", color = Color(0xFF34D399),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp)
                )
            }
            // Cambiar de perfil desde cualquier pestaña, sin pasar por Ajustes
            ProfileChip { showProfiles = true }
            CastIconButton()
        }
    }

    // Barra "emitiendo": abre el mando de la TV
    val castBar: @Composable () -> Unit = {
        if (CastManager.connected && CastManager.title.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF1B2A25))
                    .tvClickable(RoundedCornerShape(0.dp), scale = 1f) { showCastScreen = true }
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
    }

    val content: @Composable () -> Unit = {
        when (tab) {
            Tab.DISCOVER -> DiscoverScreen(catalogType, { catalogType = it }, kids = kids, onOpen = { detail = it })
            Tab.SEARCH -> if (kids) DiscoverScreen(catalogType, { catalogType = it }, kids = true, onOpen = { detail = it }) else SearchScreen(onOpen = { detail = it })
            Tab.DOWNLOADS -> DownloadsScreen { u -> play(u, PlayCtx()) }
            Tab.SETTINGS -> SettingsScreen()
        }
    }

    if (Tv.isTv) {
        // TELE: las secciones van en una columna a la izquierda (como Stremio
        // para TV). Con el mando se llega a ellas yendo a la izquierda desde
        // cualquier sitio, y siempre se ve cuál está activa.
        Row(Modifier.fillMaxSize().background(Bg)) {
            TvNavRail(visibleTabs, tab, onProfiles = { showProfiles = true }) { tab = it }
            Column(Modifier.weight(1f).padding(end = Tv.overscan)) {
                topBar()
                castBar()
                Box(Modifier.weight(1f)) { content() }
            }
        }
    } else {
        Scaffold(
            containerColor = Bg,
            topBar = topBar,
            bottomBar = {
                Column {
                    castBar()
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
            Box(Modifier.padding(pad)) { content() }
        }
    }
}

/**
 * Barra de secciones de la tele. La sección activa va marcada con el color de
 * la app y una barra lateral; la que tiene el foco del mando, con el anillo
 * blanco. Son dos cosas distintas a propósito: una dice DÓNDE ESTÁS y la otra
 * QUÉ VAS A PULSAR, que es justo lo que se pierde sin pantalla táctil.
 */
@Composable
private fun TvNavRail(
    tabs: List<Tab>,
    current: Tab,
    onProfiles: () -> Unit,
    onSelect: (Tab) -> Unit
) {
    // El foco arranca en la sección activa: al encender ya se ve dónde estás
    val first = rememberTvFocus()
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)   // hay que esperar a que el nodo esté colocado
        runCatching { first.requestFocus() }
    }

    Column(
        Modifier.width(210.dp).fillMaxHeight().background(Surface1)
            .padding(start = Tv.overscan, end = 10.dp, top = 18.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "CaViPlay", color = Accent, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 18.dp)
        )
        tabs.forEach { t ->
            val selected = t == current
            Row(
                Modifier.fillMaxWidth()
                    .tvRow(
                        RoundedCornerShape(10.dp),
                        focusRequester = if (selected) first else null
                    ) { onSelect(t) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(t.icon, contentDescription = null, tint = if (selected) Accent else Muted)
                Spacer(Modifier.width(12.dp))
                Text(
                    t.label,
                    color = if (selected) Accent else Color.White,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        // El perfil activo, al fondo de la barra: en la tele es donde se busca y
        // hasta ahora estaba enterrado en Ajustes → Cuenta.
        Sync.activeProfile?.let { p ->
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth()
                    .tvRow(RoundedCornerShape(10.dp)) { onProfiles() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(p.avatar, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Perfil", color = Muted, style = MaterialTheme.typography.labelSmall)
                    Text(
                        p.name, color = Color.White, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Cambiar de perfil", tint = Muted)
            }
        }
    }
}

/** Quién está viendo: el avatar del perfil activo, en la barra superior. */
@Composable
private fun ProfileChip(onClick: () -> Unit) {
    val p = Sync.activeProfile ?: return
    Row(
        Modifier.tvClickable(RoundedCornerShape(20.dp)) { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(p.avatar, style = MaterialTheme.typography.titleMedium)
        // En la tele cabe el nombre; en el móvil la barra va justa de sitio
        if (Tv.isTv) {
            Spacer(Modifier.width(6.dp))
            Text(
                p.name, color = Color.White, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.widthIn(max = 140.dp)
            )
        }
    }
}

/**
 * "¿Quién está viendo?" al estilo de Netflix. Se llega desde la barra superior en
 * cualquier pestaña y, en la tele, desde el fondo de la barra de secciones.
 */
@Composable
private fun ProfilePicker(onClose: () -> Unit, onManage: () -> Unit) {
    val activeFocus = rememberTvFocus()
    LaunchedEffect(Unit) {
        if (!Tv.isTv) return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        runCatching { activeFocus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("¿Quién está viendo?") },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (Sync.profiles.isEmpty()) Text(
                    "Todavía no hay perfiles. Se crean en Ajustes → Cuenta.",
                    color = Muted, style = MaterialTheme.typography.bodySmall
                )
                Sync.profiles.forEach { p ->
                    val active = Sync.activeProfile?.id == p.id
                    Row(
                        Modifier.fillMaxWidth()
                            .tvRow(
                                RoundedCornerShape(12.dp),
                                // El foco empieza en el perfil activo
                                focusRequester = if (active) activeFocus else null
                            ) { Sync.selectProfile(p.id); onClose() }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(p.avatar, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.name + if (p.kids) "  🧒" else "",
                                color = if (active) Accent else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (p.kids) Text(
                                "Modo infantil", color = Muted,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (active) Icon(Icons.Filled.Check, contentDescription = "Activo", tint = Accent)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, modifier = Modifier.tvFocusRing()) { Text("Cerrar") }
        },
        dismissButton = {
            TextButton(onClick = onManage, modifier = Modifier.tvFocusRing()) { Text("Gestionar") }
        }
    )
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

    BackHandler { onClose() }
    // Con el mando, el foco arranca en play/pausa: es lo que se busca al entrar
    val playFocus = rememberTvFocus()
    LaunchedEffect(Unit) {
        if (!Tv.isTv) return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        runCatching { playFocus.requestFocus() }
    }

    Column(
        Modifier.fillMaxSize().background(Bg).padding(if (Tv.isTv) Tv.overscan else 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹ Volver a la app", color = Accent,
                modifier = Modifier.tvClickable(RoundedCornerShape(8.dp)) { onClose() }.padding(6.dp)
            )
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
            OutlinedButton(
                modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                onClick = { cp?.let { p -> runCatching { p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)) } } }
            ) { Text("⏪ 10s") }
            Button(
                modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp), focusRequester = playFocus),
                onClick = { cp?.let { p -> runCatching { if (p.isPlaying) p.pause() else p.play() } } }
            ) { Text(if (playing) "⏸ Pausa" else "▶ Reproducir") }
            OutlinedButton(
                modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)),
                onClick = { cp?.let { p -> runCatching { p.seekTo(p.currentPosition + 10_000) } } }
            ) { Text("10s ⏩") }
        }

        Text(
            "Puedes volver a la app y elegir otra película: se enviará a esta misma TV.",
            style = MaterialTheme.typography.labelSmall, color = Muted
        )

        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { CastManager.stop(); onClose() },
                modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
            ) { Text("⏹ Parar") }
            OutlinedButton(
                onClick = { CastManager.disconnect(); onClose() },
                modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
            ) { Text("Desconectar TV") }
        }
    }
}

@Composable
fun PosterCard(t: Tmdb.Title, width: Int = if (Tv.isTv) 165 else 120, onClick: () -> Unit) {
    val watched = WatchStore.isWatchedTitle(t.type, t.tmdbId)
    Column(Modifier.width(width.dp).tvClickable(RoundedCornerShape(12.dp), onClick = onClick)) {
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
    Column(
        Modifier.width(if (Tv.isTv) 165.dp else 120.dp)
            .tvClickable(RoundedCornerShape(12.dp), onClick = onClick)
    ) {
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
    // Las recomendaciones son del TIPO elegido arriba: con el filtro en Series no
    // se recomiendan películas. Depende de `type`, así que se recalculan al
    // cambiar de pestaña.
    LaunchedEffect(type, WatchStore.list.size, Sync.favorites.size) {
        recs.clear()
        if (!Tmdb.hasKey) return@LaunchedEffect
        val seeds = (
            WatchStore.seeds(type) + Sync.favorites.filter { it.type == type }.map { it.tmdbId to it.type }
            ).distinctBy { it.first }.take(6)
        if (seeds.isEmpty()) return@LaunchedEffect
        Tmdb.recommendations(seeds) { list ->
            // Red de seguridad: que no se cuele nada del otro tipo
            onMain { recs.clear(); recs.addAll(list.filter { it.type == type }) }
        }
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
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = if (Tv.isTv) 4.dp else 12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
    ) {
        // Aviso de nueva versión (auto-actualización)
        Update.available?.let { up ->
            item {
                Card(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        .tvClickable(RoundedCornerShape(12.dp), scale = 1.02f) { Update.downloadAndInstall(ctx) },
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
                if (Sync.googleEnabled) {
                    if (Sync.email == null) {
                        OutlinedButton(
                            onClick = { Sync.signInIntent()?.let { launcher.launch(it) } },
                            modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
                        ) { Text("Entrar con Google") }
                    } else {
                        TextButton(onClick = { Sync.signOut() }, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) { Text("👤 Salir") }
                    }
                }
            }
            if (Sync.enabled && Sync.email != null) {
                Text("Sincronizado: ${Sync.email}", style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = type == "movie", onClick = { onType("movie") },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp))
                ) { Text("Películas") }
                SegmentedButton(
                    selected = type == "series", onClick = { onType("series") },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp))
                ) { Text("Series") }
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
                        AssistChip(
                            onClick = { browse = "genre:$gid" to gname }, label = { Text(gname) },
                            modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp))
                        )
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
        val cont = WatchStore.continueWatching(type)
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
        // Mi lista (favoritos sincronizados con la cuenta), del tipo elegido
        val favs = Sync.favorites.filter { it.type == type }
        if (favs.isNotEmpty()) item {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("Mi lista", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(favs.size) { i ->
                        val f = favs[i]
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
                            Modifier.width(if (Tv.isTv) 165.dp else 120.dp).aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(10.dp)).background(Surface1)
                                .tvClickable(RoundedCornerShape(10.dp)) { browse = prov to row.name },
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
    BackHandler { onBack() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.tvFocusRing()) { Text("← Volver") }
            Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (Tv.isTv) 150.dp else 110.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items.forEach { t -> item { PosterCard(t, width = if (Tv.isTv) 150 else 110) { onOpen(t) } } }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (!end) Button(onClick = { loadNext() }, enabled = !loading, modifier = Modifier.tvFocusRing()) {
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
                    SegmentedButton(
                        selected = type == "movie", onClick = { type = "movie" },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp))
                    ) { Text("Películas") }
                    SegmentedButton(
                        selected = type == "series", onClick = { type = "series" },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp))
                    ) { Text("Series") }
                }
                Button(onClick = { go() }, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) { Text("Buscar") }
            }
            if (status.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(status, color = Muted, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(10.dp))
        }
        items(results.size) { i ->
            Row(
                Modifier.fillMaxWidth()
                    .tvRow(RoundedCornerShape(10.dp)) { onOpen(results[i]) }
                    .padding(vertical = 6.dp, horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AsyncImage(model = results[i].poster, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.width(if (Tv.isTv) 90.dp else 70.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
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
                    Text("Esta compilación no lleva Firebase, así que no hay cuentas ni sincronización.", color = Muted, style = MaterialTheme.typography.bodySmall)
                } else if (Sync.email == null) {
                    Text(
                        "Inicia sesión para tener tus perfiles, favoritos, historial y el token de " +
                            "Real-Debrid en todos tus dispositivos (y compartidos con la app del PC).",
                        color = Muted, style = MaterialTheme.typography.bodySmall
                    )
                    // --- Email y contraseña (sin depender de Google) ---
                    var mail by remember { mutableStateOf("") }
                    var pass by remember { mutableStateOf("") }
                    var showPass by remember { mutableStateOf(false) }
                    var authMsg by remember { mutableStateOf("") }
                    var authBusy by remember { mutableStateOf(false) }

                    OutlinedTextField(
                        value = mail, onValueChange = { mail = it.trim() },
                        label = { Text("Email") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pass, onValueChange = { pass = it },
                        label = { Text("Contraseña (mínimo ${Sync.MIN_PASS})") }, singleLine = true,
                        visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        trailingIcon = {
                            // Con el mando de la tele escribir a ciegas es horrible:
                            // que se pueda ver lo escrito.
                            IconButton(onClick = { showPass = !showPass }, modifier = Modifier.tvFocusRing()) {
                                Icon(
                                    if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showPass) "Ocultar" else "Ver"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    val done: (Boolean, String?) -> Unit = { ok, err ->
                        onMain { authBusy = false; authMsg = if (ok) "" else (err ?: "Error") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { authBusy = true; authMsg = "Entrando…"; Sync.signInEmail(mail, pass, done) },
                            enabled = !authBusy,
                            modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
                        ) { Text("Entrar") }
                        OutlinedButton(
                            onClick = { authBusy = true; authMsg = "Creando la cuenta…"; Sync.signUpEmail(mail, pass, done) },
                            enabled = !authBusy,
                            modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
                        ) { Text("Crear cuenta") }
                        TextButton(
                            onClick = {
                                authBusy = true
                                Sync.resetPassword(mail) { ok, msg ->
                                    onMain { authBusy = false; authMsg = msg ?: if (ok) "Correo enviado." else "Error" }
                                }
                            },
                            enabled = !authBusy,
                            modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
                        ) { Text("Olvidé la contraseña") }
                    }
                    if (authMsg.isNotBlank()) Text(
                        authMsg, color = Color(0xFFFBBF24), style = MaterialTheme.typography.labelSmall
                    )
                    if (Sync.googleEnabled) {
                        HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 4.dp))
                        Button(
                            onClick = { Sync.signInIntent()?.let { launcher.launch(it) } },
                            modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
                        ) { Text("Entrar con Google") }
                        Text(
                            "Las dos formas valen; si ya entrabas con Google, sigue usando ese botón " +
                                "para encontrar tus datos de siempre.",
                            color = Muted, style = MaterialTheme.typography.labelSmall
                        )
                    }
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
                    // Se lee al entrar, no al arrancar la app: son dos peticiones
                    // y aquí es donde se miran.
                    LaunchedEffect(RealDebrid.token) {
                        if (RealDebrid.info == null) RealDebrid.refreshInfo()
                    }
                    Text("⚡ Conectado${RealDebrid.account?.let { " · $it" } ?: ""}", color = Color(0xFF34D399), style = MaterialTheme.typography.bodyMedium)
                    RdAccountInfo()
                    Text(
                        if (Sync.email != null)
                            "Vinculado a la cuenta ${Sync.email}: el mismo Real-Debrid en todos tus " +
                                "dispositivos con esa cuenta. Al cerrar sesión se queda con la cuenta, " +
                                "no en este móvil."
                        else
                            "Guardado solo en este móvil (cifrado). Inicia sesión en Ajustes → Cuenta " +
                                "para tenerlo en todos tus dispositivos.",
                        color = Muted, style = MaterialTheme.typography.labelSmall
                    )
                    OutlinedButton(
                        onClick = {
                            RealDebrid.disconnect()
                            // También en la nube: si no, al arrancar se volvería a
                            // bajar y parecería que no se ha desconectado.
                            if (Sync.email != null) Sync.clearAccountRdToken()
                        },
                        modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
                    ) { Text("Desconectar") }
                } else {
                    Text("⚠️ Real-Debrid es imprescindible: la app no descarga por BitTorrent, todo el vídeo llega por streaming directo desde los servidores de RD. Pega tu token para empezar. Se comparte con tu cuenta si has entrado con Google.", color = Color(0xFFFBBF24), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = rdInput, onValueChange = { rdInput = it }, label = { Text("Token de Real-Debrid") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        rdStatus = "Validando…"
                        val tk = rdInput.trim()
                        RealDebrid.connect(tk) { ok, msg ->
                            onMain {
                                rdStatus = if (ok) {
                                    // Se sube a la cuenta: el token va con ella, no
                                    // con el aparato.
                                    if (Sync.email != null) {
                                        Sync.saveAccountRdToken(tk)
                                        "Conectado como $msg · vinculado a ${Sync.email}"
                                    } else "Conectado como $msg"
                                } else (msg ?: "Error")
                            }
                        }
                    }, enabled = rdInput.isNotBlank(), modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) { Text("Conectar") }
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        Prefs.PLAYER_APP to "Reproductor de la app",
                        Prefs.PLAYER_VLC to "Siempre VLC",
                        Prefs.PLAYER_ASK to "Preguntar",
                        Prefs.PLAYER_EXTERNAL to "Otra app"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = Prefs.playerMode == mode,
                            onClick = { Prefs.savePlayerMode(mode) },
                            label = { Text(label) },
                            modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp))
                        )
                    }
                }
                val hasVlc = ExternalPlayer.vlcInstalled(ctx)
                if (Prefs.playerMode == Prefs.PLAYER_VLC && !hasVlc) {
                    Text(
                        "⚠️ VLC no está instalado en este dispositivo, así que no se puede usar.",
                        color = Color(0xFFFBBF24), style = MaterialTheme.typography.labelSmall
                    )
                    OutlinedButton(
                        onClick = { ExternalPlayer.installVlc(ctx) },
                        modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
                    ) { Text("Instalar VLC") }
                }
                Text(
                    "«Siempre VLC» va directo a VLC sin preguntar: es el que mejor se lleva con " +
                        "los MKV y el audio DTS/TrueHD. A cambio se pierden el «continuar " +
                        "viendo» y el siguiente episodio automático, que son del reproductor de " +
                        "la app. «Otra app» abre el diálogo para elegir.",
                    color = Muted, style = MaterialTheme.typography.labelSmall
                )

                HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Emitir a la TV con VLC", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (Prefs.castWithVlc) "Activado: al pulsar Ver con una TV elegida, el vídeo se abre en VLC."
                            else "Desactivado: emite la propia app (Chromecast).",
                            color = Muted, style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Switch(
                        checked = Prefs.castWithVlc,
                        onCheckedChange = { Prefs.saveCastWithVlc(it) },
                        modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
                    )
                }
                Text(
                    "VLC transcodifica en el móvil, así que se traga cualquier MKV con Dolby o " +
                        "DTS que el Chromecast rechaza. Dos peajes: el vídeo pasa por el teléfono " +
                        "(que tiene que quedarse encendido y en la misma WiFi) y el último paso lo " +
                        "das tú, pulsando el icono de emitir DENTRO de VLC — Android no permite " +
                        "elegirle el dispositivo desde fuera. Al activarlo, la app cierra su " +
                        "propia sesión de Chromecast para no pelearse con VLC por la TV.",
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
                            "CaViPlay en Ajustes de Android → Red → Ahorro de datos → Datos sin restricción.",
                        color = Color(0xFFFBBF24), style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(
                    "El cambio afecta a las descargas NUEVAS: el sistema conserva la condición " +
                        "de red con la que se encoló cada una.",
                    color = Muted, style = MaterialTheme.typography.labelSmall
                )

                HorizontalDivider(color = Color(0x22FFFFFF), modifier = Modifier.padding(vertical = 4.dp))

                // --- Dónde se guardan ---
                var folderMsg by remember { mutableStateOf("") }
                val pickFolder = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    // Sin el permiso PERSISTENTE, la carpeta deja de valer al
                    // reiniciar la app y las descargas fallarían al día siguiente.
                    val ok = runCatching {
                        ctx.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }.isSuccess
                    if (ok) { Prefs.saveDownloadTree(uri.toString()); folderMsg = "" }
                    else folderMsg = "Android no dio permiso permanente sobre esa carpeta. Prueba con otra."
                }
                Text("Carpeta de descargas", style = MaterialTheme.typography.bodyMedium)
                Text(Downloads.folderLabel(), color = Color(0xFF34D399), style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            folderMsg = ""
                            // En una Android TV puede no haber selector de ficheros
                            runCatching { pickFolder.launch(null) }.onFailure {
                                folderMsg = "Este dispositivo no tiene selector de carpetas " +
                                    "(pasa en algunas teles). Se seguirá usando la carpeta de la app."
                            }
                        },
                        modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
                    ) { Text("Elegir carpeta…") }
                    if (Prefs.downloadTree.isNotBlank()) OutlinedButton(
                        onClick = { Prefs.saveDownloadTree(""); folderMsg = "" },
                        modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
                    ) { Text("Usar la de la app") }
                }
                if (folderMsg.isNotBlank()) Text(
                    folderMsg, color = Color(0xFFFBBF24), style = MaterialTheme.typography.labelSmall
                )
                Text(
                    if (Downloads.usingAppFolder)
                        "Ahora se guardan en la carpeta privada de la app " +
                            "(Android/data/com.carbaxo.torrentbox/files/Movies): no la ve la galería y " +
                            "se borra si desinstalas la app. Elige otra carpeta (Descargas, Películas, " +
                            "la tarjeta SD…) para que los vídeos queden accesibles y sobrevivan."
                    else
                        "Los vídeos se guardan ahí, visibles para la galería y otras apps, y no se " +
                            "borran al desinstalar. Afecta a las descargas NUEVAS; las que ya están " +
                            "siguen donde estaban. Si la carpeta deja de estar disponible (tarjeta " +
                            "fuera), la descarga cae a la carpeta de la app en vez de fallar.",
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
fun DownloadsScreen(onPlayUrl: (String) -> Unit) {
    val ctx = LocalContext.current
    val all = Downloads.list
    // Separa la ACTIVIDAD (en curso) de lo que ya está LISTO PARA VER
    val ready = all.filter { it.done }
    val active = all.filter { !it.done }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = if (Tv.isTv) 4.dp else 12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
    ) {
        item {
            Text("Descargas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            // Espacio libre en la carpeta donde se guardan
            var space by remember { mutableStateOf("") }
            LaunchedEffect(all.size, Prefs.downloadTree) {
                space = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
                        // Con carpeta propia el hueco de la carpeta de la app no dice
                        // nada útil: mejor decir dónde se guardan.
                        if (Downloads.usingAppFolder) "Libre: ${Search.humanSize(dir.usableSpace)}"
                        else "Se guardan en: ${Downloads.folderLabel()}"
                    }.getOrDefault("")
                }
            }
            Text(space, style = MaterialTheme.typography.labelSmall, color = Muted)
            // Si el sistema bloquea los datos en segundo plano, la descarga se
            // queda parada sin explicacion: mejor decirlo aqui.
            if (active.isNotEmpty() && RdDownloads.dataSaverBlocks(ctx)) {
                Text(
                    "⚠️ El «Ahorro de datos» de Android puede tener parada la descarga con " +
                        "datos móviles. Excluye CaViPlay en Ajustes de Android → Red → " +
                        "Ahorro de datos, o conéctate a WiFi.",
                    color = Color(0xFFFBBF24), style = MaterialTheme.typography.labelSmall
                )
            }
            if (all.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Aún no hay descargas. Abre un título, busca fuentes y pulsa ⬇ Descargar.\n" +
                        "Se pueden pausar y continuar, y siguen aunque cierres la app.",
                    color = Muted, style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // --- Añadir un magnet/enlace a mano + estado de la cuenta de RD ---
        item { Spacer(Modifier.height(10.dp)); RdCloudSection(onPlayUrl = onPlayUrl) }

        // --- Descargando / pausadas (lo que requiere atención va primero) ---
        if (active.isNotEmpty()) {
            item {
                Text(
                    "⏳ En curso", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                )
            }
            items(active.size) { i ->
                val d = active[i]
                DownloadCard(
                    d = d,
                    onPlay = { Downloads.uriFor(ctx, d)?.let(onPlayUrl) },
                    onPause = { Downloads.pause(ctx, d.id) },
                    onResume = { Downloads.resume(ctx, d.id) },
                    onRemove = { Downloads.remove(ctx, d.id) }
                )
            }
        }

        // --- Listas para ver ---
        if (ready.isNotEmpty()) {
            item {
                Text(
                    "▶ Listas para ver", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = Color(0xFF34D399),
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                )
            }
            items(ready.size) { i ->
                val d = ready[i]
                DownloadCard(
                    d = d,
                    onPlay = { Downloads.uriFor(ctx, d)?.let(onPlayUrl) },
                    onPause = {}, onResume = {},
                    onRemove = { Downloads.remove(ctx, d.id) }
                )
            }
        }
    }
}

/**
 * Una descarga: progreso, velocidad y los botones de pausar/continuar.
 *
 * "Continuar" no reempieza: la descarga se reanuda desde el byte que haya en
 * disco, y si el enlace de Real-Debrid ha caducado se pide otro por dentro.
 */
@OptIn(ExperimentalLayoutApi::class)   // FlowRow (botones de la descarga)
@Composable
private fun DownloadCard(
    d: Downloads.Job,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(d.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)

            if (d.done) LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
            else if (d.known) LinearProgressIndicator(progress = { d.pct }, modifier = Modifier.fillMaxWidth())
            else if (d.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            Text(
                when {
                    d.done -> "✓ Disponible sin conexión  ·  ${Search.humanSize(d.total)}"
                    d.failed -> d.error.ifBlank { "Error en la descarga" }
                    d.paused -> "⏸ Pausada  ·  ${(d.pct * 100).toInt()}%  ·  ${Search.humanSize(d.bytes)}" +
                        (if (d.known) " / ${Search.humanSize(d.total)}" else "")
                    d.known -> "${(d.pct * 100).toInt()}%  ·  ${Search.humanSize(d.bytes)} / ${Search.humanSize(d.total)}" +
                        (if (d.speed > 0) "  ·  ${Search.humanSize(d.speed)}/s" else "")
                    d.bytes > 0 -> "Descargando… ${Search.humanSize(d.bytes)}"
                    else -> "En cola…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    d.done -> Color(0xFF34D399)
                    d.failed -> Color(0xFFFBBF24)
                    else -> Muted
                }
            )
            // Un mensaje informativo mientras corre (p. ej. renovando el enlace)
            if (!d.failed && !d.done && d.error.isNotBlank()) Text(
                d.error, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFBBF24)
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (d.done) Button(
                    onClick = onPlay,
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
                ) { Text("▶ Ver") }
                if (d.running || d.state == Downloads.QUEUED) OutlinedButton(
                    onClick = onPause,
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
                ) { Text("⏸ Pausar") }
                if (d.paused || d.failed) Button(
                    onClick = onResume,
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
                ) { Text("▶ Continuar") }
                // Un fichero a medias se puede ir viendo: el reproductor aguanta
                if (!d.done && d.bytes > 0) OutlinedButton(
                    onClick = onPlay,
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
                ) { Text("Ver lo bajado") }
                OutlinedButton(
                    onClick = onRemove,
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
                ) { Text("Borrar") }
            }
        }
    }
}


/**
 * Cuenta y límites de Real-Debrid, leídos de su propia API (`/user` y
 * `/torrents/activeCount`).
 *
 * Los números no se estiman: dependen del plan y de los puntos de fidelidad, y
 * el de los huecos de torrent es el que explica el fallo más desconcertante —al
 * llenarse, añadir un magnet deja de funcionar sin motivo aparente—.
 */
@Composable
private fun RdAccountInfo() {
    val a = RealDebrid.info
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (a == null) {
            Text(
                if (RealDebrid.infoLoading) "Leyendo la cuenta…"
                else RealDebrid.infoError.ifBlank { "" },
                color = if (RealDebrid.infoError.isNotBlank()) Color(0xFFFBBF24) else Muted,
                style = MaterialTheme.typography.labelSmall
            )
        } else {
            if (a.premium) {
                Text(
                    "⏳ Premium: quedan ${a.days} días" +
                        (if (a.expiration.isNotBlank()) "  ·  hasta el ${a.expiresPretty}" else ""),
                    color = if (a.days <= 7) Color(0xFFFBBF24) else Muted,
                    style = MaterialTheme.typography.labelSmall
                )
            } else {
                Text(
                    "⚠️ Esta cuenta NO es premium: Real-Debrid no dará enlaces.",
                    color = Color(0xFFFBBF24), style = MaterialTheme.typography.labelSmall
                )
            }
            Text("🎟 Puntos de fidelidad: ${a.points}", color = Muted, style = MaterialTheme.typography.labelSmall)
            if (a.slotsKnown) {
                Text(
                    "📥 Torrents activos: ${a.slotsUsed} / ${a.slotsLimit}",
                    color = if (a.slotsFull) Color(0xFFFBBF24) else Muted,
                    style = MaterialTheme.typography.labelSmall
                )
                LinearProgressIndicator(
                    progress = { (a.slotsUsed.toFloat() / a.slotsLimit).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp)
                )
                if (a.slotsFull) Text(
                    "Sin huecos libres: hasta que acaben o borres alguno, Real-Debrid " +
                        "rechazará los magnets nuevos. Se quitan en Descargas → En tu Real-Debrid.",
                    color = Color(0xFFFBBF24), style = MaterialTheme.typography.labelSmall
                )
            }
            if (RealDebrid.infoError.isNotBlank()) Text(
                RealDebrid.infoError, color = Color(0xFFFBBF24), style = MaterialTheme.typography.labelSmall
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { RealDebrid.refreshInfo() },
                enabled = !RealDebrid.infoLoading,
                modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
            ) { Text("Actualizar datos") }
            if (RealDebrid.infoLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * Añadir un magnet o un enlace a Real-Debrid A MANO, y ver qué está haciendo RD
 * con los torrents de la cuenta.
 *
 * Es la salida a los dos fallos habituales de los enlaces de los buscadores, que
 * no dependen de la app: que RD todavía no tenga el torrent en su caché (lo baja
 * a sus servidores, y eso tarda) o que el archivo ya se haya borrado de ella. En
 * los dos casos la solución es meter el magnet en la cuenta y esperar a que RD lo
 * tenga; desde aquí se ve el progreso real en vez de un mensaje de error.
 */
@Composable
private fun RdCloudSection(onPlayUrl: (String) -> Unit) {
    val ctx = LocalContext.current
    // rememberSaveable: la sección vive en un item de la lista y se destruye al
    // salir de pantalla; el magnet pegado no se puede perder por eso.
    var input by rememberSaveable { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf<List<RealDebrid.Torrent>>(emptyList()) }
    var listErr by remember { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Elegir archivo dentro de un pack: (archivos, ¿descargar o ver?)
    var picker by remember { mutableStateOf<Pair<List<RealDebrid.RdFile>, Boolean>?>(null) }

    fun refresh() {
        if (!RealDebrid.configured) return
        RealDebrid.torrents { l, err ->
            onMain { if (l != null) { list = l; listErr = "" } else listErr = err ?: "" }
        }
    }

    /** Del enlace de RD a la URL directa, y a ver o a descargar. */
    fun useLink(link: String, download: Boolean) {
        busy = true; msg = "Preparando el enlace…"
        RealDebrid.unrestrict(link) { url, name, err ->
            onMain {
                busy = false
                when {
                    url == null -> msg = err ?: "No se pudo preparar el enlace."
                    download -> {
                        RdDownloads.enqueue(ctx, url, name ?: "video")
                        msg = "⬇ Descarga encolada: ${name ?: ""}"
                    }
                    else -> { msg = ""; onPlayUrl(url) }
                }
            }
        }
    }

    /** Abre un torrent ya listo: si trae varios archivos, deja elegir. */
    fun openTorrent(t: RealDebrid.Torrent, download: Boolean) {
        busy = true; msg = "Mirando qué archivos tiene…"
        RealDebrid.torrentFiles(t.id) { files, err ->
            onMain {
                busy = false
                when {
                    files == null -> msg = err ?: "No se pudo leer el torrent."
                    files.size == 1 -> useLink(files[0].link, download)
                    else -> { msg = ""; picker = files to download }
                }
            }
        }
    }

    fun add() {
        val v = input.trim()
        busy = true
        if (v.startsWith("magnet:", ignoreCase = true)) {
            msg = "Añadiendo el magnet a Real-Debrid…"
            RealDebrid.addMagnet(v) { id, err ->
                onMain {
                    busy = false
                    if (id == null) msg = err ?: "No se pudo añadir."
                    else {
                        input = ""; expanded = true
                        msg = "✅ Añadido. Real-Debrid lo está bajando a sus servidores; " +
                            "cuando ponga «listo» ya se puede ver."
                        refresh()
                    }
                }
            }
        } else {
            // Enlace de hoster (1fichier, Mega…): es el "Descargador" de la web de RD
            msg = "Preparando el enlace con Real-Debrid…"
            RealDebrid.unrestrict(v) { url, name, err ->
                onMain {
                    busy = false
                    if (url == null) msg = err ?: "No se pudo preparar el enlace."
                    else {
                        RdDownloads.enqueue(ctx, url, name ?: "video")
                        input = ""; msg = "⬇ Descarga encolada: ${name ?: ""}"
                    }
                }
            }
        }
    }

    // Un magnet llegado de fuera (o de un enlace que falló) entra por aquí
    LaunchedEffect(MagnetInbox.pending) {
        MagnetInbox.pending?.let {
            input = it; expanded = true
            msg = "Pegado. Pulsa «Añadir a Real-Debrid»."
            MagnetInbox.clear()
        }
    }

    // Refresco: rápido mientras RD esté trabajando, lento si no hay nada en marcha
    LaunchedEffect(RealDebrid.configured) {
        while (RealDebrid.configured) {
            refresh()
            kotlinx.coroutines.delay(if (list.any { it.working }) 4000L else 20000L)
        }
    }
    // Los huecos de torrent se miran aquí: es donde se liberan
    LaunchedEffect(RealDebrid.token, list.size) {
        if (RealDebrid.configured) RealDebrid.refreshInfo()
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Añadir a Real-Debrid", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (RealDebrid.configured) {
                    IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Refresh, "Actualizar") }
                }
            }
            if (!RealDebrid.configured) {
                Text(
                    "Conecta Real-Debrid en Ajustes para poder añadir magnets.",
                    color = Color(0xFFFBBF24), style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "Pega un magnet y Real-Debrid lo baja a sus servidores; luego se ve al " +
                        "instante. Sirve para lo que falla en la ficha: cuando RD todavía no " +
                        "lo tiene o el archivo se borró de su caché. Un enlace de hoster " +
                        "(1fichier, Mega…) se prepara y se descarga directamente.",
                    color = Muted, style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    label = { Text("magnet:?xt=… o un enlace") },
                    minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { add() }, enabled = input.isNotBlank() && !busy,
                        modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
                    ) {
                        Text("Añadir a Real-Debrid")
                    }
                    OutlinedButton(modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp)), onClick = {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        val t = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                        if (t.isNullOrBlank()) msg = "No hay nada copiado." else { input = t; msg = "" }
                    }) { Text("Pegar") }
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                if (msg.isNotBlank()) Text(msg, color = Muted, style = MaterialTheme.typography.labelSmall)

                // --- Lo que hay en la cuenta de RD ---
                Spacer(Modifier.height(2.dp))
                val working = list.count { it.working }
                Row(
                    Modifier.fillMaxWidth()
                        .tvClickable(RoundedCornerShape(8.dp), scale = 1f) { expanded = !expanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "En tu Real-Debrid (${list.size})" + if (working > 0) " · $working en marcha" else "",
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        if (expanded) "Ocultar" else "Mostrar"
                    )
                }
                RealDebrid.info?.let { a ->
                    if (a.slotsKnown) Text(
                        "Huecos de torrent: ${a.slotsUsed} / ${a.slotsLimit}" +
                            if (a.slotsFull) " — sin huecos: borra alguno para poder añadir" else "",
                        color = if (a.slotsFull) Color(0xFFFBBF24) else Muted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (listErr.isNotBlank()) Text(listErr, color = Color(0xFFFBBF24), style = MaterialTheme.typography.labelSmall)
                if (expanded) {
                    if (list.isEmpty() && listErr.isBlank()) {
                        Text("No hay nada en la cuenta.", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    list.forEach { t ->
                        RdCloudRow(
                            t = t,
                            onPlay = { openTorrent(t, false) },
                            onDownload = { openTorrent(t, true) },
                            onDelete = {
                                RealDebrid.deleteTorrent(t.id) { err ->
                                    onMain { if (err != null) msg = err; refresh() }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // --- Elegir archivo cuando el torrent trae varios (packs de temporada) ---
    picker?.let { (files, download) ->
        AlertDialog(
            onDismissRequest = { picker = null },
            title = { Text(if (download) "¿Cuál descargo?" else "¿Cuál pongo?") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    files.forEach { f ->
                        Text(
                            f.name + if (f.bytes > 0) "   ·   ${Search.humanSize(f.bytes)}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth()
                                .tvRow(RoundedCornerShape(8.dp)) { picker = null; useLink(f.link, download) }
                                .padding(vertical = 8.dp, horizontal = 6.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = null }) { Text("Cancelar") } }
        )
    }
}

/** Una línea de la lista de torrents de la cuenta de Real-Debrid. */
@Composable
private fun RdCloudRow(
    t: RealDebrid.Torrent,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(t.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val detail = buildList {
            add(RealDebrid.statusEs(t.status))
            if (t.working && t.status != "queued") add("${t.progress}%")
            if (t.speed > 0) add("${Search.humanSize(t.speed)}/s")
            if (t.status == "downloading" && t.seeders > 0) add("${t.seeders} semillas")
            if (t.bytes > 0) add(Search.humanSize(t.bytes))
            if (t.ready && t.links > 1) add("${t.links} archivos")
        }.joinToString("  ·  ")
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = if (t.ready) Color(0xFF34D399) else if (t.working) Muted else Color(0xFFFBBF24)
        )
        if (t.working && t.progress in 1..99) {
            LinearProgressIndicator(
                progress = { t.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(3.dp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (t.ready) {
                TextButton(onClick = onPlay, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) { Text("▶ Ver") }
                TextButton(onClick = onDownload, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) { Text("⬇ Descargar") }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDelete, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) {
                Icon(Icons.Filled.Delete, "Quitar de Real-Debrid", tint = Muted)
            }
        }
        HorizontalDivider(color = Color(0x22FFFFFF))
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
    val filtered = when (qualityFilter) {
        "all" -> byEngine
        Search.QUALITY_OTHER -> byEngine.filter { it.quality == Search.QUALITY_OTHER }
        else -> byEngine.filter { it.quality == qualityFilter }
    }
    // Los que ya están en la cuenta de RD suben al principio. sortedByDescending
    // es estable, así que dentro de cada grupo se mantiene el orden por motor,
    // idioma y semillas.
    val shown = filtered.sortedByDescending { it.infoHash in RealDebrid.cachedHashes }

    // Qué enlaces están YA en la cuenta de Real-Debrid: esos se reproducen al
    // instante, así que van arriba. Solo se puede saber de la propia cuenta: RD
    // desactivó instantAvailability, que era lo que decía si algo estaba en su
    // caché global.
    LaunchedEffect(RealDebrid.token) { RealDebrid.refreshCached() }
    val cached = RealDebrid.cachedHashes
    val instant = shown.count { it.infoHash in cached }

    // Estado de la ventana flotante "Cargando…" / "Preparando la descarga"
    var prep by remember { mutableStateOf<Prep?>(null) }

    /**
     * Pide el enlace a Real-Debrid mostrando un diálogo con el progreso. Si RD
     * aún está bajando el torrent a sus servidores, reintenta solo (antes había
     * que volver a pulsar el botón, y parecía que no respondía).
     */
    fun prepare(r: Search.Result, download: Boolean, attempt: Int = 0) {
        if (attempt == 0) {
            prep = Prep(
                download,
                if (download) "Pidiendo el enlace a Real-Debrid…" else "Preparando el vídeo…",
                magnet = r.magnet
            )
        }
        RealDebrid.streamMagnet(r.magnet) { url, fname, err, progress ->
            onMain {
                val cur = prep ?: return@onMain     // cancelado por el usuario
                when {
                    url != null -> {
                        prep = null
                        if (download) {
                            RdDownloads.enqueue(ctx, url, fname ?: title.title, magnet = r.magnet)
                            onOpenDownloads()
                        } else onPlayUrl(url, buildCtx().withSource(r))
                    }
                    progress != null && attempt < 25 -> {
                        // Explicar QUÉ está pasando: no es que el archivo no exista,
                        // es que RD no lo tenía cacheado y lo está bajando él. Y eso
                        // depende de las semillas del torrent.
                        prep = cur.copy(
                            atRd = true,
                            msg = "Real-Debrid no lo tenía en caché y lo está bajando a sus " +
                                "servidores: ${progress}%. Cuando acabe, se verá al instante." +
                                if (r.seeders == 0)
                                    "\n\n⚠️ Este enlace no declara semillas. Si nadie lo comparte, " +
                                        "puede no avanzar: mejor prueba otro con semillas."
                                else ""
                        )
                        onMainDelayed(4000) { prepare(r, download, attempt + 1) }
                    }
                    progress != null -> prep = cur.copy(
                        error = "Real-Debrid sigue bajándolo a sus servidores (${progress}%). " +
                            "No se pierde: sigue en tu cuenta y el progreso se ve en Descargas."
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
                        },
                        modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp))
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
                        modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp)),
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
            Row(
                Modifier.fillMaxWidth()
                    .tvClickable(RoundedCornerShape(8.dp), scale = 1f) { linksExpanded = !linksExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Enlaces (${shown.size})" +
                        (if (instant > 0) " · ⚡$instant al instante" else "") +
                        (if (label.isNotBlank()) " · $label" else ""),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                Icon(if (linksExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (linksExpanded) "Plegar" else "Desplegar")
            }
        }
        if (linksExpanded) shown.forEach { r ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Ya está en la cuenta: no hay que esperar a que RD lo baje
                    if (r.infoHash in cached) Text(
                        "⚡ Ya en tu Real-Debrid · se reproduce al instante",
                        color = Color(0xFF34D399), style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(r.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildList {
                            if (r.engineLabel.isNotBlank()) add("⚙ ${r.engineLabel}")
                            add("${Lang.flag(r.lang)} ${Lang.label(r.lang)}")
                            if (r.quality != Search.QUALITY_OTHER) add(r.quality)
                            // "0 seeders" no significa que esté muerto: significa que
                            // el addon no manda el dato. Mostrarlo hacía parecer
                            // inservibles todos los enlaces de Peerflix.
                            if (r.seeders > 0) add("▲ ${r.seeders} seeders")
                            // El tamaño no siempre lo da el addon: si no, no se pone
                            if (r.sizeBytes > 0) add("💾 ${Search.humanSize(r.sizeBytes)}")
                        }.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall, color = Muted
                    )
                    // Lo demás que diga el addon (fuente, grupo, códec…): es lo único
                    // que distingue dos enlaces cuando no manda el nombre del fichero.
                    if (r.info.isNotBlank()) Text(
                        r.info, style = MaterialTheme.typography.labelSmall,
                        color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    if (RealDebrid.configured) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (CastManager.connected && !Prefs.castWithVlc) {
                                        // Con TV conectada va directo a la TV; el
                                        // CastManager muestra el progreso y elige la
                                        // versión con audio compatible.
                                        onCastMagnet(r.magnet, buildCtx().withSource(r))
                                    } else {
                                        // Con "emitir con VLC" hace falta el enlace
                                        // resuelto, así que pasa por Real-Debrid igual
                                        // que al ver en el móvil.
                                        prepare(r, download = false)
                                    }
                                },
                                modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
                            ) {
                                Text(
                                    when {
                                        CastManager.connected && Prefs.castWithVlc -> "📺 Ver en la TV (VLC)"
                                        CastManager.connected -> "📺 Ver en la TV"
                                        else -> "▶ Ver"
                                    }
                                )
                            }
                            OutlinedButton(
                                onClick = { prepare(r, download = true) },
                                modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))
                            ) { Text("⬇ Descargar") }
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
            },
            // Si RD no pudo con el enlace (no lo tiene cacheado, o el archivo se
            // borró), se puede meter el magnet en la cuenta y esperar a que lo baje.
            dismissButton = {
                when {
                    p.error != null && p.magnet.isNotBlank() -> TextButton(onClick = {
                        MagnetInbox.offer(p.magnet)
                        prep = null
                        onOpenDownloads()
                    }, modifier = Modifier.tvFocusRing()) { Text("Añadirlo a Real-Debrid") }
                    // Mientras RD lo baja no hay que quedarse mirando: sigue solo
                    p.atRd -> TextButton(onClick = {
                        prep = null
                        onOpenDownloads()
                    }, modifier = Modifier.tvFocusRing()) { Text("Ver progreso") }
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
                        // De cada campo se queda el que informa: un motor puede dar
                        // el nombre del fichero y el otro solo su etiqueta.
                        name = Search.bestName(prev.name, r.name),
                        sizeBytes = maxOf(prev.sizeBytes, r.sizeBytes),
                        seeders = maxOf(prev.seeders, r.seeders),
                        quality = if (prev.quality != Search.QUALITY_OTHER) prev.quality else r.quality,
                        lang = prev.lang ?: r.lang,
                        info = prev.info.ifBlank { r.info }
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

    // En series NO se despliega nada solo. Antes se abría el primer episodio sin
    // ver y sus enlaces empujaban el resto de la temporada hacia abajo: había que
    // bajar un montón para llegar al episodio 2. Ahora la lista se ve entera y los
    // enlaces salen al tocar el episodio que quieras.
    // Al cambiar de temporada se cierra lo que hubiera abierto.
    LaunchedEffect(selSeason) { expandedEpisode = -1 }

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

    BackHandler { onBack() }
    // En la tele el foco empieza en "Volver": arriba a la izquierda, como en
    // cualquier app de TV, y desde ahí se baja al contenido.
    val backFocus = rememberTvFocus()
    LaunchedEffect(title.tmdbId) {
        if (!Tv.isTv) return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        runCatching { backFocus.requestFocus() }
    }

    val dt = detail

    // --- Piezas de la ficha, para poder colocarlas de dos formas distintas ---

    val backButton: @Composable () -> Unit = {
        TextButton(
            onClick = onBack,
            modifier = Modifier.padding(8.dp).tvFocusRing(focusRequester = backFocus)
        ) { Text("← Volver", color = Color.White) }
    }

    val heading: @Composable () -> Unit = {
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
    }

    // Tráiler + favorito. En la tele van en columna bajo la carátula; en el móvil,
    // en fila con el resto del contenido.
    val actions: @Composable (Boolean) -> Unit = { stacked ->
        val trailerBtn: @Composable () -> Unit = {
            trailerKey?.let { k ->
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF87171)),
                    onClick = { openTrailer(k) },
                    modifier = (if (stacked) Modifier.fillMaxWidth() else Modifier)
                        .tvFocusRing(RoundedCornerShape(20.dp))
                ) { Text("🎬 Tráiler") }
            }
        }
        val favBtn: @Composable () -> Unit = {
            if (Sync.enabled && Sync.email != null) {
                val fid = "tmdb:${title.tmdbId}"
                OutlinedButton(
                    onClick = { Sync.toggleFavorite(title) },
                    modifier = (if (stacked) Modifier.fillMaxWidth() else Modifier)
                        .tvFocusRing(RoundedCornerShape(20.dp))
                ) {
                    Text(if (Sync.isFav(fid)) "❤ En Mi lista" else "🤍 Añadir a Mi lista")
                }
            }
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { trailerBtn(); favBtn() }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { trailerBtn(); favBtn() }
        }
    }

    // Fuentes: película, o serie organizada por temporadas/episodios
    val sourcesBlock: @Composable () -> Unit = {
        if (dt != null && dt.type == "series" && dt.seasons.isNotEmpty()) {
            Text("Temporadas", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dt.seasons.forEach { s ->
                    FilterChip(
                        selected = selSeason == s.season,
                        onClick = { selSeason = s.season; expandedEpisode = -1 },
                        label = { Text("T${s.season} · ${s.episodes} ep.") },
                        modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp))
                    )
                }
            }
            if (episodes.isNotEmpty()) Text(
                "Toca un episodio para ver sus enlaces.",
                color = Muted, style = MaterialTheme.typography.labelSmall
            )
            selSeason?.let { sn ->
                // Sin "temporada completa": Peerflix y Torrentio dan enlaces
                // por episodio (los packs de temporada salen entre ellos).
                episodes.forEach { ep ->
                    val open = expandedEpisode == ep.episode
                    Card(
                        Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(12.dp), scale = 1.02f) {
                            if (open) {
                                // Volver a tocarlo lo cierra: así se sigue
                                // navegando la temporada sin estorbos.
                                expandedEpisode = -1
                            } else {
                                expandedEpisode = ep.episode
                                runSearch("${dt.title} · T${sn}E${ep.episode} · ${ep.name}", sn, ep.episode)
                            }
                        },
                        colors = CardDefaults.cardColors(containerColor = Surface1)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            val seen = WatchStore.isWatchedEpisode(title.tmdbId, sn, ep.episode)
                            Text(
                                (if (seen) "✓ " else "") + "${ep.episode}. ${ep.name}" + (if (open) "  ▾" else "  ▸"),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (seen) Color(0xFF34D399) else MaterialTheme.colorScheme.onSurface
                            )
                            if (ep.overview.isNotBlank()) Text(ep.overview, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    // Enlaces JUSTO debajo del episodio elegido
                    if (open) {
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
                    modifier = Modifier
                        .tvClickable(RoundedCornerShape(8.dp)) { expandedEpisode = -1; loadSources(dt) }
                        .padding(6.dp)
                )
            }
            SourcesSection(sources, loadingSources, sourcesLabel, title, ctx, { buildCtx() }, onPlayUrl, onCastMagnet, onOpenDownloads)
        }
    }

    if (Tv.isTv) {
        // TELE (al estilo de Stremio para TV): la carátula ENTERA a la izquierda
        // con sus acciones debajo, y a la derecha la ficha y los enlaces. Así los
        // enlaces se ven de entrada, en vez de quedar por debajo de un banner que
        // se comía la pantalla y obligaba a bajar mucho.
        Row(
            Modifier.fillMaxSize().padding(
                start = Tv.overscan, end = Tv.overscan, top = 10.dp, bottom = 10.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(
                Modifier.width(210.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                backButton()
                AsyncImage(
                    model = title.poster,
                    contentDescription = title.title,
                    // Fit y no Crop: la carátula se ve completa, sin recortar
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))
                )
                actions(true)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                heading()
                sourcesBlock()
                Spacer(Modifier.height(24.dp))
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box {
                AsyncImage(
                    model = dt?.backdrop ?: title.poster,
                    contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(220.dp)
                )
                backButton()
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                heading()
                actions(false)
                sourcesBlock()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
