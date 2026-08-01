package com.carbaxo.torrentbox

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Canales de YouTube en «En directo», reproducidos **dentro** de VizPlay.
 *
 * El «dentro» es el punto entero. Ya hubo atajos a YouTube y se quitaron porque
 * abrían la app de YouTube, y eso no es esta app: se pierde el perfil infantil,
 * el niño acaba en la pantalla de recomendaciones y para volver hay que salir.
 * Aquí se usa el **reproductor incrustado oficial** en un WebView propio, con la
 * navegación cerrada, así que no hay forma de terminar fuera (ver [YtPlayer]).
 *
 * ### Por qué el incrustado y no extraer el vídeo
 *
 * Lo suyo sería sacar la URL real del stream y dársela a ExoPlayer, que es mejor
 * reproductor y permitiría Chromecast. Se descartó **por ahora** por dos razones,
 * en este orden:
 *
 * 1. Las librerías que lo hacen (NewPipeExtractor, yt-dlp) se rompen cada vez que
 *    YouTube cambia algo por dentro, y arreglarlo significa publicar una versión
 *    nueva del APK. Esto lo van a usar unos niños en la tele: que deje de
 *    funcionar un martes cualquiera es peor que no tener Chromecast.
 * 2. NewPipeExtractor solo se publica en JitPack, que no era accesible al
 *    programar esto, así que habría habido que fijar una versión a ciegas.
 *
 * El incrustado, en cambio, es la vía que YouTube mantiene a propósito: no se
 * rompe. Lo que se pierde es Chromecast y el control fino con el mando. Si algún
 * día compensa, el sitio por donde entra la extracción es [Ref], sustituyendo la
 * URL de incrustación por la del manifiesto y tirando de ExoPlayer.
 *
 * ### Por qué no hay canales de serie
 *
 * No se pueden comprobar los IDs de YouTube sin acceso a YouTube, y un ID mal
 * copiado **no da error**: cae en otro vídeo cualquiera. En una sección infantil
 * eso es inaceptable, así que los pone el usuario, que sí los está mirando.
 * Compartiendo desde la propia app de YouTube son dos toques.
 */
object YouTube {

    enum class Kind { VIDEO, PLAYLIST }

    /** Lo mínimo para reproducir: qué es y su identificador. */
    data class Ref(val kind: Kind, val id: String)

    data class Entry(val ref: Ref, val name: String)

    /** Canales añadidos, en el orden en que se añadieron. */
    val list = mutableStateListOf<Entry>()

    /**
     * Último aviso, para pintarlo en Ajustes.
     *
     * Hace falta sobre todo para el enlace que llega **compartido** desde la app de
     * YouTube: ahí no hay ningún formulario delante, así que sin esto el usuario no
     * sabría si se añadió, si estaba repetido o si el enlace no valía.
     */
    var status by mutableStateOf("")

    private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        load()
    }

    // ------------------------------------------------------------- interpretar

    /**
     * Un ID de vídeo son 11 caracteres del alfabeto base64 URL; uno de lista
     * empieza por `PL`, `UU`, `LL`, `RD`… y es más largo. No se validan más allá
     * de la forma: quien decide si existe es YouTube al cargarlo.
     */
    private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")
    private val LIST_ID = Regex("""[A-Za-z0-9_-]{12,}""")

    /**
     * Saca la referencia de **cualquier** forma de enlace de YouTube.
     *
     * Se aceptan todas porque cada sitio comparte a su manera: la app de Android
     * manda `youtu.be`, el navegador `watch?v=`, un directo `/live/`, y de un canal
     * sale `/playlist?list=`. Exigir una sola forma sería rechazar justo lo que el
     * usuario acaba de copiar.
     *
     * Se busca dentro del texto y no se exige que empiece por `http` porque al
     * compartir llega «Título del vídeo \n enlace», igual que en [MagnetInbox].
     */
    fun parse(text: String?): Ref? {
        val t = text?.trim().orEmpty()
        if (t.isBlank()) return null

        // Una lista manda sobre el vídeo: si el enlace trae las dos cosas
        // (watch?v=X&list=Y), lo que el usuario ha abierto es la lista.
        Regex("""[?&]list=([A-Za-z0-9_-]{12,})""").find(t)?.let {
            return Ref(Kind.PLAYLIST, it.groupValues[1])
        }
        for (re in listOf(
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""youtube\.com/live/([A-Za-z0-9_-]{11})"""),
            Regex("""youtube\.com/embed/([A-Za-z0-9_-]{11})"""),
            Regex("""youtube\.com/shorts/([A-Za-z0-9_-]{11})""")
        )) re.find(t)?.let { return Ref(Kind.VIDEO, it.groupValues[1]) }

        // Un ID pelado, por si se pega solo el identificador
        if (t.matches(VIDEO_ID)) return Ref(Kind.VIDEO, t)
        if (t.startsWith("PL") || t.startsWith("UU") || t.startsWith("RD")) {
            if (t.matches(LIST_ID)) return Ref(Kind.PLAYLIST, t)
        }
        return null
    }

    /** ¿Este texto es un enlace de YouTube? Para no mandarlo al buzón de magnets. */
    fun looksLikeYouTube(text: String?): Boolean {
        val t = text?.lowercase().orEmpty()
        return ("youtube.com" in t || "youtu.be" in t) && parse(text) != null
    }

    /**
     * URL del reproductor incrustado.
     *
     * Va por `youtube-nocookie.com`, que es el dominio de incrustación sin cookies
     * de seguimiento: para una sección infantil es lo mínimo. `rel=0` limita las
     * sugerencias del final al mismo canal e `iv_load_policy=3` quita las
     * anotaciones, las dos cosas para que no acabe en otro sitio.
     */
    fun embedUrl(ref: Ref): String {
        val base = "https://www.youtube-nocookie.com/embed"
        val common = "autoplay=1&playsinline=1&rel=0&modestbranding=1&iv_load_policy=3"
        return when (ref.kind) {
            Kind.VIDEO -> "$base/${ref.id}?$common"
            Kind.PLAYLIST -> "$base/videoseries?list=${ref.id}&$common"
        }
    }

    // ---------------------------------------------------------------- guardado

    /**
     * Fichero propio y no las preferencias: son varias líneas y crecen, y
     * SharedPreferences se lee entero cada vez que se abre. Formato TSV para poder
     * mirarlo (y arreglarlo) a mano si hace falta.
     */
    private fun file(): File? = appCtx?.let { File(it.filesDir, "canales_youtube.tsv") }

    private fun load() {
        val f = file() ?: return
        list.clear()
        if (!f.exists()) return
        runCatching {
            f.readLines().forEach { line ->
                val p = line.split("\t")
                if (p.size >= 3) {
                    val k = if (p[0] == "PLAYLIST") Kind.PLAYLIST else Kind.VIDEO
                    if (p[1].isNotBlank()) list.add(Entry(Ref(k, p[1]), p[2]))
                }
            }
        }
    }

    private fun save() {
        val f = file() ?: return
        runCatching {
            f.writeText(list.joinToString("\n") { "${it.ref.kind}\t${it.ref.id}\t${it.name}" })
        }
    }

    /**
     * Añade un enlace.
     *
     * @param name cómo llamarlo en la lista. En blanco se pone uno genérico: el
     *   título de verdad solo lo sabe YouTube, y pedirlo obligaría a consultar su
     *   API con una clave, que es mucho aparato para una etiqueta.
     * @return null si se añadió, o el motivo si no.
     */
    fun add(link: String?, name: String): String? {
        val ref = parse(link) ?: return "Ese enlace no es de YouTube. Copia la dirección de " +
            "un vídeo, un directo o una lista de reproducción."
        if (list.any { it.ref == ref }) return "Ese canal ya está en la lista."
        val label = name.trim().ifBlank {
            if (ref.kind == Kind.PLAYLIST) "Lista de YouTube" else "Vídeo de YouTube"
        }
        list.add(Entry(ref, label))
        save()
        return null
    }

    fun remove(e: Entry) {
        list.remove(e)
        save()
    }

    /**
     * Los canales tal como los espera «En directo».
     *
     * Van con `imported = true` porque los ha puesto el usuario a mano: el filtro
     * infantil no debe esconderlos por que el nombre no cuadre con un patrón, que
     * es exactamente el fallo que ya tuvo la lista M3U importada.
     */
    fun channels(): List<Iptv.Channel> = list.map {
        Iptv.Channel(
            name = it.name,
            url = "youtube://${it.ref.kind}/${it.ref.id}",
            group = "YouTube",
            imported = true
        )
    }

    /** Reconstruye la referencia desde la URL falsa de [channels]. */
    fun fromChannelUrl(url: String): Ref? {
        if (!url.startsWith("youtube://")) return null
        val p = url.removePrefix("youtube://").split("/", limit = 2)
        if (p.size != 2 || p[1].isBlank()) return null
        val kind = runCatching { Kind.valueOf(p[0]) }.getOrNull() ?: return null
        return Ref(kind, p[1])
    }
}
