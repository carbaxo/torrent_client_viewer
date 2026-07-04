package com.carbaxo.torrentbox

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null

    // Contexto del título (para marcar visto / continuar viendo)
    private var tmdbId = -1
    private var mediaType = "movie"
    private var season = -1
    private var episode = -1
    private var titleName = ""
    private var poster: String? = null

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val directUrl = intent.getStringExtra("url")
        val infoHash = intent.getStringExtra("infoHash")
        tmdbId = intent.getIntExtra("tmdbId", -1)
        mediaType = intent.getStringExtra("type") ?: "movie"
        season = intent.getIntExtra("season", -1)
        episode = intent.getIntExtra("episode", -1)
        titleName = intent.getStringExtra("name") ?: ""
        poster = intent.getStringExtra("poster")
        val resumeMs = intent.getLongExtra("resumeMs", 0L)

        val url: String = when {
            !directUrl.isNullOrBlank() -> directUrl
            infoHash != null -> { StreamServer.ensureStarted(); StreamServer.urlFor(infoHash) }
            else -> { finish(); return }
        }

        val view = PlayerView(this)
        setContentView(view)

        player = ExoPlayer.Builder(this).build().also { p ->
            view.player = p
            view.keepScreenOn = true
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            if (resumeMs > 0) p.seekTo(resumeMs)
            p.playWhenReady = true
        }
    }

    private fun saveProgress() {
        val p = player ?: return
        val posMs = p.currentPosition
        val durMs = p.duration // puede ser negativo si aún no se conoce
        if (tmdbId > 0 && posMs > 5000) {
            WatchStore.record(
                tmdbId = tmdbId,
                type = mediaType,
                season = season.takeIf { it > 0 },
                episode = episode.takeIf { it > 0 },
                name = titleName,
                poster = poster,
                position = posMs / 1000.0,
                duration = if (durMs > 0) durMs / 1000.0 else 0.0
            )
        }
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        saveProgress()
        player?.release()
        player = null
    }
}
