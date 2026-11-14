package dev.firelauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.firelauncher.BuildConfig
import dev.firelauncher.data.AppEntry
import dev.firelauncher.data.LauncherSettings
import dev.firelauncher.data.PlayMode
import dev.firelauncher.data.SettingsStore
import dev.firelauncher.data.TrailerLanguage
import dev.firelauncher.data.UiLanguage
import dev.firelauncher.data.TrailerState

private class SettingRow(
    val title: String,
    val description: String,
    val value: String? = null,
    /** Sinistra/destra: -1 o +1. Null se la riga non ha un valore da far scorrere. */
    val onStep: ((Int) -> Unit)? = null,
    val onClick: (() -> Unit)? = null,
    /** Titolo di sezione da mostrare sopra questa riga. */
    val section: String? = null,
    /** Chiave stabile per la lista (di default il titolo). */
    val key: String = title,
)

@Composable
fun SettingsScreen(
    settings: LauncherSettings,
    trailerState: TrailerState,
    visibleTrailers: Int,
    homeServiceEnabled: Boolean,
    onChange: ((LauncherSettings) -> LauncherSettings) -> Unit,
    onRefreshTrailers: () -> Unit,
    onOpenFireTvSettings: () -> Unit,
    cityStatus: String?,
    onCitySubmit: (String) -> Unit,
    apps: List<AppEntry>,
    onAddFavorite: () -> Unit,
    onBack: () -> Unit,
) {
    var editingCity by remember { mutableStateOf(false) }
    BackHandler { if (editingCity) editingCity = false else onBack() }
    val s = settings
    val on = tr("Attivo", "On")
    val off = tr("Disattivo", "Off")
    val appsSection = tr("App in home", "Home screen apps")
    val favoriteRows = s.favorites.mapIndexed { i, pkg ->
        val label = apps.find { it.pkg == pkg }?.label ?: pkg
        SettingRow(
            "${i + 1}. $label",
            tr(
                "Sinistra e destra spostano l'app nella fila della home. OK la toglie dalla home (resta installata).",
                "Left and right move the app along the home row. OK removes it from the home screen (it stays installed).",
            ),
            tr("‹ sposta ›", "‹ move ›"),
            onStep = { d -> onChange { it.copy(favorites = SettingsStore.move(it.favorites, pkg, d)) } },
            onClick = { onChange { it.copy(favorites = it.favorites - pkg) } },
            section = if (i == 0) appsSection else null,
            key = "fav:$pkg",
        )
    } + SettingRow(
        tr("Aggiungi app", "Add app"),
        tr("Scegli un'app installata da mettere in fondo alla fila della home.", "Pick an installed app to add at the end of the home row."),
        "+",
        onClick = onAddFavorite,
        section = if (s.favorites.isEmpty()) appsSection else null,
        key = "fav:add",
    )
    val monthsAgo = tr("mesi fa", "months ago")
    val rows = listOf(
        SettingRow(
            tr("Mostra trailer in home", "Show trailers on home"),
            tr(
                "Lo slider dei trailer sopra le app. Se lo disattivi, al suo posto c'è un orologio grande.",
                "The trailer carousel above the apps. When off, a large clock takes its place.",
            ),
            if (s.trailersEnabled) on else off,
            onStep = { onChange { it.copy(trailersEnabled = !it.trailersEnabled) } },
            onClick = { onChange { it.copy(trailersEnabled = !it.trailersEnabled) } },
            section = "Trailer",
            key = "trailers",
        ),
        SettingRow(
            tr("Usciti da non più di", "Released at most"),
            tr("Il film più vecchio da mostrare. Usa sinistra e destra per cambiare.", "The oldest movie to show. Use left and right to change it."),
            "${s.monthsFrom} $monthsAgo",
            onStep = { d -> onChange { it.copy(monthsFrom = it.monthsFrom + d) } },
            key = "from",
        ),
        SettingRow(
            tr("Usciti da almeno", "Released at least"),
            tr("Il film più recente da mostrare. 0 vuol dire fino a oggi.", "The newest movie to show. 0 means up to today."),
            if (s.monthsTo == 0) tr("oggi", "today") else "${s.monthsTo} $monthsAgo",
            onStep = { d -> onChange { it.copy(monthsTo = it.monthsTo + d) } },
            key = "to",
        ),
        SettingRow(
            tr("Trailer da mostrare", "Trailers to show"),
            tr(
                "Quanti trailer mettere nello slider, dai più popolari. Oltre 100 c'è \"Tutti\": l'intero periodo scelto.",
                "How many trailers in the carousel, most popular first. Past 100 there's \"All\": the whole chosen period.",
            ),
            if (s.trailerCount == LauncherSettings.ALL) tr("Tutti", "All") else "${s.trailerCount}",
            onStep = { d -> onChange { it.copy(trailerCount = SettingsStore.stepCount(it.trailerCount, d)) } },
            key = "count",
        ),
        SettingRow(
            tr("Lingua dei trailer", "Trailer language"),
            tr(
                "Prima in italiano: i trailer italiani vengono per primi, poi gli altri. Solo in italiano: nasconde quelli in altre lingue. " +
                    "Il NAS scarica sempre il trailer italiano quando esiste.",
                "Italian first: Italian trailers come first, then the rest. Italian only: hides trailers in other languages. " +
                    "The NAS always downloads the Italian trailer when one exists.",
            ),
            when (s.trailerLanguage) {
                TrailerLanguage.ITALIAN_FIRST -> tr("Prima in italiano", "Italian first")
                TrailerLanguage.ITALIAN_ONLY -> tr("Solo in italiano", "Italian only")
                TrailerLanguage.ANY -> tr("Qualsiasi lingua", "Any language")
            },
            onStep = { d ->
                onChange {
                    val all = TrailerLanguage.entries
                    it.copy(trailerLanguage = all[(all.indexOf(it.trailerLanguage) + d + all.size) % all.size])
                }
            },
            key = "trailerLanguage",
        ),
        SettingRow(
            tr("Riproduzione", "Playback"),
            tr(
                "Nella finestrina della home oppure direttamente a tutto schermo. Dalla finestrina, OK una seconda volta passa a tutto schermo.",
                "In the home preview window or straight to full screen. From the preview window, OK again goes full screen.",
            ),
            if (s.playMode == PlayMode.WINDOW) tr("Finestrina", "Preview window") else tr("Tutto schermo", "Full screen"),
            onStep = { onChange { it.copy(playMode = if (it.playMode == PlayMode.WINDOW) PlayMode.FULLSCREEN else PlayMode.WINDOW) } },
            onClick = { onChange { it.copy(playMode = if (it.playMode == PlayMode.WINDOW) PlayMode.FULLSCREEN else PlayMode.WINDOW) } },
            key = "playMode",
        ),
        SettingRow(
            tr("Audio nella finestrina", "Audio in preview window"),
            tr("A tutto schermo l'audio è sempre attivo.", "In full screen, audio is always on."),
            if (s.windowAudio) on else tr("Muto", "Muted"),
            onStep = { onChange { it.copy(windowAudio = !it.windowAudio) } },
            onClick = { onChange { it.copy(windowAudio = !it.windowAudio) } },
            key = "audio",
        ),
        SettingRow(
            tr("Aggiorna elenco trailer", "Refresh trailer list"),
            tr("Rilegge subito l'elenco dal NAS. Di solito lo fa da solo ogni mezz'ora.", "Reloads the list from the NAS now. It normally does this every half hour."),
            when {
                trailerState.loading -> tr("Aggiorno…", "Refreshing…")
                trailerState.error != null -> tr("NAS non raggiungibile", "NAS unreachable")
                else -> tr("$visibleTrailers su ${trailerState.items.size}", "$visibleTrailers of ${trailerState.items.size}")
            },
            onClick = onRefreshTrailers,
            key = "refresh",
        ),
    ) + favoriteRows + listOf(
        SettingRow(
            tr("Lingua dell'interfaccia", "Interface language"),
            tr(
                "Lingua dei menu del launcher. Titoli e trame dei film restano in italiano (da TMDB).",
                "Language of the launcher menus. Movie titles and plots stay in Italian (from TMDB).",
            ),
            if (s.uiLanguage == UiLanguage.IT) "Italiano" else "English",
            onStep = { onChange { it.copy(uiLanguage = if (it.uiLanguage == UiLanguage.IT) UiLanguage.EN else UiLanguage.IT) } },
            onClick = { onChange { it.copy(uiLanguage = if (it.uiLanguage == UiLanguage.IT) UiLanguage.EN else UiLanguage.IT) } },
            section = tr("Sistema", "System"),
            key = "uiLanguage",
        ),
        SettingRow(
            tr("Città per il meteo", "Weather city"),
            tr("Premi OK e scrivi la tua città: il meteo compare in alto a destra nella home.", "Press OK and type your city: the weather appears at the top right of the home screen."),
            cityStatus ?: s.weatherCity.ifBlank { tr("Scegli", "Choose") },
            onClick = { editingCity = true },
            key = "city",
        ),
        SettingRow(
            tr("Server NAS", "NAS server"),
            tr("Indirizzo del servizio trailer sul Synology.", "Address of the trailer service on the NAS."),
            s.serverUrl.removePrefix("http://"),
            key = "server",
        ),
        SettingRow(
            tr("Tasto Home", "Home button"),
            if (homeServiceEnabled) tr(
                "Premendo Home compare subito una schermata FireLauncher; dopo circa 5 secondi (un limite di Fire OS) si apre il launcher.",
                "Pressing Home shows a FireLauncher screen right away; after about 5 seconds (a Fire OS limit) the launcher opens.",
            ) else tr(
                "Manca il permesso \"dati di utilizzo\": il tasto Home apre ancora la home Amazon. Vedi il README per i comandi ADB.",
                "The \"usage access\" permission is missing: Home still opens the Amazon home. See the README for the ADB commands.",
            ),
            if (homeServiceEnabled) on else tr("Non attivo", "Not active"),
            key = "home",
        ),
        SettingRow(
            tr("Impostazioni Fire TV", "Fire TV settings"),
            tr("Rete, display, audio, app e tutto il resto del sistema.", "Network, display, sound, apps and the rest of the system."),
            tr("Apri", "Open"),
            onClick = onOpenFireTvSettings,
            key = "firetv",
        ),
    )

    var focused by remember { mutableStateOf(0) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    if (editingCity) {
        CityEditor(initial = s.weatherCity) { city ->
            editingCity = false
            if (city.isNotBlank()) onCitySubmit(city)
        }
        return
    }

    Row(Modifier.fillMaxSize().background(Palette.BgDeep)) {
        Column(
            Modifier.width(300.dp).fillMaxHeight().background(Palette.Bg).padding(horizontal = 32.dp, vertical = 40.dp)
        ) {
            Text(tr("Impostazioni", "Settings"), color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            Text("FireLauncher ${BuildConfig.VERSION_NAME}", color = Palette.TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(36.dp))
            val row = rows[focused.coerceIn(0, rows.lastIndex)]
            Text(row.title, color = Palette.TextSoft, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(row.description, color = Palette.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.weight(1f))
            trailerState.generatedAt?.let {
                val at = it.take(16).replace('T', ' ')
                Text(tr("Elenco del NAS generato il $at", "NAS list generated on $at"), color = Palette.Accent, fontSize = 11.sp)
            }
        }
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 40.dp),
        ) {
            rows.forEachIndexed { i, row ->
                row.section?.let { title ->
                    item(key = "section:$title") {
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        Text(title, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
                item(key = row.key) {
                    SettingItem(
                        row,
                        Modifier
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged { if (it.isFocused) focused = i },
                    )
                }
            }
        }
    }
}

@Composable
private fun CityEditor(initial: String, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial)) }
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    Column(
        Modifier.fillMaxSize().background(Palette.BgDeep).padding(horizontal = 120.dp, vertical = 80.dp)
    ) {
        Text(tr("Città per il meteo", "Weather city"), color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(tr("Scrivi il nome e premi Invio sulla tastiera.", "Type the name and press Enter on the keyboard."), color = Palette.TextMuted, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Palette.Text, fontSize = 22.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.TextSoft),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone(text.text) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
                .clip(RoundedCornerShape(10.dp)).background(Palette.Panel).padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun SettingItem(row: SettingRow, modifier: Modifier) {
    val stepper = row.onStep
    FocusTile(
        onClick = { row.onClick?.invoke() },
        modifier = modifier.fillMaxWidth().onPreviewKeyEvent { e ->
            if (stepper == null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> { stepper(-1); true }
                Key.DirectionRight -> { stepper(1); true }
                else -> false
            }
        },
        radius = 10.dp,
        focusedScale = 1.02f,
        container = Palette.Bg,
        focusedContainer = Palette.Panel,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.title, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            row.value?.let { v ->
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.PanelHi)
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                ) {
                    Text(
                        if (stepper != null && row.onClick == null) "‹  $v  ›" else v,
                        color = Palette.TextSoft, fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
