package com.carbaxo.torrentbox

/**
 * Idiomas soportados para el filtro/orden y la detección del idioma de cada
 * fuente (torrent) a partir de su nombre. Cada idioma tiene su bandera, el
 * código de TMDB equivalente y las palabras clave típicas en los nombres.
 */
object Lang {
    data class Info(
        val code: String,       // clave interna
        val label: String,      // etiqueta para la UI
        val flag: String,       // emoji bandera
        val tmdb: String,       // idioma TMDB (para catálogos/fichas)
        val keywords: List<String>
    )

    val ALL: List<Info> = listOf(
        Info("es-ES", "Español (España)", "🇪🇸", "es-ES",
            listOf("castellano", "espanol", "español", "spanish", "cast.", "[cast]", " esp ", " es ")),
        Info("es-LA", "Español (Latino)", "🇲🇽", "es-MX",
            listOf("latino", "latin", " lat ", "[lat]", "espanol latino")),
        Info("en", "Inglés", "🇬🇧", "en-US",
            listOf("english", " eng ", "[eng]", ".eng.", "vose", "v.o.s", "subs")),
        Info("multi", "Multi-idioma", "🌍", "es-ES",
            listOf("multi", "dual"))
    )

    fun byCode(code: String): Info? = ALL.firstOrNull { it.code == code }

    /** Mapea un idioma en formato TMDB (es-ES, en-US, es-MX) a nuestro código. */
    fun fromTmdb(tmdb: String?): String? = when (tmdb) {
        "es-ES" -> "es-ES"
        "es-MX", "es-419" -> "es-LA"
        "en-US", "en-GB", "en" -> "en"
        else -> null
    }

    /** Orden por defecto si el usuario no ha configurado nada. */
    val DEFAULT_ORDER = listOf("es-ES", "en")

    /**
     * Detecta el idioma de un nombre de torrent. Devuelve el código o null si
     * no hay pistas claras. "multi"/"dual" tiene prioridad; luego castellano,
     * latino, inglés.
     */
    fun detect(name: String): String? {
        val n = " " + name.lowercase().replace('.', ' ').replace('_', ' ') + " "
        // multi/dual primero (suele incluir varios)
        if (byCode("multi")!!.keywords.any { n.contains(it) }) return "multi"
        for (code in listOf("es-ES", "es-LA", "en")) {
            val info = byCode(code)!!
            if (info.keywords.any { n.contains(it) }) return code
        }
        return null
    }

    fun flag(code: String?): String = code?.let { byCode(it)?.flag } ?: "🏳️"
    fun label(code: String?): String = code?.let { byCode(it)?.label } ?: "Idioma desconocido"

    /**
     * Puntuación de un idioma según el orden de preferencia del usuario:
     * cuanto antes en la lista, menor el valor (para ordenar ascendente).
     * Los idiomas no listados y los desconocidos van al final.
     */
    fun rank(code: String?, order: List<String>): Int {
        if (code == null) return order.size + 2
        val i = order.indexOf(code)
        return if (i >= 0) i else order.size + 1
    }
}
