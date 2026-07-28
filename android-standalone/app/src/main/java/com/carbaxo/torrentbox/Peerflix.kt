package com.carbaxo.torrentbox

import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Addon **Peerflix** de Stremio: el mismo que usa la app de Stremio, así que
 * devuelve los mismos enlaces. Indexa sobre todo webs españolas (Dontorrent,
 * MejorTorrent, Wolfmax4k, Popcorntime, Bitsearch), que no están en Torrentio por
 * defecto — por eso antes faltaban.
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

    private val io = Executors.newCachedThreadPool()

    /** Base del addon: la de Ajustes si la hay, y si no la pública. */
    private fun base(): String {
        val custom = Prefs.peerflixUrl.trim()
        return Addon.cleanBase(if (custom.isNotBlank()) custom else DEFAULT_BASE)
    }

    /** Pide una URL del addon. Devuelve null si no se pudo (no lanza). */
    private fun get(url: String): List<Search.Result>? =
        Addon.get(url, Search.ENGINE_PEERFLIX)

    /**
     * PACKS de temporada o de serie completa. Ver [Torrentio.packs] para el motivo:
     * las series infantiles en castellano casi nunca se publican por capítulos.
     */
    fun packs(imdbId: String, season: Int?, onResult: (List<Search.Result>) -> Unit) {
        io.submit {
            val out = LinkedHashMap<String, Search.Result>()
            for (id in listOfNotNull(imdbId, season?.let { "$imdbId:$it" })) {
                get("${base()}/stream/series/$id.json")
                    ?.forEach { out.putIfAbsent(it.infoHash, it.copy(pack = true)) }
            }
            onResult(out.values.toList())
        }
    }

    /**
     * Busca fuentes por IMDb id. type: "movie"|"series".
     * Si el addon no responde, onResult(null, error) y el buscador sigue con los
     * demás motores: nunca se queda peor que antes.
     */
    fun streams(type: String, imdbId: String, season: Int?, episode: Int?, onResult: (List<Search.Result>?, String?) -> Unit) {
        io.submit {
            try {
                val url = base() + Addon.streamPath(type, imdbId, season, episode)
                val req = Request.Builder().url(url).header("User-Agent", "VizPlay").build()
                Addon.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@submit onResult(null, "Peerflix respondió ${resp.code}")
                    onResult(
                        Addon.parseStreams(JSONObject(resp.body?.string() ?: "{}"), Search.ENGINE_PEERFLIX),
                        null
                    )
                }
            } catch (e: Throwable) {
                onResult(null, e.message ?: "Error de red (Peerflix).")
            }
        }
    }
}
