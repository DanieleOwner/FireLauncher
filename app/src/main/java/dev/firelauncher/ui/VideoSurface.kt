package dev.firelauncher.ui

import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer

/** TextureView (non SurfaceView) così il video rispetta angoli arrotondati e animazioni. */
@Composable
fun VideoSurface(player: ExoPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
        modifier = modifier,
        onRelease = { player.clearVideoTextureView(it) },
    )
}
