package com.carbaxo.torrentbox

import okhttp3.FormBody
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
 *    (`/serie/…`, `/pelicula/…`, `magnet:`, `.torrent`).
 *  - Prueba **muchas formas de búsqueda** (varias rutas, varios nombres de
 *    parámetro, GET y POST, y tres formas de escribir los espacios) y se queda con
 *    la primera que dé resultados. La que funciona se **recuerda**, así que las
 *    búsquedas siguientes hacen una sola petición.
 *  - [test] no dice solo que falla: dice **qué rutas tiene la web de verdad** y si
 *    el texto buscado aparece en la respuesta. Con eso se arregla sin adivinar.
 *
 * El dominio es un ajuste editable porque DonTorrent **cambia de dominio
 * constantemente** por bloqueos: hoy `.management`, y por ahí andan `.watch`,
 * `.cv`, `.today`… Cuando deje de funcionar hay que cambiarlo a mano.
 */
object DonSite {

    const val DEFAULT_BASE = "https://dontorrent.management"

    private val io = Executors.newCachedThreadPool()

    /** ¿Activada la búsqueda en la web? Vacío el dominio = no se usa. */
    val enabled: Boolean get() = Prefs.donSiteUrl.isNotBlank()

    private fun base(): String = Prefs.donSiteUrl.trim().removeSuffix("/")

    /**
     * Páginas de contenido. Se aceptan uno o dos ids y con slug o sin él, porque no
     * se sabe qué forma usa el listado de búsqueda: la ficha que se conoce es
     * `/serie/67580/67581/Peppa-Pig-1-Temporada`, pero el listado bien puede
     * enlazar a `/serie/67580/Peppa-Pig`.
     */
    private val CONTENT = Regex(
        """/(?:serie|series|serie-vo|pelicula|peliculas|pelicula-hd|pelicula-4k|documental|documentales|musica|deporte|variado|juego|programa)s?/\d+(?:/[^"'\s>]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val MAGNET = Regex("""magnet:\?[^"'\s<>]+""", RegexOption.IGNORE_CASE)
    private val TORRENT_FILE = Regex("""(?:href|src)=["']([^"']*?\.torrent[^"']*)["']""", RegexOption.IGNORE_CASE)
    /** Enlaces de descarga que no acaban en .torrent pero lo sirven. */
    private val DOWNLOAD_PATH = Regex(
        """(?:href|src)=["']([^"']*/(?:torrents?|descargar|download|descarga)/[^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    /** Primer tramo de cada enlace interno: para saber qué rutas usa la web. */
    private val FIRST_SEG = Regex("""(?:href|src)=["']/([a-zA-Z0-9_-]{2,20})/""")

    /** Una forma de pedir la búsqueda: GET a una URL, o POST con un campo. */
    private data class Form(val url: String, val field: String? = null, val value: String = "") {
        val label: String get() = if (field == null) url.substringAfter("//").substringAfter("/")
        else "POST ${url.substringAfter("//").substringAfter("/")} ($field)"
    }

    /**
     * La forma que funcionó, recordada para no repetir el descubrimiento en cada
     * búsqueda: sin esto, cada ficha lanzaría una docena de peticiones a la web.
     *
     * Se guarda la **posición** en la lista, no la URL: la URL lleva el texto
     * buscado dentro, así que no serviría para la búsqueda siguiente. [forms] genera
     * siempre la misma lista en el mismo orden, así que la posición sí vale.
     */
    private var workingIdx: Int? = null

    private fun forms(q: String): List<Form> {
        val enc = java.net.URLEncoder.encode(q, "UTF-8")   // espacios como '+'
        val pct = enc.replace("+", "%20")                   // espacios como '%20'
        val slug = q.trim().replace(Regex("\\s+"), "-")     // espacios como '-'
        val b = base()
        val out = ArrayList<Form>()
        // Por RUTA. El '+' en una ruta es un '+' literal y no un espacio, así que
        // hay que probar también %20 y guiones: es el fallo más probable.
        for (p in listOf("buscar", "busqueda", "search")) {
            for (v in listOf(pct, slug, enc)) out.add(Form("$b/$p/$v"))
        }
        // Por PARÁMETRO
        for (p in listOf("buscar", "busqueda", "search", "")) {
            for (k in listOf("q", "query", "searchbar", "s", "buscar", "keyword")) {
                out.add(Form("$b/$p?$k=$enc"))
            }
        }
        // Por POST, que es lo que hacen los formularios de toda la vida
        for (k in listOf("q", "query", "searchbar", "buscar", "s")) {
            out.add(Form("$b/buscar", k, q))
        }
        return out
    }

    /** Pide una forma. Devuelve (código, cuerpo). */
    private fun fetch(f: Form): Pair<Int, String?> = runCatching {
        val rb = Request.Builder().url(f.url)
            // Sin User-Agent de navegador muchas de estas webs responden 403
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .header("Accept-Language", "es-ES,es;q=0.9")
            .header("Referer", base() + "/")
        if (f.field != null) rb.post(FormBody.Builder().add(f.field, f.value).build())
        Addon.client.newCall(rb.build()).execute().use { r ->
            r.code to (r.body?.string())
        }
    }.getOrElse { 0 to null }

    private fun bytes(url: String): ByteArray? = runCatching {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .header("Referer", base() + "/")
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

    private fun hitsIn(html: String): List<Hit> {
        val out = LinkedHashMap<String, Hit>()
        for (m in CONTENT.findAll(html)) {
            val u = abs(m.value)
            out.putIfAbsent(u, Hit(titleFromUrl(u), u))
        }
        return out.values.toList()
    }

    /** El slug de la URL como título: "Peppa-Pig-1-Temporada" → "Peppa Pig 1 Temporada". */
    private fun titleFromUrl(u: String): String {
        val last = u.trimEnd('/').substringAfterLast('/')
        // Si el último tramo es un id, no hay slug del que sacar el nombre
        val name = if (last.all { it.isDigit() }) u.trimEnd('/').substringAfterLast('/') else last
        return name.replace('-', ' ').replace('_', ' ')
            .replace(Regex("\\s{2,}"), " ").trim()
    }

    /** Busca en la web y devuelve las páginas de contenido que coinciden. */
    fun search(query: String): List<Hit> {
        if (!enabled) return emptyList()
        val all = forms(query)
        // Primero la forma que ya funcionó otra vez
        workingIdx?.let { i ->
            all.getOrNull(i)?.let { f ->
                val (_, html) = fetch(f)
                val hits = html?.let { hitsIn(it) }.orEmpty()
                if (hits.isNotEmpty()) return hits
            }
            workingIdx = null   // dejó de valer: se vuelve a descubrir
        }
        for ((i, f) in all.withIndex()) {
            val (_, html) = fetch(f)
            val hits = html?.let { hitsIn(it) }.orEmpty()
            if (hits.isNotEmpty()) { workingIdx = i; return hits }
        }
        return emptyList()
    }

    /**
     * De una página de contenido saca el magnet.
     *
     * Si la web da magnet, se usa. Si da un `.torrent`, se descarga y se le calcula
     * el **infohash** para construir el magnet: así el resto de la app
     * (Real-Debrid, descargas, Chromecast, packs) funciona sin cambiar nada, porque
     * todo el flujo ya va por magnet.
     */
    fun magnetOf(pageUrl: String, name: String): String? {
        val (_, html) = fetch(Form(pageUrl))
        if (html == null) return null
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

    private const val MAX_RESOLVE = 6

    /**
     * Busca por texto y resuelve los primeros resultados a enlaces usables.
     *
     * Solo unos pocos: cada uno es una petición más, y pedirle veinte páginas
     * seguidas por cada búsqueda es maltratar la web y hacer que la ficha tarde una
     * eternidad.
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

    /**
     * Comprueba la web y, si falla, **dice lo que hay** en vez de solo que falla.
     *
     * Esto existe porque el código se escribió sin poder ver el HTML: el mensaje
     * incluye las **rutas reales** que usa la web y si el texto buscado aparece en
     * la respuesta. Con esas dos cosas se arregla el patrón sin adivinar, en una
     * sola vuelta, en vez de ir probando a ciegas build tras build.
     */
    fun test(onResult: (String) -> Unit) {
        if (!enabled) return onResult("Pon primero el dominio de DonTorrent.")
        io.submit {
            val (code, portada) = fetch(Form("${base()}/"))
            if (portada == null) {
                return@submit onResult("1/4 No se pudo abrir ${base()} (código $code) — caído o bloqueado.")
            }
            // Qué rutas usa la portada. Si el patrón de fichas no casa, esto lo dice.
            val rutasPortada = FIRST_SEG.findAll(portada).map { it.groupValues[1].lowercase() }
                .distinct().take(12).toList()
            val fichasEnPortada = hitsIn(portada).size

            val todas = forms("Peppa Pig")
            var ok: Form? = null
            var okIdx = -1
            var hits: List<Hit> = emptyList()
            var mejor: String? = null       // la respuesta que al menos mencionaba el texto
            var rutasBusqueda: List<String> = emptyList()
            for ((i, f) in todas.withIndex()) {
                val (c, html) = fetch(f)
                if (html == null || c != 200) continue
                val h = hitsIn(html)
                if (h.isNotEmpty()) { ok = f; okIdx = i; hits = h; break }
                if (mejor == null && html.contains("peppa", true)) {
                    mejor = f.label
                    rutasBusqueda = FIRST_SEG.findAll(html).map { it.groupValues[1].lowercase() }
                        .distinct().take(12).toList()
                }
            }

            if (ok == null) {
                val diag = StringBuilder("2/4 La web abre, pero no saco resultados. ")
                if (mejor != null) {
                    diag.append(
                        "OJO: «$mejor» SÍ menciona «Peppa», así que la búsqueda funciona y lo " +
                            "que falla es mi patrón de enlaces. Rutas que usa esa página: " +
                            "${rutasBusqueda.joinToString(", ")}. "
                    )
                } else {
                    diag.append("Ninguna de las ${todas.size} formas probadas mencionaba «Peppa». ")
                }
                diag.append(
                    "Portada: $fichasEnPortada fichas reconocidas, rutas: ${rutasPortada.joinToString(", ")}."
                )
                return@submit onResult(diag.toString())
            }

            val h = hits.first()
            val (_, html) = fetch(Form(h.url))
            if (html == null) {
                return@submit onResult("3/4 Búsqueda OK con «${ok.label}» (${hits.size} resultados), pero su ficha no abre.")
            }
            val tieneMagnet = MAGNET.containsMatchIn(html)
            val fichero = TORRENT_FILE.find(html)?.groupValues?.get(1)
                ?: DOWNLOAD_PATH.find(html)?.groupValues?.get(1)
            if (!tieneMagnet && fichero == null) {
                val rutasFicha = FIRST_SEG.findAll(html).map { it.groupValues[1].lowercase() }
                    .distinct().take(12).toList()
                return@submit onResult(
                    "3/4 Búsqueda OK con «${ok.label}» (${hits.size} resultados) y la ficha abre, " +
                        "pero no localizo el enlace de descarga. Rutas de la ficha: " +
                        "${rutasFicha.joinToString(", ")}."
                )
            }
            magnetOf(h.url, h.title)
                ?: return@submit onResult(
                    "4/4 Encontrado el enlace de descarga${if (tieneMagnet) " (magnet)" else " ($fichero)"}, " +
                        "pero no se pudo leer el infohash del .torrent."
                )
            workingIdx = okIdx
            onResult(
                "✅ Funciona: ${hits.size} resultados con «${ok.label}». " +
                    "Primero: ${h.title.take(44)}. " +
                    if (tieneMagnet) "Da magnet directo." else "Da .torrent y se calculó el infohash."
            )
        }
    }
}
