package com.ledgr.app.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ledgr.app.R
import com.ledgr.app.data.dashboard.DashboardSnapshot
import com.ledgr.app.domain.forecast.Confidence
import com.ledgr.app.domain.forecast.Forecast
import com.ledgr.app.domain.ledger.Direction
import com.ledgr.app.domain.money.Money
import com.ledgr.app.ui.motion.rememberCountUp
import com.ledgr.app.ui.motion.rememberReducedMotion
import com.ledgr.app.ui.review.spokenAmount
import com.ledgr.app.ui.theme.LedgrTheme

/** W1 (docs/07): Spent, Income, Net and the month-end forecast with its range. Tapping one filters the table. */
@Composable
internal fun KpiRow(
    snapshot: DashboardSnapshot,
    onSpent: () -> Unit,
    onIncome: () -> Unit,
    onNet: () -> Unit,
    onForecast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = rememberReducedMotion()
    val colors = LedgrTheme.colors
    val k = snapshot.kpis
    val spent = rememberCountUp(k.spent, reduced)
    val income = rememberCountUp(k.income, reduced)
    val net = rememberCountUp(k.net, reduced)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KpiCard(
                label = stringResource(R.string.dash_kpi_spent),
                value = Money.format(spent),
                tone = colors.spend,
                spoken = stringResource(R.string.dash_kpi_spent_spoken, spokenSigned(k.spent)),
                onClickLabel = stringResource(R.string.dash_kpi_show_debits),
                onClick = onSpent,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            KpiCard(
                label = stringResource(R.string.dash_kpi_income),
                value = Money.format(income),
                tone = colors.income,
                spoken = stringResource(R.string.dash_kpi_income_spoken, spokenSigned(k.income)),
                onClickLabel = stringResource(R.string.dash_kpi_show_credits),
                onClick = onIncome,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val sign = if (k.net > 0) "+" else ""
            KpiCard(
                label = stringResource(R.string.dash_kpi_net),
                value = sign + Money.format(net),
                tone = if (k.net >= 0) colors.income else colors.spend,
                spoken = stringResource(R.string.dash_kpi_net_spoken, spokenSigned(k.net)),
                onClickLabel = stringResource(R.string.dash_kpi_show_all),
                onClick = onNet,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            ForecastCard(
                forecast = snapshot.forecast,
                isCurrent = snapshot.isCurrent,
                onClick = onForecast,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun KpiCard(
    label: String,
    value: String,
    tone: Color,
    spoken: String,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                .padding(16.dp)
                .semantics(mergeDescendants = true) { contentDescription = spoken },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, color = tone, maxLines = 1)
            footer?.invoke()
        }
    }
}

/** Month-end (or week-end) forecast with the P25–P75 range and a confidence label (docs/07 §4.5). */
@Composable
private fun ForecastCard(forecast: Forecast?, isCurrent: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.dash_kpi_forecast)
    if (forecast == null) {
        KpiCard(
            label = label,
            value = "—",
            tone = MaterialTheme.colorScheme.onSurfaceVariant,
            spoken = stringResource(R.string.dash_kpi_forecast_none_spoken),
            onClickLabel = stringResource(R.string.dash_forecast_how),
            onClick = onClick,
            modifier = modifier,
            footer = {
                Text(
                    stringResource(if (isCurrent) R.string.dash_forecast_no_history else R.string.dash_forecast_current_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        return
    }
    val confidence = confidenceLabel(forecast.confidence)
    val range = stringResource(R.string.dash_forecast_range, Money.formatCompact(forecast.low), Money.formatCompact(forecast.high))
    KpiCard(
        label = label,
        value = Money.format(forecast.expected),
        tone = MaterialTheme.colorScheme.primary,
        spoken = stringResource(
            R.string.dash_kpi_forecast_spoken,
            spokenAmount(forecast.expected), spokenAmount(forecast.low), spokenAmount(forecast.high), confidence,
        ),
        onClickLabel = stringResource(R.string.dash_forecast_how),
        onClick = onClick,
        modifier = modifier,
        footer = {
            Text(range, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.dash_forecast_confidence, confidence),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
internal fun confidenceLabel(c: Confidence): String = stringResource(
    when (c) {
        Confidence.LOW -> R.string.dash_confidence_low
        Confidence.MEDIUM -> R.string.dash_confidence_medium
        Confidence.GOOD -> R.string.dash_confidence_good
    },
)

/** "minus 450 rupees" for a negative amount, else the plain spoken amount (docs/08 §5). */
@Composable
internal fun spokenSigned(paise: Long): String =
    if (paise < 0) stringResource(R.string.dash_minus_spoken, spokenAmount(-paise)) else spokenAmount(paise)

/** Which direction a KPI tap filters the table to. */
internal val SPENT_DIRECTION = Direction.DEBIT
internal val INCOME_DIRECTION = Direction.CREDIT
