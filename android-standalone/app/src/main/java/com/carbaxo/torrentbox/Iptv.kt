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

        /** ¿Parece un canal infantil? Sirve para el modo infantil del perfil. */
        val kids: Boolean
            get() = Regex("clan|kids|infantil|junior|jr\\b|cartoon|boing|nick|disney|panda|baby|super3|súper3",
                RegexOption.IGNORE_CASE).containsMatchIn("$name ${group ?: ""}")
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

    /** Carga la lista: la propia del usuario si la hay, y si no la integrada. */
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
                        list.clear(); list.addAll(ch)
                        status = "${ch.size} canales"
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
