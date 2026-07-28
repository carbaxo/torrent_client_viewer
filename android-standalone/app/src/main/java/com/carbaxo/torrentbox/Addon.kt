package com.carbaxo.torrentbox

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Trozo común de los addons de **Stremio** (Peerflix, DonTorrent y cualquiera que
 * se añada después). Todos hablan el mismo protocolo:
 *
 *   película:  BASE/stream/movie/tt1234567.json
 *   serie:     BASE/stream/series/tt1234567:1:5.json
 *
 * y contestan `{"streams":[…]}`. Lo que cambia de uno a otro es la base y poco
 * más, así que interpretarlo una sola vez evita que un arreglo (como el de
 * `description`, ver abajo) haya que repetirlo en cada motor.
 */
object Addon {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    /** Los addons marcan las semillas de varias formas; ninguna es obligatoria. */
    private val SEEDERS = Regex("(?:👤|seeders?\\s*:?)\\s*(\\d+)", RegexOption.IGNORE_CASE)

    /**
     * Limpia una base pegada por el usuario. Se acepta tal cual la copie de donde
     * sea: la del manifest, la de la página de configuración o con barra final.
     * Sin esto, pegar la URL que da la web del addon daba siempre 404.
     */
    fun cleanBase(raw: String): String = raw.trim()
        .removeSuffix("/")
        .removeSuffix("/manifest.json")
        .removeSuffix("/configure")
        .removeSuffix("/")

    /** Ruta del recurso de streams para una película o un episodio. */
    fun streamPath(type: String, imdbId: String, season: Int?, episode: Int?): String {
        val kind = if (type == "series") "series" else "movie"
        val id = if (kind == "series" && season != null) "$imdbId:$season:${episode ?: 1}" else imdbId
        return "/stream/$kind/$id.json"
    }

    /**
     * Convierte la respuesta de un addon en resultados del buscador.
     *
     * El detalle se lee de `title` **y** de `description`: los addons antiguos lo
     * ponen en el primero y los modernos en el segundo (el SDK de Stremio lo
     * renombró). Leyendo solo `title`, los enlaces de un addon moderno salen sin
     * nombre de fichero, sin tamaño y con 0 semillas: parecen muertos sin serlo.
     */
    fun parseStreams(body: JSONObject, engine: String): List<Search.Result> {
        val arr = body.optJSONArray("streams") ?: return emptyList()
        val out = ArrayList<Search.Result>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val hash = s.optString("infoHash", "")
            if (hash.isBlank()) continue
            val name = s.optString("name", "")   // "Peerflix 🇪🇸 720p"
            val detail = Search.pickDetail(s.optString("title", ""), s.optString("description", ""))
            val bh = s.optJSONObject("behaviorHints")
            val binge = bh?.optString("bingeGroup", "") ?: ""
            val combined = "$name\n$detail\n$binge"
            val filename = Search.pickFilename(bh?.optString("filename", "") ?: "", detail, name)
            // Algunos addons dan las semillas como número, no dentro del texto
            val seeders = s.optInt("seeders", -1).takeIf { it >= 0 }
                ?: SEEDERS.find(detail)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            out.add(
                Search.Result(
                    name = filename,
                    infoHash = hash.lowercase(),
                    seeders = seeders,
                    sizeBytes = Torrentio.streamSize(s, detail),
                    magnet = Search.buildMagnet(hash.lowercase(), filename),
                    lang = Lang.detectFromTitle(combined),
                    quality = Search.quality(combined),
                    engine = engine,
                    info = Search.pickInfo(detail, filename)
                )
            )
        }
        return out
    }

    /**
     * Pide una URL de addon. Devuelve null si no se pudo, **sin lanzar**: un addon
     * caído no puede tumbar la búsqueda de los demás.
     */
    fun get(url: String, engine: String): List<Search.Result>? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "VizPlay").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else parseStreams(JSONObject(resp.body?.string() ?: "{}"), engine)
        }
    }.getOrNull()
}
