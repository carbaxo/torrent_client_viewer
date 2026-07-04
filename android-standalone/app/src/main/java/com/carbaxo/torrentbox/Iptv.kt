package com.carbaxo.torrentbox

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reproductor IPTV genérico (como VLC/Kodi): el usuario aporta la fuente y la
 * app hace de reproductor. Soporta dos formas estándar:
 *
 *  - Listas M3U/M3U8 (una URL): #EXTINF con tvg-name, tvg-logo, group-title.
 *  - Xtream Codes (host + usuario + contraseña): API JSON que devuelve
 *    categorías y canales; la URL de reproducción se construye como
 *    http://host:port/<user>/<pass>/<stream_id>.<ext>
 *
 * NO incluye ningún canal: es un motor neutro. Los streams son HLS/HTTP y se
 * reproducen directos en ExoPlayer, sin restricciones tipo AceStream Premium.
 */
object Iptv {
    data class Channel(
        val name: String,
        val url: String,
        val logo: String?,
        val group: String
    )

    private const val FILE = "tcv_iptv"
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val io = Executors.newCachedThreadPool()

    // --- Persistencia de la fuente configurada ---
    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun savedM3u(ctx: Context): String = sp(ctx).getString("m3u", "") ?: ""
    fun saveM3u(ctx: Context, url: String) = sp(ctx).edit().putString("m3u", url.trim()).apply()

    data class Xtream(val host: String, val user: String, val pass: String)
    fun savedXtream(ctx: Context): Xtream? {
        val h = sp(ctx).getString("xt_host", "") ?: ""
        val u = sp(ctx).getString("xt_user", "") ?: ""
        val p = sp(ctx).getString("xt_pass", "") ?: ""
        return if (h.isNotBlank() && u.isNotBlank()) Xtream(h, u, p) else null
    }
    fun saveXtream(ctx: Context, x: Xtream) {
        sp(ctx).edit()
            .putString("xt_host", normalizeHost(x.host)).putString("xt_user", x.user.trim())
            .putString("xt_pass", x.pass.trim()).apply()
    }
    fun clear(ctx: Context) = sp(ctx).edit().clear().apply()

    private fun normalizeHost(h: String): String {
        var s = h.trim()
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s.trimEnd('/')
    }

    // --- Carga de una lista M3U ---
    fun loadM3u(url: String, onResult: (List<Channel>?, String?) -> Unit) {
        io.submit {
            try {
                val req = Request.Builder().url(url.trim()).header("User-Agent", "TorrentBox").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@submit onResult(null, "El servidor respondió ${resp.code}")
                    onResult(parseM3u(resp.body?.string() ?: ""), null)
                }
            } catch (e: Throwable) {
                onResult(null, e.message ?: "Error de red (M3U).")
            }
        }
    }

    fun parseM3u(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        val lines = text.lines()
        var i = 0
        var name = ""; var logo: String? = null; var group = "General"
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                logo = attr(line, "tvg-logo")
                group = attr(line, "group-title") ?: "General"
                name = attr(line, "tvg-name") ?: line.substringAfterLast(',', "").trim()
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                if (name.isBlank()) name = line.substringAfterLast('/').ifBlank { "Canal" }
                out.add(Channel(name, line, logo?.takeIf { it.isNotBlank() }, group.ifBlank { "General" }))
                name = ""; logo = null; group = "General"
            }
            i++
        }
        return out
    }

    private fun attr(line: String, key: String): String? =
        Regex("$key=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)

    // --- Xtream Codes ---
    /** Prueba las credenciales; onResult(ok, mensaje). */
    fun testXtream(x: Xtream, onResult: (Boolean, String?) -> Unit) {
        io.submit {
            try {
                val host = normalizeHost(x.host)
                val url = "$host/player_api.php?username=${enc(x.user)}&password=${enc(x.pass)}"
                client.newCall(Request.Builder().url(url).header("User-Agent", "TorrentBox").build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@submit onResult(false, "Servidor respondió ${resp.code}")
                    val body = resp.body?.string() ?: "{}"
                    val auth = org.json.JSONObject(body).optJSONObject("user_info")?.optInt("auth", 0) ?: 0
                    if (auth == 1) onResult(true, "Conectado") else onResult(false, "Usuario o contraseña incorrectos")
                }
            } catch (e: Throwable) { onResult(false, e.message ?: "Error de red") }
        }
    }

    /** Canales en vivo vía Xtream (con su categoría). */
    fun loadXtream(x: Xtream, onResult: (List<Channel>?, String?) -> Unit) {
        io.submit {
            try {
                val host = normalizeHost(x.host)
                val base = "$host/player_api.php?username=${enc(x.user)}&password=${enc(x.pass)}"
                // Categorías: id -> nombre
                val cats = HashMap<String, String>()
                runCatching {
                    val arr = getArray("$base&action=get_live_categories")
                    for (i in 0 until arr.length()) {
                        val c = arr.getJSONObject(i)
                        cats[c.optString("category_id")] = c.optString("category_name", "General")
                    }
                }
                val arr = getArray("$base&action=get_live_streams")
                val out = ArrayList<Channel>()
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val id = s.optString("stream_id")
                    if (id.isBlank()) continue
                    val ext = s.optString("container_extension", "ts").ifBlank { "ts" }
                    val group = cats[s.optString("category_id")] ?: "General"
                    out.add(Channel(
                        name = s.optString("name", "Canal $id"),
                        url = "$host/${enc(x.user)}/${enc(x.pass)}/$id.$ext",
                        logo = s.optString("stream_icon", "").takeIf { it.isNotBlank() },
                        group = group
                    ))
                }
                onResult(out, null)
            } catch (e: Throwable) { onResult(null, e.message ?: "Error de red (Xtream).") }
        }
    }

    private fun getArray(url: String): JSONArray {
        client.newCall(Request.Builder().url(url).header("User-Agent", "TorrentBox").build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val txt = resp.body?.string() ?: "[]"
            return if (txt.trimStart().startsWith("[")) JSONArray(txt) else JSONArray()
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s.trim(), "UTF-8")
}
