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

    val configured: Boolean get() = token.isNotBlank()

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("torrentbox", Context.MODE_PRIVATE)
        token = prefs?.getString("rd_token", "") ?: ""
    }

    private fun save() { prefs?.edit()?.putString("rd_token", token)?.apply() }

    private fun rd(method: String, path: String, form: Map<String, String>? = null): JSONObject {
        val b = Request.Builder().url(API + path).header("Authorization", "Bearer $token")
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

    /** Valida y guarda el token; devuelve el nombre de usuario o un error. */
    fun connect(newToken: String, onDone: (Boolean, String?) -> Unit) {
        io.submit {
            try {
                token = newToken.trim()
                val u = rd("GET", "/user")
                val name = u.optString("username", "")
                val premium = u.optString("type", "") == "premium"
                account = name
                save()
                onDone(true, if (premium) name else "$name (SIN premium)")
            } catch (e: Throwable) {
                token = ""; account = null
                onDone(false, e.message ?: "Error")
            }
        }
    }

    fun disconnect() { token = ""; account = null; save() }

    /**
     * Convierte un magnet en una URL directa (para ver o descargar).
     * onDone(url, filename, error, progress): si url != null, listo; si
     * progress != null, RD aún lo está descargando en sus servidores.
     */
    fun streamMagnet(magnet: String, onDone: (String?, String?, String?, Int?) -> Unit) {
        io.submit {
            try {
                val added = rd("POST", "/torrents/addMagnet", mapOf("magnet" to magnet))
                val id = added.optString("id", "")
                if (id.isBlank()) return@submit onDone(null, null, "RD no aceptó el magnet.", null)

                var info = rd("GET", "/torrents/info/$id")
                if (info.optString("status") == "waiting_files_selection") {
                    val files = info.optJSONArray("files")
                    val vids = ArrayList<String>()
                    if (files != null) for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        if (Regex(VIDEO).containsMatchIn(f.optString("path"))) vids.add(f.optInt("id").toString())
                    }
                    rd("POST", "/torrents/selectFiles/$id", mapOf("files" to if (vids.isNotEmpty()) vids.joinToString(",") else "all"))
                }

                var tries = 0
                while (tries++ < 10) {
                    info = rd("GET", "/torrents/info/$id")
                    val st = info.optString("status")
                    if (st == "downloaded") break
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
                else onDone(dl, fname, null, null)
            } catch (e: Throwable) {
                onDone(null, null, e.message ?: "Error de Real-Debrid.", null)
            }
        }
    }
}
