package dev.firelauncher.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dev.firelauncher.data.Trailer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Un solo ExoPlayer per tutto il launcher.
 *
 * Per far partire i trailer subito:
 *  - il trailer selezionato viene "preparato" (caricato ma in pausa) appena ci arrivi col focus;
 *  - l'inizio dei trailer vicini viene scaricato in anticipo in una cache su disco;
 *  - il buffer minimo per partire è di mezzo secondo.
 */
@OptIn(UnstableApi::class)
class TrailerPlayer(context: Context) {
    private val app = context.applicationContext
    private val cacheFactory = CacheDataSource.Factory()
        .setCache(cache(app))
        .setUpstreamDataSourceFactory(
            DefaultHttpDataSource.Factory().setConnectTimeoutMs(3000).setReadTimeoutMs(8000)
        )
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(2_500, 20_000, 500, 1_000)
                .build()
        )
        .build()

    private val _playing = MutableStateFlow(false)
    /** True quando un trailer è in riproduzione (o in pausa a metà), cioè va mostrato il video. */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _ended = MutableStateFlow(0)
    /** Si incrementa ogni volta che un trailer finisce. */
    val ended: StateFlow<Int> = _ended.asStateFlow()

    var currentId: Int? = null
        private set

    private val precacheExecutor = Executors.newSingleThreadExecutor()
    private var precacheJobs: List<Future<*>> = emptyList()
    private var precacheWriters: List<CacheWriter> = emptyList()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    _playing.value = false
                    _ended.value += 1
                }
            }
        })
    }

    /** Carica il trailer in pausa, pronto a partire. */
    fun prepare(t: Trailer) {
        if (currentId == t.id) return
        currentId = t.id
        _playing.value = false
        player.setMediaSource(source(t))
        player.playWhenReady = false
        player.prepare()
    }

    fun play(t: Trailer, withAudio: Boolean) {
        prepare(t)
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.volume = if (withAudio) 1f else 0f
        player.play()
        _playing.value = true
    }

    fun togglePause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun stop() {
        player.pause()
        if (currentId != null) player.seekTo(0)
        _playing.value = false
    }

    /** Scarica in anticipo i primi MB dei trailer indicati. */
    fun precache(trailers: List<Trailer>) {
        precacheWriters.forEach { it.cancel() }
        precacheJobs.forEach { it.cancel(true) }
        val writers = trailers.flatMap { t ->
            listOfNotNull(t.videoUrl to VIDEO_PRECACHE_BYTES, t.audioUrl?.let { it to AUDIO_PRECACHE_BYTES })
        }.map { (url, bytes) ->
            CacheWriter(
                cacheFactory.createDataSource(),
                DataSpec.Builder().setUri(Uri.parse(url)).setLength(bytes).build(),
                null,
                null,
            )
        }
        precacheWriters = writers
        precacheJobs = writers.map { w ->
            precacheExecutor.submit {
                try {
                    w.cache()
                } catch (e: Exception) {
                    if (e !is InterruptedException) Log.d(TAG, "precache interrotto: ${e.message}")
                }
            }
        }
    }

    fun release() {
        precacheWriters.forEach { it.cancel() }
        precacheExecutor.shutdownNow()
        player.release()
    }

    private fun source(t: Trailer): MediaSource {
        val factory = ProgressiveMediaSource.Factory(cacheFactory)
        val video = factory.createMediaSource(MediaItem.fromUri(t.videoUrl))
        val audio = t.audioUrl?.let { factory.createMediaSource(MediaItem.fromUri(it)) }
            ?: return video
        return MergingMediaSource(video, audio)
    }

    companion object {
        private const val TAG = "TrailerPlayer"
        private const val VIDEO_PRECACHE_BYTES = 4L * 1024 * 1024
        private const val AUDIO_PRECACHE_BYTES = 512L * 1024
        private const val CACHE_BYTES = 400L * 1024 * 1024

        @Volatile private var sharedCache: SimpleCache? = null

        private fun cache(context: Context): SimpleCache =
            sharedCache ?: synchronized(this) {
                sharedCache ?: SimpleCache(
                    File(context.cacheDir, "trailers"),
                    LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
                    StandaloneDatabaseProvider(context),
                ).also { sharedCache = it }
            }
    }
}
