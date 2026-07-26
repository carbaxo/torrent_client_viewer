package com.carbaxo.torrentbox

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Opciones del SDK de Cast.
 *
 * IMPORTANTE: usa el **Default Media Receiver** (CC1AD845), que es el receptor
 * más permisivo con archivos "sueltos" (MP4/WebM/HLS servidos por HTTP). El que
 * traía media3 por defecto (`DefaultCastOptionsProvider`) apunta al receptor
 * *con DRM* (A12D4273), que rechaza contenidos cuyo tipo no reconoce: la TV
 * conectaba pero no llegaba a reproducir ni vídeo ni audio.
 */
class CastOptionsProvider : OptionsProvider {

    /** ID del Default Media Receiver de Google (CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID). */
    private val defaultReceiver = "CC1AD845"

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(defaultReceiver)
            // Al salir de la app se deja de emitir (si no, la TV se queda colgada)
            .setStopReceiverApplicationWhenEndingSession(true)
            // Recupera la sesión si la app vuelve al frente
            .setResumeSavedSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): MutableList<SessionProvider>? = null
}
