package com.ledgr.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Docs/08 §3: chips 12 dp, cards 20 dp, sheets 28 dp.
 * Mapping: `small` = chips, `large` = cards, `extraLarge` = sheets and dialogs.
 */
internal val LedgrShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
