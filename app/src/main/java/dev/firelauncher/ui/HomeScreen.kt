package dev.firelauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.firelauncher.data.AppEntry
import dev.firelauncher.data.LauncherSettings
import dev.firelauncher.data.PlayMode
import dev.firelauncher.data.Pref
import dev.firelauncher.data.Trailer
import dev.firelauncher.data.TrailerRepository
import dev.firelauncher.data.TrailerState
import dev.firelauncher.data.WeatherNow
import dev.firelauncher.player.TrailerPlayer
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    settings: LauncherSettings,
    trailers: List<Trailer>,
    trailerState: TrailerState,
    prefs: Map<Int, Pref>,
    watchlist: Boolean,
    watchlistCount: Int,
    weather: WeatherNow?,
    apps: List<AppEntry>,
    player: TrailerPlayer,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit,
    onToggleWatchlist: () -> Unit,
    onPref: (Trailer, Pref?) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onAllApps: () -> Unit,
    onStore: () -> Unit,
    onSettings: () -> Unit,
    onFullscreen: (Int) -> Unit,
    onStremioSearch: (Trailer) -> Unit,
) {
    val now = rememberNow()
    val showSlider = settings.trailersEnabled && (trailers.isNotEmpty() || watchlist)
    val index = selectedIndex.coerceIn(0, (trailers.size - 1).coerceAtLeast(0))
    val current = trailers.getOrNull(index)?.takeIf { showSlider }
    val playing by player.playing.collectAsState()
    val favorites = remember(apps, settings.favorites) {
        settings.favorites.mapNotNull { p -> apps.find { it.pkg == p } }
    }

    val listState = rememberLazyListState()
    val thumbFocus = remember(trailers.map { it.id }) { List(trailers.size) { FocusRequester() } }
    val modeFocus = remember { FocusRequester() }
    val firstAppFocus = remember { FocusRequester() }
    var refocus by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // Focus iniziale: sul trailer selezionato se ci sono, altrimenti sulla prima app.
    LaunchedEffect(showSlider, favorites.isNotEmpty(), watchlist) {
        delay(50)
        when {
            showSlider && trailers.isNotEmpty() -> {
                listState.scrollToItem(index)
                delay(16)
                runCatching { thumbFocus[index].requestFocus() }
            }
            showSlider -> runCatching { modeFocus.requestFocus() }
            favorites.isNotEmpty() -> runCatching { firstAppFocus.requestFocus() }
        }
    }

    // Dopo una X (o un cuore tolto in watchlist) il trailer sparisce: sposto il focus sul successivo.
    LaunchedEffect(thumbFocus) {
        if (!refocus) return@LaunchedEffect
        refocus = false
        delay(16)
        if (trailers.isNotEmpty()) runCatching { thumbFocus[index].requestFocus() }
        else runCatching { modeFocus.requestFocus() }
    }

    // Quando cambi trailer: fermo quello in corso e preparo il nuovo (e i vicini) in anticipo.
    LaunchedEffect(current?.id) {
        val t = current ?: return@LaunchedEffect
        if (player.currentId != t.id) player.stop()
        delay(250)
        player.prepare(t)
        player.precache(listOfNotNull(trailers.getOrNull(index + 1), trailers.getOrNull(index - 1)))
    }

    LaunchedEffect(toast) {
        if (toast != null) { delay(1800); toast = null }
    }

    // Il launcher non si chiude mai con Indietro: al massimo ferma il trailer o esce dalla watchlist.
    BackHandler(enabled = true) {
        when {
            playing -> player.stop()
            watchlist -> onToggleWatchlist()
        }
    }

    fun activate(i: Int) {
        val t = trailers.getOrNull(i) ?: return
        onSelectedChange(i)
        when {
            settings.playMode == PlayMode.FULLSCREEN -> onFullscreen(i)
            playing && player.currentId == t.id -> onFullscreen(i)
            else -> player.play(t, settings.windowAudio)
        }
    }

    val lang = LocalUiLanguage.current
    val msgRemoved = tr("Tolto dalla watchlist", "Removed from watchlist")
    val msgAdded = tr("♥ Aggiunto alla watchlist", "♥ Added to watchlist")
    val msgDiscarded = tr("non lo vedrai più", "you won't see it again")

    fun like(t: Trailer) {
        val liked = TrailerRepository.isLiked(t, prefs)
        if (watchlist && liked) refocus = true
        onPref(t, if (liked) null else Pref.LIKE)
        toast = if (liked) msgRemoved else msgAdded
    }

    fun nope(t: Trailer) {
        refocus = true
        player.stop()
        onPref(t, Pref.NOPE)
        toast = "✕ ${t.title(lang)}: $msgDiscarded"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    // Nella home avanti e indietro veloce mettono (o tolgono) il cuore.
                    Key.MediaFastForward, Key.MediaRewind -> { current?.let(::like); current != null }
                    // Play/pausa: mette in pausa o riprende il trailer in finestrina; se non è partito, lo avvia.
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        val t = current
                        when {
                            t == null -> false
                            playing && player.currentId == t.id -> { player.togglePause(); true }
                            else -> { activate(index); true }
                        }
                    }
                    Key.Menu -> { if (settings.trailersEnabled) onToggleWatchlist(); true }
                    else -> false
                }
            }
    ) {
        Backdrop(current?.backdropUrl ?: current?.thumbUrl)

        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 22.dp)) {
            TopBar(now, weather, showClock = showSlider, toast = toast)
            Spacer(Modifier.height(10.dp))

            if (showSlider) {
                // Schede Novità/Watchlist e pulsanti in fondo alla colonna di sinistra,
                // subito sopra le miniature dei trailer.
                val tabs: @Composable () -> Unit = { SliderTabs(watchlist, watchlistCount, modeFocus, onToggleWatchlist) }
                Row(Modifier.fillMaxWidth().height(250.dp)) {
                    if (current != null) {
                        HeroInfo(
                            trailer = current,
                            liked = TrailerRepository.isLiked(current, prefs),
                            modifier = Modifier.weight(1f).padding(end = 28.dp, top = 4.dp),
                            tabs = tabs,
                            onPlay = { activate(index) },
                            onLike = { like(current) },
                            onNope = { nope(current) },
                            onStremio = { onStremioSearch(current) },
                        )
                        VideoWindow(current, player, playing)
                    } else {
                        EmptyWatchlist(Modifier.weight(1f), tabs)
                    }
                }
                Spacer(Modifier.height(8.dp))
                StillRow(state = listState) {
                    itemsIndexed(trailers, key = { _, t -> t.id }) { i, t ->
                        TrailerThumb(
                            trailer = t,
                            liked = TrailerRepository.isLiked(t, prefs),
                            modifier = Modifier
                                .focusRequester(thumbFocus[i])
                                .onFocusChanged { if (it.isFocused) onSelectedChange(i) },
                            onClick = { activate(i) },
                        )
                    }
                }
            } else {
                EmptyHero(now, settings, trailerState, Modifier.fillMaxWidth().height(330.dp))
            }

            Spacer(Modifier.weight(1f))
            AppsRow(favorites, firstAppFocus, onLaunch, onAllApps, onStore, onSettings)
        }
    }
}

@Composable
private fun Backdrop(url: String?) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(url).crossfade(400).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.55f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Palette.Bg, 0.45f to Palette.Bg.copy(alpha = 0.75f), 1f to Color.Transparent)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.35f to Color.Transparent, 0.8f to Palette.Bg.copy(alpha = 0.92f), 1f to Palette.Bg)
            )
        )
    }
}

@Composable
private fun TopBar(now: Long, weather: WeatherNow?, showClock: Boolean, toast: String?) {
    Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(greeting(now), color = Palette.TextSoft, fontSize = 14.sp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (toast != null) {
                Box(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.PanelHi)
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    Text(toast, color = Palette.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (weather != null) {
            Text(weather.symbol, color = Palette.Text, fontSize = 18.sp)
            Spacer(Modifier.width(6.dp))
            Text("${weather.temperature}°", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(6.dp))
            Text(tr(weather.label, weather.labelEn), color = Palette.TextMuted, fontSize = 13.sp)
        }
        if (showClock) {
            if (weather != null) Spacer(Modifier.width(16.dp))
            Text(timeText(now), color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun HeroInfo(
    trailer: Trailer,
    liked: Boolean,
    modifier: Modifier,
    tabs: @Composable () -> Unit,
    onPlay: () -> Unit,
    onLike: () -> Unit,
    onNope: () -> Unit,
    onStremio: () -> Unit,
) {
    val lang = LocalUiLanguage.current
    Column(modifier) {
        val released = monthYear(trailer.releaseDate, uiLocale())
        val langNote = if (trailer.language.isNotBlank() && trailer.language != "it")
            " · " + tr("trailer in ", "trailer in ") + java.util.Locale(trailer.language).getDisplayLanguage(uiLocale())
        else ""
        Text(
            tr("Trailer · uscito a $released", "Trailer · released $released") + langNote,
            color = Palette.TextMuted, fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            trailer.title(lang), color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        val meta = buildList {
            val genres = trailer.genres(lang)
            if (genres.isNotEmpty()) add(genres.take(2).joinToString(", "))
            if (trailer.runtime > 0) add("${trailer.runtime / 60}h ${trailer.runtime % 60}m")
            if (trailer.rating > 0) add("★ %.1f".format(trailer.rating).replace('.', ','))
        }.joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, color = Palette.TextSoft, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            trailer.overview(lang), color = Palette.TextSoft, fontSize = 12.sp, lineHeight = 17.sp,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        tabs()
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(tr("Guarda", "Play"), Icons.Default.PlayArrow, filled = true, onClick = onPlay)
            PillButton(
                if (liked) tr("In watchlist", "In watchlist") else "Watchlist",
                if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                filled = false, onClick = onLike,
            )
            PillButton(tr("Scarta", "Discard"), Icons.Default.Close, filled = false, onClick = onNope)
            PillButton("Stremio", Icons.Default.Search, filled = false, onClick = onStremio)
        }
    }
}

@Composable
private fun PillButton(label: String, icon: ImageVector, filled: Boolean, onClick: () -> Unit) {
    FocusTile(
        onClick = onClick,
        radius = 20.dp,
        focusedScale = 1.05f,
        container = if (filled) Color.White else Palette.Panel.copy(alpha = 0.7f),
        focusedContainer = if (filled) Color.White else Palette.PanelHi,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = if (filled) Palette.Panel else Palette.TextSoft, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, color = if (filled) Palette.Panel else Palette.TextSoft, fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun VideoWindow(trailer: Trailer, player: TrailerPlayer, playing: Boolean) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.width(380.dp).height(214.dp).clip(shape).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // La superficie video è sempre presente: così il decoder è già pronto quando premi OK.
        VideoSurface(player.player, Modifier.fillMaxSize())
        AnimatedVisibility(visible = !playing || player.currentId != trailer.id, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(trailer.backdropUrl ?: trailer.thumbUrl).crossfade(250).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}

/** Schede sopra il carosello: Novità oppure Watchlist (anche col tasto ≡). */
@Composable
private fun SliderTabs(watchlist: Boolean, count: Int, watchlistFocus: FocusRequester, onToggle: () -> Unit) {
    Row(
        Modifier.padding(start = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab(tr("Novità", "New"), null, selected = !watchlist, modifier = Modifier) { if (watchlist) onToggle() }
        Tab(
            if (count > 0) "Watchlist · $count" else "Watchlist", Icons.Default.Favorite,
            selected = watchlist, modifier = Modifier.focusRequester(watchlistFocus),
        ) { if (!watchlist) onToggle() }
    }
}

@Composable
private fun Tab(label: String, icon: ImageVector?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FocusTile(
        onClick = onClick, modifier = modifier, radius = 12.dp, focusedScale = 1.04f,
        container = if (selected) Palette.PanelHi else Color.Transparent, focusedContainer = Palette.PanelHi,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = Color(0xFFF09595), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(
                label, fontSize = 12.sp, maxLines = 1, softWrap = false,
                color = if (selected) Palette.Text else Palette.TextMuted,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

/**
 * LazyRow che non si sposta quando ci entri dall'alto o dal basso:
 *  - torna sull'elemento che avevi selezionato (focusRestorer);
 *  - scorre solo se l'elemento col focus esce dallo schermo, e del minimo indispensabile
 *    (su Fire TV il comportamento standard lo riporterebbe sempre a sinistra).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun StillRow(
    state: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    horizontalPadding: androidx.compose.ui.unit.Dp = 6.dp,
    verticalPadding: androidx.compose.ui.unit.Dp = 8.dp,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    // La fila "sborda" di BLEED a sinistra e a destra (compensato dal padding interno):
    // gli elementi restano allineati al resto della pagina, ma quando si ingrandiscono col focus
    // la cornice bianca non viene tagliata dal bordo della lista.
    val bleed = 16.dp
    val contentPadding = PaddingValues(horizontal = horizontalPadding + bleed, vertical = verticalPadding)
    val margin = with(androidx.compose.ui.platform.LocalDensity.current) { (bleed + 4.dp).toPx() }
    val spec = remember(margin) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val end = offset + size
                return when {
                    offset >= margin && end <= containerSize - margin -> 0f
                    offset < margin -> offset - margin
                    else -> end - (containerSize - margin)
                }
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        LazyRow(
            state = state,
            modifier = modifier.bleed(bleed).focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = contentPadding,
            content = content,
        )
    }
}

/** Allarga l'elemento di [h] a sinistra e a destra oltre lo spazio che gli dà il genitore. */
private fun Modifier.bleed(h: androidx.compose.ui.unit.Dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val extra = h.roundToPx() * 2
        val wide = if (constraints.hasBoundedWidth) {
            constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra)
        } else constraints
        val placeable = measurable.measure(wide)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
        layout(width, placeable.height) { placeable.place(-extra / 2, 0) }
    }
)

@Composable
private fun TrailerThumb(trailer: Trailer, liked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val title = trailer.title(LocalUiLanguage.current)
    FocusTile(onClick = onClick, modifier = modifier.width(150.dp).height(84.dp), radius = 8.dp, focusedScale = 1.1f) {
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(trailer.thumbUrl ?: trailer.backdropUrl).build(),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))
            )
        )
        if (liked) {
            Icon(
                Icons.Default.Favorite, "In watchlist", tint = Color(0xFFF09595),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp),
            )
        }
        Text(
            title, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun EmptyWatchlist(modifier: Modifier, tabs: @Composable () -> Unit) {
    Column(modifier.padding(top = 40.dp, bottom = 4.dp)) {
        Text(tr("La tua watchlist è vuota", "Your watchlist is empty"), color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(
            tr(
                "Mentre guardi i trailer premi il pulsante Watchlist (o ⏩ / ⏪ sul telecomando) per metterli qui. " +
                    "I trailer in watchlist restano sul NAS finché non togli il cuore.",
                "While browsing trailers, press the Watchlist button (or ⏩ / ⏪ on the remote) to add them here. " +
                    "Watchlist trailers stay on the NAS until you remove the heart.",
            ),
            color = Palette.TextSoft, fontSize = 13.sp, lineHeight = 19.sp,
        )
        Spacer(Modifier.weight(1f))
        tabs()
    }
}

@Composable
private fun EmptyHero(now: Long, settings: LauncherSettings, state: TrailerState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(timeText(now), color = Palette.Text, fontSize = 72.sp, fontWeight = FontWeight.Light)
        Text(dateText(now, uiLocale()), color = Palette.TextMuted, fontSize = 16.sp)
        if (settings.trailersEnabled) {
            Spacer(Modifier.height(14.dp))
            val msg = when {
                state.loading -> tr("Carico i trailer dal NAS…", "Loading trailers from the NAS…")
                state.error != null -> tr("Trailer non disponibili: NAS non raggiungibile", "Trailers unavailable: can't reach the NAS")
                state.items.isNotEmpty() -> tr(
                    "Nessun trailer con questi filtri: cambia periodo o lingua nelle impostazioni",
                    "No trailers match these filters: change the period or language in settings",
                )
                else -> tr("Il NAS sta preparando i trailer, compariranno qui a breve", "The NAS is preparing trailers, they'll show up here soon")
            }
            Text(msg, color = Palette.TextMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AppsRow(
    favorites: List<AppEntry>,
    firstAppFocus: FocusRequester,
    onLaunch: (AppEntry) -> Unit,
    onAllApps: () -> Unit,
    onStore: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Scorrevole: le app preferite possono essere più di quelle che stanno sullo schermo.
        StillRow(state = rememberLazyListState(), modifier = Modifier.weight(1f)) {
            itemsIndexed(favorites, key = { _, a -> a.pkg }) { i, app ->
                AppBanner(app, if (i == 0) Modifier.focusRequester(firstAppFocus) else Modifier) { onLaunch(app) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LinkButton(tr("Tutte le app", "All apps"), Icons.Default.List, onAllApps)
            LinkButton(tr("Scarica app", "Get apps"), Icons.Default.ShoppingCart, onStore)
            LinkButton(tr("Impostazioni", "Settings"), Icons.Default.Settings, onSettings)
        }
    }
}

@Composable
fun AppBanner(app: AppEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusTile(onClick = onClick, modifier = modifier.width(128.dp).height(72.dp), radius = 10.dp) {
        val banner = app.banner
        if (banner != null) {
            Image(banner, app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.fillMaxSize().padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                app.icon?.let { Image(it, null, modifier = Modifier.size(40.dp)) }
                Text(app.label, color = Palette.Text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun LinkButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    FocusTile(
        onClick = onClick, radius = 14.dp, focusedScale = 1.04f,
        container = Color.Transparent, focusedContainer = Palette.PanelHi,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Palette.TextMuted, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Palette.TextSoft, fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
    }
}
