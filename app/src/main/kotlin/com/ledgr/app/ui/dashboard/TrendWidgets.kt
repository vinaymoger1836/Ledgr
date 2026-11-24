package com.ledgr.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ledgr.app.R
import com.ledgr.app.data.dashboard.DashboardSnapshot
import com.ledgr.app.domain.money.Money
import com.ledgr.app.ui.review.spokenAmount
import com.ledgr.app.ui.theme.LedgrTheme
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

private val WEEK_START: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val MONTH_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
private val MONTH_LONG: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

/** W4 (docs/07): the last 12 weeks as bars with their 4-week moving average. */
@Composable
internal fun WeeklyTrendWidget(snapshot: DashboardSnapshot, onViewAsTable: () -> Unit, modifier: Modifier = Modifier) {
    val weeks = snapshot.weekly
    val scheme = MaterialTheme.colorScheme
    val colors = LedgrTheme.colors
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(weeks) {
        producer.runTransaction {
            columnModel { series(weeks.map { it.spend }) }
            lineModel { series(weeks.map { it.movingAverage }) }
        }
    }
    val columns = ColumnCartesianLayer.ColumnProvider.series(chartColumn(colors.spend.copy(alpha = 0.75f), 12.dp))
    val average = rememberLineCartesianLayer(
        lineProvider = LineCartesianLayer.LineProvider.series(
            LineCartesianLayer.rememberLine(
                LineCartesianLayer.LineFill.single(Fill(scheme.primary)),
                LineCartesianLayer.LineStroke.Continuous(2.dp),
            ),
        ),
    )
    val chart = rememberCartesianChart(
        rememberColumnCartesianLayer(columnProvider = columns),
        average,
        startAxis = startMoneyAxis(),
        bottomAxis = indexAxis(spacing = 2) { i -> weeks.getOrNull(i)?.start?.let(WEEK_START::format).orEmpty() },
    )
    val latest = weeks.lastOrNull()
    val summary = if (latest == null || weeks.all { it.spend == 0L }) {
        stringResource(R.string.dash_weekly_summary_none)
    } else {
        stringResource(R.string.dash_weekly_summary, spokenAmount(latest.spend), spokenAmount(latest.movingAverage))
    }
    WidgetCard(
        title = stringResource(R.string.dash_weekly_title),
        summary = summary,
        onViewAsTable = onViewAsTable,
        modifier = modifier,
    ) {
        ChartTheme { LedgrChart(chart, producer, Modifier.fillMaxWidth(), height = 180.dp) }
        Legend(
            listOf(
                LegendItem(colors.spend.copy(alpha = 0.75f), stringResource(R.string.dash_legend_weekly)),
                LegendItem(scheme.primary, stringResource(R.string.dash_legend_average)),
            ),
        )
    }
}

/** W5 (docs/07): the last 12 months, spend against income. Pressing a month shows it; "Open month" jumps the period. */
@Composable
internal fun MonthlyTrendWidget(
    snapshot: DashboardSnapshot,
    onViewAsTable: () -> Unit,
    onOpenMonth: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val months = snapshot.monthly
    val colors = LedgrTheme.colors
    val scheme = MaterialTheme.colorScheme
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(months) {
        producer.runTransaction {
            columnModel {
                series(months.map { it.spend })
                series(months.map { it.income })
            }
        }
    }
    var selected by remember(months) { mutableStateOf<Int?>(null) }
    val tooltip = rememberTooltipMarker { i ->
        months.getOrNull(i)?.let { MONTH_SHORT.format(it.start) + " · " + Money.formatCompact(it.spend) + " / " + Money.formatCompact(it.income) }.orEmpty()
    }
    val listener = remember { selectionListener { selected = it } }
    val columns = ColumnCartesianLayer.ColumnProvider.series(chartColumn(colors.spend, 7.dp), chartColumn(colors.income, 7.dp))
    val chart = rememberCartesianChart(
        rememberColumnCartesianLayer(columnProvider = columns),
        startAxis = startMoneyAxis(),
        bottomAxis = indexAxis(spacing = 1) { i -> months.getOrNull(i)?.start?.let(MONTH_SHORT::format).orEmpty() },
        marker = tooltip,
        markerVisibilityListener = listener,
    )
    val last = months.lastOrNull()
    val summary = if (last == null) {
        stringResource(R.string.dash_monthly_summary_none)
    } else {
        stringResource(R.string.dash_monthly_summary, MONTH_LONG.format(last.start), spokenAmount(last.spend), spokenAmount(last.income))
    }
    WidgetCard(
        title = stringResource(R.string.dash_monthly_title),
        summary = summary,
        onViewAsTable = onViewAsTable,
        modifier = modifier,
    ) {
        ChartTheme { LedgrChart(chart, producer, Modifier.fillMaxWidth(), height = 180.dp) }
        Legend(
            listOf(
                LegendItem(colors.spend, stringResource(R.string.dash_legend_spent)),
                LegendItem(colors.income, stringResource(R.string.dash_legend_income)),
            ),
        )
        val month = selected?.let { months.getOrNull(it) }
        if (month == null) {
            Text(stringResource(R.string.dash_monthly_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.dash_monthly_selected, MONTH_LONG.format(month.start), Money.format(month.spend), Money.format(month.income)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onOpenMonth(month.start) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.dash_open_month))
                }
            }
        }
    }
}
