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
    private const val REGION = "ES"
    private const val LANG = "es-ES"

    val hasKey: Boolean get() = BuildConfig.TMDB_KEY.isNotBlank()

    data class Platform(val key: String, val name: String, val providers: String)
    val PLATFORMS = listOf(
        Platform("netflix", "Netflix", "8"),
        Platform("prime", "Prime Video", "9|119"),
        Platform("hbomax", "HBO Max", "384|1899|118"),
        Platform("disney", "Disney+", "337")
    )

    data class Title(
        val tmdbId: Int,
        val title: String,
        val originalTitle: String,
        val year: String,
        val poster: String?,
        val rating: Double,
        val type: String // "movie" | "series"
    )

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
        val genres: List<String>
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
    fun catalogs(type: String, onResult: (List<Row>?, String?) -> Unit) {
        io.submit {
            try {
                val tmdbType = if (type == "series") "tv" else "movie"
                val rows = ArrayList<Row>()
                for (p in PLATFORMS) {
                    val url = "https://api.themoviedb.org/3/discover/$tmdbType" +
                        "?api_key=${BuildConfig.TMDB_KEY}&language=$LANG" +
                        "&with_watch_providers=${enc(p.providers)}&watch_region=$REGION" +
                        "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&page=1"
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
    fun discover(type: String, provider: String?, genreId: Int?, page: Int, onResult: (List<Title>?, String?) -> Unit) {
        io.submit {
            try {
                val tmdbType = if (type == "series") "tv" else "movie"
                val sb = StringBuilder("https://api.themoviedb.org/3/discover/$tmdbType")
                    .append("?api_key=${BuildConfig.TMDB_KEY}&language=$LANG&sort_by=popularity.desc")
                    .append("&vote_count.gte=30&page=").append(page)
                if (provider != null) sb.append("&with_watch_providers=${enc(provider)}&watch_region=$REGION&with_watch_monetization_types=flatrate")
                if (genreId != null) sb.append("&with_genres=").append(genreId)
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
                    "?api_key=${BuildConfig.TMDB_KEY}&language=$LANG&include_adult=false&query=${enc(query)}&page=1")
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
                val d = get("https://api.themoviedb.org/3/$tmdbType/$tmdbId?api_key=${BuildConfig.TMDB_KEY}&language=$LANG")
                val isMovie = type == "movie"
                val date = if (isMovie) d.optString("release_date") else d.optString("first_air_date")
                val genres = ArrayList<String>()
                d.optJSONArray("genres")?.let { for (i in 0 until it.length()) genres.add(it.getJSONObject(i).optString("name")) }
                val poster = d.optString("poster_path", "")
                val back = d.optString("backdrop_path", "")
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
                        genres = genres.take(4)
                    ), null
                )
            } catch (e: Throwable) { onResult(null, e.message ?: "Error de red.") }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
