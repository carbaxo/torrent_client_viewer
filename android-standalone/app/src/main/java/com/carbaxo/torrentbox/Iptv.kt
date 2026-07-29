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

    private var appCtx: android.content.Context? = null

    fun init(ctx: android.content.Context) { appCtx = ctx.applicationContext }

    /**
     * Fichero de la lista **importada** (pegada o traída de un fichero).
     *
     * Va a un fichero y no a las preferencias porque una M3U puede pesar megas, y
     * SharedPreferences se carga entero en memoria cada vez que se abre.
     */
    private fun importedFile(): java.io.File? =
        appCtx?.let { java.io.File(it.filesDir, "canales_importados.m3u") }

    /** ¿Hay una lista importada guardada? */
    val hasImported: Boolean get() = importedFile()?.let { it.exists() && it.length() > 0 } == true

    /**
     * Guarda una lista M3U pegada o importada de un fichero, y la deja activa.
     *
     * Existe porque una lista no siempre está en una URL a la que suscribirse:
     * puede venir en un documento, en un mensaje o en un fichero descargado. Antes
     * solo se aceptaba una URL, y con un texto en la mano no había forma de usarlo.
     *
     * @return cuántos canales se han reconocido, o null si el texto no era una M3U.
     */
    fun importText(text: String): Int? {
        val ch = parse(text)
        if (ch.isEmpty()) return null
        val f = importedFile() ?: return null
        runCatching { f.writeText(text) }.getOrElse { return null }
        load()
        return ch.size
    }

    /** Borra la lista importada y vuelve a lo que hubiera. */
    fun clearImported() {
        runCatching { importedFile()?.delete() }
        load()
    }

    private fun imported(): List<Channel> = runCatching {
        importedFile()?.takeIf { it.exists() }?.readText()?.let { parse(it) }.orEmpty()
    }.getOrDefault(emptyList())

    /** Carga la lista: los canales integrados más la propia del usuario si la hay. */
    fun load() {
        val url = Prefs.iptvUrl.trim()
        val imp = imported()
        if (url.isBlank()) {
            main.post {
                val todos = merge(imp)
                list.clear(); list.addAll(todos)
                status = if (imp.isEmpty()) ""
                else "${todos.size} canales (${imp.size} de tu lista importada + RTVE)"
                loading = false
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
                        // No se deja al usuario sin nada: quedan RTVE y lo importado
                        val todos = merge(imp)
                        list.clear(); list.addAll(todos)
                        status = "Esa lista no tenía canales reconocibles; se usan los de RTVE."
                    } else {
                        // La importada cuenta igual que la de la URL: se pueden tener las dos
                        val todos = merge(imp + ch)
                        list.clear(); list.addAll(todos)
                        status = "${todos.size} canales (${ch.size} de la URL" +
                            (if (imp.isNotEmpty()) " + ${imp.size} importados" else "") + " + RTVE)"
                    }
                }
            } catch (e: Throwable) {
                main.post {
                    loading = false
                    val todos = merge(imp)
                    list.clear(); list.addAll(todos)
                    status = "No se pudo cargar la lista (${e.message ?: "error de red"}); se usan los de RTVE."
                }
            }
        }
    }
}
