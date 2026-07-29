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

    /**
     * Palabras clave **ya normalizadas**: en minúsculas y rodeadas de espacios,
     * porque [normalize] convierte puntos, guiones y corchetes en espacios. Así
     * `[CAST]`, `.Cast.` y `-CAST-` casan todos con `" cast "`, y en cambio
     * «podcast» o «Castle» no, que era el riesgo de buscar «cast» a pelo.
     */
    val ALL: List<Info> = listOf(
        Info("es-ES", "Español (España)", "🇪🇸", "es-ES",
            listOf(" castellano ", " espanol ", " español ", " spanish ", " cast ", " esp ",
                " spa ", " es es ", " espana ", " españa ", " castellano dual ")),
        Info("es-LA", "Español (Latino)", "🇲🇽", "es-MX",
            listOf(" latino ", " latin ", " lat ", " espanol latino ", " mx ", " latam ")),
        Info("en", "Inglés", "🇬🇧", "en-US",
            listOf(" english ", " eng ", " vose ", " v o s ", " vo ")),
        Info("multi", "Multi-idioma", "🌍", "es-ES",
            listOf(" multi ", " dual "))
    )

    fun byCode(code: String): Info? = ALL.firstOrNull { it.code == code }

    // Banderas emoji (Torrentio las incluye en el título de cada fuente) -> código
    private val FLAGS = mapOf(
        "🇪🇸" to "es-ES",
        "🇲🇽" to "es-LA", "🇦🇷" to "es-LA", "🇨🇴" to "es-LA", "🇨🇱" to "es-LA",
        "🇵🇪" to "es-LA", "🇻🇪" to "es-LA", "🇺🇾" to "es-LA",
        "🇺🇸" to "en", "🇬🇧" to "en"
    )

    /**
     * Detecta idioma priorizando las BANDERAS del título (Torrentio) y, si no hay,
     * cae a la detección por palabras clave.
     *
     * Si entre las banderas está la de España **gana el castellano**, aunque haya
     * más: un enlace con 🇪🇸🇬🇧 se puede ver en castellano, y marcarlo como «multi»
     * lo hundía en la lista de quien tiene el español como idioma preferido. Solo
     * es «multi» cuando hay varias y ninguna es la española.
     */
    fun detectFromTitle(title: String): String? {
        val found = FLAGS.entries.filter { title.contains(it.key) }.map { it.value }.distinct()
        return when {
            found.contains("es-ES") -> "es-ES"
            found.size >= 2 -> "multi"
            found.size == 1 -> found[0]
            else -> detect(title)
        }
    }

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
     * Deja el nombre de un torrent listo para buscar palabras: todo a minúsculas y
     * con **cualquier separador convertido en espacio**.
     *
     * Sin esto había que escribir cada palabra en sus mil formas (`cast.`,
     * `[cast]`, `-CAST-`) y aun así se escapaban la mitad; normalizando primero,
     * basta con la palabra rodeada de espacios y no hay falsos positivos con
     * «podcast» o «Castle».
     */
    private fun normalize(name: String): String =
        " " + name.lowercase()
            .replace(Regex("[._\\-\\[\\]()/+,;:!¡?¿|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim() + " "

    /**
     * Detecta el idioma de un nombre de torrent. Devuelve el código o null si no
     * hay pistas claras.
     *
     * **El castellano se comprueba ANTES que multi/dual**, y es a propósito: antes
     * iba al revés y un «Oliver y Benji Dual Castellano Japonés» se marcaba como
     * «multi» en vez de español, así que con el idioma puesto en castellano el
     * enlace se hundía en la lista. Un dual con castellano dentro **se puede ver en
     * castellano**, que es lo único que importa aquí. «multi» queda para cuando
     * dice dual y no dice de qué idiomas.
     */
    fun detect(name: String): String? {
        val n = normalize(name)
        for (code in listOf("es-ES", "es-LA", "multi", "en")) {
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
