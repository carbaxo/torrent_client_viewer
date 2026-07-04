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
        val pageUrl: String,         // página en acestreamid.com (fallback)
        val likes: Int = -1,         // -1 = no encontrado en el HTML
        val dislikes: Int = -1
    )

    // Paquetes conocidos de AceStream en Android. "Ace Stream Media" de la
    // tienda (org.acestream.media) YA incluye el engine — no hace falta otra app.
    private val ENGINE_PACKAGES = listOf(
        "org.acestream.media",
        "org.acestream.core",
        "org.acestream.node",
        "org.acestream.media.atv",
        "org.acestream.core.atv"
    )
    private const val ENGINE_HOST = "127.0.0.1"
    private const val ENGINE_PORT = 6878
    private const val SITE = "https://acestreamid.com"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val io = Executors.newCachedThreadPool()

    private val CONTENT_ID = Regex("[0-9a-fA-F]{40}")

    /**
     * ¿Hay alguna app de AceStream instalada? Comprueba paquetes conocidos
     * (declarados en <queries> del manifest, obligatorio en Android 11+) y,
     * por si es otra variante, si algo responde al esquema acestream://.
     * Es solo orientativo: la reproducción intenta el engine igualmente.
     */
    fun engineInstalled(ctx: Context): Boolean {
        val pm = ctx.packageManager
        if (ENGINE_PACKAGES.any { pkg -> runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false) })
            return true
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("acestream://0000000000000000000000000000000000000000"))
        return runCatching { pm.resolveActivity(probe, 0) != null }.getOrDefault(false)
    }

    /** Intenta arrancar la app de AceStream instalada para que levante el engine local. */
    fun startEngine(ctx: Context): Boolean {
        val pm = ctx.packageManager
        for (pkg in ENGINE_PACKAGES) {
            val launch = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() ?: continue
            runCatching {
                ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            }
        }
        return false
    }

    /** Abre el contenido en la app AceStream instalada (acestream://) como alternativa. */
    fun openExternal(ctx: Context, contentId: String): Boolean = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("acestream://$contentId")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /** Abre Google Play para instalar Ace Stream Media (o la web si no hay Play). */
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
    fun search(query: String, onResult: (List<Result>?, String?) -> Unit) =
        search(query, 1, onResult)

    /**
     * Busca (o lista todo si query está vacía) con paginación. page empieza en 1.
     * Para el listado completo prueba varias rutas de paginación conocidas y usa
     * la primera que devuelva resultados.
     */
    fun search(query: String, page: Int, onResult: (List<Result>?, String?) -> Unit) {
        io.submit {
            try {
                val q = query.trim()
                val urls: List<String> = if (q.isBlank()) {
                    // Listado completo (sin búsqueda), distintas convenciones de página
                    if (page <= 1) listOf("$SITE/", "$SITE/?page=1")
                    else listOf("$SITE/?page=$page", "$SITE/page/$page/", "$SITE/?p=$page")
                } else {
                    val enc = java.net.URLEncoder.encode(q, "UTF-8")
                    if (page <= 1) listOf("$SITE/?an=0&q=$enc")
                    else listOf("$SITE/?an=0&q=$enc&page=$page", "$SITE/page/$page/?an=0&q=$enc")
                }
                var lastCode = 0
                for (url in urls) {
                    val req = Request.Builder().url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml")
                        .build()
                    val out = try {
                        client.newCall(req).execute().use { resp ->
                            lastCode = resp.code
                            if (!resp.isSuccessful) null else parseHtml(resp.body?.string() ?: "")
                        }
                    } catch (_: Throwable) { null }
                    if (!out.isNullOrEmpty()) return@submit onResult(out, null)
                }
                if (lastCode != 0 && lastCode !in 200..299)
                    onResult(null, "acestreamid.com respondió $lastCode")
                else
                    onResult(emptyList(), if (page > 1) "No hay más páginas." else "Sin resultados (o el sitio cambió). Prueba a pegar el enlace a mano.")
            } catch (e: Throwable) {
                onResult(null, e.message ?: "Error de red (AceStream).")
            }
        }
    }

    /**
     * Extrae de forma tolerante content-ids, título aproximado y votos del HTML.
     * Busca bloques con un id de 40 hex y toma el texto/atributo más cercano;
     * los likes/dislikes se buscan en una ventana alrededor de cada id con
     * varios patrones habituales (👍/👎, clases like/dislike, iconos thumbs).
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
            if (label.isNotBlank()) {
                val (lk, dk) = nearbyVotes(html, m.range.first)
                seen.putIfAbsent(id, Result(label, id, "", pageUrl(id), lk, dk))
            }
        }
        // 2) cualquier content-id suelto: título = título de la etiqueta cercana
        for (m in CONTENT_ID.findAll(html)) {
            val id = m.value.lowercase()
            if (seen.containsKey(id)) continue
            val label = nearbyTitle(html, m.range.first).ifBlank { "AceStream ${id.take(8)}…" }
            val (lk, dk) = nearbyVotes(html, m.range.first)
            seen.putIfAbsent(id, Result(label, id, "", pageUrl(id), lk, dk))
        }
        return seen.values.take(60)
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

    // Patrones de votos: 👍 12 / 👎 3, class="like">12<, fa-thumbs-up ... 12, data-likes="12"…
    private val DISLIKES = Regex(
        "(?:👎|thumbs?[-_ ]?down|dislikes?)[^0-9<]{0,40}>?\\s*(\\d{1,6})|data-dislikes?\\s*=\\s*[\"'](\\d{1,6})",
        RegexOption.IGNORE_CASE
    )
    private val LIKES = Regex(
        "(?:👍|thumbs?[-_ ]?up|(?<![Dd][Ii][Ss])likes?)[^0-9<]{0,40}>?\\s*(\\d{1,6})|data-likes?\\s*=\\s*[\"'](\\d{1,6})",
        RegexOption.IGNORE_CASE
    )

    /** Votos (likes, dislikes) cerca de la posición dada; -1 si no se encuentran. */
    private fun nearbyVotes(html: String, at: Int): Pair<Int, Int> {
        val from = (at - 400).coerceAtLeast(0)
        val to = (at + 600).coerceAtMost(html.length)
        val window = html.substring(from, to)
        val dk = DISLIKES.find(window)?.let { m -> (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull() } ?: -1
        // Quita los "dislike" de la ventana para que LIKES no los cuente
        val cleaned = window.replace(Regex("dislikes?", RegexOption.IGNORE_CASE), "")
        val lk = LIKES.find(cleaned)?.let { m -> (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull() } ?: -1
        return lk to dk
    }

    private fun cleanText(s: String): String =
        s.replace(Regex("&[a-zA-Z#0-9]+;"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Comprueba si el engine local está corriendo con el comando oficial:
     *   GET /webui/api/service?method=get_version  ->  { result: { version } }
     * onResult(running, version).
     */
    fun engineRunning(onResult: (Boolean, String?) -> Unit) {
        io.submit {
            try {
                val req = Request.Builder()
                    .url("http://$ENGINE_HOST:$ENGINE_PORT/webui/api/service?method=get_version")
                    .header("User-Agent", "TorrentBox").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@submit onResult(false, null)
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val version = json.optJSONObject("result")?.optString("version")?.takeIf { it.isNotBlank() }
                    onResult(version != null, version)
                }
            } catch (_: Throwable) {
                onResult(false, null)
            }
        }
    }

    /**
     * Se asegura de que el engine esté corriendo: lo comprueba (get_version),
     * si no responde arranca la app de AceStream y reintenta hasta ~20 s.
     * onStatus recibe mensajes de progreso; onResult(ok, error).
     */
    fun ensureEngine(ctx: Context, onStatus: (String) -> Unit, onResult: (Boolean, String?) -> Unit) {
        engineRunning { ok, _ ->
            if (ok) return@engineRunning onResult(true, null)
            if (!engineInstalled(ctx)) {
                return@engineRunning onResult(false, "No se encontró AceStream. Instala «Ace Stream Media» de la tienda.")
            }
            onStatus("Arrancando AceStream Engine…")
            startEngine(ctx)
            // Reintenta get_version hasta ~20 s mientras el engine arranca
            fun retry(attempt: Int) {
                if (attempt >= 10) return onResult(false, "El engine no respondió en el puerto 6878. Abre la app AceStream y vuelve a intentarlo.")
                io.submit {
                    Thread.sleep(2000)
                    engineRunning { up, _ ->
                        if (up) onResult(true, null) else retry(attempt + 1)
                    }
                }
            }
            retry(0)
        }
    }

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
