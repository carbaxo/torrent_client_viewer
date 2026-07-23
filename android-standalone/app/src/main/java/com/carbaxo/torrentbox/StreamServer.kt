package com.carbaxo.torrentbox

import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Servidor HTTP local que sirve el archivo de vídeo de un torrent con soporte
 * de peticiones Range, BLOQUEANDO hasta que las piezas necesarias estén
 * descargadas. Así ExoPlayer puede reproducir y adelantar mientras baja.
 *
 * Escucha en TODAS las interfaces (no solo loopback) para que un Chromecast u
 * otro dispositivo de la misma red pueda leer el stream por la IP de la WiFi.
 *
 * URL local: http://127.0.0.1:<port>/<infoHash>
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

    private class Server(port: Int) : NanoHTTPD(null, port) {
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
            res.addHeader("Access-Control-Allow-Origin", "*")
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
        // ~3 min sin conseguir la pieza (pocos peers) -> corta la conexión
        private val MAX_WAIT_TICKS = 900

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

        // NUNCA devuelve 0 para len>0: o lee >=1 byte, o -1 (EOF/interrupción).
        // Devolver 0 haría que NanoHTTPD cortara la respuesta a media descarga.
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            if (len <= 0) return 0

            var waited = 0
            while (true) {
                if (Thread.currentThread().isInterrupted) return -1

                // 1) Espera a que la pieza ABSOLUTA que contiene 'pos' esté lista
                if (!TorrentEngine.hasByte(d, pos) || openFile() == null) {
                    if (waited % 10 == 0) TorrentEngine.prioritizeFrom(d, pos)
                    Thread.sleep(200); waited++
                    if (waited > MAX_WAIT_TICKS) return -1 // sin progreso: corta limpio
                    continue
                }

                val file = raf ?: continue
                // No leer más allá del final de la pieza absoluta actual
                val maxThisRead = minOf(
                    len.toLong(), remaining, TorrentEngine.bytesToPieceBoundary(d, pos)
                ).toInt().coerceAtLeast(1)

                val n = synchronized(file) { file.seek(pos); file.read(b, off, maxThisRead) }
                if (n > 0) {
                    pos += n; remaining -= n; return n
                }
                // Pieza marcada como disponible pero aún no volcada a disco: espera
                Thread.sleep(120); waited++
                if (waited > MAX_WAIT_TICKS) return -1
            }
        }

        override fun close() {
            try { raf?.close() } catch (_: Throwable) {}
            raf = null
        }
    }
}
