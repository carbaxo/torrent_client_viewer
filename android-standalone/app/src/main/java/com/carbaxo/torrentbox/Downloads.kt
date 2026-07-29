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

    /** Extensiones que se adoptan. Una carpeta compartida tiene de todo. */
    private val VIDEO_EXT = Regex("\\.(mkv|mp4|avi|m4v|webm|mov|wmv|mpg|mpeg|ts)$", RegexOption.IGNORE_CASE)

    /**
     * Vídeos que están en la carpeta pero no en la lista, para que aparezcan en
     * Descargas listos para ver.
     *
     * Se miran DOS sitios:
     *
     *  1. La carpeta privada de la app, que es la de por defecto.
     *  2. La carpeta que haya elegido el usuario en Ajustes → Descargas, que es un
     *     documento del sistema (`content://`) y hay que recorrer con
     *     DocumentsContract. Antes no se miraba, y eso tenía dos consecuencias
     *     molestas: al reinstalar la app, los vídeos seguían en el disco pero
     *     desaparecían de la lista; y un vídeo bajado por OTRA app en esa misma
     *     carpeta (una app de torrents, para lo que Real-Debrid rechaza) no había
     *     forma de verlo desde aquí.
     */
    private fun adoptLooseFiles() {
        val ctx = appCtx ?: return
        // Se compara por ruta Y por nombre: la carpeta propia guarda rutas y la
        // elegida guarda content://, y el mismo vídeo no debe entrar dos veces.
        // Mutables: al adoptar hay que apuntarlo, o el mismo nombre en las dos
        // carpetas entraria dos veces en la misma pasada.
        val knownFiles = synchronized(jobs) { jobs.mapTo(HashSet()) { it.file } }
        val knownNames = synchronized(jobs) { jobs.mapTo(HashSet()) { it.name } }

        fun adopt(name: String, target: String, size: Long) {
            if (size <= 0 || !VIDEO_EXT.containsMatchIn(name)) return
            if (target in knownFiles || name in knownNames) return
            knownFiles.add(target); knownNames.add(name)
            synchronized(jobs) {
                jobs.add(
                    Job(
                        id = "file:${target.hashCode()}", name = name, file = target,
                        url = "", bytes = size, total = size, state = DONE
                    )
                )
            }
        }

        ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.listFiles()?.forEach { f ->
            if (f.isFile) adopt(f.name, f.absolutePath, f.length())
        }

        val tree = Prefs.downloadTree
        if (tree.isBlank()) return
        runCatching {
            val treeUri = android.net.Uri.parse(tree)
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            )
            val cols = arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_SIZE,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                val ahora = System.currentTimeMillis()
                while (c.moveToNext()) {
                    val mime = c.getString(3) ?: ""
                    if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = c.getString(1) ?: continue
                    val size = if (c.isNull(2)) 0L else c.getLong(2)
                    // Un fichero tocado hace un momento puede estar BAJANDO ahora
                    // mismo (otra app escribiendo en la carpeta): adoptarlo lo
                    // daría por completo y se vería a medias.
                    val mod = if (c.isNull(4)) 0L else c.getLong(4)
                    if (mod > 0 && ahora - mod < 60_000L) continue
                    val docUri = android.provider.DocumentsContract
                        .buildDocumentUriUsingTree(treeUri, c.getString(0))
                    adopt(name, docUri.toString(), size)
                }
            }
        }
    }

    /**
     * Vuelve a mirar las carpetas. Hace falta porque [adoptLooseFiles] solo corría
     * al arrancar: si otra app deja un vídeo en la carpeta mientras VizPlay está
     * abierta, sin esto no aparecería hasta reiniciarla.
     */
    fun rescan() {
        adoptLooseFiles()
        save(force = true)
        publish()
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
        val target = createTarget(ctx, name)
        val id = java.util.UUID.randomUUID().toString()
        synchronized(jobs) {
            jobs.add(Job(id = id, name = name.ifBlank { safeName(name) }, file = target, url = url, magnet = magnet))
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

    /** El tipo importa: el selector del sistema añade extensión según el mime. */
    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2t"
        else -> "application/octet-stream"
    }

    /**
     * Crea el fichero de destino y devuelve dónde escribir: una **ruta** si se usa
     * la carpeta de la app, o un **Uri de documento** si el usuario ha elegido
     * carpeta (Ajustes → Descargas).
     *
     * Si la carpeta elegida ya no sirve (tarjeta fuera, permiso revocado), no se
     * pierde la descarga: cae a la carpeta de la app.
     */
    private fun createTarget(ctx: Context, name: String): String {
        val fname = safeName(name)
        val tree = Prefs.downloadTree
        if (tree.isNotBlank()) {
            val made = runCatching {
                val treeUri = android.net.Uri.parse(tree)
                val dirUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                )
                // createDocument ya evita pisar: renombra si el nombre existe
                android.provider.DocumentsContract.createDocument(
                    ctx.contentResolver, dirUri, mimeOf(fname), fname
                )
            }.getOrNull()
            if (made != null) return made.toString()
        }
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        return File(dir, uniqueName(dir, fname)).absolutePath
    }

    /** ¿El destino es un documento del sistema en vez de una ruta nuestra? */
    private fun isDoc(target: String) = target.startsWith("content://")

    /** Bytes ya escritos en el destino: es de donde se continúa. */
    internal fun existing(ctx: Context, target: String): Long = runCatching {
        if (!isDoc(target)) return@runCatching File(target).length()
        ctx.contentResolver.query(
            android.net.Uri.parse(target), arrayOf(android.provider.OpenableColumns.SIZE), null, null, null
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L } ?: 0L
    }.getOrDefault(0L)

    internal fun deleteTarget(ctx: Context, target: String) {
        runCatching {
            if (isDoc(target)) android.provider.DocumentsContract
                .deleteDocument(ctx.contentResolver, android.net.Uri.parse(target))
            else File(target).delete()
        }
    }

    /**
     * Destino abierto y colocado en el byte [at], recortado a esa longitud. Se usa
     * un canal (y no un FileOutputStream en modo "append") porque así vale igual
     * para una ruta y para un documento del sistema, y porque posicionar
     * explícitamente deja el fichero exactamente como debe estar al continuar.
     */
    internal class Writer(
        private val toClose: List<java.io.Closeable>,
        val channel: java.nio.channels.FileChannel
    ) : java.io.Closeable {
        override fun close() { toClose.forEach { runCatching { it.close() } } }
    }

    internal fun openAt(ctx: Context, target: String, at: Long): Writer {
        if (isDoc(target)) {
            val pfd = ctx.contentResolver.openFileDescriptor(android.net.Uri.parse(target), "rw")
                ?: throw java.io.IOException("La carpeta elegida no permite escribir.")
            val fos = FileOutputStream(pfd.fileDescriptor)
            val ch = fos.channel
            ch.truncate(at); ch.position(at)
            return Writer(listOf(fos, pfd), ch)
        }
        val f = File(target)
        f.parentFile?.mkdirs()
        val raf = java.io.RandomAccessFile(f, "rw")
        raf.setLength(at); raf.seek(at)
        return Writer(listOf(raf), raf.channel)
    }

    /** Nombre legible de la carpeta de descargas, para Ajustes. */
    fun folderLabel(): String {
        val tree = Prefs.downloadTree
        if (tree.isBlank()) return "Carpeta privada de la app"
        return runCatching {
            val id = android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(tree))
            val vol = id.substringBefore(':', "")
            val path = id.substringAfter(':', "")
            val where = if (vol == "primary") "Memoria interna" else "Tarjeta SD"
            if (path.isBlank()) where else "$where / $path"
        }.getOrDefault("Carpeta elegida")
    }

    /** Se usa la carpeta privada de la app (y por tanto se puede medir el hueco). */
    val usingAppFolder: Boolean get() = Prefs.downloadTree.isBlank()

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
        job?.let { deleteTarget(ctx, it.file) }
        synchronized(jobs) { jobs.removeAll { it.id == id } }
        save(force = true); publish()
    }

    /** Uri para reproducir: content:// vía FileProvider, que VLC también acepta. */
    fun uriFor(ctx: Context, job: Job): String? {
        // Un documento del sistema ya es un content:// que vale para el
        // reproductor propio y para VLC (se le pasa el permiso de lectura).
        if (isDoc(job.file)) return job.file.takeIf { existing(ctx, it) > 0 }
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

    internal fun finish(id: String) = edit(id) { j ->
        val len = appCtx?.let { existing(it, j.file) }?.takeIf { n -> n > 0 } ?: j.bytes
        j.copy(state = DONE, bytes = len, total = if (j.total > 0) j.total else len, speed = 0L, error = "")
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
                // El destino puede ser una ruta nuestra o un documento en la
                // carpeta que haya elegido el usuario: Downloads se encarga.
                val have = Downloads.existing(applicationContext, job.file)

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
                // manda el fichero entero: hay que empezar de cero (openAt(…, 0)
                // recorta el fichero, así que no hace falta borrarlo).
                val partial = resp.code == 206 && have > 0
                val len = body.contentLength()
                val total = if (partial) have + len else len
                if (total > 0) Downloads.setTotal(id, total)

                var written = if (partial) have else 0L
                var lastTick = System.currentTimeMillis()
                var lastBytes = written

                Downloads.openAt(applicationContext, job.file, if (partial) have else 0L).use { w ->
                    body.byteStream().use { ins ->
                        val buf = ByteArray(256 * 1024)
                        val bb = java.nio.ByteBuffer.wrap(buf)
                        while (true) {
                            if (!isActive) throw CancellationException("pausada")
                            val n = ins.read(buf)
                            if (n <= 0) break
                            bb.clear(); bb.limit(n)
                            while (bb.hasRemaining()) w.channel.write(bb)
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
                        runCatching { w.channel.force(false) }
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
