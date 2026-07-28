package com.carbaxo.torrentbox

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Addon **Peerflix** de Stremio: el mismo que usa la app de Stremio, así que
 * devuelve los mismos enlaces. Indexa sobre todo webs españolas (Dontorrent,
 * MejorTorrent, Wolfmax4k, Popcorntime, Bitsearch), que no están en The Pirate
 * Bay ni en Torrentio por defecto — por eso antes faltaban.
 *
 * Protocolo estándar de addon de Stremio, igual que Torrentio:
 *   película:  https://peerflix.mov/stream/movie/tt1234567.json
 *   serie:     https://peerflix.mov/stream/series/tt1234567:1:5.json
 *
 * Si el usuario configura su Peerflix en <https://config.peerflix.mov> (por
 * ejemplo con su cuenta de Real-Debrid), obtiene una URL con su configuración
 * dentro; se puede pegar en Ajustes y se usará esa.
 */
object Peerflix {
    const val DEFAULT_BASE = "https://peerflix.mov"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val io = Executors.newCachedThreadPool()

    // Los addons marcan las semillas de varias formas; ninguna es obligatoria
    private val SEEDERS = Regex("(?:👤|seeders?\\s*:?)\\s*(\\d+)", RegexOption.IGNORE_CASE)

    /** Base del addon: la de Ajustes si la hay, y si no la pública. */
    private fun base(): String {
        val custom = Prefs.peerflixUrl.trim()
        val b = if (custom.isNotBlank()) custom else DEFAULT_BASE
        // Acepta que peguen la URL del manifest o con barra final
        return b.removeSuffix("/").removeSuffix("/manifest.json").removeSuffix("/")
    }

    /**
     * Busca fuentes por IMDb id. type: "movie"|"series".
     * Si el addon no responde, onResult(null, error) y el buscador sigue con los
     * demás motores: nunca se queda peor que antes.
     */
    fun streams(type: String, imdbId: String, season: Int?, episode: Int?, onResult: (List<Search.Result>?, String?) -> Unit) {
        io.submit {
            try {
                val kind = if (type == "series") "series" else "movie"
                val id = if (kind == "series" && season != null)
                    "$imdbId:$season:${episode ?: 1}" else imdbId
                val url = "${base()}/stream/$kind/$id.json"
                val req = Request.Builder().url(url).header("User-Agent", "TorrentBox").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@submit onResult(null, "Peerflix respondió ${resp.code}")
                    val arr = JSONObject(resp.body?.string() ?: "{}").optJSONArray("streams")
                    val out = ArrayList<Search.Result>()
                    if (arr != null) for (i in 0 until arr.length()) {
                        val s = arr.getJSONObject(i)
                        val hash = s.optString("infoHash", "")
                        if (hash.isBlank()) continue
                        val name = s.optString("name", "")   // "Peerflix 🇪🇸 720p"
                        // Este addon manda el detalle en "description", no en
                        // "title": leyendo solo title salían sin nombre de fichero,
                        // sin tamaño y con 0 seeders.
                        val detail = Search.pickDetail(s.optString("title", ""), s.optString("description", ""))
                        val bh = s.optJSONObject("behaviorHints")
                        val binge = bh?.optString("bingeGroup", "") ?: ""
                        val combined = "$name\n$detail\n$binge"
                        val filename = Search.pickFilename(bh?.optString("filename", "") ?: "", detail, name)
                        // Algunos addons dan las semillas como número, no en el texto
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
                                engine = Search.ENGINE_PEERFLIX,
                                info = Search.pickInfo(detail, filename)
                            )
                        )
                    }
                    onResult(out, null)
                }
            } catch (e: Throwable) {
                onResult(null, e.message ?: "Error de red (Peerflix).")
            }
        }
    }
}
