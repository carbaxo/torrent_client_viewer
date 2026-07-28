package com.carbaxo.torrentbox

import okhttp3.Request

/**
 * Convierte **lo que sea** que pegue el usuario en algo que Real-Debrid acepte.
 *
 * Esto sustituye al intento anterior de buscar dentro de la web de DonTorrent, y
 * es mucho mejor idea: adivinar el formato de búsqueda de una web que no se puede
 * ni abrir desde el entorno de desarrollo era un pozo sin fondo, mientras que
 * resolver **un** enlace que el usuario ya ha encontrado es un problema pequeño y
 * cerrado. Menos código, y no se rompe cuando la web cambia de maquetación.
 *
 * Acepta tres cosas:
 *
 *  1. Un **magnet** — se pasa tal cual.
 *  2. Un **.torrent** — se descarga y se sube a la cuenta. Es lo que dan las webs
 *     españolas: DonTorrent no ofrece magnet.
 *  3. La **página** de la ficha — se abre y se busca dentro el magnet o el
 *     `.torrent`. Es lo cómodo: se comparte la página desde el navegador y ya.
 *
 * Los enlaces de hoster (1fichier, Mega…) NO son de aquí: esos van por
 * `RealDebrid.unrestrict`, que es el «Descargador» de la web de RD.
 */
object LinkAdd {

    /** Lo que se ha conseguido sacar del enlace. */
    sealed class Found {
        data class Magnet(val magnet: String) : Found()
        data class TorrentFile(val data: ByteArray, val from: String) : Found()
    }

    private val MAGNET = Regex("""magnet:\?[^"'\s<>]+""", RegexOption.IGNORE_CASE)
    private val TORRENT_HREF = Regex("""(?:href|src)=["']([^"']*?\.torrent[^"']*)["']""", RegexOption.IGNORE_CASE)
    /** Botones de descarga que sirven el .torrent sin que la URL lo diga. */
    private val DOWNLOAD_HREF = Regex(
        """(?:href|src)=["']([^"']*/(?:torrents?|descargar|descarga|download)/[^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    /** Cabeceras de navegador: sin ellas varias de estas webs responden 403. */
    private fun req(url: String, referer: String?) = Request.Builder().url(url)
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        .header("Accept-Language", "es-ES,es;q=0.9")
        .apply { if (referer != null) header("Referer", referer) }
        .build()

    private fun origin(url: String): String =
        runCatching { java.net.URL(url).let { "${it.protocol}://${it.host}" } }.getOrDefault("")

    private fun abs(href: String, pageUrl: String): String = when {
        href.startsWith("http", true) -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> origin(pageUrl) + href
        else -> pageUrl.substringBeforeLast('/') + "/" + href
    }

    /** Descarga el .torrent y comprueba que **es** un torrent de verdad. */
    private fun torrentBytes(url: String, referer: String?): ByteArray? = runCatching {
        Addon.client.newCall(req(url, referer)).execute().use { r ->
            if (!r.isSuccessful) return null
            val data = r.body?.bytes() ?: return null
            // Una página de error HTML también se descarga sin fallar; si no se le
            // puede sacar el infohash, no es un torrent y mejor decirlo que subir
            // basura a la cuenta.
            if (Bencode.infoHash(data) == null) null else data
        }
    }.getOrNull()

    /**
     * Resuelve el enlace. Trabajo de red: llamar desde un hilo secundario.
     *
     * @return el resultado, o null y el motivo en el segundo valor.
     */
    fun resolve(input: String): Pair<Found?, String?> {
        if (input.isBlank()) return null to "No has pegado nada."
        // Se limpia antes de mirar: el fragmento «#:~:text=…» que añade Chrome al
        // copiar un enlace no es parte de la dirección, y un enlace sin «https://»
        // delante sigue siendo un enlace.
        val v = Links.tidy(input)
        if (v.startsWith("magnet:", true)) return Found.Magnet(v) to null
        if (!v.startsWith("http", true)) {
            return null to if (Links.looksTruncated(v))
                "Ese enlace está a medias: le falta el principio (le falta el dominio, " +
                    "tipo «https://…»). En el móvil es fácil que el copiar y pegar se lleve " +
                    "solo un trozo. Más seguro: en el navegador usa Compartir → VizPlay."
            else "Eso no es un enlace ni un magnet."
        }

        // Un .torrent directo
        if (v.substringBefore('?').endsWith(".torrent", true)) {
            val data = torrentBytes(v, null)
                ?: return null to "Ese .torrent no se pudo descargar (o no era un torrent)."
            return Found.TorrentFile(data, v) to null
        }

        // La página de la ficha: se busca dentro
        val html = runCatching {
            Addon.client.newCall(req(v, origin(v) + "/")).execute().use { r ->
                if (!r.isSuccessful) return null to "La página respondió ${r.code}."
                r.body?.string()
            }
        }.getOrNull() ?: return null to "No se pudo abrir la página."

        MAGNET.find(html)?.value?.let { return Found.Magnet(it) to null }

        val href = TORRENT_HREF.find(html)?.groupValues?.get(1)
            ?: DOWNLOAD_HREF.find(html)?.groupValues?.get(1)
            ?: return null to
                "La página abre, pero no encuentro dentro ni un magnet ni un .torrent. " +
                "Prueba a pegar directamente el enlace del botón «Descargar» " +
                "(mantén pulsado → «Copiar dirección del enlace»)."
        val data = torrentBytes(abs(href, v), v)
            ?: return null to "Encontré el enlace de descarga ($href) pero no pude bajar el .torrent."
        return Found.TorrentFile(data, href) to null
    }

    /**
     * Resuelve y lo añade a Real-Debrid. `onDone(id, error, queHizo)` — lo último
     * es para poder decirle al usuario si acabó siendo magnet o fichero.
     */
    fun addToRd(input: String, onDone: (String?, String?, String) -> Unit) {
        io.execute {
            val (found, err) = runCatching { resolve(input) }
                .getOrElse { null to (it.message ?: "Error al resolver el enlace.") }
            when (found) {
                null -> onDone(null, err ?: "No se pudo resolver el enlace.", "")
                is Found.Magnet -> RealDebrid.addMagnet(found.magnet) { id, e -> onDone(id, e, "magnet") }
                is Found.TorrentFile -> RealDebrid.addTorrentFile(found.data) { id, e ->
                    onDone(id, e, ".torrent (${found.data.size / 1024} KB)")
                }
            }
        }
    }

    private val io = java.util.concurrent.Executors.newCachedThreadPool()
}
