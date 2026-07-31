package com.carbaxo.torrentbox

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/**
 * Instala un APK con el **PackageInstaller de sesión** en vez de con
 * `ACTION_VIEW` + `content://`.
 *
 * El motivo es puro diagnóstico. Con `ACTION_VIEW` el sistema se traga el error y
 * solo pinta «Aplicación no instalada», sin decir nunca por qué: puede ser la
 * firma, el espacio, un APK corrupto o una versión más vieja, y todos se ven
 * exactamente igual. Con una sesión, Android devuelve el estado al `PendingIntent`
 * que se le pasa, con su código y su `EXTRA_STATUS_MESSAGE` (el
 * `INSTALL_FAILED_...` de verdad), así que el fallo se puede leer y contar.
 *
 * De paso quita de la ecuación al `FileProvider`: los bytes del APK se le
 * escriben al sistema por un stream de la sesión, sin `content://` de por medio ni
 * permisos de URI que conceder a un proceso ajeno.
 *
 * Sigue haciendo falta `REQUEST_INSTALL_PACKAGES` y que el usuario tenga activado
 * «instalar apps desconocidas»: la sesión no salta la confirmación, la pide con
 * `STATUS_PENDING_USER_ACTION` (ver [Receiver]).
 */
object Installer {

    private const val ACTION = "com.carbaxo.torrentbox.INSTALL_STATUS"

    /** Último error de instalación, en castellano. Lo pinta la pantalla de ajustes. */
    var lastError: String? = null

    /**
     * Copia el APK a una sesión de instalación y la lanza.
     *
     * @return null si la sesión arrancó (el resultado llegará al [Receiver]), o el
     *   motivo si no se pudo ni empezar.
     */
    fun install(ctx: Context, apk: File): String? {
        val app = ctx.applicationContext
        return try {
            val pi = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(app.packageName)
                // Decirle el tamaño por adelantado: así, si no cabe, falla aquí con
                // STATUS_FAILURE_STORAGE en vez de a medio copiar.
                if (apk.length() > 0) setSize(apk.length())
            }
            val id = pi.createSession(params)
            pi.openSession(id).use { session ->
                session.openWrite("vizplay", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 256 * 1024) }
                    session.fsync(out)
                }
                // FLAG_MUTABLE es obligatorio desde Android 12: el sistema tiene que
                // poder meterle los extras del resultado. Sin él no llega el estado.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val intent = Intent(ACTION).setPackage(app.packageName)
                val pending = PendingIntent.getBroadcast(app, id, intent, flags)
                session.commit(pending.intentSender)
            }
            null
        } catch (e: Throwable) {
            "No se pudo abrir el instalador: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Traduce el estado que devuelve la sesión.
     *
     * Los dos que importan son CONFLICT y STORAGE: son las dos causas reales de que
     * un APK correcto no entre, y hasta ahora las dos salían como «Aplicación no
     * instalada» a secas.
     */
    private fun explain(status: Int, msg: String?): String {
        val base = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                "Instalación cancelada."
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                "El sistema ha bloqueado la instalación. Activa «instalar apps " +
                    "desconocidas» para VizPlay."
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "Choca con la copia que ya tienes instalada: está firmada con otra " +
                    "clave, o es más nueva que esta. Desinstala VizPlay y vuelve a " +
                    "instalar (perderás el token de Real-Debrid y los ajustes)."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "Este APK no es compatible con el aparato."
            PackageInstaller.STATUS_FAILURE_INVALID ->
                "El APK está corrupto o incompleto. Vuelve a descargarlo."
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                "No hay espacio libre suficiente para instalar. Libera unos 100 MB."
            else -> "La instalación ha fallado (estado $status)."
        }
        // El mensaje crudo trae el INSTALL_FAILED_... que da el nombre exacto del
        // fallo; se enseña porque es lo único buscable si nada de lo anterior encaja.
        return if (msg.isNullOrBlank()) base else "$base [$msg]"
    }

    /**
     * Recibe el resultado de la sesión.
     *
     * Va declarado en el manifest y no registrado a mano porque en una instalación
     * que sale bien el proceso muere reemplazado, y un receptor dinámico se iría con
     * él sin llegar a contar nada.
     */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
            )
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Android pide confirmación al usuario: hay que lanzar el intent
                    // que él mismo adjunta. Es la pantalla de «¿instalar?».
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            ctx.startActivity(confirm)
                        } catch (e: Throwable) {
                            report("No se pudo pedir la confirmación: ${e.message}")
                        }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> report(null)
                else -> report(Installer.explain(status, msg))
            }
        }

        private fun report(err: String?) {
            Installer.lastError = err
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Update.status = err ?: ""
            }
        }
    }
}
