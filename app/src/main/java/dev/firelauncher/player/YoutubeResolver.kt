package dev.firelauncher.player

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Modalità senza NAS: ricava con NewPipeExtractor gli indirizzi diretti di video e audio di un trailer
 * YouTube, che poi ExoPlayer riproduce come quelli del NAS. Gli indirizzi scadono dopo qualche ora:
 * li tengo in memoria per un po' così tornare su un trailer già visto è immediato.
 */
object YoutubeResolver {
    private const val TAG = "YoutubeResolver"
    private const val MAX_HEIGHT = 1080
    private const val TTL_MS = 3 * 60 * 60 * 1000L

    data class Streams(val video: String, val audio: String?, val cacheKey: String, val resolvedAt: Long)

    private val cache = ConcurrentHashMap<String, Streams>()
    @Volatile private var initialized = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun init() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(OkDownloader(http), Localization("it", "IT"), ContentCountry("IT"))
                initialized = true
            }
        }
    }

    /** Già risolto e non scaduto? */
    fun cached(videoId: String): Streams? =
        cache[videoId]?.takeIf { System.currentTimeMillis() - it.resolvedAt < TTL_MS }

    /**
     * Prova i trailer nell'ordine dato ("yt:<id>"); restituisce il primo che si riesce a riprodurre.
     * Bloccante: va chiamata fuori dal thread principale.
     */
    fun resolve(candidates: List<String>): Streams? {
        init()
        for (candidate in candidates) {
            val id = candidate.removePrefix("yt:")
            cached(id)?.let { return it }
            try {
                val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
                val video = info.videoOnlyStreams
                    .filter {
                        it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.MPEG_4 &&
                            it.codec?.startsWith("avc") == true && it.height in 1..MAX_HEIGHT
                    }
                    .maxByOrNull { it.height }
                val audio = info.audioStreams
                    .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.M4A }
                    .maxByOrNull { it.averageBitrate }
                val streams = if (video != null && audio != null) {
                    Streams(video.content, audio.content, "yt:$id:${video.itag}", System.currentTimeMillis())
                } else {
                    // Ripiego: video con audio già insieme (qualità più bassa, di solito 360p).
                    val muxed = info.videoStreams
                        .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.MPEG_4 }
                        .maxByOrNull { it.height } ?: continue
                    Streams(muxed.content, null, "yt:$id:${muxed.itag}", System.currentTimeMillis())
                }
                cache[id] = streams
                return streams
            } catch (e: Exception) {
                Log.w(TAG, "Trailer $id non disponibile: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return null
    }

    /** Il "downloader" che NewPipeExtractor usa per parlare con YouTube. */
    private class OkDownloader(private val client: OkHttpClient) : Downloader() {
        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody()
            val builder = okhttp3.Request.Builder()
                .url(request.url())
                .method(request.httpMethod(), body)
                .header("User-Agent", USER_AGENT)
            for ((name, values) in request.headers()) {
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            client.newCall(builder.build()).execute().use { r ->
                if (r.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
                return Response(r.code, r.message, r.headers.toMultimap(), r.body.string(), r.request.url.toString())
            }
        }

        companion object {
            private const val USER_AGENT =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
        }
    }
}
