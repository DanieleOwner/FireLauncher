package dev.firelauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.firelauncher.data.Pref
import dev.firelauncher.data.Trailer
import dev.firelauncher.data.TrailerRepository
import dev.firelauncher.player.TrailerPlayer
import kotlinx.coroutines.delay

@Composable
fun FullscreenPlayer(
    trailers: List<Trailer>,
    startIndex: Int,
    player: TrailerPlayer,
    prefs: Map<Int, Pref>,
    onPref: (Trailer, Pref?) -> Unit,
    onIndexChange: (Int) -> Unit,
    onExit: () -> Unit,
) {
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (trailers.size - 1).coerceAtLeast(0))) }
    val trailer = trailers.getOrNull(index)
    var overlayTick by remember { mutableIntStateOf(0) }
    var showOverlay by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    var timeLabel by remember { mutableStateOf("") }
    val ended by player.ended.collectAsState()
    val endedAtStart = remember { ended }
    val focus = remember { FocusRequester() }

    BackHandler {
        player.stop()
        onExit()
    }

    fun go(delta: Int) {
        val next = index + delta
        if (next !in trailers.indices) return
        index = next
        onIndexChange(next)
        overlayTick++
    }

    var feedback by remember { mutableStateOf<String?>(null) }
    val msgRemoved = tr("Tolto dalla watchlist", "Removed from watchlist")
    val msgAdded = tr("♥ Aggiunto alla watchlist", "♥ Added to watchlist")
    LaunchedEffect(feedback) { if (feedback != null) { delay(1800); feedback = null } }

    LaunchedEffect(trailer?.id) {
        val t = trailer ?: run { onExit(); return@LaunchedEffect }
        player.play(t, withAudio = true)
        player.precache(listOfNotNull(trailers.getOrNull(index + 1)))
    }

    // A fine trailer passa al successivo; dopo l'ultimo torna alla home.
    LaunchedEffect(ended) {
        if (ended == endedAtStart) return@LaunchedEffect
        if (index < trailers.lastIndex) go(1) else onExit()
    }

    LaunchedEffect(overlayTick) {
        showOverlay = true
        delay(4000)
        showOverlay = false
    }

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        while (true) {
            val p = player.player
            val dur = p.duration.takeIf { it > 0 } ?: 0L
            progress = if (dur > 0) (p.currentPosition.toFloat() / dur).coerceIn(0f, 1f) else 0f
            timeLabel = "${fmt(p.currentPosition)} / ${fmt(dur)}"
            delay(500)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> { go(1); true }
                    Key.DirectionLeft -> { go(-1); true }
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> {
                        player.togglePause(); overlayTick++; true
                    }
                    // Avanti e indietro veloce: entrambi mettono (o tolgono) il cuore.
                    Key.MediaFastForward, Key.MediaRewind -> {
                        trailer?.let { t ->
                            val liked = TrailerRepository.isLiked(t, prefs)
                            onPref(t, if (liked) null else Pref.LIKE)
                            feedback = if (liked) msgRemoved else msgAdded
                        }
                        overlayTick++; true
                    }
                    Key.DirectionUp, Key.DirectionDown -> { overlayTick++; true }
                    else -> false
                }
            },
    ) {
        VideoSurface(player.player, Modifier.fillMaxSize())

        feedback?.let { msg ->
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 28.dp).clip(RoundedCornerShape(16.dp))
                    .background(Palette.PanelHi).padding(horizontal = 16.dp, vertical = 6.dp)
            ) { Text(msg, color = Color.White, fontSize = 14.sp) }
        }

        AnimatedVisibility(
            visible = showOverlay && trailer != null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            val t = trailer ?: return@AnimatedVisibility
            Column(
                Modifier.fillMaxWidth().background(
                    Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))
                ).padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 32.dp)
            ) {
                Text(t.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Trailer · ${monthYear(t.releaseDate, uiLocale())} · " +
                        tr("${index + 1} di ${trailers.size}", "${index + 1} of ${trailers.size}"),
                    color = Palette.TextMuted, fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.PanelHi)) {
                    Box(Modifier.fillMaxWidth(progress).height(4.dp).background(Color.White))
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    Text(timeLabel, color = Palette.TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        (if (TrailerRepository.isLiked(t, prefs)) "♥ In watchlist   " else "") +
                            tr("OK pausa · ‹ › altro trailer · Indietro esci", "OK pause · ‹ › other trailer · Back to exit"),
                        color = Palette.TextMuted, fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
