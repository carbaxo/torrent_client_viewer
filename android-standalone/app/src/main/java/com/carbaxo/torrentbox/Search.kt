package com.carbaxo.torrentbox


/** Modelo de un enlace y utilidades compartidas por los motores de búsqueda. */
object Search {
    data class Result(
        val name: String,
        val infoHash: String,
        val seeders: Int,
        val sizeBytes: Long,
        val magnet: String,
        val lang: String? = null,  // código de idioma detectado (Lang), o null
        val quality: String = "Unknown", // 4K/1080p/720p/480p/SD/Unknown
        /** Motor(es) que lo devolvieron: EXTRA, PEERFLIX, TORRENTIO, unidos con "+". */
        val engine: String = "",
        /**
         * Texto extra que da el addon (fuente, códec, grupo…). Se muestra tal cual
         * bajo el nombre: es lo único que distingue dos enlaces cuando el addon no
         * manda el nombre del fichero.
         */
        val info: String = "",
        /**
         * Es un PACK (temporada o serie completa), no un episodio suelto. Al
         * pulsarlo hay que elegir capítulo dentro, no reproducir el primero.
         */
        val pack: Boolean = false
    ) {
        /** ¿Lo devolvió este motor? (un enlace puede venir de los dos). */
        fun fromEngine(e: String) = e == Search.ENGINE_ALL || engine.contains(e)

        /** Etiqueta para la tarjeta: "Extra", "Peerflix+Torrentio"… */
        val engineLabel: String
            get() = engine.split('+').filter { it.isNotBlank() }
                .joinToString("+") { e -> Search.engineName(e) }
    }

    const val ENGINE_TORRENTIO = "torrentio"
    const val ENGINE_PEERFLIX = "peerflix"   // addon de Stremio (webs españolas)
    const val ENGINE_EXTRA = "extra"         // addon de Stremio a elección del usuario
    const val ENGINE_RD = "rd"               // tu propia cuenta de Real-Debrid
    const val ENGINE_ALL = "all"

    /** Nombre bonito de un motor para los chips y las insignias. */
    fun engineName(e: String): String = when (e) {
        ENGINE_TORRENTIO -> "Torrentio"
        ENGINE_PEERFLIX -> "Peerflix"
        ENGINE_EXTRA -> "Extra"
        ENGINE_RD -> "En tu cuenta"   // no es una fuente: ya lo tienes en RD
        ENGINE_ALL -> "Todos"
        else -> e.replaceFirstChar { it.uppercase() }
    }

    /**
     * Texto descriptivo de un stream de addon. Los addons viejos lo ponen en
     * `title` y los nuevos en `description` (el SDK de Stremio lo renombró). Si se
     * lee solo `title`, los enlaces de un addon moderno salen SIN nombre de
     * fichero, SIN tamaño y con 0 seeders: parecen enlaces muertos cuando no lo
     * son.
     */
    fun pickDetail(title: String, description: String): String {
        val t = title.trim()
        val d = description.trim()
        return when {
            t.isBlank() -> d
            d.isBlank() || d == t -> t
            else -> "$t\n$d"
        }
    }

    /**
     * Nombre a mostrar. Por orden de fiabilidad: el que declara el propio addon
     * (`behaviorHints.filename`), la primera línea del texto descriptivo, y como
     * último recurso la etiqueta del addon ("Peerflix 🇪🇸 1080p"), que informa poco
     * pero es mejor que nada.
     */
    fun pickFilename(hintedName: String, detail: String, label: String): String {
        hintedName.trim().takeIf { it.isNotBlank() }?.let { return it }
        detail.split('\n').map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("👤") && !it.startsWith("💾") }
            ?.let { return it }
        return label.replace('\n', ' ').trim()
    }

    /**
     * Lo que queda del texto descriptivo una vez fuera **todo lo que ya se muestra
     * en su propio sitio**: el nombre, las semillas y el tamaño. Si no se quitan,
     * la línea repite el nombre casi igual y saca "👤 0" y el tamaño por segunda
     * vez, que es ruido en vez de información.
     *
     * Lo que sobrevive es lo que de verdad añade algo: la fuente (🌐), el grupo,
     * el códec, las pistas de audio…
     */
    fun pickInfo(detail: String, filename: String): String {
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
        val fn = norm(filename)
        return detail.split('\n').map { it.trim() }
            .filter { it.isNotBlank() }
            // Fuera las líneas que son el nombre otra vez (con o sin extensión)
            .filterNot { line ->
                val n = norm(line)
                n.isNotEmpty() && fn.isNotEmpty() &&
                    (n == fn || fn.contains(n.take(40)) || n.contains(fn.take(40)))
            }
            .map { line ->
                line
                    // Semillas y tamaño tienen su propio hueco en la tarjeta
                    .replace(Regex("👤\\s*[\\d.,]+"), " ")
                    .replace(Regex("💾\\s*[\\d.,]+\\s*(TB|GB|MB|GiB|MiB)", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("\\s{2,}"), " ")
                    .trim()
                    .trim('·', '-', '|', ' ')
            }
            .filter { it.isNotBlank() }
            .joinToString("  ·  ")
            .take(200)
    }

    /**
     * Compara "04x2" y "04x10" como los vería una persona: por el VALOR de los
     * números, no letra a letra. Sin esto, la lista de capítulos de un pack sale
     * 1, 10, 11, 2, 20… que es inservible con 300 ficheros.
     */
    fun naturalCompare(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val nx = x.toLongOrNull(); val ny = y.toLongOrNull()
            val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size - rb.size
    }

    /** De dos nombres del mismo torrent, el que informa más (un fichero real). */
    fun bestName(a: String, b: String): String {
        fun score(s: String): Int {
            var n = s.length.coerceAtMost(80)
            if (Regex("\\.(mkv|mp4|avi|webm|m4v)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) n += 100
            if (Regex("(19|20)\\d{2}").containsMatchIn(s)) n += 20
            return n
        }
        return if (score(b) > score(a)) b else a
    }

    /** Une los motores de dos resultados con el mismo infoHash. */
    fun mergeEngines(a: String, b: String): String =
        (a.split('+') + b.split('+')).filter { it.isNotBlank() }.distinct().sorted().joinToString("+")

    /**
     * Orden de preferencia de los motores al listar los enlaces.
     *
     * Primero lo que ya está en **tu** Real-Debrid: se ve al instante y no depende
     * de semillas ni de la caché de nadie. Luego el addon extra, que si alguien se
     * molestó en configurarlo es porque busca algo que los otros no le dan. Y
     * Peerflix antes de Torrentio porque indexa las webs españolas.
     */
    private val ENGINE_ORDER = listOf(ENGINE_RD, ENGINE_EXTRA, ENGINE_PEERFLIX, ENGINE_TORRENTIO)

    /**
     * Posición del motor en ese orden. Si un torrent lo devuelven varios, cuenta
     * el mejor colocado (así "Peerflix+Torrentio" va con los de Peerflix).
     */
    fun enginePriority(engine: String): Int =
        engine.split('+').filter { it.isNotBlank() }
            .minOfOrNull { e -> ENGINE_ORDER.indexOf(e).let { if (it < 0) ENGINE_ORDER.size else it } }
            ?: ENGINE_ORDER.size

    /**
     * Orden de la lista de enlaces: primero por MOTOR (Extra → Peerflix →
     * Torrentio), luego por el idioma preferido y, a igualdad, por seeders.
     */
    fun sortByEngineAndLang(list: List<Result>, order: List<String>): List<Result> =
        list.sortedWith(
            compareBy<Result> { enginePriority(it.engine) }
                .thenBy { Lang.rank(it.lang, order) }
                .thenByDescending { it.seeders }
        )

    /** Ordena por prioridad de idioma del usuario y, a igualdad, por seeders. */
    fun sortByLang(list: List<Result>, order: List<String>): List<Result> =
        list.sortedWith(compareBy<Result> { Lang.rank(it.lang, order) }.thenByDescending { it.seeders })

    /** Calidad a partir del nombre del torrent (mismas etiquetas que la web). */
    /**
     * Calidad a partir del nombre. Reconoce también las formas que usan las webs
     * españolas ("[MicroHD][1080 px]", "1920x1080"), que antes quedaban como
     * desconocidas y desaparecían al filtrar. Lo que no se puede identificar se
     * queda como Unknown y se muestra en el chip "Otras": nunca se esconde.
     */
    fun quality(name: String): String {
        val n = name.lowercase()
        return when {
            Regex("\\b(4k|2160\\s?p?x?|uhd)\\b").containsMatchIn(n) || n.contains("3840x2160") -> "4K"
            Regex("\\b1080\\s?(p|px)?\\b").containsMatchIn(n) || n.contains("1920x1080") ||
                Regex("\\b(fhd|fullhd|full hd)\\b").containsMatchIn(n) -> "1080p"
            Regex("\\b720\\s?(p|px)?\\b").containsMatchIn(n) || n.contains("1280x720") ||
                Regex("\\bhdtv\\b").containsMatchIn(n) -> "720p"
            Regex("\\b480\\s?(p|px)?\\b").containsMatchIn(n) || n.contains("854x480") -> "480p"
            Regex("\\b(sd|dvdrip|dvdscr|cam|telesync|ts|360p|240p)\\b").containsMatchIn(n) -> "SD"
            else -> "Unknown"
        }
    }

    /** Calidades en el orden en que se muestran los chips. */
    val QUALITIES = listOf("4K", "1080p", "720p", "480p", "SD")
    const val QUALITY_OTHER = "Unknown"

    /** Construye la query para un episodio concreto: "Título S01E02". */
    fun episodeQuery(title: String, season: Int, episode: Int): String =
        "$title S%02dE%02d".format(season, episode)

    private val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.stealth.si:80/announce"
    )

    fun buildMagnet(infoHash: String, name: String): String {
        val sb = StringBuilder("magnet:?xt=urn:btih:").append(infoHash)
        sb.append("&dn=").append(java.net.URLEncoder.encode(name, "UTF-8"))
        for (tr in TRACKERS) sb.append("&tr=").append(java.net.URLEncoder.encode(tr, "UTF-8"))
        return sb.toString()
    }

    fun humanSize(bytes: Long): String {
        if (bytes <= 0) return "?"
        val u = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble(); var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return String.format(if (v >= 10 || i == 0) "%.0f %s" else "%.1f %s", v, u[i])
    }
}
