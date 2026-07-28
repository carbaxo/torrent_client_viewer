package com.carbaxo.torrentbox

import okhttp3.Request
import java.util.concurrent.Executors

/**
 * Búsqueda **por texto** directamente en la web de DonTorrent.
 *
 * Por qué hace falta, si Peerflix ya indexa DonTorrent: porque los addons de
 * Stremio buscan **por IMDb id** (`tt1234567:1:5`). Un pack español como
 * «Peppa Pig 1 Temporada (1x01 al 1x13)» no lleva esa numeración por ningún lado,
 * así que el addon no sabe asociarlo a un episodio y nunca aparece — aunque el
 * torrent esté ahí. Buscando por texto, como lo haría una persona, sí sale.
 *
 * Se escribió **sin poder ver el HTML de la web** (bloqueada desde el entorno de
 * desarrollo), así que está hecho a propósito para no depender de cómo esté
 * maquetada:
 *
 *  - No usa clases CSS ni estructura de tablas: **busca enlaces por su forma**
 *    (`/serie/…`, `/pelicula/…`, `magnet:`, `.torrent`). Un rediseño de la web no
 *    lo rompe; solo lo rompería que cambien las rutas.
 *  - Prueba **varias formas de búsqueda** (por ruta y por parámetro) y se queda
 *    con la primera que devuelva resultados.
 *  - [test] cuenta en qué paso concreto falla, para poder arreglarlo sin adivinar.
 *
 * El dominio es un ajuste editable porque DonTorrent **cambia de dominio
 * constantemente** por bloqueos: hoy `.management`, y por ahí andan `.watch`,
 * `.cv`, `.today`… Cuando deje de funcionar hay que cambiarlo a mano, no hay
 * forma de evitarlo.
 */
object DonSite {

    const val DEFAULT_BASE = "https://dontorrent.management"

    private val io = Executors.newCachedThreadPool()

    /** ¿Activada la búsqueda en la web? Vacío el dominio = no se usa. */
    val enabled: Boolean get() = Prefs.donSiteUrl.isNotBlank()

    private fun base(): String = Prefs.donSiteUrl.trim().removeSuffix("/")

    /** Páginas de contenido de la web: /serie/123/456/Slug, /pelicula/123/Slug… */
    private val CONTENT = Regex(
        """/(serie|series|pelicula|peliculas|documental|documentales|variado|deporte)/[0-9]+/[^"'\s>]+""",
        RegexOption.IGNORE_CASE
    )
    private val MAGNET = Regex("""magnet:\?[^"'\s<>]+""", RegexOption.IGNORE_CASE)
    private val TORRENT_FILE = Regex("""(?:href|src)=["']([^"']*?\.torrent[^"']*)["']""", RegexOption.IGNORE_CASE)
    /** Enlaces de descarga que no acaban en .torrent pero lo sirven. */
    private val DOWNLOAD_PATH = Regex(
        """(?:href|src)=["']([^"']*/(?:torrents?|descargar|download)/[^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    private fun get(url: String): String? = runCatching {
        val req = Request.Builder().url(url)
            // Sin un User-Agent de navegador, muchas de estas webs devuelven 403
            .header("User-Agent", "Mozilla/5.0 (Android 13; Mobile) VizPlay")
            .header("Accept-Language", "es-ES,es;q=0.9")
            .build()
        Addon.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        }
    }.getOrNull()

    private fun bytes(url: String): ByteArray? = runCatching {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Android 13; Mobile) VizPlay")
            .build()
        Addon.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()
        }
    }.getOrNull()

    /** Convierte un href relativo en absoluto. */
    private fun abs(href: String): String = when {
        href.startsWith("http", true) -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> base() + href
        else -> "${base()}/$href"
    }

    /** Una página de contenido encontrada en la búsqueda. */
    data class Hit(val title: String, val url: String)

    /**
     * Formas de búsqueda que se prueban, en orden. No se sabe cuál usa la web (no
     * se pudo mirar), así que se intentan las habituales y gana la primera que
     * devuelva enlaces de contenido.
     */
    private fun searchUrls(q: String): List<String> {
        val q1 = java.net.URLEncoder.encode(q, "UTF-8")
        return listOf(
            "${base()}/buscar/$q1",
            "${base()}/buscar?q=$q1",
            "${base()}/buscar?query=$q1",
            "${base()}/buscar?searchbar=$q1",
            "${base()}/busqueda/$q1"
        )
    }

    /** El slug de la URL como título legible: "Peppa-Pig-1-Temporada" → "Peppa Pig 1 Temporada". */
    private fun titleFromUrl(u: String): String =
        u.trimEnd('/').substringAfterLast('/')
            .replace('-', ' ').replace('_', ' ')
            .replace(Regex("\\s{2,}"), " ").trim()

    /** Busca en la web y devuelve las páginas de contenido que coinciden. */
    fun search(query: String): List<Hit> {
        if (!enabled) return emptyList()
        for (url in searchUrls(query)) {
            val html = get(url) ?: continue
            val hits = LinkedHashMap<String, Hit>()
            for (m in CONTENT.findAll(html)) {
                val u = abs(m.value)
                hits.putIfAbsent(u, Hit(titleFromUrl(u), u))
            }
            if (hits.isNotEmpty()) return hits.values.toList()
        }
        return emptyList()
    }

    /**
     * De una página de contenido saca el magnet.
     *
     * Si la web da magnet directamente, se usa. Si da un `.torrent`, se descarga y
     * se le calcula el **infohash** para construir el magnet: así el resto de la
     * app (Real-Debrid, descargas, Chromecast, packs) funciona sin cambiar nada,
     * porque todo el flujo ya va por magnet.
     */
    fun magnetOf(pageUrl: String, name: String): String? {
        val html = get(pageUrl) ?: return null
        MAGNET.find(html)?.value?.let { return it }
        val cand = TORRENT_FILE.find(html)?.groupValues?.get(1)
            ?: DOWNLOAD_PATH.find(html)?.groupValues?.get(1)
            ?: return null
        val data = bytes(abs(cand)) ?: return null
        val hash = Bencode.infoHash(data) ?: return null
        return Search.buildMagnet(hash, name)
    }

    /** ¿El nombre dice que es una temporada o serie completa? */
    private fun looksPack(name: String): Boolean = Regex(
        "temporada|completa|complete|season|\\d+x\\d+\\s*al\\s*\\d+x\\d+|pack",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(name)

    /**
     * Busca por texto y resuelve los primeros resultados a enlaces usables.
     *
     * Solo se resuelven unos pocos: cada uno es una petición más a la web, y
     * pedirle veinte páginas seguidas por cada búsqueda es maltratarla y hacer que
     * la ficha tarde una eternidad.
     */
    fun streams(query: String, onResult: (List<Search.Result>?, String?) -> Unit) {
        if (!enabled) return onResult(emptyList(), null)
        io.submit {
            try {
                val hits = search(query)
                if (hits.isEmpty()) return@submit onResult(emptyList(), null)
                val out = ArrayList<Search.Result>()
                for (h in hits.take(MAX_RESOLVE)) {
                    val magnet = magnetOf(h.url, h.title) ?: continue
                    val hash = Regex("btih:([0-9a-fA-F]{40})").find(magnet)
                        ?.groupValues?.get(1)?.lowercase() ?: continue
                    out.add(
                        Search.Result(
                            name = h.title,
                            infoHash = hash,
                            seeders = 0,          // la web no publica semillas
                            sizeBytes = 0L,       // ni el tamaño de forma fiable
                            magnet = magnet,
                            lang = "es",          // DonTorrent publica en castellano
                            quality = Search.quality(h.title),
                            engine = Search.ENGINE_DONWEB,
                            info = "🌐 DonTorrent (web)",
                            pack = looksPack(h.title)
                        )
                    )
                }
                onResult(out, null)
            } catch (e: Throwable) {
                onResult(null, e.message ?: "Error buscando en DonTorrent.")
            }
        }
    }

    private const val MAX_RESOLVE = 6

    /**
     * Comprueba la web paso a paso y dice **dónde** falla. Existe porque esto se
     * escribió sin poder ver el HTML: si algo no cuadra, este mensaje dice si el
     * problema es el dominio, la forma de la búsqueda o el enlace de descarga, en
     * vez de dejar un "no hay enlaces" que no explica nada.
     */
    fun test(onResult: (String) -> Unit) {
        if (!enabled) return onResult("Pon primero el dominio de DonTorrent.")
        io.submit {
            val portada = get("${base()}/")
            if (portada == null) {
                return@submit onResult("1/4 No se pudo abrir ${base()} — dominio caído o bloqueado.")
            }
            var usada: String? = null
            var hits: List<Hit> = emptyList()
            for (u in searchUrls("Peppa Pig")) {
                val html = get(u) ?: continue
                val found = CONTENT.findAll(html).map { abs(it.value) }.distinct().toList()
                if (found.isNotEmpty()) {
                    usada = u; hits = found.map { Hit(titleFromUrl(it), it) }; break
                }
            }
            if (usada == null) {
                return@submit onResult(
                    "2/4 La web responde, pero ninguna forma de búsqueda dio resultados. " +
                        "Hay que ajustar la URL de búsqueda."
                )
            }
            val h = hits.first()
            val html = get(h.url)
                ?: return@submit onResult("3/4 Búsqueda OK (${hits.size} resultados), pero su ficha no abre.")
            val tieneMagnet = MAGNET.containsMatchIn(html)
            val fichero = TORRENT_FILE.find(html)?.groupValues?.get(1)
                ?: DOWNLOAD_PATH.find(html)?.groupValues?.get(1)
            if (!tieneMagnet && fichero == null) {
                return@submit onResult(
                    "3/4 Búsqueda OK (${hits.size} resultados) y la ficha abre, pero no se " +
                        "encuentra el enlace de descarga. Hace falta el href del botón «Descargar»."
                )
            }
            magnetOf(h.url, h.title)
                ?: return@submit onResult(
                    "4/4 Se encontró el enlace de descarga${if (tieneMagnet) " (magnet)" else " ($fichero)"}, " +
                        "pero no se pudo sacar el infohash del .torrent."
                )
            onResult(
                "✅ Funciona: ${hits.size} resultados buscando «Peppa Pig». " +
                    "Primero: ${h.title.take(48)}. " +
                    (if (tieneMagnet) "Da magnet directo." else "Da .torrent y se calculó el infohash.") +
                    " Búsqueda usada: ${usada.removePrefix(base())}"
            )
        }
    }
}
