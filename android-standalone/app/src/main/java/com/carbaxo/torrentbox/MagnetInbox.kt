package com.carbaxo.torrentbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Buzón de magnets y enlaces que llegan de FUERA de la app: al pulsar un magnet
 * en el navegador (intent VIEW con esquema `magnet`) o al compartir un texto con
 * TorrentBox (ACTION_SEND). También lo usa la propia app para pasar el magnet de
 * un enlace que falló a la pantalla de Descargas.
 *
 * La pestaña Descargas lo recoge, rellena el campo de "Añadir a Real-Debrid" y
 * lo vacía con [clear].
 */
object MagnetInbox {

    /** Lo último que llegó de fuera, pendiente de recoger. */
    var pending by mutableStateOf<String?>(null)
        private set

    /**
     * Guarda un magnet o un enlace. Del texto compartido se extrae el magnet
     * aunque venga rodeado de más texto (los navegadores comparten el título
     * junto al enlace). No puede llamarse "setPending": la propiedad ya genera
     * ese setter en la JVM y chocarían.
     */
    fun offer(text: String?) {
        val t = text?.trim().orEmpty()
        if (t.isBlank()) return
        val magnet = Regex("magnet:\\?[^\\s\"'<>]+", RegexOption.IGNORE_CASE).find(t)?.value
        pending = magnet ?: t.takeIf { it.startsWith("http", ignoreCase = true) }
    }

    fun clear() { pending = null }
}
