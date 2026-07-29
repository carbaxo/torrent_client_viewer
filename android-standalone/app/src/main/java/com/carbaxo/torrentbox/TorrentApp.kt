package com.carbaxo.torrentbox

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Pasa un magnet a **otra app** de torrents (LibreTorrent, Flud…).
 *
 * Es la salida para lo que Real-Debrid rechaza con `infringing_file`: ese bloqueo
 * es de su lista de copyright, no del torrent, así que un cliente BitTorrent normal
 * se lo baja sin enterarse. Y se hace **fuera** de VizPlay a propósito.
 *
 * Se descartó meter el motor BitTorrent dentro de la app —ya lo tuvo y se quitó—
 * por tres motivos que la activación bajo demanda **no** arregla: las librerías
 * nativas de libtorrent4j van en el APK aunque no se usen (eran el grueso de su
 * tamaño), mientras baja tu IP es visible para todo el swarm, y es la pieza más
 * compleja de mantener. Delegando, todo eso es problema de una app que mantiene
 * otro, y VizPlay sigue sin conectarse a ningún peer.
 *
 * Para ver el vídeo después no hace falta salir de VizPlay: si la app de torrents
 * guarda en la carpeta elegida en Ajustes → Descargas, el fichero aparece solo en
 * Descargas (ver `Downloads.adoptLooseFiles`).
 */
object TorrentApp {

    /**
     * Abre el magnet con la app que lo maneje.
     *
     * No se comprueba antes si hay alguna instalada: para eso habría que declarar
     * el esquema en `<queries>` del manifest, y con Android 11+ eso es una lista
     * que hay que mantener. Sale más simple intentarlo y explicar el fallo, que es
     * además el único caso en que el usuario necesita saber algo.
     *
     * @return null si se abrió, o el motivo si no.
     */
    fun open(ctx: Context, magnet: String): String? {
        val m = magnet.trim()
        if (!m.startsWith("magnet:", ignoreCase = true)) return "Ese enlace no es un magnet."
        return try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(m)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            null
        } catch (_: Throwable) {
            "No tienes ninguna app de torrents instalada. LibreTorrent (F-Droid) o Flud valen; " +
                "configúrala para guardar en la misma carpeta que VizPlay y el vídeo te " +
                "aparecerá aquí en Descargas."
        }
    }
}
