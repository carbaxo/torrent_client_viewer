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
    data class Info(val build: Int, val url: String)

    private const val RELEASE_API =
        "https://api.github.com/repos/carbaxo/torrent_client_viewer/releases/tags/android-latest"

    var available by mutableStateOf<Info?>(null)
    var status by mutableStateOf("")
    var checked by mutableStateOf(false)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
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
                val req = Request.Builder().url(RELEASE_API)
                    .header("User-Agent", "TorrentBox")
                    .header("Accept", "application/vnd.github+json").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@submit onMain { checked = true; status = "" }
                    val d = JSONObject(resp.body?.string() ?: "{}")
                    val remoteBuild = Regex("Build (\\d+)").find(d.optString("body"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    var url: String? = null
                    var apkEpoch = 0L
                    d.optJSONArray("assets")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val a = arr.getJSONObject(i)
                            if (a.optString("name") == "TorrentBox.apk") {
                                url = a.optString("browser_download_url")
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
                        available = if ((newerByDate || newerByBuild) && url != null)
                            Info(if (remoteBuild > 0) remoteBuild else BuildConfig.CI_BUILD + 1, url!!) else null
                    }
                }
            } catch (_: Throwable) {
                onMain { checked = true }
            }
        }
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
                val req = Request.Builder().url(info.url).header("User-Agent", "TorrentBox").build()
                client.newCall(req).execute().use { resp ->
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
