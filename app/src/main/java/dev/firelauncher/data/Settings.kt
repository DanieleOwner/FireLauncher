package dev.firelauncher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlayMode { WINDOW, FULLSCREEN }

/** Quali trailer mostrare in base alla lingua. */
enum class TrailerLanguage { ITALIAN_FIRST, ITALIAN_ONLY, ANY }

/** Lingua dell'interfaccia del launcher (titoli e trame restano quelli di TMDB). */
enum class UiLanguage { IT, EN }

data class LauncherSettings(
    val trailersEnabled: Boolean = true,
    /** Film usciti al massimo questi mesi fa. */
    val monthsFrom: Int = 18,
    /** Film usciti almeno questi mesi fa. */
    val monthsTo: Int = 6,
    /** Quanti trailer nello slider; [ALL] = tutti quelli del periodo. */
    val trailerCount: Int = 20,
    val playMode: PlayMode = PlayMode.WINDOW,
    val windowAudio: Boolean = true,
    val serverUrl: String = DEFAULT_SERVER,
    val weatherCity: String = "Terni",
    val weatherLat: Double = 42.5636,
    val weatherLon: Double = 12.6427,
    /** Pacchetti delle app in home, nell'ordine in cui compaiono. */
    val favorites: List<String> = Apps.FAVORITES,
    val trailerLanguage: TrailerLanguage = TrailerLanguage.ITALIAN_FIRST,
    val uiLanguage: UiLanguage = UiLanguage.IT,
) {
    val hasWeatherPlace: Boolean get() = !weatherLat.isNaN() && !weatherLon.isNaN()

    companion object {
        const val DEFAULT_SERVER = "http://192.168.1.9:8787"
        const val MAX_MONTHS = 24
        const val MIN_COUNT = 5
        const val MAX_COUNT = 100
        const val ALL = Int.MAX_VALUE
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<LauncherSettings> = _state.asStateFlow()

    fun update(change: (LauncherSettings) -> LauncherSettings) {
        val next = change(_state.value).sanitized()
        _state.value = next
        prefs.edit()
            .putBoolean("trailersEnabled", next.trailersEnabled)
            .putInt("monthsFrom", next.monthsFrom)
            .putInt("monthsTo", next.monthsTo)
            .putInt("trailerCount", next.trailerCount)
            .putString("playMode", next.playMode.name)
            .putBoolean("windowAudio", next.windowAudio)
            .putString("serverUrl", next.serverUrl)
            .putString("weatherCity", next.weatherCity)
            .putString("weatherLat", next.weatherLat.toString())
            .putString("weatherLon", next.weatherLon.toString())
            .putString("favorites", next.favorites.joinToString(","))
            .putString("trailerLanguage", next.trailerLanguage.name)
            .putString("uiLanguage", next.uiLanguage.name)
            .apply()
    }

    private fun read(): LauncherSettings {
        val d = LauncherSettings()
        return LauncherSettings(
            trailersEnabled = prefs.getBoolean("trailersEnabled", d.trailersEnabled),
            monthsFrom = prefs.getInt("monthsFrom", d.monthsFrom),
            monthsTo = prefs.getInt("monthsTo", d.monthsTo),
            trailerCount = prefs.getInt("trailerCount", d.trailerCount),
            playMode = runCatching { PlayMode.valueOf(prefs.getString("playMode", null)!!) }
                .getOrDefault(d.playMode),
            windowAudio = prefs.getBoolean("windowAudio", d.windowAudio),
            serverUrl = prefs.getString("serverUrl", null) ?: d.serverUrl,
            weatherCity = prefs.getString("weatherCity", null) ?: d.weatherCity,
            weatherLat = prefs.getString("weatherLat", null)?.toDoubleOrNull() ?: d.weatherLat,
            weatherLon = prefs.getString("weatherLon", null)?.toDoubleOrNull() ?: d.weatherLon,
            favorites = prefs.getString("favorites", null)
                ?.split(',')?.filter { it.isNotBlank() } ?: d.favorites,
            trailerLanguage = runCatching { TrailerLanguage.valueOf(prefs.getString("trailerLanguage", null)!!) }
                .getOrDefault(d.trailerLanguage),
            uiLanguage = runCatching { UiLanguage.valueOf(prefs.getString("uiLanguage", null)!!) }
                .getOrDefault(d.uiLanguage),
        ).sanitized()
    }

    private fun LauncherSettings.sanitized(): LauncherSettings {
        val to = monthsTo.coerceIn(0, LauncherSettings.MAX_MONTHS - 1)
        val from = monthsFrom.coerceIn(to + 1, LauncherSettings.MAX_MONTHS)
        val count = when {
            trailerCount > LauncherSettings.MAX_COUNT -> LauncherSettings.ALL
            else -> trailerCount.coerceAtLeast(LauncherSettings.MIN_COUNT)
        }
        return copy(
            monthsTo = to, monthsFrom = from, trailerCount = count,
            serverUrl = serverUrl.trimEnd('/'), favorites = favorites.distinct(),
        )
    }

    companion object {
        /** Sposta un'app preferita di una posizione (-1 verso sinistra, +1 verso destra). */
        fun move(list: List<String>, pkg: String, delta: Int): List<String> {
            val i = list.indexOf(pkg)
            val j = i + delta
            if (i < 0 || j !in list.indices) return list
            return list.toMutableList().apply { add(j, removeAt(i)) }
        }

        /** Passo successivo del numero di trailer: 5..100 a passi di 5, poi "Tutti". */
        fun stepCount(current: Int, delta: Int): Int = when {
            current == LauncherSettings.ALL && delta < 0 -> LauncherSettings.MAX_COUNT
            current == LauncherSettings.ALL -> LauncherSettings.ALL
            current + delta * 5 > LauncherSettings.MAX_COUNT -> LauncherSettings.ALL
            else -> (current + delta * 5).coerceAtLeast(LauncherSettings.MIN_COUNT)
        }
    }
}
