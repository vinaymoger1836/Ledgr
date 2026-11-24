package com.ledgr.app.ui.nav

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Two outlined icons that material-icons-core does not have (we avoid the large extended set).
 * Drawn as 2.2-wide round strokes on a 24 dp grid to sit beside the core Outlined icons.
 */
internal object LedgrIcons {

    /** Dashboard: three bars of different heights. */
    val Dashboard: ImageVector = ImageVector.Builder(
        name = "Dashboard",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(6f, 19f); lineTo(6f, 12f)
            moveTo(12f, 19f); lineTo(12f, 5f)
            moveTo(18f, 19f); lineTo(18f, 9f)
        }
    }.build()

    /** Reconcile: two opposing arrows (ledger vs statement). */
    val Reconcile: ImageVector = ImageVector.Builder(
        name = "Reconcile",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // top arrow, pointing right
            moveTo(4f, 8f); lineTo(19f, 8f)
            moveTo(15f, 4f); lineTo(19f, 8f); lineTo(15f, 12f)
            // bottom arrow, pointing left
            moveTo(20f, 16f); lineTo(5f, 16f)
            moveTo(9f, 12f); lineTo(5f, 16f); lineTo(9f, 20f)
        }
    }.build()
}
