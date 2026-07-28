package com.carbaxo.torrentbox

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Sesión de Chromecast COMPARTIDA por toda la app (como HBO o Netflix): la TV se
 * elige una vez con el botón de la barra superior y, a partir de ahí, cualquier
 * título que se abra se envía a esa TV; se puede cambiar de película sin volver
 * a conectar.
 *
 * El problema del sonido: el receptor de Google Cast solo decodifica AAC, MP3,
 * Opus, Vorbis y FLAC. Casi todas las releases traen **Dolby AC3/EAC3 o DTS**,
 * así que al enviar el archivo tal cual se ve la imagen pero NO se oye nada.
 * La solución es no enviar nunca el archivo original si se puede evitar: se pide
 * a Real-Debrid su versión **transcodificada a H.264 + AAC** y se prueba en
 * cadena, pasando al siguiente candidato en cuanto la TV falla O si se queda
 * cargando sin arrancar (la TV no siempre informa del error):
 *
 *   1. HLS de Real-Debrid          (m3u8, H.264 + AAC)  <- audio garantizado
 *   2. MP4 convertido por RD       (video/mp4,  AAC)    <- audio garantizado
 *   3. WebM convertido por RD      (video/webm, AAC)    <- audio garantizado
 *   4. Enlace original como mp4    (el receptor detecta el formato)
 *   5. Enlace original con su tipo real (último intento)
 */
@UnstableApi
object CastManager {

    /** Reintentos mientras Real-Debrid descarga el torrent en sus servidores. */
    private const val MAX_RD_TRIES = 15

    private var castContext: CastContext? = null

    /** Reproductor remoto (null si no hay Google Play Services). */
    var player: CastPlayer? = null
        private set

    // ---- Estado observable por la interfaz (Compose) ----
    var available by mutableStateOf(false); private set   // SDK de Cast utilizable
    var connected by mutableStateOf(false); private set   // hay TV conectada
    var deviceName by mutableStateOf<String?>(null); private set
    var status by mutableStateOf(""); private set         // "Enviando a la TV…", errores…
    var warning by mutableStateOf(""); private set        // aviso de audio/formato
    var title by mutableStateOf(""); private set
    var poster by mutableStateOf<String?>(null); private set
    /** Contexto del título emitido (para guardar "continuar viendo"). */
    var playCtx by mutableStateOf(PlayCtx()); private set

    /**
     * Aviso de conexión/desconexión con la TV para pantallas que no son Compose
     * (el reproductor). No usar `setSessionAvailabilityListener` desde fuera:
     * solo admite un oyente y pisaría el de este gestor.
     */
    var onSessionChanged: ((Boolean) -> Unit)? = null

    private data class Candidate(val url: String, val mime: String, val label: String, val converted: Boolean)

    private var candidates: List<Candidate> = emptyList()
    private var candidateIdx = 0
    private var startMs = 0L

    /**
     * Vigilante de arranque. La TV no siempre avisa de un error: con formatos que
     * no digiere (MKV es el caso típico) o con un archivo muy pesado se queda
     * "cargando" indefinidamente y sin ese aviso la cadena de respaldo no
     * avanzaba nunca. Si en unos segundos no ha empezado, se pasa al siguiente.
     */
    private var watchdog: Runnable? = null

    private val mainH = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    // ------------------------------------------------------------------
    // Inicio
    // ------------------------------------------------------------------
    /** Se puede llamar varias veces: reintenta si Play Services no estaba listo. */
    fun init(context: Context) {
        if (castContext != null) return
        runCatching {
            val cc = CastContext.getSharedInstance(context.applicationContext)
            castContext = cc
            player = CastPlayer(cc).also { cp ->
                cp.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                    override fun onCastSessionAvailable() {
                        connected = true
                        deviceName = currentDeviceName()
                        // Si había algo esperando a que se conectara la TV, va ahora
                        if (candidates.isNotEmpty()) loadCandidate(candidateIdx)
                        onSessionChanged?.invoke(true)
                    }

                    override fun onCastSessionUnavailable() {
                        connected = false
                        deviceName = null
                        status = ""
                        warning = ""
                        onSessionChanged?.invoke(false)
                    }
                })
                cp.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) = nextCandidate(error)
                    override fun onPlaybackStateChanged(state: Int) {
                        // Ya reproduce: quita el aviso y desarma el vigilante
                        if (state == Player.STATE_READY) {
                            cancelWatchdog()
                            status = ""
                        }
                    }
                })
            }
            available = true
            connected = player?.isCastSessionAvailable == true
            if (connected) deviceName = currentDeviceName()
        }
    }

    private fun currentDeviceName(): String? = runCatching {
        castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName
    }.getOrNull()

    // ------------------------------------------------------------------
    // Enviar contenido a la TV
    // ------------------------------------------------------------------

    /**
     * Emite un magnet: lo resuelve en Real-Debrid y envía a la TV la versión
     * convertida (con audio AAC) en cuanto esté.
     */
    fun castMagnet(magnet: String, ctx: PlayCtx) {
        begin(ctx)
        if (!RealDebrid.configured) {
            status = "Configura Real-Debrid en Ajustes para emitir a la TV"
            return
        }
        status = "⚡ Preparando en Real-Debrid…"
        resolveRd(magnet, 0)
    }

    /** Emite una URL ya resuelta de Real-Debrid (tiene que ser http/https). */
    fun castUrl(url: String, ctx: PlayCtx) {
        begin(ctx)
        // Un fichero ya descargado vive SOLO en el móvil (content://): la TV no
        // puede ir a buscarlo, así que se dice en claro en vez de enviarlo y
        // dejar que la TV se quede cargando.
        if (!url.startsWith("http", ignoreCase = true)) {
            status = "Esto ya está descargado en el móvil y la TV no puede acceder a su " +
                "almacenamiento. Para verlo en la TV, emite desde la ficha de la película " +
                "(sin descargar) o dale a ver aquí en el móvil."
            return
        }
        buildLadder(url)
    }

    private fun begin(ctx: PlayCtx) {
        playCtx = ctx
        title = ctx.name.ifBlank { "VizPlay" }
        poster = ctx.poster
        startMs = ctx.resumeMs
        warning = ""
        candidates = emptyList()
        candidateIdx = 0
        status = "📺 Enviando a la TV…"
    }

    /** Para de emitir (la TV vuelve a su pantalla de inicio). */
    fun stop() {
        cancelWatchdog()
        runCatching { player?.stop() }
        candidates = emptyList()
        status = ""
        warning = ""
        title = ""
        poster = null
    }

    /** Cierra la sesión con la TV. */
    fun disconnect() {
        stop()
        runCatching { castContext?.sessionManager?.endCurrentSession(true) }
    }

    // ------------------------------------------------------------------
    // Real-Debrid -> URL
    // ------------------------------------------------------------------
    private fun resolveRd(magnet: String, attempt: Int) {
        RealDebrid.streamMagnet(magnet) { url, _, err, progress ->
            mainH.post {
                when {
                    url != null -> buildLadder(url)
                    progress != null && attempt < MAX_RD_TRIES -> {
                        status = "⚡ Real-Debrid lo está preparando… ${progress}%"
                        mainH.postDelayed({ resolveRd(magnet, attempt + 1) }, 4000)
                    }
                    progress != null -> status =
                        "Real-Debrid sigue preparándolo (${progress}%). Inténtalo en un par de minutos."
                    else -> status = err ?: "Error de Real-Debrid"
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Cadena de candidatos
    // ------------------------------------------------------------------

    /** Pide a RD las versiones convertidas y monta la cadena a probar. */
    private fun buildLadder(url: String) {
        status = "⚡ Preparando audio compatible con la TV…"
        RealDebrid.transcodeVariants(url) { variants, err ->
            // El HLS se comprueba antes de enviarlo: si RD devuelve un enlace
            // roto, mejor descartarlo aquí que quedarnos en negro en la TV.
            val hls = variants.firstOrNull { it.kind == "hls" }
            validateHls(hls?.url) { hlsOk ->
                mainH.post {
                    val list = ArrayList<Candidate>()
                    variants.forEach { v ->
                        when (v.kind) {
                            "hls" -> if (hlsOk) list.add(
                                Candidate(v.url, MimeTypes.APPLICATION_M3U8, "HLS convertido (AAC)", true)
                            )
                            "mp4" -> list.add(Candidate(v.url, MimeTypes.VIDEO_MP4, "MP4 convertido (AAC)", true))
                            "webm" -> list.add(Candidate(v.url, MimeTypes.VIDEO_WEBM, "WebM convertido (AAC)", true))
                        }
                    }
                    list.addAll(directCandidates(url))
                    if (list.none { it.converted }) {
                        warning = "⚠️ Sin versión convertida" + (err?.let { " ($it)" } ?: "") +
                            ". Si la película lleva audio Dolby/DTS puede verse sin sonido: prueba otra fuente (mejor si pone AAC)."
                    }
                    startLadder(list)
                }
            }
        }
    }

    /**
     * El archivo original: primero anunciado como MP4 (así el receptor detecta
     * el formato real, que es lo que más veces funciona) y luego con su tipo
     * exacto. Declarar "video/x-matroska" de primeras hace que el receptor lo
     * rechace sin intentarlo.
     */
    private fun directCandidates(url: String): List<Candidate> {
        val real = mimeFor(url)
        val first = Candidate(url, MimeTypes.VIDEO_MP4, "archivo original", false)
        return if (real == MimeTypes.VIDEO_MP4) listOf(first)
        else listOf(first, Candidate(url, real, real.substringAfterLast('/'), false))
    }

    private fun startLadder(list: List<Candidate>) {
        candidates = list
        candidateIdx = 0
        if (list.isEmpty()) { status = "No hay nada que enviar a la TV"; return }
        loadCandidate(0)
    }

    private fun loadCandidate(i: Int) {
        val cp = player ?: return
        val c = candidates.getOrNull(i) ?: return
        candidateIdx = i
        if (!cp.isCastSessionAvailable) {
            status = "Conecta con una TV para empezar a emitir"
            return
        }
        status = if (i == 0) "📺 Enviando a la TV…" else "Probando en la TV: ${c.label}…"
        if (c.converted) warning = ""
        val item = MediaItem.Builder()
            .setUri(c.url)
            .setMimeType(c.mime)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .apply { poster?.let { setArtworkUri(Uri.parse(it)) } }
                    .build()
            )
            .build()
        cp.setMediaItem(item, startMs)
        cp.prepare()
        cp.playWhenReady = true
        // Las versiones convertidas pueden tardar (RD las genera al vuelo); el
        // archivo original, si va a ir, arranca rápido.
        armWatchdog(if (c.converted) 20_000L else 12_000L)
    }

    private fun armWatchdog(afterMs: Long) {
        cancelWatchdog()
        val r = Runnable {
            watchdog = null
            val cp = player ?: return@Runnable
            val started = runCatching { cp.playbackState == Player.STATE_READY }.getOrDefault(false)
            if (!started) advance("La TV se queda cargando")
        }
        watchdog = r
        mainH.postDelayed(r, afterMs)
    }

    private fun cancelWatchdog() {
        watchdog?.let { mainH.removeCallbacks(it) }
        watchdog = null
    }

    private fun nextCandidate(error: PlaybackException) =
        advance("La TV dio error (${error.errorCodeName})")

    /** Pasa al siguiente candidato, o explica que ya no queda ninguno. */
    private fun advance(reason: String) {
        cancelWatchdog()
        val next = candidateIdx + 1
        if (next < candidates.size) {
            status = "$reason; probando ${candidates[next].label}…"
            loadCandidate(next)
            return
        }
        // Se mira ANTES de vaciar la lista: si lo último que se probó era el
        // archivo original, el culpable casi siempre es su formato.
        val wasOriginal = candidates.getOrNull(candidateIdx)?.converted == false
        candidates = emptyList()
        status = "$reason y no queda otra versión que probar. " + if (wasOriginal)
            "Suele ser el formato: un Chromecast no reproduce MKV ni audio Dolby/DTS. " +
                "Prueba otra fuente (mejor MP4) o dale a ver en el móvil."
        else "Prueba otra fuente o dale a ver en el móvil."
    }

    /** Lo que se está emitiendo lleva audio convertido (sonará seguro). */
    val playingConverted: Boolean
        get() = candidates.getOrNull(candidateIdx)?.converted == true

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Tipo real del vídeo según la extensión de la URL. */
    private fun mimeFor(url: String): String {
        val last = url.substringAfterLast('/').substringBefore('?')
        val name = runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        return when (name.substringAfterLast('.', "").lowercase()) {
            "webm" -> MimeTypes.VIDEO_WEBM
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "ts" -> MimeTypes.VIDEO_MP2T
            "avi" -> "video/x-msvideo"
            else -> MimeTypes.VIDEO_MP4 // mp4, m4v, mov y desconocidos
        }
    }

    /** Comprueba que el HLS de Real-Debrid existe de verdad antes de enviarlo. */
    private fun validateHls(url: String?, onDone: (Boolean) -> Unit) {
        if (url.isNullOrBlank()) return onDone(false)
        Thread {
            val ok = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    val head = runCatching { r.body?.source()?.readUtf8Line() ?: "" }.getOrDefault("")
                    val type = r.header("Content-Type") ?: ""
                    r.isSuccessful && (head.contains("#EXTM3U") || type.contains("mpegurl", true))
                }
            }.getOrDefault(false)
            onDone(ok)
        }.start()
    }
}
