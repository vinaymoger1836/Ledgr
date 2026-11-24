package com.ledgr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.ledgr.app.data.settings.ThemeMode

private val LocalLedgrColors = staticCompositionLocalOf { LightLedgrColors }
private val LocalCategoryPalette = staticCompositionLocalOf { CategoryPalette.Light }

/** Whether [mode] resolves to the dark scheme, given the system setting. */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** App theme. Follows the system by default; [themeMode] is the user's override from DataStore. */
@Composable
fun LedgrTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = themeMode.isDark(isSystemInDarkTheme())
    CompositionLocalProvider(
        LocalLedgrColors provides if (dark) DarkLedgrColors else LightLedgrColors,
        LocalCategoryPalette provides if (dark) CategoryPalette.Dark else CategoryPalette.Light,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColorScheme else LightColorScheme,
            typography = LedgrTypography,
            shapes = LedgrShapes,
            content = content,
        )
    }
}

/** Access to the app-specific token roles: `LedgrTheme.colors.spend`, `LedgrTheme.categoryColors[3]`. */
object LedgrTheme {
    val colors: LedgrColors
        @Composable @ReadOnlyComposable get() = LocalLedgrColors.current

    val categoryColors: CategoryPalette
        @Composable @ReadOnlyComposable get() = LocalCategoryPalette.current
}
