package com.carbaxo.torrentbox

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Reproduce un vídeo o una lista de YouTube **sin salir de VizPlay**.
 *
 * Es un WebView con el reproductor incrustado oficial y nada más. La razón de que
 * sea una Activity aparte y no una pantalla dentro de la app es que así el botón
 * Atrás la cierra entera, con el WebView y el vídeo, sin dejar audio sonando por
 * detrás —el fallo clásico de meter un WebView en una pestaña—.
 *
 * Ver [YouTube] para por qué se usa el incrustado en vez de extraer el stream.
 */
class YtPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"

        /**
         * Hosts por los que se le deja navegar. Es una lista **blanca**: cualquier
         * otra cosa se bloquea.
         *
         * Hace falta porque el reproductor incrustado tiene enlaces que sacan de
         * aquí —el título del vídeo, el nombre del canal, «Ver en YouTube»— y un
         * niño los va a pulsar. Sin esto, el primer toque en el título abre la app
         * de YouTube y estamos donde estábamos.
         */
        private val ALLOWED = listOf("youtube-nocookie.com", "ytimg.com", "googlevideo.com")
    }

    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) { finish(); return }

        val w = WebView(this)
        web = w
        w.setBackgroundColor(Color.BLACK)
        w.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Sin esto el autoplay no arranca: Android exige un gesto del usuario
            // para reproducir, y el gesto ya lo dio al pulsar el canal.
            mediaPlaybackRequiresUserGesture = false
            // El incrustado sirve HTTPS; no se permite bajar a HTTP por el camino.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val host = req.url.host ?: return true      // sin host, no se navega
                val ok = ALLOWED.any { host == it || host.endsWith(".$it") }
                // true = «ya me ocupo yo», y no se hace nada: el enlace se queda
                // en nada en vez de abrir otra app.
                return !ok
            }
        }
        setContentView(w)
        w.loadUrl(url)
    }

    // Atrás cierra la Activity, que es el comportamiento por defecto y el que se
    // quiere: NO se engancha el goBack() del WebView a propósito. Con goBack(),
    // Atrás dentro del reproductor te dejaría en una página intermedia de YouTube
    // en vez de devolverte a VizPlay, y habría que pulsarlo varias veces.

    override fun onPause() {
        super.onPause()
        // Pausa el vídeo al irse (llamada, botón de inicio…). Sin esto el audio
        // sigue sonando con la pantalla apagada.
        web?.onPause()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onDestroy() {
        // Orden importante: sacarlo de la jerarquía antes de destruirlo, o algunos
        // WebView dejan el proceso de render vivo.
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.loadUrl("about:blank")
            it.destroy()
        }
        web = null
        super.onDestroy()
    }
}
