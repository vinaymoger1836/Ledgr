package com.ledgr.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/*
 * Design tokens (docs/08 §3). One token set, two schemes. Hex values are the single source of truth;
 * the surface values in res/values{,-night}/colors.xml (window_background) must match `surface` here.
 *
 * Contrast (WCAG 2.x), verified in the P0 phase report:
 *   text roles >= 4.5:1 against the surface they sit on, category colours >= 3:1 against `surface`.
 */

// ── Material roles ──────────────────────────────────────────────────────────────────────────────

internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF3730A3),              // deep indigo
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3E1FF),
    onPrimaryContainer = Color(0xFF16106B),
    secondary = Color(0xFF5B5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E1F9),
    onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF475E86),             // slate blue (same family as `transfer`)
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD9E3FA),
    onTertiaryContainer = Color(0xFF0A1B3C),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF2EFF7),
    inversePrimary = Color(0xFFC1C1FF),
    surfaceTint = Color(0xFF3730A3),
    surfaceBright = Color(0xFFFBF8FF),
    surfaceDim = Color(0xFFDBD9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9),
)

internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFC1C1FF),
    onPrimary = Color(0xFF1F1B7A),
    primaryContainer = Color(0xFF38379A),
    onPrimaryContainer = Color(0xFFE1E0FF),
    secondary = Color(0xFFC3C4DD),
    onSecondary = Color(0xFF2C2F42),
    secondaryContainer = Color(0xFF434559),
    onSecondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFFA9C0EC),
    onTertiary = Color(0xFF14304F),
    tertiaryContainer = Color(0xFF2E4466),
    onTertiaryContainer = Color(0xFFD9E3FA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF918F9A),
    outlineVariant = Color(0xFF46464F),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF303036),
    inversePrimary = Color(0xFF5352A8),
    surfaceTint = Color(0xFFC1C1FF),
    surfaceBright = Color(0xFF38393F),
    surfaceDim = Color(0xFF121318),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
)

// ── App-specific roles (not in Material) ────────────────────────────────────────────────────────

/**
 * Money-meaning colours. Always paired with a sign, icon or label: colour is never the only cue (docs/08 §5).
 * `danger` is the Material `error` role; use `MaterialTheme.colorScheme.error`.
 */
@Immutable
data class LedgrColors(
    val isDark: Boolean,
    val spend: Color,
    val onSpendContainer: Color,
    val spendContainer: Color,
    val income: Color,
    val onIncomeContainer: Color,
    val incomeContainer: Color,
    val transfer: Color,
    val onTransferContainer: Color,
    val transferContainer: Color,
    val warning: Color,
    val onWarningContainer: Color,
    val warningContainer: Color,
)

internal val LightLedgrColors = LedgrColors(
    isDark = false,
    spend = Color(0xFFB93A08),                // warm coral
    spendContainer = Color(0xFFFFDBD0),
    onSpendContainer = Color(0xFF3B0A00),
    income = Color(0xFF15803D),               // green
    incomeContainer = Color(0xFFCFF5D9),
    onIncomeContainer = Color(0xFF00210D),
    transfer = Color(0xFF475E86),             // slate blue
    transferContainer = Color(0xFFD9E3FA),
    onTransferContainer = Color(0xFF0A1B3C),
    warning = Color(0xFF8A5A00),              // amber
    warningContainer = Color(0xFFFFE5B0),
    onWarningContainer = Color(0xFF2C1A00),
)

internal val DarkLedgrColors = LedgrColors(
    isDark = true,
    spend = Color(0xFFFF8A65),
    spendContainer = Color(0xFF5C1F0D),
    onSpendContainer = Color(0xFFFFDBD0),
    income = Color(0xFF6FD08C),
    incomeContainer = Color(0xFF0D3B1F),
    onIncomeContainer = Color(0xFFCFF5D9),
    transfer = Color(0xFFA9C0EC),
    transferContainer = Color(0xFF2E4466),
    onTransferContainer = Color(0xFFD9E3FA),
    warning = Color(0xFFFFBA3B),
    warningContainer = Color(0xFF4A3000),
    onWarningContainer = Color(0xFFFFE5B0),
)
