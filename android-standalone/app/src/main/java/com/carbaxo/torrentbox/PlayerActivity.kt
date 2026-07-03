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

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val infoHash = intent.getStringExtra("infoHash")
        if (infoHash == null) { finish(); return }

        StreamServer.ensureStarted()
        val url = StreamServer.urlFor(infoHash)

        val view = PlayerView(this)
        setContentView(view)

        player = ExoPlayer.Builder(this).build().also { p ->
            view.player = p
            view.keepScreenOn = true
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.playWhenReady = true
        }
    }

    override fun onStop() {
        super.onStop()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
