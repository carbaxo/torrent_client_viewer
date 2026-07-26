package com.carbaxo.torrentbox

import android.content.Context
import androidx.compose.runtime.mutableStateListOf

/**
 * Preferencias locales del dispositivo: orden de idiomas preferido para ordenar
 * las fuentes. El orden también puede venir de la nube (settings del perfil);
 * aquí se guarda la copia efectiva.
 *
 * En modo Real-Debrid no hay carpetas ni límites de velocidad que configurar:
 * el streaming va directo desde los servidores de RD y las descargas las
 * coloca el DownloadManager del sistema en la carpeta privada de la app.
 */
object Prefs {
    private const val FILE = "tcv_prefs"
    private lateinit var appCtx: Context

    // Estado observable por Compose
    val languageOrder = mutableStateListOf<String>()

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val sp = appCtx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val order = sp.getString("langOrder", null)
            ?.split(",")?.map { it.trim() }?.filter { Lang.byCode(it) != null }
            ?.takeIf { it.isNotEmpty() } ?: Lang.DEFAULT_ORDER
        languageOrder.clear(); languageOrder.addAll(order)
    }

    private fun sp() = appCtx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun setLanguageOrder(order: List<String>) {
        languageOrder.clear(); languageOrder.addAll(order)
        sp().edit().putString("langOrder", order.joinToString(",")).apply()
        // Sube el idioma principal a la nube (settings del perfil), como la web
        runCatching { Sync.saveSettingsLanguage(primaryTmdbLang()) }
    }

    /** Idioma principal en formato TMDB (para catálogos y fichas). */
    fun primaryTmdbLang(): String =
        Lang.byCode(languageOrder.firstOrNull() ?: "es-ES")?.tmdb ?: "es-ES"
}
