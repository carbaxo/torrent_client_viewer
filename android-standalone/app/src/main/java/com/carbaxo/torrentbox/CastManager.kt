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
 * Sesión de Chromecast COMPARTIDA por toda la app (como HBO o Netflix): la TV
 * se elige una vez con el botón de la barra superior y, a partir de ahí,
 * cualquier título que se abra se envía a esa TV; se puede cambiar de película
 * sin volver a conectar.
 *
 * Emitir a un Chromecast es delicado: el receptor solo admite MP4/WebM y
 * HLS/DASH, y no decodifica audio Dolby (AC3/EAC3) ni DTS. Por eso NO se envía
 * un único enlace: se construye una CADENA de candidatos y se pasa al siguiente
 * en cuanto uno falla, informando por pantalla:
 *
 *   1. HLS de Real-Debrid (H.264 + AAC) -> compatible con cualquier Chromecast
 *   2. Enlace directo anunciado como video/mp4 -> el receptor detecta el formato
 *   3. Enlace directo con su tipo real (mkv/webm/…) -> último intento
 */
@UnstableApi
object CastManager {

    /** Reintentos mientras Real-Debrid descarga el torrent en sus servidores. */
    private const val MAX_RD_TRIES = 15

    private var appCtx: Context? = null
    private var castContext: CastContext? = null

    /** Reproductor remoto (null si no hay Google Play Services). */
    var player: CastPlayer? = null
        private set

    // ---- Estado observable por la interfaz (Compose) ----
    var available by mutableStateOf(false); private set   // SDK de Cast utilizable
    var connected by mutableStateOf(false); private set   // hay TV conectada
    var deviceName by mutableStateOf<String?>(null); private set
    var status by mutableStateOf(""); private set         // "Enviando a la TV…", errores…
    var warning by mutableStateOf(""); private set        // aviso de formato dudoso
    var title by mutableStateOf(""); private set
    var poster by mutableStateOf<String?>(null); private set
    /** Contexto del título emitido (para guardar "continuar viendo"). */
    var playCtx by mutableStateOf(PlayCtx()); private set

    private data class Candidate(val url: String, val mime: String, val label: String)

    private var candidates: List<Candidate> = emptyList()
    private var candidateIdx = 0
    private var startMs = 0L

    /**
     * Aviso de conexión/desconexión con la TV para pantallas que no son Compose
     * (el reproductor). No usar `setSessionAvailabilityListener` desde fuera:
     * solo admite un oyente y pisaría el de este gestor.
     */
    var onSessionChanged: ((Boolean) -> Unit)? = null

    private val mainH = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    // ------------------------------------------------------------------
    // Inicio
    // ------------------------------------------------------------------
    fun init(context: Context) {
        if (castContext != null) return
        appCtx = context.applicationContext
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
                        // Ya reproduce: quita el "Enviando a la TV…"
                        if (state == Player.STATE_READY) status = ""
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
     * Emite un magnet: lo resuelve con Real-Debrid (preferido, da audio AAC) y
     * si no está configurado o falla, lo descarga como torrent en el móvil.
     */
    fun castMagnet(magnet: String, ctx: PlayCtx, preferRd: Boolean = true) {
        begin(ctx)
        if (preferRd && RealDebrid.configured) {
            status = "⚡ Preparando en Real-Debrid…"
            resolveRd(magnet, 0)
        } else {
            status = "Buscando peers del torrent…"
            resolveTorrent(magnet)
        }
    }

    /** Emite una URL ya resuelta (Real-Debrid, descarga local o stream propio). */
    fun castUrl(url: String, ctx: PlayCtx) {
        begin(ctx)
        buildLadder(url)
    }

    /** Emite un torrent que ya se está descargando en el móvil. */
    fun castInfoHash(infoHash: String, ctx: PlayCtx) {
        begin(ctx)
        StreamServer.ensureStarted()
        startLadder(directCandidates(StreamServer.urlFor(infoHash)))
    }

    private fun begin(ctx: PlayCtx) {
        playCtx = ctx
        title = ctx.name.ifBlank { "TorrentBox" }
        poster = ctx.poster
        startMs = ctx.resumeMs
        warning = ""
        candidates = emptyList()
        candidateIdx = 0
        status = "📺 Enviando a la TV…"
    }

    /** Para de emitir (la TV vuelve a la pantalla de inicio del receptor). */
    fun stop() {
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
    // Real-Debrid / torrent -> URL
    // ------------------------------------------------------------------
    private fun resolveRd(magnet: String, attempt: Int) {
        RealDebrid.streamMagnet(magnet) { url, _, err, progress ->
            mainH.post {
                when {
                    url != null -> buildLadder(url)
                    progress != null && attempt < MAX_RD_TRIES -> {
                        status = "⚡ Real-Debrid preparando… ${progress}%"
                        mainH.postDelayed({ resolveRd(magnet, attempt + 1) }, 4000)
                    }
                    else -> {
                        status = (err ?: "Real-Debrid tarda demasiado") + " — probando torrent…"
                        resolveTorrent(magnet)
                    }
                }
            }
        }
    }

    private fun resolveTorrent(magnet: String) {
        StreamServer.ensureStarted()
        TorrentEngine.addMagnet(magnet, Prefs.bufferDirFile()) { d, err ->
            mainH.post {
                if (d == null || d.videoIndex < 0) {
                    status = err ?: "Esa fuente no tiene vídeo"
                    return@post
                }
                // Algunos MP4 llevan el índice (moov) al final: pide también la
                // cola, si no la TV se queda esperando para siempre.
                val total = d.ti.files().fileSize(d.videoIndex)
                runCatching { TorrentEngine.prioritizeFrom(d, (total - 2L * 1024 * 1024).coerceAtLeast(0)) }
                waitBufferThenCast(d, 0)
            }
        }
    }

    /**
     * Espera a tener el principio del vídeo antes de enviarlo. Un Chromecast
     * corta la conexión si el servidor tarda en contestar, y al empezar un
     * torrent las piezas aún no están: sin este colchón la TV no arranca.
     */
    private fun waitBufferThenCast(d: TorrentEngine.Download, tries: Int) {
        val mb = 1024L * 1024
        val total = d.ti.files().fileSize(d.videoIndex)
        val goal = 6
        val ready = (0 until goal).count { i ->
            val off = i * mb
            off < total && runCatching { TorrentEngine.hasByte(d, off) }.getOrDefault(false)
        }
        if (ready >= goal || (total <= goal * mb && ready > 0) || tries > 90) {
            status = "📺 Enviando a la TV…"
            startLadder(directCandidates(StreamServer.urlFor(d.infoHash)))
            return
        }
        runCatching { TorrentEngine.prioritizeFrom(d, 0) }
        status = "Preparando el vídeo para la TV… $ready/$goal MB"
        mainH.postDelayed({ waitBufferThenCast(d, tries + 1) }, 1000)
    }

    // ------------------------------------------------------------------
    // Cadena de candidatos
    // ------------------------------------------------------------------

    /** Con Real-Debrid intenta primero su HLS (audio AAC); si no, enlace directo. */
    private fun buildLadder(url: String) {
        if (RealDebrid.downloadIdFor(url) == null) {
            startLadder(directCandidates(url))
            return
        }
        status = "⚡ Preparando audio compatible con la TV…"
        RealDebrid.transcodeUrl(url) { m3u8 ->
            validateHls(m3u8) { ok ->
                mainH.post {
                    val list = ArrayList<Candidate>()
                    if (ok && m3u8 != null) {
                        list.add(Candidate(m3u8, MimeTypes.APPLICATION_M3U8, "HLS con audio AAC"))
                    }
                    list.addAll(directCandidates(url))
                    startLadder(list)
                }
            }
        }
    }

    /**
     * Para un archivo suelto: primero anunciado como MP4 (así el receptor
     * detecta el formato real, que es lo que más veces funciona) y luego con su
     * tipo exacto. Declarar "video/x-matroska" de primeras hace que el receptor
     * lo rechace sin intentarlo.
     */
    private fun directCandidates(url: String): List<Candidate> {
        val real = mimeFor(url)
        val first = Candidate(url, MimeTypes.VIDEO_MP4, "enlace directo")
        return if (real == MimeTypes.VIDEO_MP4) listOf(first)
        else listOf(first, Candidate(url, real, real.substringAfterLast('/')))
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
        val playable = castableUrl(c.url)
        if (playable == null) {
            status = "No se pudo obtener la IP del móvil; conéctate a la misma WiFi que la TV"
            return
        }
        warning = riskWarning(c.mime)
        status = if (i == 0) "📺 Enviando a la TV…" else "Probando ${c.label} en la TV…"
        val item = MediaItem.Builder()
            .setUri(playable)
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
    }

    private fun nextCandidate(error: PlaybackException) {
        val next = candidateIdx + 1
        if (next < candidates.size) {
            loadCandidate(next)
        } else {
            candidates = emptyList()
            status = "La TV no pudo reproducir este archivo (${error.errorCodeName}). " +
                if (RealDebrid.configured) "Prueba otra fuente."
                else "Configura Real-Debrid en Ajustes o elige una fuente MP4 con audio AAC."
        }
    }

    private fun riskWarning(mime: String): String = when (mime) {
        MimeTypes.VIDEO_MATROSKA, "video/x-msvideo" ->
            "⚠️ MKV/AVI no siempre funciona en un Chromecast. Con «⚡ Ver RD» se envía convertido y va seguro."
        else -> ""
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Tipo real del vídeo, por el nombre del archivo del torrent o de la URL. */
    private fun mimeFor(url: String): String {
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

    /** El stream local se sirve por la IP de la LAN para que la TV lo alcance. */
    private fun castableUrl(u: String): String? {
        if (!u.contains("127.0.0.1")) return u
        val ip = lanIp() ?: return null
        return u.replace("127.0.0.1", ip)
    }

    /** IP del móvil en la red local (WiFi, ethernet o hotspot). */
    private fun lanIp(): String? {
        val ctx = appCtx
        if (ctx != null) runCatching {
            @Suppress("DEPRECATION")
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo.ipAddress
            if (ip != 0) return String.format(
                "%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff
            )
        }
        // WifiManager devuelve 0 con ethernet (Android TV) o compartiendo datos
        return runCatching {
            java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { java.util.Collections.list(it.inetAddresses) }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
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
