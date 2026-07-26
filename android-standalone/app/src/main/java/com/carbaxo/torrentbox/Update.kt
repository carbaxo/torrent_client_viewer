package com.carbaxo.torrentbox

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Auto-actualización: comprueba la Release `android-latest` de GitHub (la CI
 * escribe "Build N — commit ..." en el cuerpo), y si hay un build más nuevo
 * que el propio (BuildConfig.CI_BUILD) descarga el APK y lanza el instalador.
 */
object Update {
    /** url = enlace público; assetApiUrl = el de la API (necesario si el repo es privado). */
    data class Info(val build: Int, val url: String, val assetApiUrl: String = "")

    private const val RELEASE_API =
        "https://api.github.com/repos/carbaxo/torrent_client_viewer/releases/tags/android-latest"

    var available by mutableStateOf<Info?>(null)
    var status by mutableStateOf("")
    var checked by mutableStateOf(false)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    // Para descargar el APK de un repo privado hay que seguir la redirección a
    // mano: el enlace firmado al que apunta rechaza la cabecera Authorization.
    private val noRedirect = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).build()
    private val io = Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private fun onMain(b: () -> Unit) { main.post(b) }

    /**
     * Comprueba si hay una versión más nueva. Fiable aunque GitHub reutilice el
     * tag `android-latest` (fecha congelada): compara la fecha REAL del APK
     * subido (`updated_at` del asset, que cambia en cada publicación) con la
     * hora de compilación de esta app (BuildConfig.BUILD_EPOCH).
     */
    fun check() {
        io.submit {
            try {
                val tok = Prefs.githubToken
                val b = Request.Builder().url(RELEASE_API)
                    .header("User-Agent", "TorrentBox")
                    .header("Accept", "application/vnd.github+json")
                if (tok.isNotBlank()) b.header("Authorization", "Bearer $tok")
                client.newCall(b.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        // Antes cualquier fallo se traducía en "estás en la última
                        // versión", que era mentira: el repo es privado y sin token
                        // la API responde 404.
                        val msg = when (resp.code) {
                            401, 403, 404 ->
                                if (tok.isBlank()) "No se puede consultar la Release: el repositorio es privado. " +
                                    "Pega abajo un token de GitHub con permiso de lectura."
                                else "El token de GitHub no vale o no tiene permiso de lectura del repositorio (HTTP ${resp.code})."
                            else -> "No se pudo comprobar (HTTP ${resp.code})."
                        }
                        return@submit onMain { checked = true; available = null; status = msg }
                    }
                    val d = JSONObject(resp.body?.string() ?: "{}")
                    val remoteBuild = Regex("Build (\\d+)").find(d.optString("body"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    var url: String? = null
                    var apiUrl = ""
                    var apkEpoch = 0L
                    d.optJSONArray("assets")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val a = arr.getJSONObject(i)
                            if (a.optString("name") == "TorrentBox.apk") {
                                url = a.optString("browser_download_url")
                                apiUrl = a.optString("url")
                                apkEpoch = parseIso(a.optString("updated_at"))
                            }
                        }
                    }
                    // Nuevo si el APK de la Release se subió claramente DESPUÉS de
                    // compilar esta app (margen de 2 min), o si el nº de build es mayor.
                    val newerByDate = apkEpoch > 0 && apkEpoch > BuildConfig.BUILD_EPOCH + 120_000L
                    val newerByBuild = remoteBuild > 0 && remoteBuild > BuildConfig.CI_BUILD
                    onMain {
                        checked = true
                        status = ""
                        available = if ((newerByDate || newerByBuild) && url != null)
                            Info(
                                if (remoteBuild > 0) remoteBuild else BuildConfig.CI_BUILD + 1,
                                url!!, apiUrl
                            ) else null
                    }
                }
            } catch (e: Throwable) {
                onMain { checked = true; status = "No se pudo comprobar: ${e.message ?: "error de red"}" }
            }
        }
    }

    /**
     * Abre el APK de la Release. Con repo privado hay que pedirlo a la API del
     * asset con el token y seguir la redirección a mano, porque el enlace
     * firmado de destino falla si se le manda la cabecera Authorization.
     */
    private fun openAsset(info: Info): okhttp3.Response {
        val tok = Prefs.githubToken
        if (tok.isBlank() || info.assetApiUrl.isBlank()) {
            return client.newCall(
                Request.Builder().url(info.url).header("User-Agent", "TorrentBox").build()
            ).execute()
        }
        val first = noRedirect.newCall(
            Request.Builder().url(info.assetApiUrl)
                .header("User-Agent", "TorrentBox")
                .header("Accept", "application/octet-stream")
                .header("Authorization", "Bearer $tok").build()
        ).execute()
        val loc = first.header("Location")
        if (loc == null) return first          // ya es el fichero (o un error)
        first.close()
        return client.newCall(
            Request.Builder().url(loc).header("User-Agent", "TorrentBox").build()
        ).execute()
    }

    /** ISO-8601 de GitHub ("2026-07-04T19:16:25Z") a epoch ms; 0 si falla. */
    private fun parseIso(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return runCatching {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(s)?.time ?: 0L
        }.getOrDefault(0L)
    }

    /** Descarga el APK de la Release y abre el instalador del sistema. */
    fun downloadAndInstall(ctx: Context) {
        val info = available ?: return
        val app = ctx.applicationContext
        status = "Descargando actualización…"
        io.submit {
            try {
                val dir = File(app.filesDir, "apk").apply { mkdirs() }
                val f = File(dir, "TorrentBox.apk")
                openAsset(info).use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    val body = resp.body ?: throw RuntimeException("Respuesta vacía")
                    body.byteStream().use { input ->
                        f.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            var total = 0L
                            var lastShown = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                total += n
                                if (total - lastShown > 2_000_000) {
                                    lastShown = total
                                    val t = total
                                    onMain { status = "Descargando… ${Search.humanSize(t)}" }
                                }
                            }
                        }
                    }
                }
                onMain { status = "Abriendo instalador…" }
                val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", f)
                val i = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(i)
                onMain { status = "" }
            } catch (e: Throwable) {
                onMain { status = "Error al actualizar: ${e.message}" }
            }
        }
    }
}
