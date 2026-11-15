package io.github.q0ochen.schafkopf.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.q0ochen.schafkopf.domain.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6B41),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB9F0C8),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF6B5A1F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF5E19B),
    onSecondaryContainer = Color(0xFF221B00),
    tertiary = Color(0xFF7A4E1D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCBC),
    onTertiaryContainer = Color(0xFF2B1700),
    background = Color(0xFFF7FAF5),
    onBackground = Color(0xFF181D19),
    surface = Color(0xFFF7FAF5),
    onSurface = Color(0xFF181D19),
    surfaceVariant = Color(0xFFDCE5DB),
    onSurfaceVariant = Color(0xFF404941),
    outline = Color(0xFF707971),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD8A6),
    onPrimary = Color(0xFF00391D),
    primaryContainer = Color(0xFF00522D),
    onPrimaryContainer = Color(0xFFB9F0C8),
    secondary = Color(0xFFD8C582),
    onSecondary = Color(0xFF3A3000),
    secondaryContainer = Color(0xFF524607),
    onSecondaryContainer = Color(0xFFF5E19B),
    tertiary = Color(0xFFF0BC8A),
    onTertiary = Color(0xFF462A00),
    tertiaryContainer = Color(0xFF5F3D0E),
    onTertiaryContainer = Color(0xFFFFDCBC),
    // Echtes Schwarz: spart auf OLED-Bildschirmen Strom und blendet im schummrigen Wirtshaus nicht.
    // Helle Schrift darauf erreicht ein Kontrastverhältnis von über 15:1 (WCAG AAA).
    background = Color(0xFF000000),
    onBackground = Color(0xFFF1F5EF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF1F5EF),
    surfaceVariant = Color(0xFF1C211D),
    onSurfaceVariant = Color(0xFFD0D8CE),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0C0F0C),
    surfaceContainer = Color(0xFF121612),
    surfaceContainerHigh = Color(0xFF1A1F1A),
    surfaceContainerHighest = Color(0xFF232923),
    outline = Color(0xFF9AA39A),
    outlineVariant = Color(0xFF3A423B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * Farben, die Material nicht kennt: Gewinn und Verlust sowie je eine feste Farbe pro Spieler.
 * Die Spielerfarben stammen aus der für Farbfehlsichtige geeigneten Okabe-Ito-Palette; Beträge tragen
 * zusätzlich immer ein Vorzeichen, damit nichts nur an der Farbe hängt.
 */
@Immutable
data class ExtraColors(
    val gain: Color,
    val loss: Color,
    val players: List<Color>,
)

private val LightExtra = ExtraColors(
    gain = Color(0xFF176B36),
    loss = Color(0xFFB3261E),
    players = listOf(
        Color(0xFF0072B2), Color(0xFFD55E00), Color(0xFF007A5A), Color(0xFFAA4A8A),
        Color(0xFF8A6D00), Color(0xFF3A86C8), Color(0xFF5C5C5C), Color(0xFF7A3E00),
    ),
)

private val DarkExtra = ExtraColors(
    gain = Color(0xFF7FDC9A),
    loss = Color(0xFFFFB4AB),
    players = listOf(
        Color(0xFF56B4E9), Color(0xFFF0A040), Color(0xFF3FD0A8), Color(0xFFE8A0CC),
        Color(0xFFF0E442), Color(0xFF9CCBEE), Color(0xFFC8C8C8), Color(0xFFE0A070),
    ),
)

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

/** Farbe eines Spielers für Diagramme und Markierungen. */
@Composable
fun playerColor(colorIndex: Int): Color {
    val colors = LocalExtraColors.current.players
    return colors[((colorIndex % colors.size) + colors.size) % colors.size]
}

@Composable
fun SchafkopfTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalExtraColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}
