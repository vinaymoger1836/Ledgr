package com.ledgr.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle

/** System font (Roboto / Google Sans when present); the Material 3 scale is kept as is. */
internal val LedgrTypography = Typography()

/**
 * Amounts use tabular figures so columns of money line up and count-ups don't jitter (docs/08 §3).
 * Apply to any style: `MaterialTheme.typography.titleLarge.tabular()`.
 */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
