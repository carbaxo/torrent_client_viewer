package com.carbaxo.torrentbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.mutableStateListOf
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Gestor de descargas propio, con **pausa y continuación**.
 *
 * Antes esto lo hacía el DownloadManager de Android, que parecía la opción
 * sensata (lo lleva el sistema, sigue con la app cerrada) pero tiene dos
 * problemas de fondo para este caso:
 *
 *  1. **No hay pausa.** Su API pública no la ofrece: solo encolar y cancelar.
 *  2. **No sabe renovar el enlace.** Los enlaces directos de Real-Debrid van
 *     atados a tu IP y caducan. Si la descarga se corta y se reintenta más
 *     tarde, el enlace ya no vale y el DownloadManager solo puede fallar. Eso es
 *     lo que se veía como "se para a mitad".
 *
 * Aquí se descarga con peticiones HTTP `Range`, así que continuar es pedir
 * "desde el byte N"; y si el enlace ha caducado, se le pide a Real-Debrid uno
 * nuevo con el magnet guardado y se sigue desde donde iba, sin volver a empezar.
 *
 * Corre en un Worker de WorkManager en primer plano: sobrevive a cerrar la app y
 * a reiniciar el móvil, y respeta la preferencia de datos móviles.
 */
object Downloads {

    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val DONE = "done"
    const val ERROR = "error"

    private const val PREFS = "torrentbox_dl"
    private const val KEY = "jobs"
    private const val CHANNEL = "downloads"

    data class Job(
        val id: String,
        val name: String,
        val file: String,
        val url: String,
        val magnet: String = "",
        val bytes: Long = 0L,
        val total: Long = 0L,
        val state: String = QUEUED,
        val error: String = "",
        /** Solo en memoria: bytes/s de la última medición. */
        val speed: Long = 0L
    ) {
        val done: Boolean get() = state == DONE
        val running: Boolean get() = state == RUNNING
        val paused: Boolean get() = state == PAUSED
        val failed: Boolean get() = state == ERROR
        val known: Boolean get() = total > 0
        val pct: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    /**
     * Copia observable por Compose. La verdad vive en [jobs], que es sincronizada
     * y se puede leer desde el hilo del Worker; esta solo se refresca para la UI.
     */
    val list = mutableStateListOf<Job>()

    private val jobs = java.util.Collections.synchronizedList(ArrayList<Job>())
    private var appCtx: Context? = null
    private var lastSave = 0L

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private fun onMain(b: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) b() else main.post(b)
    }

    internal val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ------------------------------------------------------------------
    //  Ciclo de vida y persistencia
    // ------------------------------------------------------------------

    fun init(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        createChannel()
        load()
        adoptLooseFiles()
        // Nada puede estar "descargando" si acabamos de arrancar: si el proceso
        // murió a mitad, el estado quedó congelado en RUNNING y sin esto la UI
        // mostraría una descarga viva que no existe.
        synchronized(jobs) {
            for (i in jobs.indices) if (jobs[i].running) jobs[i] = jobs[i].copy(state = PAUSED, speed = 0L)
        }
        save(force = true)
        publish()
    }

    private fun prefs() = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load() {
        val raw = prefs()?.getString(KEY, "") ?: ""
        if (raw.isBlank()) return
        runCatching {
            val arr = JSONArray(raw)
            synchronized(jobs) {
                jobs.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    jobs.add(
                        Job(
                            id = o.optString("id"),
                            name = o.optString("name"),
                            file = o.optString("file"),
                            url = o.optString("url"),
                            magnet = o.optString("magnet"),
                            bytes = o.optLong("bytes"),
                            total = o.optLong("total"),
                            state = o.optString("state", QUEUED),
                            error = o.optString("error")
                        )
                    )
                }
            }
        }
    }

    private fun save(force: Boolean = false) {
        val now = System.currentTimeMillis()
        // El progreso llega varias veces por segundo: no hace falta escribir disco
        // cada vez, solo en los cambios de estado y de vez en cuando.
        if (!force && now - lastSave < 3000) return
        lastSave = now
        val arr = JSONArray()
        synchronized(jobs) {
            for (j in jobs) arr.put(
                JSONObject()
                    .put("id", j.id).put("name", j.name).put("file", j.file)
                    .put("url", j.url).put("magnet", j.magnet)
                    .put("bytes", j.bytes).put("total", j.total)
                    .put("state", j.state).put("error", j.error)
            )
        }
        prefs()?.edit()?.putString(KEY, arr.toString())?.apply()
    }

    private fun publish() {
        val snap = synchronized(jobs) { jobs.toList() }
        onMain { list.clear(); list.addAll(snap) }
    }

    /** Ficheros que están en la carpeta pero no en la lista (versiones previas). */
    private fun adoptLooseFiles() {
        val ctx = appCtx ?: return
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return
        val known = synchronized(jobs) { jobs.map { File(it.file).name }.toSet() }
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.length() > 0 && f.name !in known) {
                synchronized(jobs) {
                    jobs.add(
                        Job(
                            id = "file:${f.name.hashCode()}", name = f.name, file = f.absolutePath,
                            url = "", bytes = f.length(), total = f.length(), state = DONE
                        )
                    )
                }
            }
        }
    }

    fun get(id: String): Job? = synchronized(jobs) { jobs.firstOrNull { it.id == id } }

    private fun edit(id: String, force: Boolean = true, block: (Job) -> Job) {
        synchronized(jobs) {
            val i = jobs.indexOfFirst { it.id == id }
            if (i < 0) return
            jobs[i] = block(jobs[i])
        }
        save(force)
        publish()
    }

    // ------------------------------------------------------------------
    //  API para la app
    // ------------------------------------------------------------------

    private fun safeName(name: String): String {
        val n = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "video" }
        return if (n.contains('.')) n else "$n.mp4"
    }

    /**
     * Encola una descarga. [magnet] es opcional pero muy recomendable: es lo que
     * permite pedirle a Real-Debrid un enlace nuevo si el actual caduca.
     */
    fun add(ctx: Context, url: String, name: String, magnet: String = ""): String {
        init(ctx)
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val file = File(dir, uniqueName(dir, safeName(name)))
        val id = java.util.UUID.randomUUID().toString()
        synchronized(jobs) {
            jobs.add(Job(id = id, name = name.ifBlank { file.name }, file = file.absolutePath, url = url, magnet = magnet))
        }
        save(force = true); publish()
        start(ctx, id)
        return id
    }

    /** Evita pisar un fichero ya descargado con el mismo nombre. */
    private fun uniqueName(dir: File?, name: String): String {
        if (dir == null || !File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (File(dir, "$base ($n)$ext").exists()) n++
        return "$base ($n)$ext"
    }

    private fun workName(id: String) = "dl-$id"

    fun start(ctx: Context, id: String) {
        val job = get(id) ?: return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (Prefs.downloadOverMobile) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .build()
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("id" to id, "name" to job.name))
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, req)
    }

    /** Pausa: se marca antes de cancelar para que el Worker no lo tome por error. */
    fun pause(ctx: Context, id: String) {
        edit(id) { it.copy(state = PAUSED, speed = 0L) }
        WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(workName(id))
    }

    /** Continúa desde el byte donde se quedó (o reintenta si había fallado). */
    fun resume(ctx: Context, id: String) {
        edit(id) { it.copy(state = QUEUED, error = "") }
        start(ctx, id)
    }

    fun remove(ctx: Context, id: String) {
        val job = get(id)
        WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(workName(id))
        job?.let { runCatching { File(it.file).delete() } }
        synchronized(jobs) { jobs.removeAll { it.id == id } }
        save(force = true); publish()
    }

    /** Uri para reproducir: content:// vía FileProvider, que VLC también acepta. */
    fun uriFor(ctx: Context, job: Job): String? {
        val f = File(job.file)
        if (!f.exists()) return null
        return runCatching {
            androidx.core.content.FileProvider
                .getUriForFile(ctx, "${ctx.packageName}.fileprovider", f).toString()
        }.getOrElse { android.net.Uri.fromFile(f).toString() }
    }

    // ------------------------------------------------------------------
    //  Lo que usa el Worker
    // ------------------------------------------------------------------

    internal fun mark(id: String, state: String, error: String = "") =
        edit(id) { it.copy(state = state, error = error, speed = if (state == RUNNING) it.speed else 0L) }

    internal fun setUrl(id: String, url: String) = edit(id) { it.copy(url = url) }
    internal fun setTotal(id: String, total: Long) = edit(id) { it.copy(total = total) }

    internal fun progress(id: String, bytes: Long, total: Long, speed: Long) =
        edit(id, force = false) { it.copy(bytes = bytes, total = if (total > 0) total else it.total, speed = speed, state = RUNNING) }

    internal fun finish(id: String) = edit(id) {
        val len = runCatching { File(it.file).length() }.getOrDefault(it.bytes)
        it.copy(state = DONE, bytes = len, total = if (it.total > 0) it.total else len, speed = 0L, error = "")
    }

    /**
     * Pide a Real-Debrid un enlace nuevo para el mismo magnet. Bloqueante a
     * propósito: se llama desde el hilo de E/S del Worker.
     */
    internal fun freshUrl(magnet: String): String? {
        if (magnet.isBlank()) return null
        val out = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        RealDebrid.streamMagnet(magnet) { url, _, _, _ -> out.set(url); latch.countDown() }
        runCatching { latch.await(120, TimeUnit.SECONDS) }
        return out.get()
    }

    internal fun httpReason(code: Int): String = when (code) {
        401, 403 -> "Real-Debrid rechazó el enlace (caducado o de otra IP)."
        404, 410 -> "El archivo ya no está en Real-Debrid."
        416 -> "El servidor no admite continuar la descarga."
        429 -> "Demasiadas peticiones a Real-Debrid; prueba en un rato."
        in 500..599 -> "Real-Debrid falló ($code); prueba en un rato."
        else -> "El servidor respondió $code."
    }

    // ------------------------------------------------------------------
    //  Notificación de primer plano
    // ------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ctx = appCtx ?: return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Descargas", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Progreso de las descargas de Real-Debrid" }
        )
    }

    private fun notifId(id: String): Int = 1000 + (id.hashCode() and 0xFFFF)

    private fun notification(ctx: Context, name: String, done: Long, total: Long): Notification {
        val pct = if (total > 0) ((done * 100) / total).toInt() else 0
        return androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
            .setContentTitle(name.take(60))
            .setContentText(
                if (total > 0) "$pct%  ·  ${Search.humanSize(done)} / ${Search.humanSize(total)}"
                else "Descargando… ${Search.humanSize(done)}"
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, total <= 0)
            .build()
    }

    internal fun foreground(ctx: Context, id: String, name: String, done: Long, total: Long): ForegroundInfo {
        val n = notification(ctx, name, done, total)
        val nid = notifId(id)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(nid, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(nid, n)
        }
    }
}

/**
 * Descarga un fichero con continuación por `Range` y renovación del enlace de
 * Real-Debrid si ha caducado. Va en primer plano, así que puede durar lo que
 * haga falta y sobrevive a que se cierre la app.
 */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        Downloads.init(applicationContext)
        val id = inputData.getString("id") ?: ""
        val name = inputData.getString("name") ?: "Descarga"
        return Downloads.foreground(applicationContext, id, name, 0, 0)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Downloads.init(applicationContext)
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val job = Downloads.get(id) ?: return@withContext Result.failure()
        Downloads.mark(id, Downloads.RUNNING)
        setForeground(Downloads.foreground(applicationContext, id, job.name, job.bytes, job.total))

        var url = job.url
        var renewed = false

        try {
            // Como maximo: intento normal + intento con enlace renovado
            var pass = 0
            while (pass++ < 2) {
                val file = File(job.file)
                file.parentFile?.mkdirs()
                val have = if (file.exists()) file.length() else 0L

                val b = Request.Builder().url(url).header("User-Agent", "TorrentBox")
                if (have > 0) b.header("Range", "bytes=$have-")

                val resp = try {
                    Downloads.http.newCall(b.build()).execute()
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    // Corte de red: no es culpa del enlace, WorkManager lo reintenta
                    Downloads.mark(id, Downloads.ERROR, "Se cortó la conexión. Pulsa continuar.")
                    return@withContext Result.retry()
                }

                // 416 = el rango pedido no existe: el fichero ya estaba completo
                if (resp.code == 416) {
                    resp.close()
                    Downloads.finish(id)
                    return@withContext Result.success()
                }

                if (!resp.isSuccessful) {
                    val code = resp.code
                    resp.close()
                    // Los enlaces de RD caducan y van atados a la IP: con el magnet
                    // se puede pedir uno nuevo y seguir desde donde iba.
                    if (!renewed && job.magnet.isNotBlank()) {
                        renewed = true
                        Downloads.mark(id, Downloads.RUNNING, "Renovando el enlace en Real-Debrid…")
                        val fresh = Downloads.freshUrl(job.magnet)
                        if (fresh != null && fresh != url) {
                            url = fresh
                            Downloads.setUrl(id, fresh)
                            continue
                        }
                    }
                    Downloads.mark(id, Downloads.ERROR, Downloads.httpReason(code))
                    return@withContext Result.failure()
                }

                val body = resp.body
                if (body == null) {
                    resp.close()
                    Downloads.mark(id, Downloads.ERROR, "Real-Debrid devolvió una respuesta vacía.")
                    return@withContext Result.failure()
                }

                // 206 = nos da el trozo pedido. 200 con have>0 = ignoró el Range y
                // manda el fichero entero: hay que empezar de cero.
                val partial = resp.code == 206 && have > 0
                if (!partial && have > 0) runCatching { file.delete() }
                val len = body.contentLength()
                val total = if (partial) have + len else len
                if (total > 0) Downloads.setTotal(id, total)

                var written = if (partial) have else 0L
                var lastTick = System.currentTimeMillis()
                var lastBytes = written

                FileOutputStream(file, partial).use { out ->
                    body.byteStream().use { ins ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (!isActive) throw CancellationException("pausada")
                            val n = ins.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            written += n
                            val now = System.currentTimeMillis()
                            if (now - lastTick >= 800) {
                                val dt = now - lastTick
                                val speed = if (dt > 0) (written - lastBytes) * 1000 / dt else 0L
                                Downloads.progress(id, written, total, speed)
                                setForeground(Downloads.foreground(applicationContext, id, job.name, written, total))
                                lastTick = now; lastBytes = written
                            }
                        }
                        out.flush()
                    }
                }
                resp.close()

                // Si el servidor cortó antes de tiempo, que WorkManager reintente:
                // al volver, el Range continúa desde lo que haya en disco.
                if (total > 0 && written < total) {
                    Downloads.progress(id, written, total, 0L)
                    Downloads.mark(id, Downloads.ERROR, "La descarga se cortó a mitad. Pulsa continuar.")
                    return@withContext Result.retry()
                }

                Downloads.finish(id)
                return@withContext Result.success()
            }
            Downloads.mark(id, Downloads.ERROR, "No se pudo continuar la descarga. Pulsa continuar.")
            Result.retry()
        } catch (e: CancellationException) {
            // Pausa o cancelación: el trozo descargado se queda en disco
            throw e
        } catch (e: Throwable) {
            Downloads.mark(id, Downloads.ERROR, e.message ?: "Error de descarga")
            Result.failure()
        }
    }
}
