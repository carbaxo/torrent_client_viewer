package com.carbaxo.torrentbox

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Progreso de reproducción + "visto", por dispositivo y sincronizado con la
 * nube (states[perfil].progress, mismo esquema que la web).
 *
 * key    = "movie:<tmdb>"  |  "series:<tmdb>:<season>:<episode>"
 * titleId= "movie:<tmdb>"  |  "series:<tmdb>"   (la web marca el título visto)
 * "visto" si position/duration > 0.9.
 */
object WatchStore {
    data class Prog(
        val key: String, val titleId: String, val tmdbId: Int, val type: String,
        val season: Int?, val episode: Int?, val name: String, val poster: String?,
        val position: Double, val duration: Double, val watched: Boolean, val updatedAt: String
    )

    private lateinit var appCtx: Context
    val list = mutableStateListOf<Prog>()

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val raw = sp().getString("progress", null) ?: return
        runCatching { list.addAll(parse(JSONArray(raw))) }
    }

    private fun sp() = appCtx.getSharedPreferences("tcv_prefs", Context.MODE_PRIVATE)
    private fun iso() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun persistLocal() {
        sp().edit().putString("progress", toJson().toString()).apply()
    }

    fun toJson(): JSONArray {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("key", p.key); put("titleId", p.titleId); put("tmdbId", p.tmdbId)
                put("type", p.type); if (p.season != null) put("season", p.season)
                if (p.episode != null) put("episode", p.episode)
                put("name", p.name); if (p.poster != null) put("poster", p.poster)
                put("position", p.position); put("duration", p.duration)
                put("watched", p.watched); put("updatedAt", p.updatedAt)
            })
        }
        return arr
    }

    fun parse(arr: JSONArray): List<Prog> {
        val out = ArrayList<Prog>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("key")
            if (key.isBlank()) continue
            out.add(Prog(
                key = key,
                titleId = o.optString("titleId", key),
                tmdbId = o.optInt("tmdbId", Regex("(\\d+)").find(o.optString("titleId"))?.value?.toIntOrNull() ?: 0),
                type = if (o.optString("type") == "series" || key.startsWith("series")) "series" else "movie",
                season = if (o.has("season")) o.optInt("season") else null,
                episode = if (o.has("episode")) o.optInt("episode") else null,
                name = o.optString("name", ""),
                poster = o.optString("poster", "").takeIf { it.isNotBlank() && it != "null" },
                position = o.optDouble("position", 0.0),
                duration = o.optDouble("duration", 0.0),
                watched = o.optBoolean("watched", false),
                updatedAt = o.optString("updatedAt", "")
            ))
        }
        return out
    }

    /** Para escribir en Firestore (states[perfil].progress). */
    fun toMaps(): List<Map<String, Any?>> = list.map { p ->
        val m = HashMap<String, Any?>()
        m["key"] = p.key; m["titleId"] = p.titleId; m["tmdbId"] = p.tmdbId; m["type"] = p.type
        if (p.season != null) m["season"] = p.season
        if (p.episode != null) m["episode"] = p.episode
        m["name"] = p.name; if (p.poster != null) m["poster"] = p.poster
        m["position"] = p.position; m["duration"] = p.duration
        m["watched"] = p.watched; m["updatedAt"] = p.updatedAt
        m
    }

    /** Sustituye el estado local por el de la nube (al elegir perfil). */
    fun loadFromMaps(maps: List<Map<String, Any?>>) {
        val arr = JSONArray()
        maps.forEach { arr.put(JSONObject(it)) }
        list.clear(); list.addAll(parse(arr)); persistLocal()
    }

    private fun upsert(p: Prog) {
        val i = list.indexOfFirst { it.key == p.key }
        if (i >= 0) list.removeAt(i)
        list.add(0, p)
        persistLocal()
        // Empuja a la nube si hay perfil activo (no-op si no hay sesión)
        runCatching { Sync.saveProgressCloud(toMaps()) }
    }

    /** Registra progreso de reproducción. */
    fun record(tmdbId: Int, type: String, season: Int?, episode: Int?, name: String, poster: String?, position: Double, duration: Double) {
        if (tmdbId <= 0 || position < 5) return
        val isSeries = type == "series" && season != null
        val titleId = "${if (isSeries) "series" else "movie"}:$tmdbId"
        val key = if (isSeries) "series:$tmdbId:$season:${episode ?: 1}" else "movie:$tmdbId"
        val watched = duration > 0 && position / duration > 0.9
        upsert(Prog(key, titleId, tmdbId, if (isSeries) "series" else "movie", season, episode, name, poster, position, duration, watched, iso()))
    }

    fun isWatchedTitle(type: String, tmdbId: Int): Boolean {
        val tid = "${if (type == "series") "series" else "movie"}:$tmdbId"
        return list.any { it.titleId == tid && it.watched }
    }

    fun isWatchedEpisode(tmdbId: Int, season: Int, episode: Int): Boolean =
        list.any { it.key == "series:$tmdbId:$season:$episode" && it.watched }

    /** Progreso de un episodio/título (para reanudar / barra). */
    fun progressFor(key: String): Prog? = list.firstOrNull { it.key == key }

    /** En curso (para "Continuar viendo"). */
    fun continueWatching(): List<Prog> = list
        .filter { !it.watched && it.position > 20 && (it.duration <= 0 || it.position / it.duration < 0.95) }
        .sortedByDescending { it.updatedAt }

    /** Semillas para recomendaciones: títulos vistos/en curso, recientes. */
    fun seeds(): List<Pair<Int, String>> = list
        .sortedByDescending { it.updatedAt }
        .map { it.tmdbId to it.type }
        .distinct()
        .filter { it.first > 0 }
        .take(6)
}
