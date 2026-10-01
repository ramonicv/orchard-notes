package dev.rortega.orchardnotes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = GoldDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE7A3),
    onPrimaryContainer = Color(0xFF3A2800),
    secondary = Gold,
    onSecondary = InkLight,
    secondaryContainer = Color(0xFFFFF1C7),
    onSecondaryContainer = Color(0xFF3A2800),
    background = PaperLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = PaperLightVariant,
    onSurfaceVariant = InkLightMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F5F0),
    surfaceContainer = PaperLightVariant,
    surfaceContainerHigh = Color(0xFFEAE7E0),
    surfaceContainerHighest = Color(0xFFE3DFD7),
    outline = Color(0xFFCBC6BC),
    outlineVariant = Color(0xFFE2DED6),
    error = ErrorLight,
)

private val DarkColors = darkColorScheme(
    primary = GoldLight,
    onPrimary = Color(0xFF3A2800),
    primaryContainer = Color(0xFF5A4300),
    onPrimaryContainer = Color(0xFFFFE7A3),
    secondary = Gold,
    onSecondary = Color(0xFF1C1B19),
    secondaryContainer = Color(0xFF4A3A10),
    onSecondaryContainer = Color(0xFFFFE7A3),
    background = PaperDark,
    onBackground = InkDark,
    surface = PaperDark,
    onSurface = InkDark,
    surfaceVariant = PaperDarkVariant,
    onSurfaceVariant = InkDarkMuted,
    surfaceContainerLowest = Color(0xFF141416),
    surfaceContainerLow = Color(0xFF232325),
    surfaceContainer = PaperDarkElevated,
    surfaceContainerHigh = Color(0xFF333336),
    surfaceContainerHighest = PaperDarkVariant,
    outline = Color(0xFF5A5A5E),
    outlineVariant = Color(0xFF3F3F42),
    error = ErrorDark,
)

@Composable
fun OrchardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = OrchardTypography,
        content = content,
    )
}
