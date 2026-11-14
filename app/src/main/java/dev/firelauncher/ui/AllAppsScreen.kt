package dev.firelauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.firelauncher.data.AppEntry

@Composable
fun AllAppsScreen(
    apps: List<AppEntry>,
    onLaunch: (AppEntry) -> Unit,
    onBack: () -> Unit,
    title: String = tr("Tutte le app", "All apps"),
    subtitle: String = tr("${apps.size} app installate", "${apps.size} installed apps"),
) {
    BackHandler(onBack = onBack)
    val first = remember { FocusRequester() }
    LaunchedEffect(apps.isNotEmpty()) { if (apps.isNotEmpty()) runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().background(Palette.Bg).padding(horizontal = 48.dp, vertical = 28.dp)) {
        Text(title, color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = Palette.TextMuted, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(8.dp),
        ) {
            itemsIndexed(apps, key = { _, a -> a.pkg }) { i, app ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AppBanner(app, if (i == 0) Modifier.focusRequester(first) else Modifier) { onLaunch(app) }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        app.label, color = Palette.TextSoft, fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.width(128.dp),
                    )
                }
            }
        }
    }
}
