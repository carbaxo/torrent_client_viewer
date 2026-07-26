package com.carbaxo.torrentbox

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Abre el vídeo en OTRA app (VLC, MX Player, Just Player…) en lugar del
 * reproductor propio. Útil sobre todo con MKV y audio DTS/TrueHD, que esas apps
 * decodifican por software aunque el móvil no los soporte de fábrica.
 *
 * Funciona igual con el streaming de Real-Debrid (URL https) y con un fichero ya
 * descargado (content:// del DownloadManager), porque se concede permiso de
 * lectura sobre el Uri.
 */
object ExternalPlayer {

    /**
     * Lanza la app externa. Devuelve un mensaje de error si no se pudo, o null
     * si se abrió bien.
     *
     * @param chooser fuerza el diálogo "Abrir con…" en vez de ir directo a la
     *   app que el sistema tenga por defecto.
     */
    fun open(
        ctx: Context,
        url: String,
        title: String = "",
        positionMs: Long = 0L,
        chooser: Boolean = true
    ): String? {
        if (url.isBlank()) return "No hay nada que reproducir."
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndTypeAndNormalize(Uri.parse(url), "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Extras que entienden los reproductores más usados
            if (title.isNotBlank()) {
                putExtra("title", title)              // VLC, MX Player
                putExtra("secure_uri", true)          // VLC: permite content://
            }
            if (positionMs > 0) putExtra("position", positionMs.toInt())  // MX Player
        }
        return try {
            ctx.startActivity(if (chooser) Intent.createChooser(intent, "Abrir el vídeo con…") else intent)
            null
        } catch (_: ActivityNotFoundException) {
            "No hay ninguna app de vídeo que pueda abrirlo. Instala VLC o MX Player."
        } catch (e: Throwable) {
            e.message ?: "No se pudo abrir el reproductor externo."
        }
    }
}
