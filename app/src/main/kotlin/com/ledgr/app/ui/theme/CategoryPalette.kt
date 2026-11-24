package com.ledgr.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The fixed 10-colour category palette (docs/08 §3). `categories.yaml` and the `category.color` column
 * store an index 0..9; the theme picks the light or dark shade. Every shade is >= 3:1 against `surface`
 * in its scheme (checked in the P0 report). Category identity is always shown with an icon and name too.
 */
@Immutable
class CategoryPalette private constructor(private val shades: List<Color>) {

    val size: Int get() = shades.size

    /** Colour for a stored index; out-of-range values wrap so a bad row can never crash the UI. */
    operator fun get(index: Int): Color = shades[Math.floorMod(index, shades.size)]

    internal companion object {
        // index: 0 coral · 1 amber · 2 green · 3 teal · 4 blue · 5 indigo · 6 purple · 7 pink · 8 brown · 9 slate
        val Light = CategoryPalette(
            listOf(
                Color(0xFFD9480F), Color(0xFFB45309), Color(0xFF15803D), Color(0xFF0F766E), Color(0xFF1D4ED8),
                Color(0xFF4F46E5), Color(0xFF7E22CE), Color(0xFFBE185D), Color(0xFF8B5E3C), Color(0xFF475569),
            ),
        )
        val Dark = CategoryPalette(
            listOf(
                Color(0xFFFF8A65), Color(0xFFFBBF24), Color(0xFF4ADE80), Color(0xFF2DD4BF), Color(0xFF60A5FA),
                Color(0xFFA5B4FC), Color(0xFFC084FC), Color(0xFFF472B6), Color(0xFFD2A679), Color(0xFF94A3B8),
            ),
        )
    }
}
