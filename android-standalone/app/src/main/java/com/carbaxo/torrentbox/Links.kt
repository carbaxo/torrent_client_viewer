package com.carbaxo.torrentbox

/**
 * Arregla los enlaces que llegan pegados o compartidos desde un móvil, que casi
 * nunca llegan limpios.
 *
 * Los dos casos vistos de verdad, no imaginados:
 *
 *  - **Fragmento de texto de Chrome**: al usar «copiar enlace al texto resaltado»
 *    añade `#:~:text=…` al final. Es un trozo de URL que solo entiende el
 *    navegador; al pedir la página con eso puesto, unas webs lo ignoran y otras
 *    responden mal.
 *  - **Falta el esquema**: se pega `dontorrent.management/serie/…` sin el
 *    `https://` delante, y entonces no parece un enlace.
 *
 * Y uno que NO se puede arreglar y hay que saber decirlo: que el pegado se haya
 * llevado el enlace **a medias** y falte el principio (dominio incluido). Para eso
 * está [looksTruncated], que permite dar un aviso que se entienda en vez de un
 * «eso no es un enlace» que no ayuda a nadie.
 */
object Links {

    /** Quita la morralla y pone el esquema si falta. */
    fun tidy(raw: String): String {
        var s = raw.trim().trim('"', '\'', '<', '>')
        // El fragmento de texto de Chrome no es parte de la dirección
        s = s.substringBefore("#:~:")
        if (s.startsWith("magnet:", true) || s.startsWith("http", true)) return s
        // «dominio.algo/ruta» sin esquema: se le pone https
        if (Regex("^[a-z0-9.-]+\\.[a-z]{2,}(/|$)", RegexOption.IGNORE_CASE).containsMatchIn(s)) {
            return "https://$s"
        }
        return s
    }

    /**
     * ¿Tiene pinta de enlace al que le falta el principio? Es lo que pasa cuando
     * el pegado en el móvil se lleva solo parte: queda algo como
     * `02/33703/Oliver-y-Benji-…`, sin dominio, y no hay forma de reconstruirlo.
     */
    fun looksTruncated(s: String): Boolean {
        val v = s.trim()
        if (v.startsWith("magnet:", true) || v.startsWith("http", true)) return false
        // Empieza por números o barra y tiene aspecto de ruta con varios tramos
        return Regex("^/?\\d+/").containsMatchIn(v) ||
            (v.startsWith("/") && v.count { it == '/' } >= 2) ||
            (v.count { it == '/' } >= 2 && !v.contains(' ') && !v.contains('.'))
    }
}
