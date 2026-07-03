package com.carbaxo.torrentbox

import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Servidor HTTP local (127.0.0.1) que sirve el archivo de vídeo de un torrent
 * con soporte de peticiones Range, BLOQUEANDO hasta que las piezas necesarias
 * estén descargadas. Así ExoPlayer puede reproducir y adelantar mientras baja.
 *
 * URL: http://127.0.0.1:<port>/<infoHash>
 */
object StreamServer {
    const val PORT = 8090
    private var server: Server? = null

    fun ensureStarted(): Int {
        if (server == null) {
            server = Server(PORT).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        }
        return PORT
    }

    fun urlFor(infoHash: String): String = "http://127.0.0.1:$PORT/$infoHash"

    fun stop() {
        server?.stop()
        server = null
    }

    private class Server(port: Int) : NanoHTTPD("127.0.0.1", port) {
        override fun serve(session: IHTTPSession): Response {
            val infoHash = session.uri.trim('/')
            val d = TorrentEngine.get(infoHash)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no torrent")
            if (d.videoIndex < 0) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no video file")
            }

            val total = d.ti.files().fileSize(d.videoIndex)
            val mime = mimeFor(d.ti.files().fileName(d.videoIndex))
            val range = session.headers["range"]

            var start = 0L
            var end = total - 1
            var partial = false
            if (range != null && range.startsWith("bytes=")) {
                val spec = range.substring(6).split("-")
                try {
                    if (spec[0].isNotEmpty()) {
                        start = spec[0].toLong()
                        if (spec.size > 1 && spec[1].isNotEmpty()) end = spec[1].toLong()
                    } else if (spec.size > 1 && spec[1].isNotEmpty()) {
                        // sufijo: últimos N bytes
                        start = maxOf(0L, total - spec[1].toLong())
                    }
                    partial = true
                } catch (_: NumberFormatException) { partial = false }
            }
            if (start < 0 || start >= total) start = 0
            if (end >= total) end = total - 1
            val length = end - start + 1

            // Prioriza la descarga a partir de donde se empieza a reproducir
            TorrentEngine.prioritizeFrom(d, start)

            val stream: InputStream = PieceWaitingStream(d, start, length)
            val status = if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            val res = newFixedLengthResponse(status, mime, stream, length)
            res.addHeader("Accept-Ranges", "bytes")
            if (partial) res.addHeader("Content-Range", "bytes $start-$end/$total")
            return res
        }

        private fun mimeFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts" -> "video/mp2t"
            else -> "video/mp4"
        }
    }

    /**
     * InputStream que lee del archivo en disco pero espera a que cada pieza
     * esté disponible antes de leerla, priorizando las siguientes.
     */
    private class PieceWaitingStream(
        private val d: TorrentEngine.Download,
        startOffset: Long,
        private var remaining: Long
    ) : InputStream() {

        private var pos = startOffset
        private var raf: RandomAccessFile? = null
        private val pieceLen = TorrentEngine.pieceLength(d).toLong()

        private fun openFile(): RandomAccessFile? {
            if (raf == null) {
                val f = TorrentEngine.diskFile(d)
                if (f.exists()) raf = RandomAccessFile(f, "r")
            }
            return raf
        }

        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n <= 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1

            // Espera hasta que el byte en 'pos' esté descargado
            var waited = 0
            while (!TorrentEngine.hasByte(d, pos) || openFile() == null) {
                if (Thread.currentThread().isInterrupted) return -1
                if (waited % 10 == 0) TorrentEngine.prioritizeFrom(d, pos) // reafirma prioridad
                Thread.sleep(200)
                waited++
                // Tope de espera muy alto: streaming puede tardar si hay pocos peers
                if (waited > 5 * 60 * 5) return -1 // ~5 min sin pieza -> corta
            }

            val file = raf ?: return -1
            // No leas más allá del final de la pieza actual (la siguiente puede no estar)
            val pieceEnd = ((pos / pieceLen) + 1) * pieceLen
            val maxThisRead = minOf(len.toLong(), remaining, pieceEnd - pos).toInt()

            synchronized(file) {
                file.seek(pos)
                val n = file.read(b, off, maxThisRead)
                if (n <= 0) {
                    // Aún no escrito en disco: espera un poco y reintenta
                    Thread.sleep(200)
                    return 0
                }
                pos += n
                remaining -= n
                return n
            }
        }

        override fun close() {
            try { raf?.close() } catch (_: Throwable) {}
            raf = null
        }
    }
}
