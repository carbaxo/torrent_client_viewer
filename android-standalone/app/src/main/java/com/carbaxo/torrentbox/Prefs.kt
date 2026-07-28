package com.carbaxo.torrentbox

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Preferencias locales del dispositivo: orden de idiomas preferido para ordenar
 * las fuentes. El orden también puede venir de la nube (settings del perfil);
 * aquí se guarda la copia efectiva.
 *
 * También: dónde guardar las descargas, si valen los datos móviles, con qué
 * reproductor abrir el vídeo y cómo emitir a la TV.
 */
object Prefs {
    private const val FILE = "tcv_prefs"
    private lateinit var appCtx: Context

    // Estado observable por Compose
    val languageOrder = mutableStateListOf<String>()

    /**
     * Motor de búsqueda elegido en la ficha (Todos / Torrentio / Peerflix). Se
     * recuerda entre títulos y lo reutiliza el siguiente episodio, como Stremio.
     */
    var engine by mutableStateOf(Search.ENGINE_ALL)
        private set

    /**
     * URL propia del addon Peerflix. Vacío = se usa la pública
     * (https://peerflix.mov). Si el usuario configura el suyo en
     * config.peerflix.mov (p. ej. con su Real-Debrid), pega aquí esa URL.
     */
    var peerflixUrl by mutableStateOf("")
        private set

    /**
     * Token de GitHub (solo lectura) para la auto-actualización. Hace falta
     * porque el repositorio es PRIVADO: sin él, la API de Releases responde 404
     * y la app no puede saber si hay una versión nueva. Se guarda cifrado.
     */
    var githubToken by mutableStateOf("")
        private set

    /**
     * Descargar también con datos móviles (y en roaming). Activado por defecto:
     * si se desactiva, la descarga espera a tener WiFi.
     */
    var downloadOverMobile by mutableStateOf(true)
        private set

    /**
     * Carpeta elegida por el usuario para las descargas, como Uri de árbol del
     * Storage Access Framework. Vacío = la carpeta privada de la app.
     *
     * Se guarda el Uri y no una ruta porque desde Android 10 una app no puede
     * escribir por ruta fuera de lo suyo: hay que pasar por el permiso
     * persistente que concede el propio selector del sistema.
     */
    var downloadTree by mutableStateOf("")
        private set

    /**
     * Lista M3U propia de canales de TV. Vacío = los canales integrados de RTVE.
     */
    var iptvUrl by mutableStateOf("")
        private set

    fun saveIptvUrl(u: String) {
        iptvUrl = u.trim()
        sp().edit().putString("iptvUrl", iptvUrl).apply()
        Iptv.load()
    }

    fun saveDownloadTree(uri: String) {
        downloadTree = uri.trim()
        sp().edit().putString("dlTree", downloadTree).apply()
    }

    fun saveDownloadOverMobile(on: Boolean) {
        downloadOverMobile = on
        sp().edit().putBoolean("dlOverMobile", on).apply()
    }

    // --- Con qué se reproduce: el reproductor de la app u otra app ---
    const val PLAYER_APP = "app"          // el reproductor propio
    const val PLAYER_VLC = "vlc"          // siempre VLC, sin preguntar
    const val PLAYER_ASK = "ask"          // preguntar cada vez
    const val PLAYER_EXTERNAL = "external" // otra app (diálogo "abrir con…")

    var playerMode by mutableStateOf(PLAYER_APP)
        private set

    fun savePlayerMode(mode: String) {
        playerMode = mode
        sp().edit().putString("playerMode", mode).apply()
    }

    /**
     * Emitir a la TV con VLC en vez de con el Chromecast de la propia app.
     * VLC transcodifica en el móvil, así que se come cualquier MKV con DTS, pero
     * el vídeo pasa por el teléfono y hay que darle al icono de emitir DENTRO de
     * VLC: no existe forma de decírselo desde fuera.
     */
    var castWithVlc by mutableStateOf(false)
        private set

    fun saveCastWithVlc(on: Boolean) {
        castWithVlc = on
        sp().edit().putBoolean("castWithVlc", on).apply()
    }

    private var secure: android.content.SharedPreferences? = null

    /** Almacén cifrado; si el Keystore del fabricante falla, reserva a normal. */
    private fun secureStore(): android.content.SharedPreferences {
        secure?.let { return it }
        val app = appCtx
        val sp = try {
            val master = androidx.security.crypto.MasterKey.Builder(app)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                app, "torrentbox_secure_prefs", master,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Throwable) {
            app.getSharedPreferences("torrentbox_secure_fallback", Context.MODE_PRIVATE)
        }
        secure = sp
        return sp
    }

    fun saveGithubToken(t: String) {
        githubToken = t.trim()
        secureStore().edit().putString("ghToken", githubToken).apply()
    }

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val sp = appCtx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val order = sp.getString("langOrder", null)
            ?.split(",")?.map { it.trim() }?.filter { Lang.byCode(it) != null }
            ?.takeIf { it.isNotEmpty() } ?: Lang.DEFAULT_ORDER
        languageOrder.clear(); languageOrder.addAll(order)
        engine = sp.getString("engine", Search.ENGINE_ALL) ?: Search.ENGINE_ALL
        peerflixUrl = sp.getString("peerflixUrl", "") ?: ""
        githubToken = runCatching { secureStore().getString("ghToken", "") ?: "" }.getOrDefault("")
        downloadOverMobile = sp.getBoolean("dlOverMobile", true)
        downloadTree = sp.getString("dlTree", "") ?: ""
        iptvUrl = sp.getString("iptvUrl", "") ?: ""
        playerMode = sp.getString("playerMode", PLAYER_APP) ?: PLAYER_APP
        castWithVlc = sp.getBoolean("castWithVlc", false)
    }

    fun savePeerflixUrl(u: String) {
        peerflixUrl = u.trim()
        sp().edit().putString("peerflixUrl", peerflixUrl).apply()
    }

    /**
     * Cambia el motor elegido y lo recuerda. No puede llamarse "setEngine":
     * la propiedad `engine` ya genera ese setter en la JVM y chocarían.
     */
    fun selectEngine(e: String) {
        engine = e
        sp().edit().putString("engine", e).apply()
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
