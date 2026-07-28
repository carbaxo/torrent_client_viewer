package com.carbaxo.torrentbox

import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Un **addon de Stremio cualquiera**, a elección del usuario. Vacío por defecto:
 * si no se configura, no existe y no se pregunta a nadie.
 *
 * Nació como un motor fijo de DonTorrent y se quedó en esto por dos motivos que
 * conviene no olvidar:
 *
 *  1. El addon público de DonTorrent que se iba a usar (`streamingaddons.xyz`)
 *     **ya no existe**: su dominio no resuelve.
 *  2. Y sobre todo: **Peerflix ya indexa DonTorrent**. Sus proveedores son
 *     Bitsearch, Popcorntime, DonTorrent, MejorTorrent y Wolfmax4K. Un motor
 *     dedicado a DonTorrent no añadía ni un enlace.
 *
 * Así que en vez de un motor concreto que puede morir, aquí se pega la URL del
 * addon que se quiera y ya. Sirve, por ejemplo, para:
 *
 *  - **MediaFusion**, **Comet** o **Jackettio**, que se pueden configurar con
 *    indexadores españoles y dan una URL con esa configuración dentro.
 *  - Cualquier addon nuevo que aparezca, sin esperar a una versión de la app.
 *
 * No hace falta configurarlo con Real-Debrid: basta con que devuelva `infoHash`,
 * porque la app ya manda el magnet a RD por su cuenta.
 */
object ExtraAddon {

    private val io = Executors.newCachedThreadPool()

    /** ¿Hay addon puesto? Si no, este motor no se consulta ni se muestra. */
    val configured: Boolean get() = Prefs.extraAddonUrl.isNotBlank()

    private fun base(): String = Addon.cleanBase(Prefs.extraAddonUrl)

    /**
     * PACKS de temporada o de serie completa. Ver [Torrentio.packs]: en castellano
     * lo normal es que una serie se publique entera y no capítulo a capítulo, así
     * que preguntando solo por el episodio no sale nada.
     */
    fun packs(imdbId: String, season: Int?, onResult: (List<Search.Result>) -> Unit) {
        if (!configured) return onResult(emptyList())
        io.submit {
            val out = LinkedHashMap<String, Search.Result>()
            for (id in listOfNotNull(imdbId, season?.let { "$imdbId:$it" })) {
                Addon.get("${base()}/stream/series/$id.json", Search.ENGINE_EXTRA)
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
        if (!configured) return onResult(emptyList(), null)
        io.submit {
            try {
                val url = base() + Addon.streamPath(type, imdbId, season, episode)
                val req = Request.Builder().url(url).header("User-Agent", "VizPlay").build()
                Addon.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@submit onResult(null, "El addon extra respondió ${resp.code}")
                    onResult(
                        Addon.parseStreams(JSONObject(resp.body?.string() ?: "{}"), Search.ENGINE_EXTRA),
                        null
                    )
                }
            } catch (e: Throwable) {
                onResult(null, e.message ?: "Error de red (addon extra).")
            }
        }
    }

    /**
     * Prueba de conexión para Ajustes. Hace falta porque estos addons van y
     * vienen: cambian de dominio, se caen y algunos exigen una URL con la
     * configuración dentro. Sin comprobarlo, el usuario no puede distinguir «esta
     * película no tiene enlaces» de «la URL que he pegado no vale».
     */
    fun test(onResult: (String) -> Unit) {
        if (!configured) return onResult("Pon primero la URL del addon.")
        io.submit {
            // El caballero oscuro: si un addon de películas funciona, esta la tiene
            val url = base() + Addon.streamPath("movie", "tt0468569", null, null)
            val r = runCatching {
                val req = Request.Builder().url(url).header("User-Agent", "VizPlay").build()
                Addon.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching "El addon respondió ${resp.code}. Revisa la URL."
                    val n = Addon.parseStreams(
                        JSONObject(resp.body?.string() ?: "{}"), Search.ENGINE_EXTRA
                    ).size
                    if (n > 0) "✅ Funciona: $n enlaces de prueba."
                    else "Responde, pero sin enlaces. Puede que necesite una URL con tu configuración dentro."
                }
            }
            onResult(r.getOrElse { "No se pudo conectar: ${it.message ?: "error de red"}" })
        }
    }
}
