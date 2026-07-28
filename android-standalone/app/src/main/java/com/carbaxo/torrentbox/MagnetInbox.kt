package com.carbaxo.torrentbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Buzón de magnets y enlaces que llegan de FUERA de la app: al pulsar un magnet
 * en el navegador (intent VIEW con esquema `magnet`) o al compartir un texto con
 * VizPlay (ACTION_SEND). También lo usa la propia app para pasar el magnet de
 * un enlace que falló a la pantalla de Descargas.
 *
 * La pestaña Descargas lo recoge, rellena el campo de "Añadir a Real-Debrid" y
 * lo vacía con [clear].
 */
object MagnetInbox {

    /** Lo último que llegó de fuera, pendiente de recoger. */
    var pending by mutableStateOf<String?>(null)
        private set

    private val MAGNET = Regex("magnet:\\?[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    private val URL = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)

    /**
     * Guarda un magnet o un enlace, **sacándolo de dentro** del texto compartido.
     *
     * Se busca con expresión regular en vez de exigir que el texto empiece por
     * `http`: los navegadores comparten «Título de la página \n enlace», así que
     * exigiendo el principio se descartaba justo lo que el usuario acababa de
     * compartir. Compartir desde el navegador es además mucho más fiable que
     * copiar y pegar a mano en el móvil, donde es fácil llevarse el enlace a
     * medias.
     *
     * No puede llamarse "setPending": la propiedad ya genera ese setter en la JVM
     * y chocarían.
     */
    fun offer(text: String?) {
        val t = text?.trim().orEmpty()
        if (t.isBlank()) return
        pending = (MAGNET.find(t)?.value ?: URL.find(t)?.value)?.let { Links.tidy(it) }
    }

    fun clear() { pending = null }

    /**
     * Un **fichero .torrent** que llega de fuera: al pulsar el que acaba de bajar
     * el navegador, o al compartirlo desde el gestor de archivos.
     *
     * Va aparte de [pending] porque no es texto: es un `content://` del que hay que
     * leer los bytes, y hay que hacerlo **mientras el permiso del intent sigue
     * vivo**. Es el camino más sencillo de todos para las webs que no dan magnet:
     * el navegador ya sabe bajar el fichero, así que la app no tiene que leer
     * ninguna página.
     */
    var pendingFile by mutableStateOf<android.net.Uri?>(null)
        private set

    fun offerFile(uri: android.net.Uri?) { if (uri != null) pendingFile = uri }

    fun clearFile() { pendingFile = null }
}
