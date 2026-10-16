package dev.turbodash.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val background = dev.turbodash.app.Brand.background
    val surface = dev.turbodash.app.Brand.surface
    val surfaceHigh = dev.turbodash.app.Brand.surfaceHigh
    val track = dev.turbodash.app.Brand.track
    val text = Color(0xFFF1F3F5)
    val muted = Color(0xFF8A94A6)
    val accent = dev.turbodash.app.Brand.accent
    val ok = Color(0xFF2EC4B6)
    val warn = dev.turbodash.app.Brand.warn
    val danger = Color(0xFFE63946)
    val info = Color(0xFF4EA8DE)
}

private val scheme = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Color.Black,
    secondary = Palette.ok,
    // Selected tab pill and chips in the brand color instead of the Material default purple.
    secondaryContainer = Palette.accent.copy(alpha = 0.22f),
    onSecondaryContainer = Palette.text,
    background = Palette.background,
    onBackground = Palette.text,
    surface = Palette.surface,
    onSurface = Palette.text,
    surfaceVariant = Palette.surfaceHigh,
    onSurfaceVariant = Palette.muted,
    surfaceContainer = Palette.surface,
    surfaceContainerHigh = Palette.surfaceHigh,
    // Cards, dialogs and menus use these: keep them in the brand greys, not the Material defaults.
    surfaceContainerHighest = Palette.surfaceHigh,
    surfaceContainerLow = Palette.surface,
    surfaceContainerLowest = Palette.background,
    error = Palette.danger,
)

@Composable
fun TurboDashTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
