package com.carbaxo.torrentbox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Avisos de episodios nuevos: al abrir la app se compara el último episodio
 * emitido (TMDB last_episode_to_air) de cada serie favorita con el que se vio
 * la última vez que comprobamos, y si ha cambiado se lanza una notificación.
 */
object EpisodeAlerts {
    @Volatile private var ran = false

    fun check(ctx: Context) {
        if (ran || !Tmdb.hasKey) return
        val favs = Sync.favorites.filter { it.type == "series" }.take(12)
        if (favs.isEmpty()) return
        ran = true
        val app = ctx.applicationContext
        val sp = app.getSharedPreferences("tcv_prefs", Context.MODE_PRIVATE)
        for (f in favs) {
            Tmdb.lastEpisode(f.tmdbId) { last ->
                if (last == null) return@lastEpisode
                val (s, e, name) = last
                if (s <= 0 || e <= 0) return@lastEpisode
                val key = "lastEp:${f.tmdbId}"
                val now = "$s:$e"
                val prev = sp.getString(key, null)
                sp.edit().putString(key, now).apply()
                // Solo avisa si ya conocíamos un episodio anterior distinto
                if (prev != null && prev != now) {
                    notify(app, f.tmdbId, f.title, "Nuevo episodio: T${s}E$e" + if (name.isNotBlank()) " · $name" else "")
                }
            }
        }
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel("episodes", "Episodios nuevos", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val open = PendingIntent.getActivity(
                ctx, id,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = androidx.core.app.NotificationCompat.Builder(ctx, "episodes")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(id, n)
        }
    }
}
