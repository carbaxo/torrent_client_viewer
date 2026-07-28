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
    private val TORRENT_HREF = Regex("""(?:href|src|data-href)=["']([^"']*?\.torrent[^"']*)["']""", RegexOption.IGNORE_CASE)
    /** Botones de descarga que sirven el .torrent sin que la URL lo diga. */
    private val DOWNLOAD_HREF = Regex(
        """(?:href|src|data-href)=["']([^"']*/(?:torrents?|descargar|descarga|download|get|dl)/[^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    /**
     * Último recurso: un `.torrent` **en cualquier parte** del HTML, no solo dentro
     * de un `href`. Pilla los que la página monta desde JavaScript y los que van
     * en atributos raros o sin comillas, que es donde se atascó el primer intento.
     */
    private val TORRENT_ANY = Regex("""[^"'\s<>()]+\.torrent""", RegexOption.IGNORE_CASE)

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
            ?: TORRENT_ANY.find(html)?.value
            ?: return null to
                "La página abre, pero no encuentro dentro ni un magnet ni un .torrent. " +
                "Prueba a pegar directamente el enlace del botón «Descargar» " +
                "(mantén pulsado → «Copiar dirección del enlace»).\n\n" + diagnose(html)
        val data = torrentBytes(abs(href, v), v)
            ?: return null to "Encontré el enlace de descarga ($href) pero no pude bajar el .torrent."
        return Found.TorrentFile(data, href) to null
    }

    private val ANY_HREF = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /**
     * Cuando no se encuentra el enlace de descarga, dice **qué contiene la página**
     * en vez de solo que no lo encuentra.
     *
     * Existe porque estas webs no se pueden abrir desde el entorno donde se
     * programa la app: sin esto, cada intento de acertar con el patrón sería un
     * build a ciegas. Con esto, un solo mensaje pegado por el usuario basta para
     * saber si el botón lleva otra ruta, si el HTML no trae enlaces (página hecha
     * con JavaScript, que habría que atacar de otra forma) o si la web ha devuelto
     * un aviso en vez de la ficha.
     */
    private fun diagnose(html: String): String {
        val hrefs = ANY_HREF.findAll(html).map { it.groupValues[1] }.toList()
        val interesting = hrefs.filter {
            Regex("tor|desc|down|get|dl|magnet", RegexOption.IGNORE_CASE).containsMatchIn(it)
        }.distinct().take(4)
        val segs = hrefs.mapNotNull {
            Regex("^/?([a-zA-Z0-9_-]{2,20})/").find(it)?.groupValues?.get(1)?.lowercase()
        }.distinct().take(10)
        return buildString {
            append("Diagnóstico: ${html.length} caracteres, ${hrefs.size} enlaces.")
            if (segs.isNotEmpty()) append(" Rutas: ${segs.joinToString(", ")}.")
            if (interesting.isNotEmpty())
                append(" Candidatos: ${interesting.joinToString(" | ") { it.take(70) }}.")
            else if (hrefs.size < 5)
                append(" Casi no hay enlaces: la página se monta con JavaScript, así que " +
                    "leerla no va a servir. Hará falta el enlace del .torrent a mano.")
        }
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
