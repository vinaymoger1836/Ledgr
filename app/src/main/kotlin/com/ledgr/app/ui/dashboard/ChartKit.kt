package com.ledgr.app.ui.dashboard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgr.app.R
import com.ledgr.app.domain.money.Money
import com.ledgr.app.ui.motion.rememberReducedMotion
import com.patrykandpatrick.vico.compose.cartesian.CartesianChart
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme

/** Chart motion (docs/08 §4): 500–700 ms on first show, 250 ms when a filter changes, none at animator scale 0. */
private const val ENTRY_MS = 600
private const val CHANGE_MS = 250

/** Draws Vico charts from the app's Material colours, so they follow the light and dark theme (docs/08 §3). */
@Composable
internal fun ChartTheme(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val theme = rememberM3VicoTheme(
        columnCartesianLayerColors = listOf(scheme.primary, scheme.secondary, scheme.tertiary),
        lineColor = scheme.outlineVariant,
        textColor = scheme.onSurfaceVariant,
    )
    ProvideVicoTheme(theme, content)
}

/**
 * One Vico chart with the dashboard's fixed behaviour: no scroll or zoom (the whole period always fits), the entry
 * and filter-change animations from the motion spec, both switched off when the system animator scale is 0.
 */
@Composable
internal fun LedgrChart(
    chart: CartesianChart,
    producer: CartesianChartModelProducer,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
) {
    val reduced = rememberReducedMotion()
    CartesianChartHost(
        chart = chart,
        modelProducer = producer,
        modifier = modifier.clearAndSetSemantics { },
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
        animationSpec = if (reduced) null else tween(CHANGE_MS, easing = FastOutSlowInEasing),
        initialAnimationSpec = if (reduced) null else tween(ENTRY_MS, easing = FastOutSlowInEasing),
        chartAreaHeight = height,
    )
}

/** A column in the chart's colour at a given thickness (drawing only). */
@Composable
internal fun chartColumn(color: Color, thickness: Dp) = rememberLineComponent(Fill(color), thickness)

private val MoneyFormatter = CartesianValueFormatter { _, value, _ -> Money.formatCompact(value.toLong()) }

/** Left axis labelled in compact rupees (`₹12.4K`). The chart values are paise `Long`s; only drawing converts. */
@Composable
internal fun startMoneyAxis(count: Int = 4): VerticalAxis<Axis.Position.Vertical.Start> {
    val placer = remember(count) { VerticalAxis.ItemPlacer.count({ count }) }
    return VerticalAxis.rememberStart(valueFormatter = MoneyFormatter, itemPlacer = placer)
}

/** Right axis, for a second scale (the cumulative line on the daily bars). */
@Composable
internal fun endMoneyAxis(count: Int = 4): VerticalAxis<Axis.Position.Vertical.End> {
    val placer = remember(count) { VerticalAxis.ItemPlacer.count({ count }) }
    return VerticalAxis.rememberEnd(valueFormatter = MoneyFormatter, itemPlacer = placer)
}

/**
 * The tooltip a bar shows while it is pressed or dragged over (docs/07 W2). [text] gets the point index and returns
 * the line to show; the selection is also reported through [onSelect] so the widget can keep it on screen afterwards.
 */
@Composable
internal fun rememberTooltipMarker(text: (Int) -> String): DefaultCartesianMarker {
    val scheme = MaterialTheme.colorScheme
    val label = rememberTextComponent(
        style = TextStyle(color = scheme.onSurface, fontSize = 12.sp),
        padding = Insets(horizontal = 8.dp, vertical = 4.dp),
        background = rememberShapeComponent(Fill(scheme.surfaceContainerHighest), RoundedCornerShape(8.dp)),
    )
    val formatter = remember(text) {
        DefaultCartesianMarker.ValueFormatter { _, targets -> text(targets.firstOrNull()?.x?.toInt() ?: 0) }
    }
    return rememberDefaultCartesianMarker(label = label, valueFormatter = formatter)
}

/** Reports which point is under the finger, and null when the marker goes away. */
internal fun selectionListener(onSelect: (Int?) -> Unit) = object : CartesianMarkerVisibilityListener {
    override fun onShown(marker: CartesianMarker, targets: List<CartesianMarker.Target>) =
        onSelect(targets.firstOrNull()?.x?.toInt())

    override fun onUpdated(marker: CartesianMarker, targets: List<CartesianMarker.Target>) =
        onSelect(targets.firstOrNull()?.x?.toInt())
}

/** Bottom axis whose labels come from the point index (day of month, week start, month name...). */
@Composable
internal fun indexAxis(spacing: Int, label: (Int) -> String): HorizontalAxis<Axis.Position.Horizontal.Bottom> {
    val formatter = remember(label) { CartesianValueFormatter { _, value, _ -> label(value.toInt()) } }
    val placer = remember(spacing) { HorizontalAxis.ItemPlacer.aligned(spacing = { spacing }, addExtremeLabelPadding = true) }
    return HorizontalAxis.rememberBottom(valueFormatter = formatter, itemPlacer = placer)
}

/**
 * The frame every widget shares: title, a spoken summary on the heading (docs/08 §5: charts say in words what they
 * show), an optional "View as table" button, and the content.
 */
@Composable
internal fun WidgetCard(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    onViewAsTable: (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            heading()
                            contentDescription = "$title. $summary"
                        },
                )
                action?.invoke()
                if (onViewAsTable != null) {
                    TextButton(onClick = onViewAsTable, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.dash_view_as_table))
                    }
                }
            }
            // The chart itself is a picture (see LedgrChart); the heading above and "View as table" carry the information.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}
