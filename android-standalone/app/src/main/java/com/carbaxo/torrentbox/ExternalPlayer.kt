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
 *
 * Se puede fijar una app concreta con [open]`(pkg = VLC)`. Para que eso funcione
 * en Android 11+ hay que declarar los paquetes en `<queries>` del manifiesto: sin
 * eso el sistema oculta las demás apps y parecería que VLC no está instalado.
 */
object ExternalPlayer {

    const val VLC = "org.videolan.vlc"
    const val MX_FREE = "com.mxtech.videoplayer.ad"
    const val MX_PRO = "com.mxtech.videoplayer.pro"

    /** ¿Está instalada esa app? */
    fun installed(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(pkg, 0) != null
    }.getOrDefault(false)

    fun vlcInstalled(ctx: Context) = installed(ctx, VLC)

    /** Nombre presentable para los mensajes. */
    fun label(pkg: String?): String = when (pkg) {
        VLC -> "VLC"
        MX_FREE, MX_PRO -> "MX Player"
        null -> "otra app"
        else -> pkg
    }

    /** Abre la ficha de VLC en Play Store (o en la web si no hay Play Store). */
    fun installVlc(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$VLC"))) }
            .onFailure {
                runCatching {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$VLC"))
                    )
                }
            }
    }

    /**
     * Lanza la app externa. Devuelve un mensaje de error si no se pudo, o null
     * si se abrió bien.
     *
     * @param pkg app concreta (p. ej. [VLC]). null = que elija el usuario.
     */
    fun open(
        ctx: Context,
        url: String,
        title: String = "",
        positionMs: Long = 0L,
        pkg: String? = null
    ): String? {
        if (url.isBlank()) return "No hay nada que reproducir."
        if (pkg != null && !installed(ctx, pkg)) {
            return "${label(pkg)} no está instalado en este dispositivo."
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndTypeAndNormalize(Uri.parse(url), "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (title.isNotBlank()) {
                putExtra("title", title)              // lo entienden VLC y MX Player
                putExtra("secure_uri", true)          // VLC: permite content://
            }
            if (positionMs > 0) {
                // Ojo con el tipo: VLC lee "position" como LONG y MX Player como
                // INT. Si se manda el que no es, la app ignora el extra y empieza
                // desde el principio; por eso se elige según a quién va.
                if (pkg == VLC) {
                    putExtra("position", positionMs)
                    putExtra("from_start", false)
                } else {
                    putExtra("position", positionMs.toInt())
                }
            }
            if (pkg != null) setPackage(pkg)
        }
        return try {
            ctx.startActivity(if (pkg == null) Intent.createChooser(intent, "Abrir el vídeo con…") else intent)
            null
        } catch (_: ActivityNotFoundException) {
            if (pkg != null) "${label(pkg)} no pudo abrir el vídeo."
            else "No hay ninguna app de vídeo que pueda abrirlo. Instala VLC o MX Player."
        } catch (e: Throwable) {
            e.message ?: "No se pudo abrir el reproductor externo."
        }
    }
}
