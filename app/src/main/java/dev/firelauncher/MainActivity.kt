package dev.firelauncher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import dev.firelauncher.data.AppEntry
import dev.firelauncher.data.Apps
import dev.firelauncher.data.SettingsStore
import dev.firelauncher.data.Trailer
import dev.firelauncher.data.TrailerRepository
import dev.firelauncher.data.UiLanguage
import dev.firelauncher.ui.LocalUiLanguage
import dev.firelauncher.ui.tr
import androidx.compose.runtime.CompositionLocalProvider
import dev.firelauncher.data.Weather
import dev.firelauncher.data.WeatherNow
import dev.firelauncher.player.TrailerPlayer
import dev.firelauncher.ui.AllAppsScreen
import dev.firelauncher.ui.FullscreenPlayer
import dev.firelauncher.ui.HomeScreen
import dev.firelauncher.ui.LauncherTheme
import dev.firelauncher.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object AllApps : Screen
    data object Settings : Screen
    data object PickFavorite : Screen
    data class Fullscreen(val index: Int) : Screen
}

class MainActivity : ComponentActivity() {
    private lateinit var settings: SettingsStore
    private lateinit var trailers: TrailerRepository
    private lateinit var player: TrailerPlayer

    private val apps = MutableStateFlow<List<AppEntry>>(emptyList())
    private val screen = MutableStateFlow<Screen>(Screen.Home)
    private val selected = MutableStateFlow(0)
    private val homeServiceEnabled = MutableStateFlow(false)
    private val watchlist = MutableStateFlow(false)
    private val weather = MutableStateFlow<WeatherNow?>(null)
    private val cityStatus = MutableStateFlow<String?>(null)
    private var weatherFetchedAt = 0L

    private fun refreshWeather(force: Boolean = false) {
        val s = settings.state.value
        if (!s.hasWeatherPlace) { weather.value = null; return }
        val now = System.currentTimeMillis()
        if (!force && now - weatherFetchedAt < 20 * 60 * 1000L) return
        weatherFetchedAt = now
        lifecycleScope.launch {
            Weather.current(s.weatherLat, s.weatherLon)?.let { weather.value = it } ?: run { weatherFetchedAt = 0L }
        }
    }

    private fun setCity(city: String) {
        val en = settings.state.value.uiLanguage == UiLanguage.EN
        cityStatus.value = if (en) "Searching…" else "Cerco…"
        lifecycleScope.launch {
            val place = Weather.geocode(city)
            if (place == null) {
                cityStatus.value = if (en) "Not found" else "Non trovata"
            } else {
                settings.update { it.copy(weatherCity = place.name, weatherLat = place.lat, weatherLon = place.lon) }
                cityStatus.value = null
                refreshWeather(force = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        trailers = TrailerRepository(this)
        player = TrailerPlayer(this)
        trailers.loadCached(settings.state.value.serverUrl)
        handleIntent(intent)

        setContent {
            val s by settings.state.collectAsState()
            CompositionLocalProvider(LocalUiLanguage provides s.uiLanguage) { LauncherTheme {
                val appList by apps.collectAsState()
                val trailerState by trailers.state.collectAsState()
                val current by screen.collectAsState()
                val sel by selected.collectAsState()
                val serviceOn by homeServiceEnabled.collectAsState()
                val prefs by trailers.prefs.collectAsState()
                val inWatchlist by watchlist.collectAsState()
                val weatherNow by weather.collectAsState()
                val cityMsg by cityStatus.collectAsState()
                val visible = remember(trailerState.items, s, prefs, inWatchlist) {
                    TrailerRepository.filter(trailerState.items, s, prefs, inWatchlist)
                }
                val watchCount = remember(trailerState.items, prefs) {
                    TrailerRepository.filter(trailerState.items, s, prefs, watchlist = true).size
                }

                when (val sc = current) {
                    Screen.Home -> HomeScreen(
                        settings = s,
                        trailers = visible,
                        trailerState = trailerState,
                        prefs = prefs,
                        watchlist = inWatchlist,
                        watchlistCount = watchCount,
                        weather = weatherNow,
                        apps = appList,
                        player = player,
                        selectedIndex = sel,
                        onSelectedChange = { selected.value = it },
                        onToggleWatchlist = {
                            player.stop()
                            watchlist.value = !watchlist.value
                            selected.value = 0
                        },
                        onPref = { t, p -> trailers.setPref(s.serverUrl, t.id, p) },
                        onLaunch = ::launch,
                        onAllApps = { screen.value = Screen.AllApps },
                        onStore = { Apps.storeIntent(packageManager)?.let(::safeStart) },
                        onSettings = { screen.value = Screen.Settings },
                        onFullscreen = { screen.value = Screen.Fullscreen(it) },
                        onStremioSearch = ::searchOnStremio,
                    )
                    Screen.AllApps -> AllAppsScreen(appList, ::launch, onBack = { screen.value = Screen.Home })
                    Screen.Settings -> SettingsScreen(
                        settings = s,
                        trailerState = trailerState,
                        visibleTrailers = visible.size,
                        homeServiceEnabled = serviceOn,
                        onChange = settings::update,
                        onRefreshTrailers = { trailers.refresh(s.serverUrl, force = true) },
                        onOpenFireTvSettings = { Apps.settingsIntents().any(::safeStart) },
                        cityStatus = cityMsg,
                        onCitySubmit = ::setCity,
                        apps = appList,
                        onAddFavorite = { screen.value = Screen.PickFavorite },
                        onBack = { screen.value = Screen.Home },
                    )
                    Screen.PickFavorite -> AllAppsScreen(
                        apps = appList.filter { it.pkg !in s.favorites },
                        onLaunch = { app ->
                            settings.update { it.copy(favorites = it.favorites + app.pkg) }
                            screen.value = Screen.Settings
                        },
                        onBack = { screen.value = Screen.Settings },
                        title = tr("Aggiungi alla home", "Add to home screen"),
                        subtitle = tr("Scegli l'app da mettere in fondo alla fila", "Pick the app to add at the end of the row"),
                    )
                    is Screen.Fullscreen -> FullscreenPlayer(
                        trailers = visible,
                        startIndex = sc.index,
                        player = player,
                        prefs = prefs,
                        onPref = { t, p -> trailers.setPref(s.serverUrl, t.id, p) },
                        onIndexChange = { selected.value = it },
                        onExit = { screen.value = Screen.Home },
                    )
                }
            } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        HomeOverlay.hide()
        reloadApps()
        trailers.refresh(settings.state.value.serverUrl)
        homeServiceEnabled.value = isHomeServiceEnabled()
        HomeWatcherService.start(this)
        refreshWeather()
    }

    override fun onPause() {
        super.onPause()
        player.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }

    private fun handleIntent(intent: Intent?) {
        val fromSettingsIcon = intent?.component?.className?.endsWith(".SettingsAlias") == true
        screen.value = if (fromSettingsIcon) Screen.Settings else Screen.Home
    }

    private fun reloadApps() {
        lifecycleScope.launch {
            apps.value = withContext(Dispatchers.IO) { Apps.load(this@MainActivity) }
        }
    }

    private fun launch(app: AppEntry) = safeStart(app.launch)

    private fun searchOnStremio(t: Trailer) {
        val search = Intent(Intent.ACTION_VIEW, Uri.parse("stremio:///search?search=" + Uri.encode(t.title(settings.state.value.uiLanguage))))
            .setPackage(Apps.STREMIO)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!safeStart(search)) {
            apps.value.find { it.pkg == Apps.STREMIO }?.let(::launch)
        }
    }

    private fun safeStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w("Launcher", "Impossibile avviare $intent", e)
        false
    }

    private fun isHomeServiceEnabled(): Boolean = HomeWatcherService.hasPermission(this)
}
