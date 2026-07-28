package com.carbaxo.torrentbox

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Canales **oficiales y gratuitos de YouTube** con dibujos en español, para el
 * perfil infantil.
 *
 * Por qué existe esto: Peppa Pig y Bluey en castellano no están en los índices de
 * torrents (se comprobó: ni por episodio ni en packs), y un canal M3U 24/7 de una
 * sola serie en castellano tampoco existe — los hay de Peppa y de Bluey, pero en
 * inglés y para Estados Unidos y Reino Unido. En cambio los dueños de las series
 * SÍ publican los episodios completos y doblados en sus canales oficiales de
 * YouTube, gratis y para siempre.
 *
 * No se intenta reproducir el vídeo dentro de la app: meter un stream de YouTube
 * en ExoPlayer no es ni legal ni estable. Se abre la app de YouTube, que en
 * Android TV viene instalada de fábrica y se maneja con el mando.
 *
 * La lista es corta a propósito: solo canales **oficiales** cuya versión en
 * español está confirmada. Es mejor tener tres que funcionan que veinte a medias.
 */
object KidsTv {

    data class Show(
        val name: String,
        val emoji: String,
        val url: String,
        /** Qué se encuentra ahí, para que el adulto sepa qué está abriendo. */
        val note: String
    )

    val OFFICIAL = listOf(
        Show(
            "Bluey", "🐶", "https://www.youtube.com/@BlueyEspanol",
            "Canal oficial en español de España. Temporadas 1, 2 y 3 completas."
        ),
        Show(
            "Peppa Pig", "🐷", "https://www.youtube.com/@PeppaPigEspanolOficial",
            "Canal oficial en español. Episodios completos."
        ),
        Show(
            "Pocoyó", "🧒", "https://www.youtube.com/@PocoyoES",
            "Canal oficial en español. Serie española."
        )
    )

    /**
     * Abre el canal en la app de YouTube (o en el navegador si no está).
     *
     * No se fija el paquete a mano: dejando que lo resuelva el sistema funciona
     * igual en el móvil y en Android TV, que usan apps de YouTube distintas, y no
     * hace falta declarar nada en el manifest.
     *
     * @return null si se abrió, o un mensaje de error si no había nada que lo abriera.
     */
    fun open(ctx: Context, show: Show): String? = try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(show.url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        null
    } catch (_: Throwable) {
        "No hay ninguna app que pueda abrir YouTube en este dispositivo."
    }
}
