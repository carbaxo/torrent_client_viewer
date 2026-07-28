package com.carbaxo.torrentbox

/**
 * Lo mínimo de **bencode** para sacar el `infohash` de un fichero `.torrent`.
 *
 * Hace falta porque webs como DonTorrent no dan magnet, dan el `.torrent`. Con el
 * infohash se construye el magnet y **todo lo demás de la app sigue igual**:
 * Real-Debrid, las descargas, el Chromecast y los packs ya trabajan con magnets.
 * La alternativa —subir el fichero a Real-Debrid con `addTorrent`— obligaría a
 * duplicar ese camino entero.
 *
 * El infohash es el **SHA-1 de los bytes crudos del valor de la clave `info`**,
 * tal cual vienen en el fichero. Por eso aquí no se decodifica a estructuras de
 * Kotlin: se recorre el fichero anotando **posiciones**, porque volver a
 * serializar lo decodificado cambiaría un solo byte (el orden de las claves, un
 * entero con ceros delante) y el hash saldría distinto y no valdría para nada.
 */
object Bencode {

    /**
     * Devuelve la posición siguiente al elemento que empieza en [pos], o -1 si el
     * fichero está mal formado.
     */
    private fun skip(b: ByteArray, pos: Int): Int {
        if (pos < 0 || pos >= b.size) return -1
        return when (val c = b[pos].toInt().toChar()) {
            'i' -> {                                   // i<entero>e
                val e = indexOf(b, 'e'.code.toByte(), pos + 1)
                if (e < 0) -1 else e + 1
            }
            'l', 'd' -> {                              // lista / diccionario
                var p = pos + 1
                while (p < b.size && b[p].toInt().toChar() != 'e') {
                    p = skip(b, p)
                    if (p < 0) return -1
                }
                if (p >= b.size) -1 else p + 1
            }
            in '0'..'9' -> {                           // <longitud>:<bytes>
                val colon = indexOf(b, ':'.code.toByte(), pos)
                if (colon < 0) return -1
                val len = String(b, pos, colon - pos, Charsets.US_ASCII).toIntOrNull() ?: return -1
                val end = colon + 1 + len
                if (len < 0 || end > b.size) -1 else end
            }
            else -> -1
        }
    }

    private fun indexOf(b: ByteArray, needle: Byte, from: Int): Int {
        var i = from
        while (i < b.size) { if (b[i] == needle) return i; i++ }
        return -1
    }

    /** Lee una cadena bencode en [pos]; devuelve el texto y dónde acaba. */
    private fun readString(b: ByteArray, pos: Int): Pair<String, Int>? {
        val colon = indexOf(b, ':'.code.toByte(), pos)
        if (colon < 0) return null
        val len = String(b, pos, colon - pos, Charsets.US_ASCII).toIntOrNull() ?: return null
        val end = colon + 1 + len
        if (len < 0 || end > b.size) return null
        return String(b, colon + 1, len, Charsets.US_ASCII) to end
    }

    /**
     * Infohash (SHA-1 en hexadecimal, minúsculas) de un `.torrent`, o null si el
     * fichero no es un torrent válido. No lanza: lo que llega de la red puede ser
     * cualquier cosa, incluida una página de error HTML con nombre `.torrent`.
     */
    fun infoHash(torrent: ByteArray): String? = runCatching {
        if (torrent.size < 3 || torrent[0].toInt().toChar() != 'd') return null
        var p = 1
        while (p < torrent.size && torrent[p].toInt().toChar() != 'e') {
            val (key, afterKey) = readString(torrent, p) ?: return null
            val valueEnd = skip(torrent, afterKey)
            if (valueEnd < 0) return null
            if (key == "info") {
                val md = java.security.MessageDigest.getInstance("SHA-1")
                md.update(torrent, afterKey, valueEnd - afterKey)
                return md.digest().joinToString("") { "%02x".format(it) }
            }
            p = valueEnd
        }
        null
    }.getOrNull()
}
