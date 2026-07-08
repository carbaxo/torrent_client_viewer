package com.carbaxo.torrentbox

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Catálogos y fichas desde TMDB (igual que la web), directamente desde el
 * móvil, sin backend. La API key se inyecta en tiempo de compilación
 * (BuildConfig.TMDB_KEY) desde un secreto de GitHub Actions.
 */
object Tmdb {
    const val IMG = "https://image.tmdb.org/t/p/w342"
    const val BACKDROP = "https://image.tmdb.org/t/p/w780"
    const val STILL = "https://image.tmdb.org/t/p/w300"
    private const val REGION = "ES"
    // Idioma según las preferencias del usuario (con reserva a es-ES)
    private fun L(): String = try { Prefs.primaryTmdbLang() } catch (_: Throwable) { "es-ES" }

    val hasKey: Boolean get() = BuildConfig.TMDB_KEY.isNotBlank()

    data class Platform(val key: String, val name: String, val providers: String)
    val PLATFORMS = listOf(
        Platform("netflix", "Netflix", "8"),
        Platform("prime", "Prime Video", "9|119"),
        Platform("hbomax", "HBO Max", "384|1899|118"),
        Platform("disney", "Disney+", "337")
    )

    // Géneros TMDB aptos para el modo infantil (mismos que la web):
    // Familia (10751) / Animación (16) / Kids (10762, solo TV)
    private fun kidsGenres(type: String) = if (type == "series") "10762|16|10751" else "10751|16"

    data class Title(
        val tmdbId: Int,
        val title: String,
        val originalTitle: String,
        val year: String,
        val poster: String?,
        val rating: Double,
        val type: String // "movie" | "series"
    )

    data class Season(val season: Int, val name: String, val episodes: Int)
    data class Episode(val episode: Int, val name: String, val overview: String, val still: String?)

    data class Detail(
        val tmdbId: Int,
        val type: String,
        val title: String,
        val originalTitle: String,
        val year: String,
        val overview: String,
        val backdrop: String?,
        val poster: String?,
        val rating: Double,
        val genres: List<String>,
        val seasons: List<Season> = emptyList()
    )

    data class Row(val name: String, val items: List<Title>)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val io = Executors.newCachedThreadPool()

    private fun get(url: String): JSONObject {
        val req = Request.Builder().url(url).header("User-Agent", "TorrentBox").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("TMDB ${resp.code}")
            return JSONObject(resp.body?.string() ?: "{}")
        }
    }

    private fun mapTitle(o: JSONObject, type: String): Title? {
        val isMovie = type == "movie"
        val title = if (isMovie) o.optString("title", o.optString("original_title"))
        else o.optString("name", o.optString("original_name"))
        if (title.isBlank()) return null
        val date = if (isMovie) o.optString("release_date") else o.optString("first_air_date")
        val poster = o.optString("poster_path", "")
        return Title(
            tmdbId = o.optInt("id"),
            title = title,
            originalTitle = if (isMovie) o.optString("original_title", title) else o.optString("original_name", title),
            year = if (date.length >= 4) date.substring(0, 4) else "",
            poster = if (poster.isNotBlank()) IMG + poster else null,
            rating = (o.optDouble("vote_average", 0.0) * 10).toInt() / 10.0,
            type = type
        )
    }

    /** Catálogos de todas las plataformas para un tipo. */
    fun catalogs(type: String, kids: Boolean = false, onResult: (List<Row>?, String?) -> Unit) {
        io.submit {
            try {
                val tmdbType = if (type == "series") "tv" else "movie"
                val kidsFilter = if (kids) "&with_genres=${enc(kidsGenres(type))}" else ""
                val rows = ArrayList<Row>()
                for (p in PLATFORMS) {
                    val url = "https://api.themoviedb.org/3/discover/$tmdbType" +
                        "?api_key=${BuildConfig.TMDB_KEY}&language=${L()}" +
                        "&with_watch_providers=${enc(p.providers)}&watch_region=$REGION" +
                        "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&page=1$kidsFilter"
                    try {
                        val d = get(url)
                        val arr = d.optJSONArray("results") ?: continue
                        val items = ArrayList<Title>()
                        for (i in 0 until arr.length()) mapTitle(arr.getJSONObject(i), type)?.let { items.add(it) }
                        if (items.isNotEmpty()) rows.add(Row(p.name, items))
                    } catch (_: Throwable) { /* una plataforma que falle no tumba el resto */ }
                }
                if (rows.isEmpty()) onResult(null, "No se pudieron cargar los catálogos.")
                else onResult(rows, null)
            } catch (e: Throwable) {
                onResult(null, e.message ?: "Error de red.")
            }
        }
    }

    /** Explorar (paginado) por plataforma o género. */
    fun discover(type: String, provider: String?, genreId: Int?, page: Int, kids: Boolean = false, onResult: (List<Title>?, String?) -> Unit) {
        io.submit {
            try {
                val tmdbType = if (type == "series") "tv" else "movie"
                val sb = StringBuilder("https://api.themoviedb.org/3/discover/$tmdbType")
                    .append("?api_key=${BuildConfig.TMDB_KEY}&language=${L()}&sort_by=popularity.desc")
                    .append("&vote_count.gte=30&page=").append(page)
                if (provider != null) sb.append("&with_watch_providers=${enc(provider)}&watch_region=$REGION&with_watch_monetization_types=flatrate")
                // En modo infantil restringimos a géneros familiares (salvo que ya
                // se pida un género concreto)
                if (genreId != null) sb.append("&with_genres=").append(genreId)
                else if (kids) sb.append("&with_genres=").append(enc(kidsGenres(type)))
                val d = get(sb.toString())
                val arr = d.optJSONArray("results")
                val items = ArrayList<Title>()
                if (arr != null) for (i in 0 until arr.length()) mapTitle(arr.getJSONObject(i), type)?.let { items.add(it) }
                onResult(items, null)
            } catch (e: Throwable) { onResult(null, e.message ?: "Error de red.") }
        }
    }

    /** Búsqueda de títulos por texto (TMDB search). */
    fun searchText(query: String, type: String, onResult: (List<Title>?, String?) -> Unit) {
        io.submit {
            try {
                val tmdbType = if (type == "series") "tv" else "movie"
                val d = get("https://api.themoviedb.org/3/search/$tmdbType" +
                    "?api_key=${BuildConfig.TMDB_KEY}&language=${L()}&include_adult=false&query=${enc(query)}&page=1")
                val arr = d.optJSONArray("results")
                val items = ArrayList<Title>()
                if (arr != null) for (i in 0 until arr.length()) mapTitle(arr.getJSONObject(i), type)?.let { items.add(it) }
                onResult(items, null)
            } catch (e: Throwable) { onResult(null, e.message ?: "Error de red.") }
        }
    }

    /** Ficha de un título. */
    fun detail(type: String, tmdbId: Int, onResult: (Detail?, String?) -> Unit) {
        io.submit {
            try {
                val tmdbType = if (type == "series") "tv" else "movie"
                val d = get("https://api.themoviedb.org/3/$tmdbType/$tmdbId?api_key=${BuildConfig.TMDB_KEY}&language=${L()}")
                val isMovie = type == "movie"
                val date = if (isMovie) d.optString("release_date") else d.optString("first_air_date")
                val genres = ArrayList<String>()
                d.optJSONArray("genres")?.let { for (i in 0 until it.length()) genres.add(it.getJSONObject(i).optString("name")) }
                val poster = d.optString("poster_path", "")
                val back = d.optString("backdrop_path", "")
                // Temporadas (solo series): descarta especiales (season 0) y vacías
                val seasons = ArrayList<Season>()
                if (!isMovie) d.optJSONArray("seasons")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.getJSONObject(i)
                        val num = s.optInt("season_number", -1)
                        val eps = s.optInt("episode_count", 0)
                        if (num > 0 && eps > 0) seasons.add(Season(num, s.optString("name", "Temporada $num"), eps))
                    }
                }
                onResult(
                    Detail(
                        tmdbId = d.optInt("id"),
                        type = type,
                        title = if (isMovie) d.optString("title") else d.optString("name"),
                        originalTitle = if (isMovie) d.optString("original_title", d.optString("title")) else d.optString("original_name", d.optString("name")),
                        year = if (date.length >= 4) date.substring(0, 4) else "",
                        overview = d.optString("overview"),
                        backdrop = if (back.isNotBlank()) BACKDROP + back else null,
                        poster = if (poster.isNotBlank()) IMG + poster else null,
                        rating = (d.optDouble("vote_average", 0.0) * 10).toInt() / 10.0,
                        genres = genres.take(4),
                        seasons = seasons
                    ), null
                )
            } catch (e: Throwable) { onResult(null, e.message ?: "Error de red.") }
        }
    }

    /** Recomendaciones a partir de semillas [(tmdbId, type)] (máx. 6). */
    fun recommendations(seeds: List<Pair<Int, String>>, onResult: (List<Title>) -> Unit) {
        io.submit {
            val out = LinkedHashMap<Int, Title>()
            val seedIds = seeds.map { it.first }.toSet()
            for ((id, type) in seeds.take(6)) {
                try {
                    val tmdbType = if (type == "series") "tv" else "movie"
                    val d = get("https://api.themoviedb.org/3/$tmdbType/$id/recommendations?api_key=${BuildConfig.TMDB_KEY}&language=${L()}&page=1")
                    val arr = d.optJSONArray("results") ?: continue
                    for (i in 0 until arr.length()) {
                        val t = mapTitle(arr.getJSONObject(i), type) ?: continue
                        if (t.tmdbId !in seedIds && !out.containsKey(t.tmdbId)) out[t.tmdbId] = t
                    }
                } catch (_: Throwable) { /* una semilla que falle no tumba el resto */ }
            }
            onResult(out.values.toList().take(30))
        }
    }

    /** Clave de YouTube del tráiler (o null). Prueba en el idioma preferido y
     *  luego en inglés, priorizando Trailer oficial > Trailer > Teaser. */
    fun trailer(type: String, tmdbId: Int, onResult: (String?) -> Unit) {
        io.submit {
            val tmdbType = if (type == "series") "tv" else "movie"
            fun pick(d: JSONObject): String? {
                val arr = d.optJSONArray("results") ?: return null
                data class V(val key: String, val type: String, val official: Boolean)
                val yt = ArrayList<V>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("site") == "YouTube" && o.optString("key").isNotBlank())
                        yt.add(V(o.optString("key"), o.optString("type"), o.optBoolean("official")))
                }
                return (yt.firstOrNull { it.type == "Trailer" && it.official }
                    ?: yt.firstOrNull { it.type == "Trailer" }
                    ?: yt.firstOrNull { it.type == "Teaser" }
                    ?: yt.firstOrNull())?.key
            }
            try {
                var key = pick(get("https://api.themoviedb.org/3/$tmdbType/$tmdbId/videos?api_key=${BuildConfig.TMDB_KEY}&language=${L()}"))
                if (key == null) key = pick(get("https://api.themoviedb.org/3/$tmdbType/$tmdbId/videos?api_key=${BuildConfig.TMDB_KEY}"))
                onResult(key)
            } catch (_: Throwable) { onResult(null) }
        }
    }

    /** Último episodio emitido de una serie (para avisos): (temporada, episodio, nombre). */
    fun lastEpisode(tmdbId: Int, onResult: (Triple<Int, Int, String>?) -> Unit) {
        io.submit {
            try {
                val d = get("https://api.themoviedb.org/3/tv/$tmdbId?api_key=${BuildConfig.TMDB_KEY}&language=${L()}")
                val le = d.optJSONObject("last_episode_to_air") ?: return@submit onResult(null)
                onResult(Triple(le.optInt("season_number"), le.optInt("episode_number"), le.optString("name", "")))
            } catch (_: Throwable) { onResult(null) }
        }
    }

    /** IMDb id (ttXXXXXXX) de un título, necesario para Torrentio. */
    fun imdbId(type: String, tmdbId: Int, onResult: (String?) -> Unit) {
        io.submit {
            try {
                val tmdbType = if (type == "series") "tv" else "movie"
                val d = get("https://api.themoviedb.org/3/$tmdbType/$tmdbId/external_ids?api_key=${BuildConfig.TMDB_KEY}")
                val id = d.optString("imdb_id", "")
                onResult(if (id.startsWith("tt")) id else null)
            } catch (_: Throwable) { onResult(null) }
        }
    }

    /** Episodios de una temporada de una serie. */
    fun episodes(tmdbId: Int, season: Int, onResult: (List<Episode>?, String?) -> Unit) {
        io.submit {
            try {
                val d = get("https://api.themoviedb.org/3/tv/$tmdbId/season/$season?api_key=${BuildConfig.TMDB_KEY}&language=${L()}")
                val arr = d.optJSONArray("episodes")
                val out = ArrayList<Episode>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val still = e.optString("still_path", "")
                    out.add(Episode(
                        episode = e.optInt("episode_number"),
                        name = e.optString("name", "Episodio ${e.optInt("episode_number")}"),
                        overview = e.optString("overview", ""),
                        still = if (still.isNotBlank()) STILL + still else null
                    ))
                }
                onResult(out, null)
            } catch (e: Throwable) { onResult(null, e.message ?: "Error de red.") }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
