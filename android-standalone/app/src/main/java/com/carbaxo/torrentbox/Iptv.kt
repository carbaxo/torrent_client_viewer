package com.carbaxo.torrentbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Canales de TV en directo desde una lista **M3U**.
 *
 * Existe por un problema concreto: las series infantiles en castellano (Peppa
 * Pig, Bluey…) no aparecen en los índices de Peerflix ni de Torrentio, ni por
 * episodio ni como pack. Se comprobó y no salía nada. Pero **Clan**, el canal
 * infantil de RTVE, emite justo eso 24 horas, es gratis y es de la televisión
 * pública: para el día a día con niños funciona mejor que cazar torrents de
 * capítulos de cinco minutos.
 *
 * Por defecto va una lista **integrada** con los canales de RTVE, que son
 * públicos y legítimos. Se puede sustituir por cualquier M3U propia en
 * Ajustes → Canales.
 */
object Iptv {

    data class Channel(
        val name: String,
        val url: String,
        val logo: String? = null,
        val group: String? = null
    ) {
        /** Nombre limpio: las listas M3U arrastran anotaciones entre corchetes. */
        val clean: String
            get() = name.replace(Regex("\\s*\\[(?:Geo-blocked|Not 24/7)\\]", RegexOption.IGNORE_CASE), "").trim()

        /**
         * ¿Parece un canal infantil? Es lo que decide qué se ve con perfil
         * infantil, y ahí se juega en los dos sentidos: si se queda corto,
         * desaparecen canales que sí valen (y como «En directo» solo existe en el
         * perfil infantil, desaparecen del todo); si se pasa de ancho, entra
         * cualquier cosa.
         *
         * Por eso van los nombres de las series concretas — Dragon Ball, los
         * clásicos de Pluto TV — en vez de un «anime» a secas, que arrastraría
         * canales de anime para adultos.
         */
        val kids: Boolean
            get() = Regex(
                "clan|kids|infantil|junior|jr\\b|cartoon|boing|nick|disney|panda|baby|super3|súper3|" +
                    "toons|dibujo|peppa|bluey|pocoy|reino infantil|dragon ?ball|saint seiya|" +
                    "caballeros del zodiaco|abeja maya|heidi|doraemon|pok[eé]mon",
                RegexOption.IGNORE_CASE
            ).containsMatchIn("$name ${group ?: ""}")
    }

    /**
     * Canales integrados: los de **RTVE**, la televisión pública española.
     *
     * Se eligen a mano y no se coge una lista pública entera a propósito: esas
     * listas mezclan emisiones oficiales de televisiones públicas con
     * retransmisiones NO autorizadas de canales de pago, y no quiero meter eso de
     * serie en una app con perfil infantil.
     *
     * Solo se ven desde España (RTVE bloquea por país), que es donde se usa.
     */
    private val BUILT_IN = listOf(
        Channel("Clan", "https://ztnr.rtve.es/ztnr/5466990.m3u8", group = "Infantil"),
        Channel("Clan (alternativo)", "https://rtvelivestream.rtve.es/rtvesec/clan/clan_main.m3u8", group = "Infantil"),
        Channel("La 1", "https://ztnr.rtve.es/ztnr/1688877.m3u8", group = "Generalista"),
        Channel("La 2", "https://ztnr.rtve.es/ztnr/1688885.m3u8", group = "Generalista"),
        Channel("Teledeporte", "https://ztnr.rtve.es/ztnr/1712295.m3u8", group = "Deportes"),
        Channel("Canal 24h", "https://ztnr.rtve.es/ztnr/1694255.m3u8", group = "Noticias")
    )

    /** Lista M3U que se puede poner de un toque en Ajustes → Canales. */
    data class Preset(val name: String, val url: String, val note: String)

    /**
     * Atajos a las plataformas **FAST** españolas: televisión gratuita, legal y
     * con publicidad, de Paramount (Pluto TV) y Samsung. Sus canales son oficiales
     * — nada de reemisiones piratas —, y ahí está lo que no hay en los índices de
     * torrents: Pluto TV España emite **Dragon Ball en castellano y sin censura**
     * 24 h, más Dragon Ball Z y Saint Seiya, y varios canales de dibujos.
     *
     * Las URL son listas mantenidas por terceros que recogen los canales de esas
     * plataformas; **no se han podido verificar** al programar esto. Por eso se
     * suman a los canales de RTVE en vez de sustituirlos, y por eso se puede
     * volver a RTVE de un toque: si una lista deja de funcionar, no se queda el
     * perfil infantil sin nada.
     */
    val PRESETS = listOf(
        Preset(
            "Pluto TV España", "https://i.mjh.nz/PlutoTV/es.m3u8",
            "Dragon Ball y Dragon Ball Z en castellano, Saint Seiya y canales de dibujos."
        ),
        Preset(
            "Samsung TV Plus España", "https://i.mjh.nz/SamsungTVPlus/es.m3u8",
            "Canales gratis de Samsung, con varios infantiles."
        )
    )

    val list = mutableStateListOf<Channel>()
    var status by mutableStateOf("")
        private set
    var loading by mutableStateOf(false)
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val io = Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Interpreta una lista M3U.
     *
     * Entre el `#EXTINF` y su URL puede haber más líneas de directiva
     * (`#EXTVLCOPT`, `#EXTGRP`…), así que la URL es la siguiente línea que NO
     * empieza por `#`: emparejar por posición fija se rompe con esas listas.
     */
    fun parse(text: String): List<Channel> {
        val lines = text.split("\n")
        val out = ArrayList<Channel>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i].trim()
            if (!l.startsWith("#EXTINF", ignoreCase = true)) { i++; continue }
            val name = l.substringAfter(",", "").trim()
            val logo = Regex("tvg-logo=\"([^\"]*)\"").find(l)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            var group = Regex("group-title=\"([^\"]*)\"").find(l)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            var j = i + 1
            while (j < lines.size && lines[j].trim().startsWith("#")) {
                val d = lines[j].trim()
                if (d.startsWith("#EXTGRP:", true)) group = d.substringAfter(":").trim().ifBlank { group }
                j++
            }
            val url = lines.getOrNull(j)?.trim().orEmpty()
            if (name.isNotBlank() && url.startsWith("http", ignoreCase = true)) {
                out.add(Channel(name, url, logo, group))
            }
            i = j + 1
        }
        return out
    }

    /**
     * Une los canales integrados con los de una lista propia, sin repetidos.
     *
     * Se SUMAN en vez de sustituirse a propósito: si poner una lista propia
     * borrase los canales de RTVE, elegir la de una plataforma FAST dejaría al
     * perfil infantil sin Clan, que es justo el canal que más se usa. Los
     * integrados van primero; de un canal repetido se queda el integrado, que es
     * el oficial de RTVE.
     */
    private fun merge(extra: List<Channel>): List<Channel> {
        val out = ArrayList<Channel>(BUILT_IN)
        val seen = BUILT_IN.mapTo(HashSet()) { it.clean.lowercase() }
        for (c in extra) if (seen.add(c.clean.lowercase())) out.add(c)
        return out
    }

    /** Carga la lista: los canales integrados más la propia del usuario si la hay. */
    fun load() {
        val url = Prefs.iptvUrl.trim()
        if (url.isBlank()) {
            main.post {
                list.clear(); list.addAll(BUILT_IN)
                status = ""; loading = false
            }
            return
        }
        loading = true
        io.submit {
            try {
                val req = Request.Builder().url(url).header("User-Agent", "VizPlay").build()
                val body = client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw RuntimeException("La lista respondió ${r.code}")
                    r.body?.string() ?: ""
                }
                val ch = parse(body)
                main.post {
                    loading = false
                    if (ch.isEmpty()) {
                        // No se deja al usuario sin nada: se vuelve a la integrada
                        list.clear(); list.addAll(BUILT_IN)
                        status = "Esa lista no tenía canales reconocibles; se usan los de RTVE."
                    } else {
                        val todos = merge(ch)
                        list.clear(); list.addAll(todos)
                        status = "${todos.size} canales (${ch.size} de tu lista + RTVE)"
                    }
                }
            } catch (e: Throwable) {
                main.post {
                    loading = false
                    list.clear(); list.addAll(BUILT_IN)
                    status = "No se pudo cargar la lista (${e.message ?: "error de red"}); se usan los de RTVE."
                }
            }
        }
    }
}
