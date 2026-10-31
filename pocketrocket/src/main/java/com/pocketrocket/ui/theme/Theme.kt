package com.pocketrocket.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.pocketrocket.settings.ThemeMode
import com.pocketrocket.settings.ThemePrefs

/** True when the app should draw dark, from the saved theme choice. */
@Composable
fun rememberDarkTheme(): Boolean {
    val context = LocalContext.current
    val mode by remember { ThemePrefs.mode(context) }.collectAsState()
    return when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
}

/**
 * The mockup's look (decision 23): its palette as [PrTheme.colors] and as the Material scheme, Inter as the type face.
 * No dynamic (wallpaper) colour: every screen uses the same navy and orange.
 */
@Composable
fun PocketRocketTheme(darkTheme: Boolean = rememberDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) PrDark else PrLight
    CompositionLocalProvider(LocalPrColors provides colors) {
        MaterialTheme(colorScheme = colors.toMaterial(), typography = AppTypography, content = content)
    }
}

/** Shortcut to the tokens: `PrTheme.colors.accent`. */
object PrTheme {
    val colors: PrColors
        @Composable @ReadOnlyComposable get() = LocalPrColors.current
}
