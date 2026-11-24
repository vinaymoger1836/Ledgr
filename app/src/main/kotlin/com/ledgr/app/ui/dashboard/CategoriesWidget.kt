package com.ledgr.app.ui.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ledgr.app.R
import com.ledgr.app.data.dashboard.DashSlice
import com.ledgr.app.data.dashboard.DashboardSnapshot
import com.ledgr.app.domain.money.Money
import com.ledgr.app.ui.motion.rememberReducedMotion
import com.ledgr.app.ui.review.spokenAmount
import com.ledgr.app.ui.theme.LedgrTheme
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

private const val SWEEP_MS = 700
private const val GAP_DEGREES = 2f

/** "31.2%" from whole per-mille, with integer maths. */
internal fun percentText(perMille: Int): String = "${perMille / 10}.${perMille % 10}%"

/**
 * W6 (docs/07): top 7 categories and "Other" as a donut that sweeps in, the period total in the centre, and a legend
 * with amounts and shares. The legend rows are the accessible, 48 dp way to filter the table by category; tapping a
 * slice does the same.
 */
@Composable
internal fun CategoriesWidget(
    snapshot: DashboardSnapshot,
    onViewAsTable: () -> Unit,
    onCategory: (DashSlice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val slices = snapshot.slices
    val total = snapshot.kpis.spent
    val top = slices.firstOrNull()
    val summary = if (top == null || total <= 0L) {
        stringResource(R.string.dash_categories_summary_none)
    } else {
        stringResource(R.string.dash_categories_summary, sliceName(top), percentText(top.sharePerMille), spokenAmount(total))
    }
    WidgetCard(
        title = stringResource(R.string.dash_categories_title),
        summary = summary,
        onViewAsTable = onViewAsTable,
        modifier = modifier,
    ) {
        if (slices.isEmpty() || total <= 0L) {
            Text(
                stringResource(R.string.dash_no_spend),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@WidgetCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Donut(slices = slices, total = total, onSlice = onCategory, modifier = Modifier.align(Alignment.CenterHorizontally))
            for (slice in slices) LegendRow(slice, onClick = { onCategory(slice) })
        }
    }
}

@Composable
private fun sliceColor(slice: DashSlice): Color =
    if (slice.isOther) MaterialTheme.colorScheme.outline else LedgrTheme.categoryColors[slice.colorIndex]

@Composable
internal fun sliceName(slice: DashSlice): String = when {
    slice.isOther -> stringResource(R.string.dash_other)
    slice.name == null -> stringResource(R.string.dash_uncategorised)
    else -> slice.name.orEmpty()
}

@Composable
private fun Donut(slices: List<DashSlice>, total: Long, onSlice: (DashSlice) -> Unit, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val sweep = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(slices, reduced) {
        if (reduced) {
            sweep.snapTo(1f)
        } else {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(SWEEP_MS, easing = FastOutSlowInEasing))
        }
    }
    val colors = slices.map { sliceColor(it) }
    val positive = slices.sumOf { it.spend.coerceAtLeast(0L) }.coerceAtLeast(1L)

    Box(modifier = modifier.size(184.dp), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(184.dp)
                .clearAndSetSemantics { }
                .pointerInput(slices) {
                    detectTapGestures { tap ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val radius = size.width / 2f
                        val d = hypot(tap.x - c.x, tap.y - c.y)
                        if (d < radius * 0.55f || d > radius) return@detectTapGestures
                        // Angle clockwise from 12 o'clock, 0..360.
                        var deg = (atan2(tap.y - c.y, tap.x - c.x) * 180f / PI.toFloat()) + 90f
                        if (deg < 0f) deg += 360f
                        var start = 0f
                        for (s in slices) {
                            val span = 360f * s.spend.coerceAtLeast(0L) / positive
                            if (deg >= start && deg < start + span) {
                                onSlice(s)
                                return@detectTapGestures
                            }
                            start += span
                        }
                    }
                },
        ) {
            val stroke = size.minDimension * 0.18f
            val inset = stroke / 2f
            val arc = Size(size.width - stroke, size.height - stroke)
            var start = -90f
            slices.forEachIndexed { i, s ->
                val full = 360f * s.spend.coerceAtLeast(0L) / positive
                val span = (full * sweep.value - GAP_DEGREES).coerceAtLeast(0f)
                if (span > 0f) {
                    drawArc(
                        color = colors[i],
                        startAngle = start + GAP_DEGREES / 2f,
                        sweepAngle = span,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arc,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                }
                start += full * sweep.value
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.dash_categories_centre), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Money.format(total), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun LegendRow(slice: DashSlice, onClick: () -> Unit) {
    val name = sliceName(slice)
    val spoken = stringResource(R.string.dash_slice_spoken, name, spokenAmount(slice.spend), percentText(slice.sharePerMille))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                enabled = !slice.isOther && slice.categoryId != null,
                onClickLabel = stringResource(R.string.dash_filter_table_by, name),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.size(12.dp).background(sliceColor(slice), CircleShape))
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
        Text(percentText(slice.sharePerMille), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Money.format(slice.spend), style = MaterialTheme.typography.bodyMedium)
    }
}
