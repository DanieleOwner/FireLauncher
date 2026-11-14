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
) {
    /** Anno*12 + mese (0-based), per confrontare le date senza java.time (API 25). */
    val releaseMonthIndex: Int? = releaseDate.split("-").let { parts ->
        val y = parts.getOrNull(0)?.toIntOrNull()
        val m = parts.getOrNull(1)?.toIntOrNull()
        if (y != null && m != null) y * 12 + (m - 1) else null
    }
}

data class TrailerState(
    val items: List<Trailer> = emptyList(),
    val generatedAt: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** Cuore (watchlist) o X (non mi interessa). */
enum class Pref(val wire: String) { LIKE("like"), NOPE("nope") }

class TrailerRepository(context: Context) {
    private val cacheFile = File(context.filesDir, "manifest.json")
    private val prefStore = context.getSharedPreferences("trailer_prefs", Context.MODE_PRIVATE)

    private val _prefs = MutableStateFlow(loadLocalPrefs())
    /** Preferenze correnti per id film. La copia "vera" sta sul NAS (config/prefs.json). */
    val prefs: StateFlow<Map<Int, Pref>> = _prefs.asStateFlow()

    /** Imposta cuore, X o nessuno dei due; lo salva subito qui e lo invia al NAS. */
    fun setPref(serverUrl: String, id: Int, pref: Pref?) {
        _prefs.update { if (pref == null) it - id else it + (id to pref) }
        saveLocalPrefs(_prefs.value, unsynced = loadUnsynced() + id)
        scope.launch { pushPref(serverUrl, id, pref) }
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

    /** Carica subito l'ultimo elenco salvato, così la home è pronta all'avvio. */
    fun loadCached(serverUrl: String) {
        scope.launch {
            val json = runCatching { cacheFile.readText() }.getOrNull() ?: return@launch
            runCatching { parse(json, serverUrl) }.onSuccess { (items, generated) ->
                _state.update { if (it.items.isEmpty()) it.copy(items = items, generatedAt = generated) else it }
            }
        }
    }

    fun refresh(serverUrl: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFetch < REFRESH_EVERY_MS) return
        lastFetch = now
        _state.update { it.copy(loading = true) }
        scope.launch {
            try {
                syncPrefs(serverUrl)
                val json = download("$serverUrl/manifest.json")
                val (items, generated) = parse(json, serverUrl)
                cacheFile.writeText(json)
                _state.value = TrailerState(items = items, generatedAt = generated)
            } catch (e: Exception) {
                Log.w(TAG, "Manifest non disponibile", e)
                lastFetch = 0L
                _state.update { it.copy(loading = false, error = "NAS non raggiungibile") }
            }
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
            path.startsWith("http") -> path
            else -> "$base/${path.trimStart('/')}"
        }
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val video = abs(o.optString("video")) ?: return@mapNotNull null
            val genres = o.optJSONArray("genres")
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
            )
        }
        return items to root.optString("generated").ifBlank { null }
    }

    companion object {
        private const val TAG = "Trailers"
        private const val REFRESH_EVERY_MS = 30 * 60 * 1000L

        fun isLiked(t: Trailer, prefs: Map<Int, Pref>): Boolean =
            prefs[t.id]?.let { it == Pref.LIKE } ?: t.likedOnServer

        /**
         * Novità: intervallo di mesi e numero scelti nelle impostazioni, senza gli scartati.
         * Watchlist: tutti i trailer col cuore, a prescindere da data e numero.
         */
        fun filter(items: List<Trailer>, s: LauncherSettings, prefs: Map<Int, Pref>, watchlist: Boolean): List<Trailer> {
            val visible = items.filter { prefs[it.id] != Pref.NOPE }
            if (watchlist) return visible.filter { isLiked(it, prefs) }
            val cal = Calendar.getInstance()
            val nowIndex = cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
            return visible.filter { t ->
                val rel = t.releaseMonthIndex ?: return@filter false
                val ago = nowIndex - rel
                ago in s.monthsTo..s.monthsFrom
            }.take(s.trailerCount)
        }
    }
}
