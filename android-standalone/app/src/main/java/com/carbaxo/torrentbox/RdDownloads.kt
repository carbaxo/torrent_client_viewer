package com.carbaxo.torrentbox

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

/**
 * Descargas vía Real-Debrid: baja el archivo (enlace directo de RD) al
 * almacenamiento de la app con el DownloadManager de Android. Quedan
 * disponibles sin conexión y se reproducen en el propio móvil.
 */
object RdDownloads {
    data class Snap(
        val id: Long,
        val name: String,
        val bytes: Long,
        val total: Long,
        val done: Boolean,
        val failed: Boolean,
        val localUri: String?
    )

    private fun dm(ctx: Context) = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("torrentbox", Context.MODE_PRIVATE)

    // Persistimos "id|nombre" separados por ; para poder listarlas tras reiniciar
    private fun ids(ctx: Context): List<Pair<Long, String>> {
        val raw = prefs(ctx).getString("rd_dls", "") ?: ""
        return raw.split(";").filter { it.isNotBlank() }.mapNotNull {
            val i = it.indexOf('|'); if (i < 0) return@mapNotNull null
            val id = it.substring(0, i).toLongOrNull() ?: return@mapNotNull null
            id to it.substring(i + 1)
        }
    }
    private fun saveIds(ctx: Context, list: List<Pair<Long, String>>) {
        prefs(ctx).edit().putString("rd_dls", list.joinToString(";") { "${it.first}|${it.second}" }).apply()
    }

    private fun safeName(name: String): String {
        val n = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "video" }
        return if (n.contains('.')) n else "$n.mp4"
    }

    /**
     * Encola la descarga del enlace directo de RD. Por defecto se permite con
     * DATOS MÓVILES y en roaming; se puede desactivar en Ajustes → Descargas.
     */
    fun enqueue(ctx: Context, url: String, name: String) {
        val fname = safeName(name)
        val mobile = Prefs.downloadOverMobile
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setDescription("Descarga vía Real-Debrid")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_MOVIES, fname)
            .setAllowedOverMetered(mobile)
            .setAllowedOverRoaming(mobile)
        val id = dm(ctx).enqueue(req)
        saveIds(ctx, ids(ctx) + (id to name))
    }

    /**
     * El "Ahorro de datos" de Android está activo y esta app no está excluida.
     * Es la causa habitual de que una descarga se quede parada con datos
     * móviles aunque la app sí los permita: lo bloquea el sistema, no nosotros.
     */
    fun dataSaverBlocks(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.restrictBackgroundStatus == android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }.getOrDefault(false)

    fun snapshots(ctx: Context): List<Snap> {
        val out = ArrayList<Snap>()
        val d = dm(ctx)
        for ((id, name) in ids(ctx)) {
            val q = DownloadManager.Query().setFilterById(id)
            d.query(q).use { c ->
                if (c != null && c.moveToFirst()) {
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    out.add(
                        Snap(
                            id = id, name = name, bytes = bytes, total = total,
                            done = status == DownloadManager.STATUS_SUCCESSFUL,
                            failed = status == DownloadManager.STATUS_FAILED,
                            localUri = local
                        )
                    )
                }
            }
        }
        return out
    }

    fun playUri(ctx: Context, id: Long): String? = try {
        dm(ctx).getUriForDownloadedFile(id)?.toString()
    } catch (_: Throwable) { null }

    fun remove(ctx: Context, id: Long) {
        try { dm(ctx).remove(id) } catch (_: Throwable) {}
        saveIds(ctx, ids(ctx).filterNot { it.first == id })
    }
}
