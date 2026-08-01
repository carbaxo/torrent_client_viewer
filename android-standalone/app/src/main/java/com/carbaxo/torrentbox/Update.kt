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
    /** @param size tamaño del APK según la Release, para comprobar el espacio antes de bajarlo. */
    data class Info(val build: Int, val url: String, val size: Long)

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
    /**
     * Borra APKs descargados que ya no sirven para nada.
     *
     * Sin esto, una instalación que sale bien deja 29 MB aparcados en la memoria
     * interna para siempre: el proceso muere reemplazado justo después y no llega a
     * limpiar. En un aparato con poco espacio eso no es una molestia, es la causa de
     * que la siguiente actualización no entre.
     *
     * Por antigüedad y no a lo bruto porque puede haber una instalación abierta
     * ahora mismo leyendo el fichero (el camino de respaldo con `content://`).
     */
    fun cleanup(ctx: Context) {
        val dir = File(ctx.applicationContext.filesDir, "apk")
        if (!dir.isDirectory) return
        io.submit {
            val cutoff = System.currentTimeMillis() - 30 * 60_000L
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        }
    }

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
                    var size = 0L
                    d.optJSONArray("assets")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val a = arr.getJSONObject(i)
                            // Se acepta cualquier .apk de la Release: al cambiar el
                            // nombre de la app, exigir un nombre exacto habria dejado
                            // sin actualizar a las versiones ya instaladas.
                            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                                url = a.optString("browser_download_url")
                                size = a.optLong("size")
                            }
                        }
                    }
                    val newer = remoteBuild > BuildConfig.CI_BUILD
                    onMain {
                        checked = true
                        available = if (newer && url != null) Info(remoteBuild, url!!, size) else null
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
    /**
     * El camino de siempre: pasarle el APK al instalador del sistema por
     * `content://`.
     *
     * No explica los fallos —Android se los traga y solo pinta «Aplicación no
     * instalada»—, pero funciona en aparatos donde la sesión no llega a mostrar la
     * confirmación. Por eso sigue aquí como plan B en vez de haberse borrado.
     */
    private fun viewFallback(app: Context, f: File) {
        val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", f)
        app.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

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
     * Y se mira el espacio libre antes de empezar, porque **eso era el fallo real**
     * de la Android TV: no había sitio, y Android lo enseñaba como un genérico
     * «Aplicación no instalada». El directorio se vacía al empezar y el `.part` se
     * borra si algo falla; el APK final se conserva hasta el siguiente arranque
     * ([cleanup]) porque el plan B lo necesita, y por eso el hueco que se exige
     * cuenta con **dos** copias a la vez.
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
                // Comprobar el espacio ANTES de bajar 29 MB para nada. Hacen falta dos
                // copias a la vez —la nuestra y la que el sistema deja en su zona de
                // preparación mientras instala— más un margen, porque un aparato al
                // límite falla igual por otro lado. Este era el fallo real en la
                // Android TV, y salía como un simple "Aplicación no instalada".
                val needed = info.size * 2 + 30_000_000L
                val free = dir.usableSpace
                if (info.size > 0 && free < needed) throw RuntimeException(
                    "no cabe: quedan ${Search.humanSize(free)} libres y hacen falta unos " +
                        "${Search.humanSize(needed)}. Libera espacio en el aparato " +
                        "(Ajustes → Almacenamiento) y vuelve a intentarlo."
                )
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
                // Primero por sesión: es el único camino que devuelve el motivo del
                // fallo. Si no se puede ni abrir, se recurre al ACTION_VIEW directo.
                val session = Installer.install(app, f)
                if (session == null) viewFallback(app, f)
                else {
                    onMain { status = "Esperando al instalador…" }
                    // Plan B por si el sistema no contesta NUNCA. En MIUI la sesión se
                    // crea y se confirma sin quejarse, pero la pantalla de «¿instalar?»
                    // no aparece, así que la app se quedaba en «Esperando al
                    // instalador…» para siempre y no había forma de actualizar.
                    // La confirmación normal llega en menos de un segundo; ocho es de
                    // sobra para no pisar un aparato simplemente lento.
                    main.postDelayed({
                        if (!Installer.reported) {
                            Installer.abandon(app, session)
                            status = "El instalador del sistema no ha respondido; " +
                                "abriéndolo por la vía clásica…"
                            runCatching { viewFallback(app, f) }.onFailure {
                                status = "No se pudo abrir el instalador: ${it.message}"
                            }
                        }
                    }, 8_000L)
                }
            } catch (e: Throwable) {
                part.delete()
                onMain { status = "Error al actualizar: ${e.message}" }
            }
        }
    }
}
