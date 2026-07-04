package com.carbaxo.torrentbox

import android.content.Context
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
}
