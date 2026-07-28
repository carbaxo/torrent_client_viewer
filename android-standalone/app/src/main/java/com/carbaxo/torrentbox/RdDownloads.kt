package com.carbaxo.torrentbox

import android.content.Context

/**
 * Puerta de entrada a las descargas.
 *
 * El trabajo lo hace [Downloads] (gestor propio con pausa/continuación). Antes
 * esto usaba el DownloadManager del sistema, que no sabe pausar ni renovar los
 * enlaces caducados de Real-Debrid; se conserva este objeto porque es lo que
 * llaman las pantallas, y para el diagnóstico del "Ahorro de datos".
 */
object RdDownloads {

    /**
     * Encola la descarga del enlace directo de RD.
     *
     * @param magnet opcional pero recomendable: es lo que permite pedir un
     *   enlace nuevo si el actual caduca a mitad de la descarga.
     */
    fun enqueue(ctx: Context, url: String, name: String, magnet: String = "") {
        Downloads.add(ctx, url, name, magnet)
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
}
