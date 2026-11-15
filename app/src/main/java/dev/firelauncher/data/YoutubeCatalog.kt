package dev.firelauncher.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Modalità senza NAS: costruisce sulla Fire Stick l'elenco dei trailer chiedendo a TMDB,
 * con gli stessi criteri del servizio NAS (popolarità nel periodo scelto, precedenza ai trailer
 * in italiano, testi in italiano e inglese). I video poi vengono presi da YouTube in streaming.
 *
 * Il risultato ha lo stesso formato di manifest.json del NAS, con video = "yt:<id YouTube>".
 */
class YoutubeCatalog(private val apiKey: String) {

    class MissingKeyException : Exception("Chiave TMDB mancante")

    suspend fun build(monthsFrom: Int, monthsTo: Int, count: Int): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw MissingKeyException()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fun monthsAgo(m: Int) = day.format(Calendar.getInstance().apply { add(Calendar.MONTH, -m) }.time)
        val from = monthsAgo(monthsFrom)
        val to = monthsAgo(monthsTo)

        val genresEn = runCatching {
            get("/genre/movie/list", mapOf("language" to "en-US")).getJSONArray("genres").let { a ->
                (0 until a.length()).associate { a.getJSONObject(it).getInt("id") to a.getJSONObject(it).getString("name") }
            }
        }.getOrDefault(emptyMap())

        // Candidati: i più popolari del periodo, con un buon margine per chi non ha trailer.
        val candidates = mutableListOf<JSONObject>()
        val pages = (count * 2 + 19) / 20
        for (page in 1..pages) {
            val data = get(
                "/discover/movie",
                mapOf(
                    "language" to "it-IT", "region" to "IT", "sort_by" to "popularity.desc",
                    "include_adult" to "false", "include_video" to "false",
                    "primary_release_date.gte" to from, "primary_release_date.lte" to to,
                    "with_release_type" to "2|3", "page" to page.toString(),
                ),
            )
            val results = data.getJSONArray("results")
            for (i in 0 until results.length()) {
                val m = results.getJSONObject(i)
                if (m.optString("backdrop_path").isNotBlank()) candidates += m
            }
            if (page >= data.optInt("total_pages", 0)) break
        }

        // Dettagli (trailer e traduzioni) in parallelo, con moderazione.
        val limit = Semaphore(6)
        val details = coroutineScope {
            candidates.map { m ->
                async { limit.withPermit { runCatching { details(m.getInt("id")) }.getOrNull() } }
            }.awaitAll()
        }

        val withTrailer = candidates.zip(details).mapNotNull { (m, d) ->
            if (d == null) return@mapNotNull null
            val runtime = d.optInt("runtime", 0)
            if (runtime in 1..59) return@mapNotNull null
            val trailers = rankTrailers(d.optJSONObject("videos")?.optJSONArray("results"))
            if (trailers.isEmpty()) null else Triple(m, d, trailers)
        }
        // Precedenza ai film con trailer in italiano, poi gli altri; infine ordine di popolarità.
        val chosen = (withTrailer.filter { it.third.first().optString("iso_639_1") == "it" } +
            withTrailer.filter { it.third.first().optString("iso_639_1") != "it" })
            .take(count)
            .sortedByDescending { it.first.optDouble("popularity", 0.0) }

        val items = JSONArray()
        for ((m, d, trailers) in chosen) {
            val backdrop = d.optString("backdrop_path").ifBlank { m.optString("backdrop_path") }
            items.put(
                JSONObject(texts(d, genresEn))
                    .put("id", m.getInt("id"))
                    .put("original_title", d.optString("original_title"))
                    .put("release_date", d.optString("release_date").ifBlank { m.optString("release_date") })
                    .put("rating", d.optDouble("vote_average", 0.0))
                    .put("runtime", d.optInt("runtime", 0))
                    .put("popularity", m.optDouble("popularity", 0.0))
                    .put("lang", trailers.first().optString("iso_639_1"))
                    .put("video", "yt:" + trailers.first().getString("key"))
                    .put("video_alternatives", JSONArray(trailers.drop(1).take(2).map { "yt:" + it.getString("key") }))
                    .put("thumb", "$IMG/w300$backdrop")
                    .put("backdrop", "$IMG/w1280$backdrop")
            )
        }
        JSONObject()
            .put("generated", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            .put("count", items.length())
            .put("items", items)
            .toString()
    }

    private fun details(id: Int): JSONObject = get(
        "/movie/$id",
        mapOf(
            "language" to "it-IT",
            "append_to_response" to "videos,translations",
            "include_video_language" to "it,en,null",
        ),
    )

    /** Trailer ufficiale in italiano, poi in inglese, poi qualsiasi trailer YouTube. */
    private fun rankTrailers(videos: JSONArray?): List<JSONObject> {
        if (videos == null) return emptyList()
        val list = (0 until videos.length()).map { videos.getJSONObject(it) }
            .filter { it.optString("site") == "YouTube" && it.optString("type") in setOf("Trailer", "Teaser") }
        return list.sortedWith(
            compareBy<JSONObject>(
                { when (it.optString("iso_639_1")) { "it" -> 0; "en" -> 1; else -> 2 } },
                { if (it.optString("type") == "Trailer") 0 else 1 },
                { if (it.optBoolean("official")) 0 else 1 },
                { -it.optInt("size", 0) },
            )
        )
    }

    /** Come texts() del servizio NAS: se manca l'italiano si ripiega sull'inglese. */
    private fun texts(d: JSONObject, genresEn: Map<Int, String>): Map<String, Any> {
        fun pick(lang: String, country: String): JSONObject {
            var found = JSONObject()
            val all = d.optJSONObject("translations")?.optJSONArray("translations") ?: return found
            for (i in 0 until all.length()) {
                val t = all.getJSONObject(i)
                if (t.optString("iso_639_1") == lang) {
                    val data = t.optJSONObject("data") ?: continue
                    if (t.optString("iso_3166_1") == country || found.length() == 0) found = data
                }
            }
            return found
        }
        val it = pick("it", "IT")
        val en = pick("en", "US")
        val orig = d.optString("original_title").trim()
        val origLang = d.optString("original_language")
        val itTitle = it.optString("title").trim()
        val enTitle = en.optString("title").trim()
        val itOver = it.optString("overview").ifBlank { d.optString("overview") }.trim()
        val enOver = en.optString("overview").trim()
        val readableOrig = if (origLang == "it" || origLang == "en") orig else ""
        val genres = d.optJSONArray("genres")
        val genreList = (0 until (genres?.length() ?: 0)).map { genres!!.getJSONObject(it) }
        return mapOf(
            "title" to itTitle.ifBlank { readableOrig.ifBlank { enTitle.ifBlank { orig } } },
            "title_en" to enTitle.ifBlank { (if (origLang == "en") orig else "").ifBlank { itTitle.ifBlank { orig } } },
            "overview" to itOver.ifBlank { enOver },
            "overview_en" to enOver.ifBlank { itOver },
            "genres" to JSONArray(genreList.map { it.optString("name") }),
            "genres_en" to JSONArray(genreList.map { g -> genresEn[g.optInt("id")] ?: g.optString("name") }),
        )
    }

    private fun get(path: String, params: Map<String, String>): JSONObject {
        // Le chiavi v3 sono 32 caratteri; i token v4 sono lunghi e vanno nell'header.
        val bearer = apiKey.length > 40
        val query = (params + if (bearer) emptyMap() else mapOf("api_key" to apiKey))
            .entries.joinToString("&") { (k, v) -> Uri.encode(k) + "=" + Uri.encode(v) }
        var lastError: Exception? = null
        repeat(3) { attempt ->
            val conn = URL("$TMDB$path?$query").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/json")
            if (bearer) conn.setRequestProperty("Authorization", "Bearer $apiKey")
            try {
                when (val code = conn.responseCode) {
                    200 -> return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    401 -> throw IllegalStateException("Chiave TMDB non valida")
                    429 -> Thread.sleep(1500L * (attempt + 1))
                    // Non scrivo l'URL nell'errore: conterrebbe la chiave.
                    else -> throw IllegalStateException("TMDB $code su $path")
                }
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(1000L * (attempt + 1))
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("TMDB non raggiungibile", lastError)
    }

    companion object {
        private const val TMDB = "https://api.themoviedb.org/3"
        private const val IMG = "https://image.tmdb.org/t/p"
    }
}
