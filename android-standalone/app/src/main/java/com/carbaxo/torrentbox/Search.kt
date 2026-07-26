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
        /** Motor que lo devolvió: TORRENTIO, PEERFLIX, o los dos unidos con "+". */
        val engine: String = ""
    ) {
        /** ¿Lo devolvió este motor? (un enlace puede venir de los dos). */
        fun fromEngine(e: String) = e == Search.ENGINE_ALL || engine.contains(e)

        /** Etiqueta para la tarjeta: "Peerflix", "Torrentio", "Peerflix+Torrentio". */
        val engineLabel: String
            get() = engine.split('+').filter { it.isNotBlank() }
                .joinToString("+") { e -> Search.engineName(e) }
    }

    const val ENGINE_TORRENTIO = "torrentio"
    const val ENGINE_PEERFLIX = "peerflix"   // addon de Stremio (webs españolas)
    const val ENGINE_ALL = "all"

    /** Nombre bonito de un motor para los chips y las insignias. */
    fun engineName(e: String): String = when (e) {
        ENGINE_TORRENTIO -> "Torrentio"
        ENGINE_PEERFLIX -> "Peerflix"
        ENGINE_ALL -> "Todos"
        else -> e.replaceFirstChar { it.uppercase() }
    }

    /** Une los motores de dos resultados con el mismo infoHash. */
    fun mergeEngines(a: String, b: String): String =
        (a.split('+') + b.split('+')).filter { it.isNotBlank() }.distinct().sorted().joinToString("+")

    /** Orden de preferencia de los motores al listar los enlaces. */
    private val ENGINE_ORDER = listOf(ENGINE_PEERFLIX, ENGINE_TORRENTIO)

    /**
     * Posición del motor en ese orden. Si un torrent lo devuelven varios, cuenta
     * el mejor colocado (así "Peerflix+Torrentio" va con los de Peerflix).
     */
    fun enginePriority(engine: String): Int =
        engine.split('+').filter { it.isNotBlank() }
            .minOfOrNull { e -> ENGINE_ORDER.indexOf(e).let { if (it < 0) ENGINE_ORDER.size else it } }
            ?: ENGINE_ORDER.size

    /**
     * Orden de la lista de enlaces: primero por MOTOR (Peerflix → Torrentio),
     * luego por el idioma preferido y, a igualdad, por seeders.
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
    fun quality(name: String): String {
        val n = name.lowercase()
        return when {
            Regex("\\b(4k|2160p|uhd)\\b").containsMatchIn(n) -> "4K"
            Regex("\\b(1080p|fhd)\\b").containsMatchIn(n) -> "1080p"
            Regex("\\b(720p|hdtv|hd)\\b").containsMatchIn(n) -> "720p"
            Regex("\\b480p\\b").containsMatchIn(n) -> "480p"
            Regex("\\b(sd|dvdrip|cam|ts|360p)\\b").containsMatchIn(n) -> "SD"
            else -> "Unknown"
        }
    }

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
