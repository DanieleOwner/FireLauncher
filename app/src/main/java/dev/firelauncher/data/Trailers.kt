package dev.firelauncher.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

data class Trailer(
    val id: Int,
    val title: String,
    val overview: String,
    val releaseDate: String,
    val rating: Double,
    val runtime: Int,
    val genres: List<String>,
    val language: String,
    val videoUrl: String,
    val audioUrl: String?,
    val thumbUrl: String?,
    val backdropUrl: String?,
    val likedOnServer: Boolean,
    val titleEn: String,
    val overviewEn: String,
    val genresEn: List<String>,
    /** Solo modalità YouTube: altri trailer dello stesso film, se il primo non è disponibile. */
    val videoAlternatives: List<String> = emptyList(),
) {
    val isYoutube: Boolean get() = videoUrl.startsWith(YOUTUBE_PREFIX)

    fun title(lang: UiLanguage) = if (lang == UiLanguage.EN) titleEn.ifBlank { title } else title
    fun overview(lang: UiLanguage) = if (lang == UiLanguage.EN) overviewEn.ifBlank { overview } else overview
    fun genres(lang: UiLanguage) = if (lang == UiLanguage.EN && genresEn.isNotEmpty()) genresEn else genres

    /** Anno*12 + mese (0-based), per confrontare le date senza java.time (API 25). */
    val releaseMonthIndex: Int? = releaseDate.split("-").let { parts ->
        val y = parts.getOrNull(0)?.toIntOrNull()
        val m = parts.getOrNull(1)?.toIntOrNull()
        if (y != null && m != null) y * 12 + (m - 1) else null
    }
}

const val YOUTUBE_PREFIX = "yt:"

enum class TrailerError { NAS_UNREACHABLE, NAS_NOT_SET, TMDB_KEY_MISSING, TMDB_UNREACHABLE }

data class TrailerState(
    val items: List<Trailer> = emptyList(),
    val generatedAt: String? = null,
    val loading: Boolean = false,
    val error: TrailerError? = null,
)

/** Cuore (watchlist) o X (non mi interessa). */
enum class Pref(val wire: String) { LIKE("like"), NOPE("nope") }

class TrailerRepository(context: Context) {
    private val filesDir = context.filesDir
    private fun cacheFile(source: TrailerSource) =
        File(filesDir, if (source == TrailerSource.NAS) "manifest.json" else "manifest_youtube.json")
    private val prefStore = context.getSharedPreferences("trailer_prefs", Context.MODE_PRIVATE)

    private val _prefs = MutableStateFlow(loadLocalPrefs())
    /**
     * Preferenze correnti per id film. In modalità NAS la copia "vera" sta sul NAS (config/prefs.json);
     * in modalità YouTube restano solo sulla Fire Stick.
     */
    val prefs: StateFlow<Map<Int, Pref>> = _prefs.asStateFlow()

    /** Imposta cuore, X o nessuno dei due; lo salva subito qui e, se c'è il NAS, lo invia anche lì. */
    fun setPref(s: LauncherSettings, id: Int, pref: Pref?) {
        _prefs.update { if (pref == null) it - id else it + (id to pref) }
        val nas = s.source == TrailerSource.NAS && s.serverUrl.isNotBlank()
        saveLocalPrefs(_prefs.value, unsynced = if (nas) loadUnsynced() + id else loadUnsynced())
        if (nas) scope.launch { pushPref(s.serverUrl, id, pref) }
    }

    private fun pushPref(serverUrl: String, id: Int, pref: Pref?): Boolean = runCatching {
        val conn = URL("$serverUrl/api/prefs").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 3000
        conn.readTimeout = 5000
        conn.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("id", id).put("state", pref?.wire ?: "none").toString()
        conn.outputStream.use { it.write(body.toByteArray()) }
        val ok = conn.responseCode == 200
        conn.disconnect()
        if (ok) saveLocalPrefs(_prefs.value, unsynced = loadUnsynced() - id)
        ok
    }.getOrElse { Log.w(TAG, "preferenza non inviata", it); false }

    /** All'aggiornamento: rimando le modifiche fatte offline, poi prendo l'elenco del NAS. */
    private fun syncPrefs(serverUrl: String) {
        val local = _prefs.value
        for (id in loadUnsynced()) pushPref(serverUrl, id, local[id])
        val json = runCatching { JSONObject(download("$serverUrl/api/prefs")) }.getOrNull() ?: return
        val server = HashMap<Int, Pref>()
        json.optJSONArray("like")?.let { a -> for (i in 0 until a.length()) server[a.getInt(i)] = Pref.LIKE }
        json.optJSONArray("nope")?.let { a -> for (i in 0 until a.length()) server[a.getInt(i)] = Pref.NOPE }
        val pending = loadUnsynced()
        val merged = server.filterKeys { it !in pending } + local.filterKeys { it in pending }
        _prefs.value = merged
        saveLocalPrefs(merged, pending)
    }

    private fun loadLocalPrefs(): Map<Int, Pref> {
        fun ids(key: String) = prefStore.getString(key, "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }
        return ids("like").associateWith { Pref.LIKE } + ids("nope").associateWith { Pref.NOPE }
    }

    private fun loadUnsynced(): Set<Int> =
        prefStore.getString("unsynced", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }.toSet()

    private fun saveLocalPrefs(p: Map<Int, Pref>, unsynced: Set<Int>) {
        prefStore.edit()
            .putString("like", p.filterValues { it == Pref.LIKE }.keys.joinToString(","))
            .putString("nope", p.filterValues { it == Pref.NOPE }.keys.joinToString(","))
            .putString("unsynced", unsynced.joinToString(","))
            .apply()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(TrailerState())
    val state: StateFlow<TrailerState> = _state.asStateFlow()
    private var lastFetch = 0L
    private var loadedSource: TrailerSource? = null

    /** Carica subito l'ultimo elenco salvato, così la home è pronta all'avvio. */
    fun loadCached(s: LauncherSettings) {
        loadedSource = s.source
        scope.launch {
            val json = runCatching { cacheFile(s.source).readText() }.getOrNull() ?: return@launch
            runCatching { parse(json, s.serverUrl) }.onSuccess { (items, generated) ->
                _state.update { if (it.items.isEmpty()) it.copy(items = items, generatedAt = generated) else it }
            }
        }
    }

    /** Cambio di sorgente (NAS o YouTube) o di server: riparto dall'elenco salvato di quella sorgente. */
    fun reset(s: LauncherSettings) {
        lastFetch = 0L
        _state.value = TrailerState()
        loadCached(s)
        refresh(s, force = true)
    }

    fun refresh(s: LauncherSettings, force: Boolean = false) {
        if (loadedSource != s.source) {
            reset(s)
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastFetch < REFRESH_EVERY_MS) return
        lastFetch = now
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            val error = when (s.source) {
                TrailerSource.NAS -> refreshFromNas(s)
                TrailerSource.YOUTUBE -> refreshFromTmdb(s, force)
            }
            if (error != null) {
                lastFetch = 0L
                _state.update { it.copy(loading = false, error = error) }
            }
        }
    }

    private fun refreshFromNas(s: LauncherSettings): TrailerError? {
        if (s.serverUrl.isBlank()) return TrailerError.NAS_NOT_SET
        return try {
            syncPrefs(s.serverUrl)
            val json = download("${s.serverUrl}/manifest.json")
            val (items, generated) = parse(json, s.serverUrl)
            cacheFile(TrailerSource.NAS).writeText(json)
            _state.value = TrailerState(items = items, generatedAt = generated)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Manifest non disponibile", e)
            TrailerError.NAS_UNREACHABLE
        }
    }

    /**
     * Modalità YouTube: l'elenco si rigenera da TMDB una volta al giorno (o se cambia il periodo).
     * I trailer in watchlist si conservano anche se non sono più tra i 50 più popolari.
     */
    private suspend fun refreshFromTmdb(s: LauncherSettings, force: Boolean): TrailerError? {
        val file = cacheFile(TrailerSource.YOUTUBE)
        val range = "${s.monthsFrom}-${s.monthsTo}"
        val cached = runCatching { JSONObject(file.readText()) }.getOrNull()
        val fresh = cached != null && cached.optString("range") == range &&
            System.currentTimeMillis() - file.lastModified() < CATALOG_MAX_AGE_MS
        if (fresh && !force) {
            val (items, generated) = parse(cached.toString(), "")
            _state.value = TrailerState(items = items, generatedAt = generated)
            return null
        }
        return try {
            val built = JSONObject(
                YoutubeCatalog(s.tmdbKey).build(s.monthsFrom, s.monthsTo, LauncherSettings.YOUTUBE_TRAILERS)
            )
            val ids = HashSet<Int>()
            val items = built.getJSONArray("items")
            for (i in 0 until items.length()) ids += items.getJSONObject(i).getInt("id")
            cached?.optJSONArray("items")?.let { old ->
                for (i in 0 until old.length()) {
                    val o = old.getJSONObject(i)
                    if (_prefs.value[o.getInt("id")] == Pref.LIKE && o.getInt("id") !in ids) items.put(o)
                }
            }
            built.put("range", range)
            val json = built.toString()
            file.writeText(json)
            val (list, generated) = parse(json, "")
            _state.value = TrailerState(items = list, generatedAt = generated)
            null
        } catch (e: YoutubeCatalog.MissingKeyException) {
            TrailerError.TMDB_KEY_MISSING
        } catch (e: Exception) {
            Log.w(TAG, "Elenco TMDB non disponibile: ${e.message}")
            TrailerError.TMDB_UNREACHABLE
        }
    }

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 5000
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String, base: String): Pair<List<Trailer>, String?> {
        val root = JSONObject(json)
        val arr = root.getJSONArray("items")
        fun abs(path: String?): String? = when {
            path.isNullOrBlank() -> null
            path.startsWith("http") || path.startsWith(YOUTUBE_PREFIX) -> path
            else -> "$base/${path.trimStart('/')}"
        }
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val video = abs(o.optString("video")) ?: return@mapNotNull null
            val genres = o.optJSONArray("genres")
            val genresEn = o.optJSONArray("genres_en")
            Trailer(
                id = o.getInt("id"),
                title = o.optString("title"),
                overview = o.optString("overview"),
                releaseDate = o.optString("release_date"),
                rating = o.optDouble("rating", 0.0),
                runtime = o.optInt("runtime", 0),
                genres = if (genres == null) emptyList() else (0 until genres.length()).map { genres.getString(it) },
                language = o.optString("lang"),
                videoUrl = video,
                audioUrl = abs(o.optString("audio")),
                thumbUrl = abs(o.optString("thumb")),
                backdropUrl = abs(o.optString("backdrop")),
                likedOnServer = o.optBoolean("liked", false),
                titleEn = o.optString("title_en"),
                overviewEn = o.optString("overview_en"),
                genresEn = if (genresEn == null) emptyList() else (0 until genresEn.length()).map { genresEn.getString(it) },
                videoAlternatives = o.optJSONArray("video_alternatives")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                } ?: emptyList(),
            )
        }
        return items to root.optString("generated").ifBlank { null }
    }

    companion object {
        private const val TAG = "Trailers"
        private const val REFRESH_EVERY_MS = 30 * 60 * 1000L
        private const val CATALOG_MAX_AGE_MS = 24 * 60 * 60 * 1000L

        /**
         * Le preferenze dell'app (sincronizzate col NAS) sono l'unica fonte: niente ripiego sul flag
         * del manifest, che può essere vecchio e farebbe ricomparire un cuore appena tolto.
         */
        fun isLiked(t: Trailer, prefs: Map<Int, Pref>): Boolean = prefs[t.id] == Pref.LIKE

        /**
         * Novità: intervallo di mesi e numero scelti nelle impostazioni, senza gli scartati.
         * Watchlist: tutti i trailer col cuore, a prescindere da data e numero.
         */
        fun filter(items: List<Trailer>, s: LauncherSettings, prefs: Map<Int, Pref>, watchlist: Boolean): List<Trailer> {
            val visible = items.filter { prefs[it.id] != Pref.NOPE }
            if (watchlist) return visible.filter { isLiked(it, prefs) }
            val cal = Calendar.getInstance()
            val nowIndex = cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
            val inRange = visible.filter { t ->
                val rel = t.releaseMonthIndex ?: return@filter false
                val ago = nowIndex - rel
                ago in s.monthsTo..s.monthsFrom
            }
            val byLanguage = when (s.trailerLanguage) {
                TrailerLanguage.ITALIAN_ONLY -> inRange.filter { it.language == "it" }
                // Ordinamento stabile: prima gli italiani, ognuno nel suo ordine di popolarità.
                TrailerLanguage.ITALIAN_FIRST -> inRange.sortedBy { if (it.language == "it") 0 else 1 }
                TrailerLanguage.ANY -> inRange
            }
            return byLanguage.take(s.trailerCount)
        }
    }
}
