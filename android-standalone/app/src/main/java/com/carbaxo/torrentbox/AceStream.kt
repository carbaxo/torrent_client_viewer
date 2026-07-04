package com.carbaxo.torrentbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AceStream: reproducción de contenidos P2P (canales/eventos) SIN salir de la app.
 *
 * No hay API pública de búsqueda, así que se SCRAPEA acestreamid.com para sacar
 * los content-id (40 hex) y su título. La reproducción usa el AceStream Engine
 * (app aparte) que expone un servidor HTTP local:
 *
 *   http://127.0.0.1:6878/ace/getstream?id=<content_id>&format=json
 *      -> { "response": { "playback_url": "http://127.0.0.1:6878/ace/r/..." } }
 *
 * ese playback_url es un HLS/HTTP normal que ExoPlayer reproduce.
 *
 * Fallback: cada resultado incluye el enlace a acestreamid.com por si el
 * engine no resuelve, y se puede pegar un enlace/content-id a mano.
 */
object AceStream {
    data class Result(
        val name: String,
        val contentId: String,       // 40 hex
        val info: String,            // categoría / detalle si se pudo extraer
        val pageUrl: String          // página en acestreamid.com (fallback)
    )

    // Paquetes conocidos del AceStream Engine en Android
    private val ENGINE_PACKAGES = listOf(
        "org.acestream.media",
        "org.acestream.core",
        "org.acestream.media.atv"
    )
    private const val ENGINE_HOST = "127.0.0.1"
    private const val ENGINE_PORT = 6878
    private const val SITE = "https://acestreamid.com"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val io = Executors.newCachedThreadPool()

    private val CONTENT_ID = Regex("[0-9a-fA-F]{40}")

    /** ¿Está instalado el AceStream Engine? (necesario para reproducir) */
    fun engineInstalled(ctx: Context): Boolean {
        val pm = ctx.packageManager
        return ENGINE_PACKAGES.any { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        }
    }

    /** Abre Google Play para instalar el engine (o la web si no hay Play). */
    fun openEngineInstall(ctx: Context) {
        val pkg = ENGINE_PACKAGES.first()
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** Abre la página del contenido en acestreamid.com (fallback si el engine falla). */
    fun openPage(ctx: Context, urlOrId: String) {
        val url = if (urlOrId.startsWith("http")) urlOrId else pageUrl(urlOrId)
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun pageUrl(contentId: String) = "$SITE/?an=0&q=$contentId"

    /**
     * Extrae el content-id (40 hex) de lo que pegue el usuario:
     *   acestream://<hash> | <hash> | http://127.0.0.1:6878/ace/getstream?id=<hash> | ...&content_id=<hash>
     */
    fun extractContentId(input: String): String? {
        val s = input.trim()
        return CONTENT_ID.find(s)?.value?.lowercase()
    }

    /**
     * Busca contenidos en acestreamid.com scrapeando el HTML de resultados.
     * onResult(list, error). Best-effort: si cambia el HTML, devuelve lo que encuentre.
     */
    fun search(query: String, onResult: (List<Result>?, String?) -> Unit) {
        io.submit {
            try {
                val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
                // acestreamid.com sirve el buscador en la home con ?q=
                val url = "$SITE/?an=0&q=$q"
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Android) TorrentBox")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@submit onResult(null, "acestreamid.com respondió ${resp.code}")
                    val html = resp.body?.string() ?: ""
                    val out = parseHtml(html)
                    if (out.isEmpty()) onResult(emptyList(), "Sin resultados (o el sitio cambió). Prueba a pegar el enlace a mano.")
                    else onResult(out, null)
                }
            } catch (e: Throwable) {
                onResult(null, e.message ?: "Error de red (AceStream).")
            }
        }
    }

    /**
     * Extrae de forma tolerante content-ids y un título aproximado del HTML.
     * Busca bloques con un id de 40 hex y toma el texto/atributo más cercano.
     */
    fun parseHtml(html: String): List<Result> {
        val seen = LinkedHashMap<String, Result>()
        // 1) enlaces acestream://<hash> con posible texto de anclaje
        val anchor = Regex(
            "(?:href|data-href|data-id|data-content-id)\\s*=\\s*[\"']?(?:acestream://)?([0-9a-fA-F]{40})[\"']?[^>]*>([^<]{0,120})",
            RegexOption.IGNORE_CASE
        )
        for (m in anchor.findAll(html)) {
            val id = m.groupValues[1].lowercase()
            val label = cleanText(m.groupValues[2])
            if (label.isNotBlank()) seen.putIfAbsent(id, Result(label, id, "", pageUrl(id)))
        }
        // 2) cualquier content-id suelto: título = título de la etiqueta cercana
        for (m in CONTENT_ID.findAll(html)) {
            val id = m.value.lowercase()
            if (seen.containsKey(id)) continue
            val label = nearbyTitle(html, m.range.first).ifBlank { "AceStream ${id.take(8)}…" }
            seen.putIfAbsent(id, Result(label, id, "", pageUrl(id)))
        }
        return seen.values.take(40)
    }

    /** Busca hacia atrás/adelante un texto legible (title="..." o texto de etiqueta). */
    private fun nearbyTitle(html: String, at: Int): String {
        val from = (at - 200).coerceAtLeast(0)
        val window = html.substring(from, (at + 40).coerceAtMost(html.length))
        Regex("title\\s*=\\s*[\"']([^\"']{3,120})[\"']", RegexOption.IGNORE_CASE).find(window)?.let {
            return cleanText(it.groupValues[1])
        }
        // texto entre >...< inmediatamente anterior
        Regex(">([^<>]{3,120})<[^>]*$").find(window)?.let { return cleanText(it.groupValues[1]) }
        return ""
    }

    private fun cleanText(s: String): String =
        s.replace(Regex("&[a-zA-Z#0-9]+;"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Resuelve el content-id contra el engine local y devuelve el playback_url
     * reproducible en ExoPlayer. onResult(url, error).
     */
    fun resolve(contentId: String, onResult: (String?, String?) -> Unit) {
        io.submit {
            try {
                val id = extractContentId(contentId) ?: return@submit onResult(null, "Enlace AceStream no válido.")
                val url = "http://$ENGINE_HOST:$ENGINE_PORT/ace/getstream?id=$id&format=json"
                val req = Request.Builder().url(url).header("User-Agent", "TorrentBox").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful)
                        return@submit onResult(null, "Engine respondió ${resp.code}. ¿Está el AceStream Engine abierto?")
                    val body = resp.body?.string() ?: "{}"
                    val json = JSONObject(body)
                    val err = json.optJSONObject("error") ?: json.opt("error")
                    val response = json.optJSONObject("response")
                    val playback = response?.optString("playback_url")?.takeIf { it.isNotBlank() }
                    when {
                        playback != null -> onResult(playback, null)
                        err != null && err.toString() != "null" -> onResult(null, "AceStream: $err")
                        else -> onResult(null, "El engine no devolvió reproducción.")
                    }
                }
            } catch (e: Throwable) {
                onResult(null, "No se pudo contactar con el AceStream Engine. Instálalo y ábrelo. (${e.message})")
            }
        }
    }
}
