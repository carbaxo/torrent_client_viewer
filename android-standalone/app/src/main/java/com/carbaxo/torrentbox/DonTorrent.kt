package com.carbaxo.torrentbox

import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Addon **DonTorrent** de Stremio: indexa DonTorrent, que publica en
 * **castellano**.
 *
 * Existe por el motivo de fondo de que no aparezcan enlaces en español: Torrentio
 * y Peerflix indexan sobre todo releases en inglés, y los estrenos doblados al
 * castellano (series de Netflix, anime como Oliver y Benji, dibujos…) viven en
 * índices españoles que ninguno de los dos mira a fondo. Este motor es lo que
 * arregla eso de raíz, para cualquier título y no solo para los previstos.
 *
 * **No hace falta configurarlo con Real-Debrid.** El addon devuelve el `infoHash`
 * del torrent y la propia app ya se encarga de mandar el magnet a Real-Debrid y
 * pedir el enlace directo. Configurarlo solo serviría para que el addon hiciera
 * por su cuenta lo que la app ya hace.
 *
 * Si aun así se quiere usar una instancia propia (o la configurada con la cuenta
 * de RD), se pega su URL en Ajustes → Buscadores y se usa esa.
 */
object DonTorrent {
    /**
     * Página pública del addon. La de configuración es `$DEFAULT_BASE/configure`;
     * los recursos van en la raíz, igual que en cualquier addon de Stremio.
     */
    const val DEFAULT_BASE = "https://streamingaddons.xyz"

    private val io = Executors.newCachedThreadPool()

    /** Base del addon: la de Ajustes si la hay, y si no la pública. */
    private fun base(): String {
        val custom = Prefs.donTorrentUrl.trim()
        return Addon.cleanBase(if (custom.isNotBlank()) custom else DEFAULT_BASE)
    }

    /**
     * PACKS de temporada o de serie completa. Ver [Torrentio.packs]: en castellano
     * lo normal es que una serie se publique entera y no capítulo a capítulo, así
     * que preguntando solo por el episodio no sale nada.
     */
    fun packs(imdbId: String, season: Int?, onResult: (List<Search.Result>) -> Unit) {
        io.submit {
            val out = LinkedHashMap<String, Search.Result>()
            for (id in listOfNotNull(imdbId, season?.let { "$imdbId:$it" })) {
                Addon.get("${base()}/stream/series/$id.json", Search.ENGINE_DONTORRENT)
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
                    if (!resp.isSuccessful) return@submit onResult(null, "DonTorrent respondió ${resp.code}")
                    onResult(
                        Addon.parseStreams(JSONObject(resp.body?.string() ?: "{}"), Search.ENGINE_DONTORRENT),
                        null
                    )
                }
            } catch (e: Throwable) {
                onResult(null, e.message ?: "Error de red (DonTorrent).")
            }
        }
    }

    /**
     * Prueba de conexión para Ajustes. Hace falta porque este addon depende de que
     * la web de DonTorrent esté accesible: se cae y se bloquea cada cierto tiempo,
     * y sin una comprobación el usuario no puede distinguir "no hay enlaces de
     * esta película" de "el addon no responde".
     */
    fun test(onResult: (String) -> Unit) {
        io.submit {
            val url = base() + Addon.streamPath("movie", "tt0468569", null, null)  // El caballero oscuro
            val r = runCatching {
                val req = Request.Builder().url(url).header("User-Agent", "VizPlay").build()
                Addon.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching "El addon respondió ${resp.code}. Revisa la URL."
                    val n = Addon.parseStreams(
                        JSONObject(resp.body?.string() ?: "{}"), Search.ENGINE_DONTORRENT
                    ).size
                    if (n > 0) "✅ Funciona: $n enlaces de prueba."
                    else "Responde, pero sin enlaces. Puede que DonTorrent esté caído o bloqueado."
                }
            }
            onResult(r.getOrElse { "No se pudo conectar: ${it.message ?: "error de red"}" })
        }
    }
}
