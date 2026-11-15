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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val _unavailable = MutableStateFlow(0)
    /** Si incrementa quando un trailer YouTube non si riesce a riprodurre. */
    val unavailable: StateFlow<Int> = _unavailable.asStateFlow()

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resolveJob: Job? = null
    /** Play premuto mentre l'indirizzo YouTube è ancora in risoluzione: parte appena è pronto. */
    private var pendingPlay: Boolean? = null

    /** Carica il trailer in pausa, pronto a partire. */
    fun prepare(t: Trailer) {
        if (currentId == t.id) return
        currentId = t.id
        _playing.value = false
        pendingPlay = null
        resolveJob?.cancel()
        if (!t.isYoutube) {
            setSource(nasSource(t))
            return
        }
        // YouTube: prima ricavo gli indirizzi (in background), poi preparo il player.
        player.stop()
        player.clearMediaItems()
        resolveJob = mainScope.launch {
            val streams = withContext(Dispatchers.IO) {
                YoutubeResolver.resolve(listOf(t.videoUrl) + t.videoAlternatives)
            }
            if (currentId != t.id) return@launch
            if (streams == null) {
                _playing.value = false
                pendingPlay = null
                _unavailable.value += 1
                return@launch
            }
            setSource(youtubeSource(streams))
            pendingPlay?.let { startPlayback(it) }
        }
    }

    fun play(t: Trailer, withAudio: Boolean) {
        prepare(t)
        _playing.value = true
        if (t.isYoutube && player.mediaItemCount == 0) {
            pendingPlay = withAudio
            return
        }
        startPlayback(withAudio)
    }

    private fun startPlayback(withAudio: Boolean) {
        pendingPlay = null
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.volume = if (withAudio) 1f else 0f
        player.play()
    }

    private fun setSource(source: MediaSource) {
        player.setMediaSource(source)
        player.playWhenReady = false
        player.prepare()
    }

    fun togglePause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, duration - 500))
    }

    fun stop() {
        pendingPlay = null
        player.pause()
        if (currentId != null && player.mediaItemCount > 0) player.seekTo(0)
        _playing.value = false
    }

    /**
     * Scarica in anticipo i primi MB dei trailer indicati (per YouTube ricava prima gli indirizzi),
     * così quando ci arrivi partono subito. Un nuovo elenco annulla il precedente.
     */
    fun precache(trailers: List<Trailer>) {
        precacheJobs.forEach { it.cancel(true) }
        currentWriter?.cancel()
        precacheJobs = trailers.map { t ->
            precacheExecutor.submit {
                try {
                    if (t.isYoutube) {
                        val s = YoutubeResolver.resolve(listOf(t.videoUrl) + t.videoAlternatives) ?: return@submit
                        cacheStart(s.video, "${s.cacheKey}:v", VIDEO_PRECACHE_BYTES)
                        s.audio?.let { cacheStart(it, "${s.cacheKey}:a", AUDIO_PRECACHE_BYTES) }
                    } else {
                        cacheStart(t.videoUrl, null, VIDEO_PRECACHE_BYTES)
                        t.audioUrl?.let { cacheStart(it, null, AUDIO_PRECACHE_BYTES) }
                    }
                } catch (e: Exception) {
                    if (e !is InterruptedException) Log.d(TAG, "precache interrotto: ${e.message}")
                }
            }
        }
    }

    @Volatile private var currentWriter: CacheWriter? = null

    private fun cacheStart(url: String, key: String?, bytes: Long) {
        if (Thread.currentThread().isInterrupted) return
        val spec = DataSpec.Builder().setUri(Uri.parse(url)).setLength(bytes).apply { key?.let { setKey(it) } }.build()
        val writer = CacheWriter(cacheFactory.createDataSource(), spec, null, null)
        currentWriter = writer
        writer.cache()
    }

    fun release() {
        currentWriter?.cancel()
        precacheExecutor.shutdownNow()
        mainScope.cancel()
        player.release()
    }

    private fun nasSource(t: Trailer): MediaSource {
        val factory = ProgressiveMediaSource.Factory(cacheFactory)
        val video = factory.createMediaSource(MediaItem.fromUri(t.videoUrl))
        val audio = t.audioUrl?.let { factory.createMediaSource(MediaItem.fromUri(it)) }
            ?: return video
        return MergingMediaSource(video, audio)
    }

    /** Gli indirizzi YouTube cambiano a ogni risoluzione: la chiave di cache resta legata al video. */
    private fun youtubeSource(s: YoutubeResolver.Streams): MediaSource {
        val factory = ProgressiveMediaSource.Factory(cacheFactory)
        fun item(url: String, key: String) = MediaItem.Builder().setUri(url).setCustomCacheKey(key).build()
        val video = factory.createMediaSource(item(s.video, "${s.cacheKey}:v"))
        val audio = s.audio?.let { factory.createMediaSource(item(it, "${s.cacheKey}:a")) } ?: return video
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
