package dev.firelauncher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.darkColorScheme

object Palette {
    val Bg = Color(0xFF1B1838)
    val BgDeep = Color(0xFF14122A)
    val Panel = Color(0xFF26215C)
    val PanelHi = Color(0xFF3C3489)
    val Accent = Color(0xFF7F77DD)
    val Text = Color(0xFFF1EFE8)
    val TextSoft = Color(0xFFCECBF6)
    val TextMuted = Color(0xFFAFA9EC)
}

@Composable
fun LauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            background = Palette.Bg,
            surface = Palette.Panel,
            onSurface = Palette.Text,
            onBackground = Palette.Text,
        ),
        content = content,
    )
}

/** Riquadro selezionabile col telecomando: si ingrandisce e prende un bordo bianco col focus. */
@Composable
fun FocusTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    radius: Dp = 10.dp,
    focusedScale: Float = 1.08f,
    container: Color = Palette.Panel,
    focusedContainer: Color = container,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            focusedContainerColor = focusedContainer,
            pressedContainerColor = focusedContainer,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), inset = 0.dp, shape = shape),
        ),
        content = content,
    )
}
