package com.carbaxo.torrentbox

/**
 * Busca en **tu propia cuenta de Real-Debrid** y lo ofrece como un motor más en la
 * ficha, junto a Peerflix y Torrentio.
 *
 * Esto es lo que cierra el círculo: cuando un título no aparece en ningún addon —
 * el caso de las series infantiles en castellano — se añade el torrent a mano una
 * vez, y a partir de ahí **sale solo en la ficha**, como cualquier otro enlace, con
 * su reproducción, su descarga y su selector de capítulos si es un pack.
 *
 * Es el motor más fiable de los tres, y por eso va primero en la lista:
 *
 *  - Usa la **API oficial** de RD, así que no se rompe cuando una web cambia.
 *  - Lo que sale ya está **en tu cuenta**: se ve al instante, sin esperar semillas
 *    ni depender de que RD lo tenga en caché.
 */
object RdEngine {

    /**
     * Copia de la lista de torrents con caducidad corta. Sin esto, cada ficha (y
     * cada episodio que se toca) pediría la lista entera a Real-Debrid otra vez.
     */
    private var cache: List<RealDebrid.Torrent> = emptyList()
    private var cacheAt = 0L
    private const val TTL_MS = 60_000L

    /** Palabras del título que valen para comparar (fuera artículos y ruido). */
    private fun tokens(s: String): List<String> =
        s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .split(' ')
            .filter { it.length >= 3 && it !in STOP }

    private val STOP = setOf("the", "los", "las", "una", "unos", "unas", "del", "por", "con", "para")

    /**
     * ¿Este torrent de la cuenta es de este título?
     *
     * Se exige que **todas** las palabras del título aparezcan en el nombre del
     * torrent, no que se parezcan: los nombres de los torrents traen mucha morralla
     * (grupo, códec, año) y comparar el conjunto entero no casaría nunca. Así
     * «Peppa Pig» encuentra «Peppa.Pig.1.Temporada.1x01.al.1x13.HDTV».
     */
    private fun matches(title: String, torrentName: String): Boolean {
        val t = tokens(title)
        if (t.isEmpty()) return false
        val n = torrentName.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        return t.all { n.contains(it) }
    }

    /** ¿El nombre dice que es una temporada o serie completa? */
    private fun looksPack(name: String): Boolean = Regex(
        "temporada|completa|complete|season|\\d+x\\d+\\s*al\\s*\\d+x\\d+|pack|s\\d{2}(?!e\\d)",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(name)

    private fun toResult(t: RealDebrid.Torrent): Search.Result = Search.Result(
        name = t.name,
        infoHash = t.hash,
        seeders = t.seeders,
        sizeBytes = t.bytes,
        magnet = Search.buildMagnet(t.hash, t.name),
        // Lo que se añade a mano aquí es casi siempre castellano; si el nombre lo
        // dice, se respeta lo que diga.
        lang = Lang.detectFromTitle(t.name) ?: "es",
        quality = Search.quality(t.name),
        engine = Search.ENGINE_RD,
        info = if (t.ready) "✅ en tu Real-Debrid" else "⏳ en tu Real-Debrid (${RealDebrid.statusEs(t.status)})",
        // Varios archivos = pack, aunque el nombre no lo diga: es lo que hace que
        // caiga en el selector de capítulos.
        pack = looksPack(t.name) || t.links > 1
    )

    /**
     * Enlaces de la cuenta que casan con el título. Nunca falla hacia fuera: si RD
     * no contesta, devuelve la lista vacía y los demás motores siguen su camino.
     */
    fun streams(title: String, onResult: (List<Search.Result>?, String?) -> Unit) {
        if (!RealDebrid.configured) return onResult(emptyList(), null)
        val fresh = System.currentTimeMillis() - cacheAt < TTL_MS
        if (fresh && cache.isNotEmpty()) {
            return onResult(cache.filter { matches(title, it.name) }.map { toResult(it) }, null)
        }
        RealDebrid.torrents { list, _ ->
            if (list == null) return@torrents onResult(emptyList(), null)
            cache = list; cacheAt = System.currentTimeMillis()
            onResult(list.filter { matches(title, it.name) }.map { toResult(it) }, null)
        }
    }

    /** Olvida la copia: se llama al añadir algo, para que salga ya en la ficha. */
    fun invalidate() { cacheAt = 0L }
}
