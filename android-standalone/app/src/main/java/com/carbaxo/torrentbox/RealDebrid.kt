package com.carbaxo.torrentbox

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Real-Debrid en el propio móvil (mismo flujo que la web/escritorio):
 * addMagnet -> selectFiles -> poll -> unrestrict -> URL HTTPS directa.
 * El token es privado y se guarda SOLO en este dispositivo (SharedPreferences).
 */
object RealDebrid {
    private const val API = "https://api.real-debrid.com/rest/1.0"
    private const val VIDEO = "(?i)\\.(mp4|mkv|avi|m4v|webm|mov|wmv|mpg|mpeg|ts)$"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val io = Executors.newCachedThreadPool()

    private var prefs: android.content.SharedPreferences? = null
    var token by mutableStateOf("")          // observable para la UI
    var account by mutableStateOf<String?>(null)   // nombre de usuario RD si válido

    // id de descarga RD por URL directa (para pedir transcodificación al emitir)
    private val idsByUrl = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun downloadIdFor(url: String): String? = idsByUrl[url]

    /** Una versión transcodificada por Real-Debrid (siempre H.264 + AAC). */
    data class Transcoded(val url: String, val kind: String)

    /**
     * Versiones transcodificadas de un enlace ya generado con streamMagnet.
     *
     * Es la pieza clave para emitir a un Chromecast: la mayoría de releases
     * llevan audio Dolby (AC3/EAC3) o DTS, que el receptor de Google Cast NO
     * decodifica —se ve la imagen pero no se oye nada—. Real-Debrid reconvierte
     * el archivo en sus servidores a H.264 + AAC, que sí suena.
     *
     * Devuelve las variantes en orden de preferencia para Cast:
     *   hls (m3u8) -> liveMP4 -> h264WebM
     * y, si no hay ninguna, el motivo para poder explicarlo en pantalla.
     */
    fun transcodeVariants(url: String, onDone: (List<Transcoded>, String?) -> Unit) {
        val id = idsByUrl[url] ?: return onDone(emptyList(), "el enlace no viene de Real-Debrid")
        io.submit {
            try {
                val t = rd("GET", "/streaming/transcode/$id")
                val out = ArrayList<Transcoded>()
                // "apple" = HLS; el resto son streams progresivos ya convertidos
                pickBest(t.optJSONObject("apple"))?.let { out.add(Transcoded(it, "hls")) }
                pickBest(t.optJSONObject("liveMP4"))?.let { out.add(Transcoded(it, "mp4")) }
                pickBest(t.optJSONObject("h264WebM"))?.let { out.add(Transcoded(it, "webm")) }
                onDone(out, if (out.isEmpty()) "Real-Debrid no ofrece versión convertida de este archivo" else null)
            } catch (e: Throwable) {
                onDone(emptyList(), e.message ?: "Real-Debrid no pudo convertir el archivo")
            }
        }
    }

    /** De un bloque de calidades {full, 1080p, 720p…} coge la mejor disponible. */
    private fun pickBest(o: JSONObject?): String? {
        if (o == null) return null
        o.optString("full", "").takeIf { it.isNotBlank() }?.let { return it }
        for (k in o.keys()) {
            val v = o.optString(k, "")
            if (v.startsWith("http")) return v
        }
        return null
    }

    val configured: Boolean get() = token.isNotBlank()

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        // Almacén cifrado; si falla (algún fabricante rompe el Keystore), reserva a normal
        prefs = try {
            val master = androidx.security.crypto.MasterKey.Builder(app)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                app, "torrentbox_secure", master,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Throwable) {
            app.getSharedPreferences("torrentbox", Context.MODE_PRIVATE)
        }
        token = prefs?.getString("rd_token", "") ?: ""
    }

    private fun save() { prefs?.edit()?.putString("rd_token", token)?.apply() }

    /**
     * Adopta el token de Real-Debrid de la CUENTA. El de la cuenta manda: si en
     * este aparato había otro, se sustituye.
     *
     * Antes salía por la puerta de atrás cuando el móvil ya tenía token
     * (`if (configured) return`), y eso rompía justo lo que se espera: al entrar
     * con otra cuenta en el mismo móvil seguías usando el Real-Debrid de la
     * cuenta anterior.
     *
     * Si el token de la nube no vale, `connect` falla y se queda el actual: no
     * se pierde el acceso por un dato viejo en Firestore.
     */
    fun adoptToken(t: String) {
        val cand = t.trim()
        if (cand.isBlank() || cand == token) return
        connect(cand) { _, _ -> }
    }

    /**
     * Llamada cruda a la API. Devuelve el cuerpo tal cual, porque unas rutas
     * responden un objeto (`/torrents/info`) y otras un array (`/torrents`).
     * Cuando RD falla suele explicar el motivo en el campo `error`: se propaga,
     * que es mucho más útil que un "respondió 400" a secas.
     */
    private fun rdRaw(method: String, path: String, form: Map<String, String>? = null, tok: String = token): String {
        val b = Request.Builder().url(API + path).header("Authorization", "Bearer $tok")
        if (form != null) {
            val fb = FormBody.Builder(); form.forEach { (k, v) -> fb.add(k, v) }
            if (method == "POST") b.post(fb.build())
        }
        if (method == "DELETE") b.delete()
        client.newCall(b.build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (resp.isSuccessful || resp.code == 204) return body
            val why = runCatching { JSONObject(body).optString("error", "") }.getOrDefault("")
            throw RuntimeException(
                when {
                    resp.code == 401 -> "Token de Real-Debrid inválido o caducado."
                    resp.code == 403 -> "Real-Debrid rechaza la cuenta (¿sin premium?)."
                    why.isNotBlank() -> "Real-Debrid: $why"
                    else -> "Real-Debrid respondió ${resp.code}."
                }
            )
        }
    }

    private fun rd(method: String, path: String, form: Map<String, String>? = null, tok: String = token): JSONObject =
        rdRaw(method, path, form, tok).let { if (it.isBlank()) JSONObject() else JSONObject(it) }

    private fun rdArray(path: String): org.json.JSONArray =
        rdRaw("GET", path).let { if (it.isBlank()) org.json.JSONArray() else org.json.JSONArray(it) }

    /** Valida y guarda el token; devuelve el nombre de usuario o un error.
     *  Valida con el token CANDIDATO y solo lo compromete si es válido, para
     *  no dejar el token compartido a medias ni romper llamadas concurrentes. */
    fun connect(newToken: String, onDone: (Boolean, String?) -> Unit) {
        io.submit {
            val cand = newToken.trim()
            try {
                val u = rd("GET", "/user", tok = cand)
                val name = u.optString("username", "")
                val premium = u.optString("type", "") == "premium"
                token = cand; account = name; save()
                onDone(true, if (premium) name else "$name (SIN premium)")
            } catch (e: Throwable) {
                onDone(false, e.message ?: "Error") // no tocar el token actual si falla
            }
        }
    }

    fun disconnect() { token = ""; account = null; save() }

    /**
     * Convierte un magnet en una URL directa (para ver o descargar).
     * onDone(url, filename, error, progress): si url != null, listo; si
     * progress != null, RD aún lo está descargando en sus servidores.
     */
    // Torrent RD ya creado por magnet, para que los reintentos (mientras RD
    // descarga a sus servidores) no añadan el mismo torrent una y otra vez
    private val torrentIdByMagnet = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun streamMagnet(magnet: String, onDone: (String?, String?, String?, Int?) -> Unit) {
        io.submit {
            try {
                val id = torrentIdByMagnet[magnet] ?: run {
                    val added = rd("POST", "/torrents/addMagnet", mapOf("magnet" to magnet))
                    val nid = added.optString("id", "")
                    if (nid.isNotBlank()) torrentIdByMagnet[magnet] = nid
                    nid
                }
                if (id.isBlank()) return@submit onDone(null, null, "RD no aceptó el magnet.", null)

                var info = rd("GET", "/torrents/info/$id")
                var selected = false
                fun selectVideos(inf: JSONObject) { selectVideoFiles(id, inf); selected = true }
                if (info.optString("status") == "waiting_files_selection") selectVideos(info)

                var tries = 0
                while (tries++ < 12) {
                    info = rd("GET", "/torrents/info/$id")
                    val st = info.optString("status")
                    if (st == "downloaded") break
                    // Puede llegar aquí en magnet_conversion/queued antes de pedir selección
                    if (st == "waiting_files_selection" && !selected) selectVideos(info)
                    if (st in listOf("magnet_error", "error", "virus", "dead")) return@submit onDone(null, null, "RD no pudo procesar el torrent ($st).", null)
                    Thread.sleep(1500)
                }
                if (info.optString("status") != "downloaded") {
                    return@submit onDone(null, null, null, info.optInt("progress", 0))
                }
                val links = info.optJSONArray("links")
                val link = if (links != null && links.length() > 0) links.getString(0) else return@submit onDone(null, null, "RD no devolvió enlaces.", null)
                val un = rd("POST", "/unrestrict/link", mapOf("link" to link))
                val dl = un.optString("download", "")
                val fname = un.optString("filename", "").ifBlank { info.optString("filename", "video") }
                if (dl.isBlank()) onDone(null, null, "No se pudo generar el enlace directo.", null)
                else {
                    un.optString("id", "").takeIf { it.isNotBlank() }?.let { idsByUrl[dl] = it }
                    onDone(dl, fname, null, null)
                }
            } catch (e: Throwable) {
                // Si el torrent cacheado ya no existe en RD, que el próximo intento lo re-añada
                torrentIdByMagnet.remove(magnet)
                onDone(null, null, e.message ?: "Error de Real-Debrid.", null)
            }
        }
    }

    // ------------------------------------------------------------------
    //  Gestión manual de la cuenta de RD: añadir un magnet o un enlace a
    //  mano y ver qué está haciendo RD con él.
    //
    //  Existe porque los enlaces de los buscadores fallan a menudo por dos
    //  motivos que no dependen de la app: RD todavía no tiene el torrent
    //  cacheado (lo tiene que bajar a sus servidores) o el archivo ya se
    //  borró de su caché. En los dos casos la solución es la misma: meter el
    //  magnet en la cuenta y esperar a que RD lo tenga.
    // ------------------------------------------------------------------

    /** Un torrent tal y como lo tiene Real-Debrid en la cuenta. */
    data class Torrent(
        val id: String,
        val name: String,
        val status: String,
        val progress: Int,
        val bytes: Long,
        val links: Int,
        val speed: Long,
        val seeders: Int
    ) {
        val ready: Boolean get() = status == "downloaded"
        /** Está trabajando: tiene sentido seguir refrescando. */
        val working: Boolean
            get() = status in listOf("magnet_conversion", "queued", "downloading", "compressing", "uploading")
    }

    /** Un archivo ya listo dentro de un torrent (los packs traen varios). */
    data class RdFile(val name: String, val bytes: Long, val link: String)

    /** El estado de RD, en castellano y sin jerga. */
    fun statusEs(st: String): String = when (st) {
        "magnet_conversion" -> "leyendo el magnet"
        "waiting_files_selection" -> "esperando a elegir archivos"
        "queued" -> "en cola"
        "downloading" -> "descargando en Real-Debrid"
        "downloaded" -> "listo"
        "compressing" -> "comprimiendo"
        "uploading" -> "subiendo"
        "magnet_error" -> "el magnet no vale"
        "error" -> "error"
        "virus" -> "rechazado (virus)"
        "dead" -> "sin semillas: nadie lo comparte"
        else -> st.ifBlank { "desconocido" }
    }

    private val BAD = listOf("magnet_error", "error", "virus", "dead")

    /** Marca en RD los archivos de vídeo del torrent (si no, se queda parado). */
    private fun selectVideoFiles(id: String, info: JSONObject) {
        val files = info.optJSONArray("files")
        val vids = ArrayList<String>()
        if (files != null) for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            if (Regex(VIDEO).containsMatchIn(f.optString("path"))) vids.add(f.optInt("id").toString())
        }
        rd("POST", "/torrents/selectFiles/$id", mapOf("files" to if (vids.isNotEmpty()) vids.joinToString(",") else "all"))
    }

    /**
     * Mete un magnet en la cuenta de RD y le dice que baje los vídeos. NO espera
     * a que termine: devuelve en cuanto RD lo ha aceptado, y el progreso se ve
     * luego en la lista. Un magnet sin `selectFiles` se queda esperando para
     * siempre, así que eso se hace aquí mismo.
     */
    fun addMagnet(magnet: String, onDone: (String?, String?) -> Unit) {
        io.submit {
            try {
                val m = magnet.trim()
                if (!m.startsWith("magnet:", ignoreCase = true))
                    return@submit onDone(null, "Eso no es un magnet (tiene que empezar por «magnet:?xt=…»).")
                val id = rd("POST", "/torrents/addMagnet", mapOf("magnet" to m)).optString("id", "")
                if (id.isBlank()) return@submit onDone(null, "Real-Debrid no aceptó el magnet.")
                // Espera a que RD lea el magnet para poder elegir los archivos
                var tries = 0
                while (tries++ < 10) {
                    val info = rd("GET", "/torrents/info/$id")
                    val st = info.optString("status")
                    if (st == "waiting_files_selection") { selectVideoFiles(id, info); break }
                    if (st in BAD) return@submit onDone(null, "Real-Debrid no pudo con el torrent: ${statusEs(st)}.")
                    if (st != "magnet_conversion" && st != "queued") break   // ya iba solo
                    Thread.sleep(1200)
                }
                onDone(id, null)
            } catch (e: Throwable) {
                onDone(null, e.message ?: "Error de Real-Debrid.")
            }
        }
    }

    /** Los torrents de la cuenta, del más reciente al más antiguo. */
    fun torrents(onDone: (List<Torrent>?, String?) -> Unit) {
        io.submit {
            try {
                val arr = rdArray("/torrents?limit=50")
                val out = ArrayList<Torrent>()
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    out.add(
                        Torrent(
                            id = t.optString("id"),
                            name = t.optString("filename").ifBlank { t.optString("original_filename", "torrent") },
                            status = t.optString("status"),
                            progress = t.optInt("progress", 0),
                            bytes = t.optLong("bytes", 0L),
                            links = t.optJSONArray("links")?.length() ?: 0,
                            speed = t.optLong("speed", 0L),
                            seeders = t.optInt("seeders", 0)
                        )
                    )
                }
                onDone(out, null)
            } catch (e: Throwable) {
                onDone(null, e.message ?: "Error de Real-Debrid.")
            }
        }
    }

    /**
     * Los archivos listos de un torrent, con su enlace de RD (aún restringido).
     * Los packs de temporada traen varios: así se puede elegir el episodio.
     */
    fun torrentFiles(id: String, onDone: (List<RdFile>?, String?) -> Unit) {
        io.submit {
            try {
                val info = rd("GET", "/torrents/info/$id")
                val st = info.optString("status")
                val links = info.optJSONArray("links")
                // Los enlaces van en el mismo orden que los archivos marcados
                val chosen = ArrayList<Pair<String, Long>>()
                info.optJSONArray("files")?.let { fs ->
                    for (i in 0 until fs.length()) {
                        val f = fs.getJSONObject(i)
                        if (f.optInt("selected", 0) == 1)
                            chosen.add(f.optString("path").trimStart('/') to f.optLong("bytes", 0L))
                    }
                }
                val out = ArrayList<RdFile>()
                for (i in 0 until (links?.length() ?: 0)) {
                    val meta = chosen.getOrNull(i)
                    out.add(RdFile(meta?.first ?: "Archivo ${i + 1}", meta?.second ?: 0L, links!!.getString(i)))
                }
                if (out.isEmpty()) onDone(null, "Todavía no hay nada listo: ${statusEs(st)}.")
                else onDone(out, null)
            } catch (e: Throwable) {
                onDone(null, e.message ?: "Error de Real-Debrid.")
            }
        }
    }

    /**
     * Convierte un enlace en la URL directa para ver o descargar. Vale para los
     * enlaces de un torrent de la cuenta y para un enlace de hoster pegado a
     * mano (1fichier, Mega…), que es lo que hace la web de RD en "Descargador".
     */
    fun unrestrict(link: String, onDone: (String?, String?, String?) -> Unit) {
        io.submit {
            try {
                val un = rd("POST", "/unrestrict/link", mapOf("link" to link.trim()))
                val dl = un.optString("download", "")
                val fname = un.optString("filename", "").ifBlank { "video" }
                if (dl.isBlank()) return@submit onDone(null, null, "Real-Debrid no devolvió un enlace directo.")
                un.optString("id", "").takeIf { it.isNotBlank() }?.let { idsByUrl[dl] = it }
                onDone(dl, fname, null)
            } catch (e: Throwable) {
                onDone(null, null, e.message ?: "Error de Real-Debrid.")
            }
        }
    }

    /** Borra el torrent de la cuenta de RD (no toca lo descargado en el móvil). */
    fun deleteTorrent(id: String, onDone: (String?) -> Unit) {
        io.submit {
            try { rdRaw("DELETE", "/torrents/delete/$id"); onDone(null) }
            catch (e: Throwable) { onDone(e.message ?: "Error de Real-Debrid.") }
        }
    }
}
