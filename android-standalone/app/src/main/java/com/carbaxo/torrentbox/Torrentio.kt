package com.carbaxo.torrentbox

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Fuentes vía Torrentio (lo mismo que usa Stremio): agrega muchos indexadores
 * y añade BANDERAS de idioma en el título, así que la detección de idioma es
 * mucho mejor que con apibay. Necesita el IMDb id del título (TMDB lo da).
 *
 *   película:  https://torrentio.strem.fun/stream/movie/tt1234567.json
 *   serie:     https://torrentio.strem.fun/stream/series/tt1234567:1:5.json
 */
object Torrentio {
    private const val BASE = "https://torrentio.strem.fun/stream"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val io = Executors.newCachedThreadPool()

    private val SEEDERS = Regex("👤\\s*(\\d+)")
    private val SIZE = Regex("💾\\s*([\\d.]+)\\s*(GB|MB|TB)", RegexOption.IGNORE_CASE)

    fun sizeToBytes(m: MatchResult?): Long {
        if (m == null) return 0
        val v = m.groupValues[1].toDoubleOrNull() ?: return 0
        return when (m.groupValues[2].uppercase()) {
            "TB" -> (v * 1024 * 1024 * 1024 * 1024).toLong()
            "GB" -> (v * 1024 * 1024 * 1024).toLong()
            "MB" -> (v * 1024 * 1024).toLong()
            else -> 0
        }
    }

    /**
     * Busca fuentes. type: "movie"|"series". Para series pasa season/episode.
     * Devuelve resultados con idioma detectado por bandera. onResult(list, error).
     */
    fun streams(type: String, imdbId: String, season: Int?, episode: Int?, onResult: (List<Search.Result>?, String?) -> Unit) {
        io.submit {
            try {
                val kind = if (type == "series") "series" else "movie"
                val id = if (kind == "series" && season != null)
                    "$imdbId:$season:${episode ?: 1}" else imdbId
                val url = "$BASE/$kind/$id.json"
                val req = Request.Builder().url(url).header("User-Agent", "TorrentBox").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@submit onResult(null, "Torrentio respondió ${resp.code}")
                    val body = resp.body?.string() ?: "{}"
                    val arr = JSONObject(body).optJSONArray("streams")
                    val out = ArrayList<Search.Result>()
                    if (arr != null) for (i in 0 until arr.length()) {
                        val s = arr.getJSONObject(i)
                        val hash = s.optString("infoHash", "")
                        if (hash.isBlank()) continue
                        val name = s.optString("name", "")     // p.ej. "Torrentio\n1080p"
                        val title = s.optString("title", "")   // nombre del fichero + 👤 💾 ⚙️ + banderas
                        val combined = "$name\n$title"
                        val filename = title.substringBefore('\n').ifBlank { name.replace("\n", " ") }
                        val seeders = SEEDERS.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val sizeBytes = sizeToBytes(SIZE.find(title))
                        out.add(
                            Search.Result(
                                name = filename,
                                infoHash = hash.lowercase(),
                                seeders = seeders,
                                sizeBytes = sizeBytes,
                                magnet = Search.buildMagnet(hash.lowercase(), filename),
                                lang = Lang.detectFromTitle(combined)
                            )
                        )
                    }
                    onResult(out, null)
                }
            } catch (e: Throwable) {
                onResult(null, e.message ?: "Error de red (Torrentio).")
            }
        }
    }
}
