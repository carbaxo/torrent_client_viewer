package com.carbaxo.torrentbox

import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Motor BitTorrent que corre EN EL DISPOSITIVO (sin depender de ningún PC).
 * Descarga a la carpeta de la app y prioriza piezas en orden para poder
 * reproducir el vídeo mientras aún se está descargando.
 */
object TorrentEngine {

    data class Download(
        val infoHash: String,
        val name: String,
        val ti: TorrentInfo,
        val handle: TorrentHandle,
        val saveDir: File,
        val videoIndex: Int
    )

    data class Snapshot(
        val infoHash: String,
        val name: String,
        val progress: Float,
        val downloadRate: Int,
        val uploadRate: Int,
        val numPeers: Int,
        val totalBytes: Long,
        val downloadedBytes: Long,
        val state: String,
        val paused: Boolean,
        val hasVideo: Boolean
    )

    private val session = SessionManager()
    private val io = Executors.newCachedThreadPool()
    private val downloads = ConcurrentHashMap<String, Download>()
    private val paused = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    @Volatile private var started = false

    private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "wmv", "flv", "mpg", "mpeg", "ts", "3gp")

    @Synchronized
    fun start() {
        if (started) return
        session.start()
        started = true
    }

    fun stop() {
        try { session.stop() } catch (_: Throwable) {}
        started = false
    }

    fun snapshots(): List<Snapshot> = downloads.values.map { d ->
        val s = d.handle.status()
        Snapshot(
            infoHash = d.infoHash,
            name = d.name,
            progress = runCatching { s.progress() }.getOrDefault(0f),
            downloadRate = runCatching { s.downloadRate() }.getOrDefault(0),
            uploadRate = runCatching { s.uploadRate() }.getOrDefault(0),
            numPeers = runCatching { s.numPeers() }.getOrDefault(0),
            totalBytes = if (d.videoIndex >= 0) d.ti.files().fileSize(d.videoIndex) else d.ti.totalSize(),
            downloadedBytes = runCatching { (s.progress() * d.ti.totalSize()).toLong() }.getOrDefault(0L),
            state = runCatching { s.state().toString() }.getOrDefault("…"),
            paused = paused.contains(d.infoHash),
            hasVideo = d.videoIndex >= 0
        )
    }

    fun get(infoHash: String): Download? = downloads[infoHash]

    /** Añade un magnet. Bloquea al obtener metadatos, así que se llama en segundo plano. */
    fun addMagnet(magnetUri: String, saveRoot: File, onResult: (Download?, String?) -> Unit) {
        start()
        io.submit {
            try {
                val data = session.fetchMagnet(magnetUri, 60, saveRoot)
                    ?: return@submit onResult(null, "No se pudieron obtener los metadatos (sin peers).")
                val ti = TorrentInfo(data)
                addTorrentInfo(ti, saveRoot, onResult)
            } catch (e: Throwable) {
                onResult(null, e.message ?: "Error al añadir el torrent.")
            }
        }
    }

    private fun addTorrentInfo(ti: TorrentInfo, saveRoot: File, onResult: (Download?, String?) -> Unit) {
        val hash = ti.infoHash().toHex()
        downloads[hash]?.let { return onResult(it, null) }

        saveRoot.mkdirs()
        session.download(ti, saveRoot)

        // Espera a que el handle esté disponible
        var handle: TorrentHandle? = null
        for (i in 0 until 100) {
            handle = session.find(ti.infoHash())
            if (handle != null && handle.isValid) break
            Thread.sleep(100)
        }
        if (handle == null || !handle.isValid) return onResult(null, "No se pudo iniciar la descarga.")

        val videoIndex = pickVideoFile(ti)
        // Descarga secuencial + prioriza el vídeo para poder verlo mientras baja
        try { handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) } catch (_: Throwable) {}
        if (videoIndex >= 0) prioritizeFileStart(ti, handle, videoIndex, 0)

        val d = Download(hash, ti.name() ?: hash, ti, handle, saveRoot, videoIndex)
        downloads[hash] = d
        onResult(d, null)
    }

    private fun pickVideoFile(ti: TorrentInfo): Int {
        val files = ti.files()
        var best = -1
        var bestSize = -1L
        for (i in 0 until files.numFiles()) {
            val ext = files.fileName(i).substringAfterLast('.', "").lowercase()
            if (ext in VIDEO_EXT && files.fileSize(i) > bestSize) {
                best = i; bestSize = files.fileSize(i)
            }
        }
        return best
    }

    fun pause(infoHash: String) {
        downloads[infoHash]?.handle?.pause()
        paused.add(infoHash)
    }
    fun resume(infoHash: String) {
        downloads[infoHash]?.handle?.resume()
        paused.remove(infoHash)
    }

    fun remove(infoHash: String, deleteFiles: Boolean) {
        val d = downloads.remove(infoHash) ?: return
        paused.remove(infoHash)
        try { session.remove(d.handle) } catch (_: Throwable) {}
        if (deleteFiles) {
            try {
                val top = d.ti.files().filePath(if (d.videoIndex >= 0) d.videoIndex else 0)
                val root = File(d.saveDir, top.substringBefore('/'))
                if (root.exists()) root.deleteRecursively() else File(d.saveDir, top).delete()
            } catch (_: Throwable) {}
        }
    }

    // ---- Ayudantes para el servidor de streaming ----

    fun diskFile(d: Download): File = File(d.saveDir, d.ti.files().filePath(d.videoIndex))

    fun pieceLength(d: Download): Int = d.ti.pieceLength()

    private fun absOffset(d: Download, offsetInFile: Long): Long =
        d.ti.files().fileOffset(d.videoIndex) + offsetInFile

    fun pieceForOffset(d: Download, offsetInFile: Long): Int =
        (absOffset(d, offsetInFile) / d.ti.pieceLength()).toInt()

    fun hasByte(d: Download, offsetInFile: Long): Boolean {
        val piece = pieceForOffset(d, offsetInFile)
        return piece in 0 until d.ti.numPieces() && d.handle.havePiece(piece)
    }

    /** Sube la prioridad de las próximas piezas a partir de un offset del archivo. */
    fun prioritizeFileStart(ti: TorrentInfo, handle: TorrentHandle, fileIndex: Int, offsetInFile: Long) {
        val abs = ti.files().fileOffset(fileIndex) + offsetInFile
        val startPiece = (abs / ti.pieceLength()).toInt()
        val ahead = 24 // ~ piezas por delante que priorizamos
        for (p in startPiece until minOf(startPiece + ahead, ti.numPieces())) {
            try { handle.piecePriority(p, Priority.SEVEN) } catch (_: Throwable) {}
        }
    }

    fun prioritizeFrom(d: Download, offsetInFile: Long) =
        prioritizeFileStart(d.ti, d.handle, d.videoIndex, offsetInFile)
}
