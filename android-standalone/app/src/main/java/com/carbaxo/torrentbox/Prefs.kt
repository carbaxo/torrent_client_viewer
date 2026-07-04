package com.carbaxo.torrentbox

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * Preferencias locales del dispositivo (no sincronizadas): orden de idiomas
 * y carpetas de descarga/buffer. El orden de idiomas también puede venir de
 * la nube (settings del perfil); aquí se guarda la copia efectiva.
 */
object Prefs {
    private const val FILE = "tcv_prefs"
    private lateinit var appCtx: Context

    // Estado observable por Compose
    val languageOrder = mutableStateListOf<String>()
    var downloadDir = mutableStateOf<String?>(null)
    var bufferDir = mutableStateOf<String?>(null)

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val sp = appCtx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val order = sp.getString("langOrder", null)
            ?.split(",")?.map { it.trim() }?.filter { Lang.byCode(it) != null }
            ?.takeIf { it.isNotEmpty() } ?: Lang.DEFAULT_ORDER
        languageOrder.clear(); languageOrder.addAll(order)
        downloadDir.value = sp.getString("downloadDir", null) ?: defaultDownloadDir().absolutePath
        bufferDir.value = sp.getString("bufferDir", null) ?: defaultBufferDir().absolutePath
    }

    private fun sp() = appCtx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun setLanguageOrder(order: List<String>) {
        languageOrder.clear(); languageOrder.addAll(order)
        sp().edit().putString("langOrder", order.joinToString(",")).apply()
    }

    /** Idioma principal en formato TMDB (para catálogos y fichas). */
    fun primaryTmdbLang(): String =
        Lang.byCode(languageOrder.firstOrNull() ?: "es-ES")?.tmdb ?: "es-ES"

    // --- Carpetas ---
    // En Android moderno solo se puede escribir sin permisos en las carpetas
    // privadas de la app en cada volumen (memoria interna y tarjeta SD).
    fun availableVolumes(): List<File> =
        appCtx.getExternalFilesDirs(null).filterNotNull().map { File(it, "torrents").apply { mkdirs() } }

    private fun defaultDownloadDir(): File =
        File(appCtx.getExternalFilesDir(null) ?: appCtx.filesDir, "torrents").apply { mkdirs() }

    private fun defaultBufferDir(): File =
        File(appCtx.getExternalFilesDir(null) ?: appCtx.filesDir, "buffer").apply { mkdirs() }

    fun setDownloadDir(path: String) {
        File(path).mkdirs()
        downloadDir.value = path
        sp().edit().putString("downloadDir", path).apply()
    }

    fun setBufferDir(path: String) {
        File(path).mkdirs()
        bufferDir.value = path
        sp().edit().putString("bufferDir", path).apply()
    }

    fun downloadDirFile(): File = File(downloadDir.value ?: defaultDownloadDir().absolutePath).apply { mkdirs() }
    fun bufferDirFile(): File = File(bufferDir.value ?: defaultBufferDir().absolutePath).apply { mkdirs() }

    /** Etiqueta corta y legible de una ruta (para mostrar en Ajustes). */
    fun shortLabel(path: String?): String {
        if (path.isNullOrBlank()) return "—"
        val root = Environment.getExternalStorageDirectory()?.absolutePath
        return when {
            root != null && path.startsWith(root) -> "Almacenamiento" + path.removePrefix(root)
            path.contains("/Android/data/") -> "App" + path.substringAfter("/files")
            else -> path
        }
    }

    /**
     * Convierte el árbol elegido con el selector del sistema (SAF) en una RUTA
     * real del sistema de ficheros, que es lo único que libtorrent sabe escribir.
     * Devuelve la ruta si es escribible, o null si el sistema no permite escribir
     * ahí sin permisos especiales (almacenamiento aislado de Android moderno).
     */
    fun resolveTreeUri(uri: Uri): String? {
        val real = treeUriToPath(uri) ?: return null
        return if (isWritable(real)) real else null
    }

    private fun treeUriToPath(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val split = docId.split(":", limit = 2)
        val type = split.getOrNull(0) ?: return null
        val rel = split.getOrNull(1) ?: ""
        if (type.equals("primary", ignoreCase = true)) {
            val base = Environment.getExternalStorageDirectory() ?: return null
            return File(base, rel).absolutePath
        }
        // Volumen extraíble (SD): derivar la raíz desde getExternalFilesDirs
        for (f in appCtx.getExternalFilesDirs(null).filterNotNull()) {
            val p = f.absolutePath
            val idx = p.indexOf("/Android/data")
            if (idx > 0) {
                val root = p.substring(0, idx)
                if (root.contains(type)) return File(root, rel).absolutePath
            }
        }
        return null
    }

    private fun isWritable(path: String): Boolean = runCatching {
        val dir = File(path).apply { mkdirs() }
        if (!dir.isDirectory) return false
        val probe = File(dir, ".tcv_write_test")
        probe.writeText("ok"); val ok = probe.exists(); probe.delete(); ok
    }.getOrDefault(false)
}
