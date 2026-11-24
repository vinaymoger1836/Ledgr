package com.ledgr.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ledgr.app.R
import com.ledgr.app.data.dashboard.DashboardSnapshot
import com.ledgr.app.domain.forecast.Confidence
import com.ledgr.app.domain.ledger.PeriodKind
import com.ledgr.app.domain.money.Money
import com.ledgr.app.ui.review.spokenAmount
import com.ledgr.app.ui.theme.LedgrTheme
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill

/**
 * W3 (docs/07): cumulative spend to today (solid), the projection to the end of the period (dashed), the P25–P75 band
 * behind it, and the previous period's running total as a faint reference. The band is drawn by a small custom
 * `Decoration`; its two edges are also drawn as thin lines, so the range is still readable if a renderer skips it.
 */
@Composable
internal fun ForecastWidget(
    snapshot: DashboardSnapshot,
    onViewAsTable: () -> Unit,
    onExplain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = LedgrTheme.colors
    val forecast = snapshot.forecast
    val period = snapshot.period
    val actual = snapshot.days.mapNotNull { it.cumulative }.ifEmpty { listOf(0L) }
    val todayIndex = actual.size - 1
    val path = forecast?.path.orEmpty()
    // x of a path point = its day number inside the period.
    val pathX = path.map { (it.date.toEpochDay() - period.start.toEpochDay()).toInt() }
    val reference = snapshot.ghostCumulative.take(period.dayCount)

    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(snapshot.days, forecast, snapshot.ghostCumulative) {
        producer.runTransaction {
            lineModel {
                series(actual)
                if (path.size >= 2) {
                    series(pathX, path.map { it.expected })
                    series(pathX, path.map { it.low })
                    series(pathX, path.map { it.high })
                } else {
                    // No projection (a past period): keep the series count so each keeps its own line style.
                    repeat(3) { series(listOf(todayIndex), listOf(actual.last())) }
                }
                series(reference.ifEmpty { listOf(0L) })
            }
        }
    }

    val bandColor = scheme.primary.copy(alpha = 0.16f)
    val decorations = remember(path, pathX, bandColor) {
        if (path.size >= 2) listOf<Decoration>(BandDecoration(pathX, path.map { it.low }, path.map { it.high }, bandColor)) else emptyList()
    }
    val edge = scheme.primary.copy(alpha = 0.45f)
    val layer = rememberLineCartesianLayer(
        lineProvider = LineCartesianLayer.LineProvider.series(
            LineCartesianLayer.rememberLine(LineCartesianLayer.LineFill.single(Fill(scheme.primary)), LineCartesianLayer.LineStroke.Continuous(2.5.dp)),
            LineCartesianLayer.rememberLine(LineCartesianLayer.LineFill.single(Fill(scheme.primary)), LineCartesianLayer.LineStroke.Dashed(thickness = 2.dp)),
            LineCartesianLayer.rememberLine(LineCartesianLayer.LineFill.single(Fill(edge)), LineCartesianLayer.LineStroke.Continuous(1.dp)),
            LineCartesianLayer.rememberLine(LineCartesianLayer.LineFill.single(Fill(edge)), LineCartesianLayer.LineStroke.Continuous(1.dp)),
            LineCartesianLayer.rememberLine(
                LineCartesianLayer.LineFill.single(Fill(scheme.onSurfaceVariant.copy(alpha = 0.5f))),
                LineCartesianLayer.LineStroke.Continuous(1.5.dp),
            ),
        ),
    )
    val chart = rememberCartesianChart(
        layer,
        startAxis = startMoneyAxis(),
        bottomAxis = indexAxis(spacing = if (period.kind == PeriodKind.WEEK) 1 else 5) { i ->
            axisLabel(period.start.plusDays(i.toLong()), period.kind == PeriodKind.WEEK)
        },
        decorations = decorations,
    )

    val summary = if (forecast == null) {
        stringResource(R.string.dash_forecast_summary_none)
    } else {
        stringResource(
            R.string.dash_forecast_summary,
            spokenAmount(forecast.expected), spokenAmount(forecast.low), spokenAmount(forecast.high), confidenceLabel(forecast.confidence),
        )
    }

    WidgetCard(
        title = stringResource(R.string.dash_forecast_title),
        summary = summary,
        onViewAsTable = onViewAsTable,
        modifier = modifier,
    ) {
        ChartTheme { LedgrChart(chart, producer, Modifier.fillMaxWidth(), height = 200.dp) }
        Legend(
            buildList {
                add(LegendItem(scheme.primary, stringResource(R.string.dash_legend_actual)))
                if (forecast != null) {
                    add(LegendItem(scheme.primary.copy(alpha = 0.6f), stringResource(R.string.dash_legend_projection)))
                    add(LegendItem(edge, stringResource(R.string.dash_legend_range)))
                }
                add(LegendItem(scheme.onSurfaceVariant.copy(alpha = 0.5f), stringResource(R.string.dash_legend_last_period)))
            },
        )
        if (forecast == null) {
            Text(
                stringResource(R.string.dash_forecast_current_only),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.dash_forecast_line, Money.format(forecast.expected), Money.format(forecast.low), Money.format(forecast.high)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.dash_forecast_confidence, confidenceLabel(forecast.confidence)),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.warning.takeIf { forecast.confidence == Confidence.LOW } ?: scheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onExplain, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.dash_forecast_how))
                    }
                }
            }
        }
    }
}

/**
 * Shades the area between the low and high paths. `x` of each point is its day index; the pixel position uses the same
 * mapping the line layer uses (start padding plus index times the x spacing), so the band lines up with the lines.
 */
private data class BandDecoration(
    val x: List<Int>,
    val low: List<Long>,
    val high: List<Long>,
    val color: Color,
) : Decoration {
    override fun drawUnderLayers(context: CartesianDrawingContext) {
        if (x.size < 2) return
        with(context) {
            val yRange = ranges.getYRange(null)
            if (yRange.length <= 0.0 || ranges.xStep <= 0.0) return
            fun px(i: Int): Float =
                layerBounds.left + layerDimensions.startPadding -
                    scroll + ((i - ranges.minX) / ranges.xStep).toFloat() * layerDimensions.xSpacing

            fun py(v: Long): Float =
                (layerBounds.bottom - ((v - yRange.minY) / yRange.length) * layerBounds.height).toFloat()

            val band = Path().apply {
                moveTo(px(x.first()), py(high.first()))
                for (k in 1 until x.size) lineTo(px(x[k]), py(high[k]))
                for (k in x.indices.reversed()) lineTo(px(x[k]), py(low[k]))
                close()
            }
            canvas.drawPath(band, Paint().apply { color = this@BandDecoration.color; style = PaintingStyle.Fill })
        }
    }
}
