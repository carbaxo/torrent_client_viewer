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
     * Comprueba si hay una versión más nueva **comparando el número de build**:
     * el de la Release (la CI escribe "Build N — commit …" en el cuerpo) contra el
     * de esta app (`BuildConfig.CI_BUILD`).
     *
     * Antes se comparaba además la FECHA del APK subido con la hora de compilación
     * de la app, y eso estaba mal de raíz: la hora de compilación se graba cuando
     * ARRANCA el build y el APK se sube cuando TERMINA, unos siete minutos después,
     * con un margen de tolerancia de solo dos minutos. Resultado: el propio APK
     * recién instalado siempre parecía más nuevo que sí mismo y el aviso de
     * «nueva versión» no desaparecía nunca.
     *
     * El número de build no tiene ese problema: es exacto, crece en cada
     * publicación y no depende de relojes ni de márgenes.
     */
    fun check() {
        io.submit {
            try {
                val b = Request.Builder().url(RELEASE_API)
                    .header("User-Agent", "VizPlay")
                    .header("Accept", "application/vnd.github+json")
                client.newCall(b.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        // Un fallo NUNCA se traduce en "estás en la última versión":
                        // eso sería afirmar algo que no se ha podido comprobar.
                        val msg = when (resp.code) {
                            404 -> "No hay ninguna Release publicada todavía."
                            403 -> "GitHub está limitando las consultas; prueba en un rato."
                            else -> "No se pudo comprobar (HTTP ${resp.code})."
                        }
                        return@submit onMain { checked = true; available = null; status = msg }
                    }
                    val d = JSONObject(resp.body?.string() ?: "{}")
                    val remoteBuild = Regex("Build (\\d+)").find(d.optString("body"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    var url: String? = null
                    d.optJSONArray("assets")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val a = arr.getJSONObject(i)
                            // Se acepta cualquier .apk de la Release: al cambiar el
                            // nombre de la app, exigir un nombre exacto habria dejado
                            // sin actualizar a las versiones ya instaladas.
                            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                                url = a.optString("browser_download_url")
                            }
                        }
                    }
                    val newer = remoteBuild > BuildConfig.CI_BUILD
                    onMain {
                        checked = true
                        available = if (newer && url != null) Info(remoteBuild, url!!) else null
                        status = when {
                            available != null -> ""
                            // Sin número de build en el cuerpo no se puede comparar, y
                            // callarse sería decir "estás al día" sin saberlo.
                            remoteBuild == 0 ->
                                "La Release no dice qué build es, así que no se puede comparar."
                            else -> ""
                        }
                    }
                }
            } catch (e: Throwable) {
                onMain { checked = true; status = "No se pudo comprobar: ${e.message ?: "error de red"}" }
            }
        }
    }

    /**
     * Descarga el APK de la Release. Con el repositorio público basta el enlace
     * normal: antes había que pedirlo a la API del asset con el token y seguir la
     * redirección a mano, porque el enlace firmado de destino falla si se le manda
     * la cabecera Authorization. Todo eso sobra.
     */
    private fun openAsset(info: Info): okhttp3.Response = client.newCall(
        Request.Builder().url(info.url).header("User-Agent", "VizPlay").build()
    ).execute()

    /**
     * Descarga el APK de la Release y abre el instalador del sistema.
     *
     * Se baja a un `.part` y solo se renombra al nombre final si el tamaño coincide
     * con el `Content-Length`. Antes se escribía directamente sobre el fichero final
     * sin comprobar nada, y una descarga cortada por la mitad producía el fallo más
     * confuso posible: el APK truncado conserva cabeceras suficientes para que el
     * instalador ABRA y pregunte, y luego revienta al verificar la firma sobre el
     * fichero completo. En pantalla: «se va a instalar…» y después «App no
     * instalada», sin decir nunca que el problema fue la descarga.
     *
     * El directorio se vacía antes y el `.part` se borra si algo falla, para no
     * dejar 28 MB aparcados en la memoria interna: en un Android TV con poco
     * espacio libre, eso solo es gasolina para el mismo error.
     */
    fun downloadAndInstall(ctx: Context) {
        val info = available ?: return
        val app = ctx.applicationContext
        status = "Descargando actualización…"
        io.submit {
            val dir = File(app.filesDir, "apk").apply { mkdirs() }
            val f = File(dir, "VizPlay.apk")
            val part = File(dir, "VizPlay.apk.part")
            try {
                dir.listFiles()?.forEach { it.delete() }
                openAsset(info).use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    val body = resp.body ?: throw RuntimeException("Respuesta vacía")
                    val expected = body.contentLength()
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
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
                        // -1 = el servidor no dijo el tamaño (respuesta troceada);
                        // entonces no hay nada con lo que comparar.
                        if (expected > 0 && part.length() != expected) throw RuntimeException(
                            "descarga incompleta (${Search.humanSize(part.length())} de " +
                                "${Search.humanSize(expected)}). Vuelve a intentarlo."
                        )
                    }
                }
                if (!part.renameTo(f)) throw RuntimeException("no se pudo guardar el APK")
                onMain { status = "Abriendo instalador…" }
                val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", f)
                val i = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(i)
                onMain { status = "" }
            } catch (e: Throwable) {
                part.delete()
                onMain { status = "Error al actualizar: ${e.message}" }
            }
        }
    }
}
