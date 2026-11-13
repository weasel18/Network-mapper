package com.networkmapper.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** "Night ops": the app is dark everywhere, with an amber accent. */
object Night {
    val Bg = Color(0xFF0F151C)
    val Panel = Color(0xFF18212B)
    val Raised = Color(0xFF212C38)
    val Line = Color(0xFF36424F)
    val Text = Color(0xFFEEF2F6)
    val Muted = Color(0xFFA9B4C0)
    val Amber = Color(0xFFFFB547)
    val Green = Color(0xFF6FE08A)
    val Red = Color(0xFFFF6B6B)
}

private val NightScheme = darkColorScheme(
    primary = Night.Amber,
    onPrimary = Night.Bg,
    primaryContainer = Color(0xFF4A3410),
    onPrimaryContainer = Color(0xFFFFD08A),
    secondary = Color(0xFF4FD1C5),
    onSecondary = Night.Bg,
    secondaryContainer = Color(0xFF26313E),
    onSecondaryContainer = Night.Text,
    tertiary = Color(0xFF4DA3FF),
    onTertiary = Night.Bg,
    background = Night.Bg,
    onBackground = Night.Text,
    surface = Night.Bg,
    onSurface = Night.Text,
    surfaceVariant = Night.Raised,
    onSurfaceVariant = Night.Muted,
    surfaceTint = Night.Panel,
    surfaceContainerLowest = Color(0xFF0B1016),
    surfaceContainerLow = Color(0xFF151D26),
    surfaceContainer = Night.Panel,
    surfaceContainerHigh = Color(0xFF1C2631),
    surfaceContainerHighest = Night.Raised,
    outline = Color(0xFF5C6B7C),
    outlineVariant = Night.Line,
    error = Night.Red,
    onError = Night.Bg,
    errorContainer = Color(0xFF3A1518),
    onErrorContainer = Color(0xFFFFB3B3),
)

@Composable
fun NetworkMapperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NightScheme, content = content)
}
