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

    /**
     * Enlace HLS transcodificado por RD (audio AAC) para una URL directa ya
     * generada con streamMagnet. Un Chromecast no decodifica el audio
     * Dolby/DTS de muchos torrents; la versión HLS de RD sí suena.
     * onDone(m3u8) con null si no hay transcodificación disponible.
     */
    fun transcodeUrl(url: String, onDone: (String?) -> Unit) {
        val id = idsByUrl[url] ?: return onDone(null)
        io.submit {
            try {
                val t = rd("GET", "/streaming/transcode/$id")
                val apple = t.optJSONObject("apple")
                var best = apple?.optString("full", "") ?: ""
                if (best.isBlank() && apple != null) {
                    for (k in apple.keys()) {
                        val v = apple.optString(k, "")
                        if (v.isNotBlank()) { best = v; break }
                    }
                }
                onDone(best.ifBlank { null })
            } catch (_: Throwable) {
                onDone(null)
            }
        }
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

    /** Adopta un token recibido de la nube (ya validado en la web); no re-sube. */
    fun adoptToken(t: String) {
        if (configured || t.isBlank()) return
        connect(t) { _, _ -> }
    }

    private fun rd(method: String, path: String, form: Map<String, String>? = null, tok: String = token): JSONObject {
        val b = Request.Builder().url(API + path).header("Authorization", "Bearer $tok")
        if (form != null) {
            val fb = FormBody.Builder(); form.forEach { (k, v) -> fb.add(k, v) }
            if (method == "POST") b.post(fb.build())
        }
        client.newCall(b.build()).execute().use { resp ->
            if (resp.code == 401) throw RuntimeException("Token de Real-Debrid inválido o caducado.")
            if (resp.code == 403) throw RuntimeException("La cuenta de Real-Debrid no es premium.")
            if (!resp.isSuccessful && resp.code != 204) throw RuntimeException("Real-Debrid respondió ${resp.code}.")
            val body = resp.body?.string()
            return if (body.isNullOrBlank()) JSONObject() else JSONObject(body)
        }
    }

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
                fun selectVideos(inf: JSONObject) {
                    val files = inf.optJSONArray("files")
                    val vids = ArrayList<String>()
                    if (files != null) for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        if (Regex(VIDEO).containsMatchIn(f.optString("path"))) vids.add(f.optInt("id").toString())
                    }
                    rd("POST", "/torrents/selectFiles/$id", mapOf("files" to if (vids.isNotEmpty()) vids.joinToString(",") else "all"))
                    selected = true
                }
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
}
