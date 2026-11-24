package com.ledgr.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ledgr.app.R
import com.ledgr.app.data.dashboard.DashboardSnapshot
import com.ledgr.app.domain.ledger.PeriodKind
import com.ledgr.app.domain.money.Money
import com.ledgr.app.ui.review.spokenAmount
import com.ledgr.app.ui.theme.LedgrTheme
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_TOOLTIP: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val DAY_FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

/**
 * W2 (docs/07): daily bars with the running total on a second scale, and the previous period as a faint dashed ghost.
 * Pressing or dragging across the bars shows a tooltip (date, amount, top merchant); the chosen day stays under the
 * chart with a "Show in table" button, since a tooltip vanishes when the finger lifts and cannot be reached by TalkBack.
 */
@Composable
internal fun SpendBarsWidget(
    snapshot: DashboardSnapshot,
    onViewAsTable: () -> Unit,
    onShowDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val days = snapshot.days
    val colors = LedgrTheme.colors
    val scheme = MaterialTheme.colorScheme
    val isWeek = snapshot.period.kind == PeriodKind.WEEK

    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(days, snapshot.ghostSpend) {
        producer.runTransaction {
            columnModel { series(days.map { it.spend }) }
            lineModel { series(snapshot.ghostSpend.ifEmpty { listOf(0L) }) }
            lineModel { series(days.mapNotNull { it.cumulative }.ifEmpty { listOf(0L) }) }
        }
    }

    var selected by remember(days) { mutableStateOf<Int?>(null) }
    val tooltip = rememberTooltipMarker { i -> days.getOrNull(i)?.let { tooltipText(it.date, it.spend, it.topMerchant) }.orEmpty() }
    val listener = remember { selectionListener { selected = it } }

    val columns = ColumnCartesianLayer.ColumnProvider.series(chartColumn(colors.spend, if (isWeek) 18.dp else 6.dp))
    val ghost = rememberLineCartesianLayer(
        lineProvider = LineCartesianLayer.LineProvider.series(
            LineCartesianLayer.rememberLine(
                fill = LineCartesianLayer.LineFill.single(Fill(scheme.onSurfaceVariant.copy(alpha = 0.55f))),
                stroke = LineCartesianLayer.LineStroke.Dashed(thickness = 1.5.dp),
            ),
        ),
    )
    val running = rememberLineCartesianLayer(
        lineProvider = LineCartesianLayer.LineProvider.series(
            LineCartesianLayer.rememberLine(
                fill = LineCartesianLayer.LineFill.single(Fill(scheme.primary)),
                stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.dp),
            ),
        ),
        verticalAxisPosition = Axis.Position.Vertical.End,
    )
    val chart = rememberCartesianChart(
        rememberColumnCartesianLayer(columnProvider = columns),
        ghost,
        running,
        startAxis = startMoneyAxis(),
        endAxis = endMoneyAxis(),
        bottomAxis = indexAxis(spacing = if (isWeek) 1 else 5) { i -> axisLabel(days.getOrNull(i)?.date, isWeek) },
        marker = tooltip,
        markerVisibilityListener = listener,
    )

    val peak = days.maxByOrNull { it.spend }?.takeIf { it.spend > 0 }
    val summary = if (peak == null) {
        stringResource(R.string.dash_spend_summary_none)
    } else {
        stringResource(R.string.dash_spend_summary_peak, DAY_FULL.format(peak.date), spokenAmount(peak.spend))
    }

    WidgetCard(
        title = stringResource(R.string.dash_spend_title),
        summary = summary,
        onViewAsTable = onViewAsTable,
        modifier = modifier,
    ) {
        ChartTheme {
            LedgrChart(chart, producer, Modifier.fillMaxWidth(), height = 200.dp)
        }
        Legend(
            listOf(
                LegendItem(colors.spend, stringResource(R.string.dash_legend_daily)),
                LegendItem(scheme.primary, stringResource(R.string.dash_legend_running)),
                LegendItem(scheme.onSurfaceVariant.copy(alpha = 0.55f), stringResource(R.string.dash_legend_previous)),
            ),
        )
        val day = selected?.let { days.getOrNull(it) }
        if (day == null) {
            Text(
                stringResource(R.string.dash_spend_hint),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    tooltipText(day.date, day.spend, day.topMerchant),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onShowDay(day.date) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.dash_show_in_table))
                }
            }
        }
    }
}

private fun tooltipText(date: LocalDate, spend: Long, top: String?): String =
    DAY_TOOLTIP.format(date) + " · " + Money.format(spend) + (top?.let { " · $it" } ?: "")

internal fun axisLabel(date: LocalDate?, isWeek: Boolean): String = when {
    date == null -> ""
    isWeek -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)
    else -> date.dayOfMonth.toString()
}

internal data class LegendItem(val color: Color, val label: String)

/** A small colour key. The labels carry the meaning, so colour is never the only cue (docs/08 §5). */
@Composable
internal fun Legend(items: List<LegendItem>, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (item in items) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(modifier = Modifier.size(10.dp).background(item.color, CircleShape))
                Text(item.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
