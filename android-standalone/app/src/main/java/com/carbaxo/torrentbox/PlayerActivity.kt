package com.carbaxo.torrentbox

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var castPlayer: CastPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var toast: TextView       // feedback de gestos (volumen/brillo/salto)
    private lateinit var nextBtn: TextView     // "Siguiente episodio"
    private lateinit var overlay: LinearLayout // botones propios (velocidad, audio, SRT)

    private val mainH = Handler(Looper.getMainLooper())

    // Contexto del título (para marcar visto / continuar viendo / siguiente ep.)
    private var tmdbId = -1
    private var mediaType = "movie"
    private var season = -1
    private var episode = -1
    private var titleName = ""
    private var poster: String? = null
    private var imdb: String? = null
    private var currentUrl: String = ""

    private val speeds = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f)
    private var speedIdx = 0

    // MIME de las pistas de audio del archivo (las detecta el reproductor local)
    private var audioMimes: List<String> = emptyList()
    // La TV está reproduciendo la versión HLS transcodificada de Real-Debrid
    private var castedTranscoded = false

    // Selector de subtítulos externos (.srt/.vtt/.ass)
    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            applyExternalSubtitle(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersive()

        val directUrl = intent.getStringExtra("url")
        val infoHash = intent.getStringExtra("infoHash")
        tmdbId = intent.getIntExtra("tmdbId", -1)
        mediaType = intent.getStringExtra("type") ?: "movie"
        season = intent.getIntExtra("season", -1)
        episode = intent.getIntExtra("episode", -1)
        titleName = intent.getStringExtra("name") ?: ""
        poster = intent.getStringExtra("poster")
        val resumeMs = intent.getLongExtra("resumeMs", 0L)

        currentUrl = when {
            !directUrl.isNullOrBlank() -> directUrl
            infoHash != null -> { StreamServer.ensureStarted(); StreamServer.urlFor(infoHash) }
            else -> { finish(); return }
        }

        val root = FrameLayout(this)
        playerView = PlayerView(this).apply {
            setShowSubtitleButton(true)
            controllerShowTimeoutMs = 3500
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        // --- Botones propios (arriba a la derecha), visibles con el controlador ---
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun mkBtn(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(28, 18, 28, 18)
            setBackgroundColor(0x66000000)
            setOnClickListener { onClick() }
        }
        val speedBtn = mkBtn("1×") {
            speedIdx = (speedIdx + 1) % speeds.size
            runCatching { activePlayer()?.setPlaybackSpeed(speeds[speedIdx]) }
            (overlay.getChildAt(0) as TextView).text = "${speeds[speedIdx]}×"
            showToast("Velocidad ${speeds[speedIdx]}×")
        }
        val audioBtn = mkBtn("Audio") {
            val p = player ?: return@mkBtn
            runCatching { TrackSelectionDialogBuilder(this, "Pista de audio", p, C.TRACK_TYPE_AUDIO).build().show() }
        }
        val srtBtn = mkBtn("SRT") {
            runCatching { pickSubtitle.launch(arrayOf("*/*")) }
        }
        val lpBtn = LinearLayout.LayoutParams(-2, -2)
        lpBtn.marginEnd = 12
        overlay.addView(speedBtn, lpBtn); overlay.addView(audioBtn, lpBtn); overlay.addView(srtBtn, lpBtn)
        // Botón de Chromecast (solo si hay Google Play Services)
        runCatching {
            val castBtn = androidx.mediarouter.app.MediaRouteButton(this)
            CastButtonFactory.setUpMediaRouteButton(applicationContext, castBtn)
            overlay.addView(castBtn, LinearLayout.LayoutParams(-2, -2))
        }
        val lpOverlay = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
        lpOverlay.topMargin = 40; lpOverlay.rightMargin = 24
        root.addView(overlay, lpOverlay)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            overlay.visibility = if (v == View.VISIBLE) View.VISIBLE else View.GONE
        })

        // --- Aviso central para los gestos ---
        toast = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 18f
            setBackgroundColor(0x88000000.toInt()); setPadding(40, 24, 40, 24)
            visibility = View.GONE
        }
        root.addView(toast, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // --- Siguiente episodio (abajo a la derecha, aparece al terminar) ---
        nextBtn = TextView(this).apply {
            text = "▶ Siguiente episodio"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 16f
            setBackgroundColor(0xCC7B5BF5.toInt()); setPadding(44, 28, 44, 28)
            visibility = View.GONE
            setOnClickListener { playNextEpisode() }
        }
        val lpNext = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
        lpNext.bottomMargin = 140; lpNext.rightMargin = 32
        root.addView(nextBtn, lpNext)

        setContentView(root)
        setupGestures()

        player = ExoPlayer.Builder(this).build().also { p ->
            playerView.player = p
            playerView.keepScreenOn = true
            p.setMediaItem(MediaItem.fromUri(currentUrl))
            p.prepare()
            if (resumeMs > 0) p.seekTo(resumeMs)
            p.playWhenReady = true
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && mediaType == "series" && episode > 0) {
                        nextBtn.visibility = View.VISIBLE
                    }
                }
                override fun onTracksChanged(tracks: Tracks) {
                    audioMimes = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        .flatMap { g -> (0 until g.length).mapNotNull { g.getTrackFormat(it).sampleMimeType } }
                    if (isCasting()) maybeWarnCastAudio()
                }
            })
        }

        // El IMDb id hace falta para buscar el siguiente episodio en Torrentio
        if (mediaType == "series" && tmdbId > 0) Tmdb.imdbId("series", tmdbId) { id -> imdb = id }

        // Chromecast: al conectar con una TV se pasa la reproducción al CastPlayer
        runCatching {
            val cc = CastContext.getSharedInstance(this)
            castPlayer = CastPlayer(cc).also { cp ->
                cp.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                    override fun onCastSessionAvailable() = switchToCast()
                    override fun onCastSessionUnavailable() = switchToLocal()
                })
                cp.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (castedTranscoded) {
                            // La versión HLS de RD no funcionó: prueba el enlace directo
                            castedTranscoded = false
                            showToast("HLS falló, probando el enlace directo…", 3000)
                            val pos = runCatching { cp.currentPosition }.getOrDefault(0L)
                            cp.setMediaItem(castItem(currentUrl), pos)
                            cp.prepare()
                            cp.playWhenReady = true
                            maybeWarnCastAudio()
                        } else {
                            showToast("La TV no pudo reproducir el vídeo (${error.errorCodeName})", 5000)
                        }
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED && mediaType == "series" && episode > 0) {
                            nextBtn.visibility = View.VISIBLE
                        }
                    }
                })
                // Si ya había una sesión de Cast abierta antes de entrar al
                // reproductor, el listener no dispara: envía ya la reproducción.
                if (cp.isCastSessionAvailable) switchToCast()
            }
        }
    }

    // ------------------- Chromecast -------------------
    /** IP del móvil en la red local (WiFi, ethernet o hotspot). */
    private fun lanIp(): String? {
        // WifiManager (rápido y fiable en WiFi normal)…
        runCatching {
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo.ipAddress
            if (ip != 0) return String.format(
                "%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff
            )
        }
        // …y si devuelve 0 (ethernet en Android TV, hotspot), busca en las interfaces
        return runCatching {
            java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { java.util.Collections.list(it.inetAddresses) }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    /** URL que la TV pueda alcanzar: el stream local se sirve por la IP de la LAN. */
    private fun castableUrl(u: String): String {
        if (!u.contains("127.0.0.1")) return u
        val ip = lanIp() ?: return u
        return u.replace("127.0.0.1", ip)
    }

    /**
     * MIME real del vídeo. El stream local no lleva extensión en la URL, así
     * que se consulta el nombre del archivo del torrent; para URLs directas
     * (Real-Debrid) se usa la extensión. La TV lo necesita correcto: con
     * "video/mp4" fijo un MKV o WebM no llega a reproducirse.
     */
    private fun castMimeType(url: String): String {
        val last = url.substringAfterLast('/').substringBefore('?')
        val fromTorrent = TorrentEngine.get(last)?.let { d ->
            if (d.videoIndex >= 0) d.ti.files().fileName(d.videoIndex) else null
        }
        val name = fromTorrent ?: runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        return when (name.substringAfterLast('.', "").lowercase()) {
            "webm" -> MimeTypes.VIDEO_WEBM
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "ts" -> MimeTypes.VIDEO_MP2T
            "avi" -> "video/x-msvideo"
            else -> MimeTypes.VIDEO_MP4 // mp4, m4v, mov y desconocidos
        }
    }

    /** Reproductor activo: el CastPlayer si estamos emitiendo, si no el local. */
    private fun activePlayer(): Player? = playerView.player ?: player

    private fun isCasting(): Boolean {
        val cp = castPlayer ?: return false
        return cp.isCastSessionAvailable && playerView.player === cp
    }

    private fun castItem(url: String, mime: String = castMimeType(url)): MediaItem = MediaItem.Builder()
        .setUri(castableUrl(url))
        .setMimeType(mime)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(titleName.ifBlank { "TorrentBox" })
                .apply { poster?.let { setArtworkUri(Uri.parse(it)) } }
                .build()
        )
        .build()

    private fun switchToCast() {
        val cp = castPlayer ?: return
        val local = player ?: return
        if (playerView.player === cp) return // ya estamos emitiendo
        val pos = local.currentPosition
        local.pause()
        playerView.player = cp
        showToast("📺 Enviando a la TV…")
        loadOnCast(currentUrl, pos)
    }

    /**
     * Carga la URL en la TV. Con Real-Debrid pide primero la versión HLS
     * transcodificada (audio AAC): el audio Dolby/DTS de muchos torrents no
     * suena en un Chromecast. Si no hay HLS, va el enlace directo con aviso.
     */
    private fun loadOnCast(url: String, pos: Long) {
        val cp = castPlayer ?: return
        if (RealDebrid.downloadIdFor(url) != null) {
            RealDebrid.transcodeUrl(url) { m3u8 ->
                mainH.post {
                    if (!isCasting()) return@post
                    castedTranscoded = m3u8 != null
                    val item = if (m3u8 != null) castItem(m3u8, MimeTypes.APPLICATION_M3U8)
                    else castItem(url)
                    cp.setMediaItem(item, pos)
                    cp.prepare()
                    cp.playWhenReady = true
                    if (m3u8 == null) maybeWarnCastAudio()
                }
            }
        } else {
            castedTranscoded = false
            cp.setMediaItem(castItem(url), pos)
            cp.prepare()
            cp.playWhenReady = true
            maybeWarnCastAudio()
        }
    }

    /** Avisa si el audio del archivo es de los que un Chromecast no decodifica. */
    private fun maybeWarnCastAudio() {
        if (castedTranscoded) return
        val bad = setOf(
            MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_TRUEHD
        )
        val good = setOf(
            MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_OPUS,
            MimeTypes.AUDIO_VORBIS, MimeTypes.AUDIO_FLAC
        )
        if (audioMimes.any { it in bad } && audioMimes.none { it in good }) {
            val label = when {
                audioMimes.any { it == MimeTypes.AUDIO_TRUEHD } -> "TrueHD"
                audioMimes.any { it.contains("dts") } -> "DTS"
                else -> "Dolby (AC3)"
            }
            val extra = if (RealDebrid.configured) "" else " o usa Real-Debrid"
            showToast("⚠️ Audio $label: puede no sonar en la TV.\nPrueba una fuente con audio AAC$extra", 7000)
        }
    }

    private fun switchToLocal() {
        val cp = castPlayer ?: return
        val local = player ?: return
        if (playerView.player === local) return
        val pos = runCatching { cp.currentPosition }.getOrDefault(0L)
        runCatching { cp.stop() }
        castedTranscoded = false
        playerView.player = local
        if (local.playbackState == Player.STATE_IDLE) local.prepare()
        if (pos > 0) local.seekTo(pos)
        local.playWhenReady = true
        showToast("De vuelta al móvil")
    }

    /** Pantalla completa inmersiva: oculta barra de estado y de navegación. */
    private fun enableImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersive()
    }

    // ------------------- Gestos -------------------
    private fun setupGestures() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        var volAcc = 0f
        var briAcc = -1f

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                volAcc = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                briAcc = window.attributes.screenBrightness.let { if (it < 0) 0.5f else it }
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = activePlayer() ?: return true
                val w = playerView.width
                when {
                    e.x < w / 3f -> { p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); showToast("⏪ -10 s") }
                    e.x > w * 2 / 3f -> { p.seekTo(p.currentPosition + 10_000); showToast("⏩ +10 s") }
                    else -> p.playWhenReady = !p.playWhenReady
                }
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float): Boolean {
                val h = playerView.height.takeIf { it > 0 } ?: return false
                val startX = e1?.x ?: return false
                if (kotlin.math.abs(dY) < kotlin.math.abs(dX)) return false
                if (startX > playerView.width / 2f) {
                    // Derecha: volumen
                    volAcc = (volAcc + dY / h * maxVol * 1.5f).coerceIn(0f, maxVol.toFloat())
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, volAcc.toInt(), 0)
                    showToast("🔊 ${(volAcc / maxVol * 100).toInt()}%")
                } else {
                    // Izquierda: brillo
                    briAcc = (briAcc + dY / h * 1.5f).coerceIn(0.02f, 1f)
                    val attrs = window.attributes; attrs.screenBrightness = briAcc; window.attributes = attrs
                    showToast("☀️ ${(briAcc * 100).toInt()}%")
                }
                return true
            }
        })
        playerView.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            true
        }
    }

    private var toastHide: Runnable? = null
    private fun showToast(msg: String, durationMs: Long = 900) {
        toast.text = msg
        toast.visibility = View.VISIBLE
        toastHide?.let { mainH.removeCallbacks(it) }
        toastHide = Runnable { toast.visibility = View.GONE }.also { mainH.postDelayed(it, durationMs) }
    }

    // ------------------- Subtítulos externos -------------------
    private fun applyExternalSubtitle(uri: Uri) {
        val p = player ?: return
        val name = uri.lastPathSegment ?: ""
        val mime = when {
            name.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
            name.endsWith(".ass", true) || name.endsWith(".ssa", true) -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val sub = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime).setLanguage("es").setLabel("Subtítulo externo")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        val pos = p.currentPosition
        val item = MediaItem.Builder().setUri(currentUrl).setSubtitleConfigurations(listOf(sub)).build()
        p.setMediaItem(item, pos)
        p.prepare()
        p.playWhenReady = true
        showToast("Subtítulo cargado")
    }

    // ------------------- Siguiente episodio -------------------
    private fun playNextEpisode() {
        val id = imdb
        if (id == null) { showToast("No se pudo identificar la serie"); return }
        nextBtn.visibility = View.GONE
        saveProgress() // deja el actual registrado como visto
        showToast("Buscando T${season}E${episode + 1}…")
        trySources(id, season, episode + 1) { found ->
            if (!found) {
                // quizá era el último de la temporada: prueba la siguiente
                showToast("Probando T${season + 1}E1…")
                trySources(id, season + 1, 1) { ok2 ->
                    if (!ok2) showToast("No hay fuentes del siguiente episodio")
                }
            }
        }
    }

    /** Busca fuentes del episodio y reproduce la mejor (RD si está configurado; si no, torrent). */
    private fun trySources(imdbId: String, s: Int, e: Int, onDone: (Boolean) -> Unit) {
        Torrentio.streams("series", imdbId, s, e) { list, _ ->
            val sorted = Search.sortByLang(list ?: emptyList(), Prefs.languageOrder)
            val best = sorted.firstOrNull()
            if (best == null) { mainH.post { onDone(false) }; return@streams }
            mainH.post {
                onDone(true)
                showToast("Cargando ${best.name.take(40)}…")
                if (RealDebrid.configured) {
                    RealDebrid.streamMagnet(best.magnet) { url, _, err, progress ->
                        mainH.post {
                            when {
                                url != null -> switchTo(url, s, e)
                                progress != null -> showToast("Real-Debrid… ${progress}%")
                                else -> { showToast(err ?: "Error RD, probando torrent…"); startTorrent(best.magnet, s, e) }
                            }
                        }
                    }
                } else startTorrent(best.magnet, s, e)
            }
        }
    }

    private fun startTorrent(magnet: String, s: Int, e: Int) {
        StreamServer.ensureStarted()
        TorrentEngine.addMagnet(magnet, Prefs.bufferDirFile()) { d, err ->
            mainH.post {
                if (d == null || d.videoIndex < 0) showToast(err ?: "Sin vídeo en esa fuente")
                else switchTo(StreamServer.urlFor(d.infoHash), s, e)
            }
        }
    }

    /** Cambia el reproductor al nuevo episodio, actualizando el contexto de progreso. */
    private fun switchTo(url: String, s: Int, e: Int) {
        season = s; episode = e
        currentUrl = url
        if (isCasting()) {
            loadOnCast(url, 0L)
        } else {
            val p = player ?: return
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.playWhenReady = true
        }
        showToast("T${s}E$e")
    }

    // ------------------- Progreso -------------------
    private fun saveProgress() {
        val p = activePlayer() ?: return
        val posMs = p.currentPosition
        val durMs = p.duration // puede ser negativo si aún no se conoce
        if (tmdbId > 0 && posMs > 5000) {
            WatchStore.record(
                tmdbId = tmdbId,
                type = mediaType,
                season = season.takeIf { it > 0 },
                episode = episode.takeIf { it > 0 },
                name = titleName,
                poster = poster,
                position = posMs / 1000.0,
                duration = if (durMs > 0) durMs / 1000.0 else 0.0
            )
        }
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        saveProgress()
        runCatching { castPlayer?.setSessionAvailabilityListener(null); castPlayer?.release() }
        castPlayer = null
        player?.release()
        player = null
    }
}
