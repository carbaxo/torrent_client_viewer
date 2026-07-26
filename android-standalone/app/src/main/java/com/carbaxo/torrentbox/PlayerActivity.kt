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
import androidx.media3.common.Player
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
        tmdbId = intent.getIntExtra("tmdbId", -1)
        mediaType = intent.getStringExtra("type") ?: "movie"
        season = intent.getIntExtra("season", -1)
        episode = intent.getIntExtra("episode", -1)
        titleName = intent.getStringExtra("name") ?: ""
        poster = intent.getStringExtra("poster")
        val resumeMs = intent.getLongExtra("resumeMs", 0L)

        // Siempre una URL directa: streaming de Real-Debrid o un fichero ya
        // descargado por el DownloadManager.
        if (directUrl.isNullOrBlank()) { finish(); return }
        currentUrl = directUrl

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
            player?.setPlaybackSpeed(speeds[speedIdx])
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
            }
        }
    }

    // ------------------- Chromecast -------------------
    // La URL de Real-Debrid es HTTPS pública, así que la TV la descarga ella
    // misma directamente de los servidores de RD: el vídeo no pasa por el móvil.
    private fun switchToCast() {
        val cp = castPlayer ?: return
        val local = player ?: return
        val pos = local.currentPosition
        local.playWhenReady = false
        val item = MediaItem.Builder()
            .setUri(currentUrl)
            .setMimeType(MimeTypes.VIDEO_MP4)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(titleName.ifBlank { "TorrentBox" }).build())
            .build()
        cp.setMediaItem(item, pos)
        cp.prepare()
        cp.playWhenReady = true
        playerView.player = cp
        showToast("📺 Enviando a la TV…")
    }

    private fun switchToLocal() {
        val cp = castPlayer ?: return
        val local = player ?: return
        val pos = runCatching { cp.currentPosition }.getOrDefault(0L)
        runCatching { cp.stop() }
        playerView.player = local
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
                val p = player ?: return true
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
    private fun showToast(msg: String) {
        toast.text = msg
        toast.visibility = View.VISIBLE
        toastHide?.let { mainH.removeCallbacks(it) }
        toastHide = Runnable { toast.visibility = View.GONE }.also { mainH.postDelayed(it, 900) }
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

    /** Busca fuentes del episodio y reproduce la mejor vía Real-Debrid. */
    private fun trySources(imdbId: String, s: Int, e: Int, onDone: (Boolean) -> Unit) {
        if (!RealDebrid.configured) {
            showToast("Conecta Real-Debrid en Ajustes para el siguiente episodio")
            onDone(true) // no seguimos probando temporadas: falta la configuración
            return
        }
        Torrentio.streams("series", imdbId, s, e) { list, _ ->
            val sorted = Search.sortByLang(list ?: emptyList(), Prefs.languageOrder)
            val best = sorted.firstOrNull()
            if (best == null) { mainH.post { onDone(false) }; return@streams }
            mainH.post {
                onDone(true)
                showToast("Cargando ${best.name.take(40)}…")
                RealDebrid.streamMagnet(best.magnet) { url, _, err, progress ->
                    mainH.post {
                        when {
                            url != null -> switchTo(url, s, e)
                            progress != null -> showToast("Real-Debrid lo está preparando… ${progress}%")
                            else -> showToast(err ?: "Error de Real-Debrid")
                        }
                    }
                }
            }
        }
    }

    /** Cambia el reproductor al nuevo episodio, actualizando el contexto de progreso. */
    private fun switchTo(url: String, s: Int, e: Int) {
        season = s; episode = e
        currentUrl = url
        val p = player ?: return
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
        showToast("T${s}E$e")
    }

    // ------------------- Progreso -------------------
    private fun saveProgress() {
        val p = player ?: return
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
