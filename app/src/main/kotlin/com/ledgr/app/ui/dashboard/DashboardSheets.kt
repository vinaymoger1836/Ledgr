package com.ledgr.app.ui.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ledgr.app.R
import com.ledgr.app.data.dashboard.DashMerchant
import com.ledgr.app.data.dashboard.DashboardSnapshot
import com.ledgr.app.domain.forecast.Forecast
import com.ledgr.app.domain.ledger.Direction
import com.ledgr.app.domain.ledger.PeriodKind
import com.ledgr.app.domain.ledger.ReconState
import com.ledgr.app.domain.ledger.Source
import com.ledgr.app.domain.ledger.TxnKind
import com.ledgr.app.domain.money.Money
import com.ledgr.app.ui.profile.MerchantAvatar
import com.ledgr.app.ui.review.spokenAmount
import com.ledgr.app.ui.txn.kindLabel
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val WEEK_OF: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

/** The numbers behind a chart, as rows of text (docs/08 §5 "View as table"). */
private class ChartTableData(val title: Int, val headers: List<String>, val rows: List<List<String>>)

@Composable
private fun chartTableData(chart: ChartId, s: DashboardSnapshot): ChartTableData = when (chart) {
    ChartId.SPEND -> ChartTableData(
        R.string.dash_spend_title,
        listOf(stringResource(R.string.dash_col_date), stringResource(R.string.dash_kpi_spent), stringResource(R.string.dash_legend_running)),
        s.days.map { listOf(DAY_SHORT.format(it.date), Money.format(it.spend), it.cumulative?.let(Money::format).orEmpty()) },
    )
    ChartId.FORECAST -> {
        val f = s.forecast
        val actual = s.days.associate { it.date to it.cumulative }
        ChartTableData(
            R.string.dash_forecast_title,
            listOf(
                stringResource(R.string.dash_col_date), stringResource(R.string.dash_legend_actual),
                stringResource(R.string.dash_legend_projection), stringResource(R.string.dash_forecast_low), stringResource(R.string.dash_forecast_high),
            ),
            if (f == null) {
                s.days.filter { it.cumulative != null }.map { listOf(DAY_SHORT.format(it.date), Money.format(it.cumulative ?: 0L), "", "", "") }
            } else {
                s.days.map { d ->
                    val p = f.path.firstOrNull { it.date == d.date }
                    listOf(
                        DAY_SHORT.format(d.date), actual[d.date]?.let(Money::format).orEmpty(),
                        p?.expected?.let(Money::format).orEmpty(), p?.low?.let(Money::format).orEmpty(), p?.high?.let(Money::format).orEmpty(),
                    )
                }
            },
        )
    }
    ChartId.WEEKLY -> ChartTableData(
        R.string.dash_weekly_title,
        listOf(stringResource(R.string.dash_col_week_of), stringResource(R.string.dash_kpi_spent), stringResource(R.string.dash_legend_average)),
        s.weekly.map { listOf(WEEK_OF.format(it.start), Money.format(it.spend), Money.format(it.movingAverage)) },
    )
    ChartId.MONTHLY -> ChartTableData(
        R.string.dash_monthly_title,
        listOf(stringResource(R.string.dash_col_month), stringResource(R.string.dash_kpi_spent), stringResource(R.string.dash_kpi_income)),
        s.monthly.map { listOf(MONTH_YEAR.format(it.start), Money.format(it.spend), Money.format(it.income)) },
    )
    ChartId.CATEGORIES -> ChartTableData(
        R.string.dash_categories_title,
        listOf(stringResource(R.string.dash_col_category), stringResource(R.string.dash_col_share), stringResource(R.string.dash_kpi_spent)),
        s.slices.map { listOf(sliceName(it), percentText(it.sharePerMille), Money.format(it.spend)) },
    )
    ChartId.MERCHANTS -> ChartTableData(
        R.string.dash_merchants_title,
        listOf(
            stringResource(R.string.dash_col_merchant), stringResource(R.string.dash_col_payments),
            stringResource(R.string.dash_kpi_spent), stringResource(R.string.dash_col_average),
        ),
        s.merchants.map {
            listOf(it.name ?: stringResource(R.string.dash_unknown_merchant), it.count.toString(), Money.format(it.spend), Money.format(it.averageTicket))
        },
    )
}

/** "View as table" for a chart: the same figures, readable by TalkBack and scrollable at any font size. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChartTableSheet(chart: ChartId, snapshot: DashboardSnapshot, onDismiss: () -> Unit) {
    val data = chartTableData(chart, snapshot)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
            Text(stringResource(data.title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                for (h in data.headers) {
                    Text(h, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
            }
            HorizontalDivider()
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(data.rows) { cells ->
                    val spoken = data.headers.zip(cells).filter { it.second.isNotBlank() }.joinToString(". ") { "${it.first} ${it.second}" }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics(mergeDescendants = true) { contentDescription = spoken },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (c in cells) Text(c, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).align(Alignment.End)) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}

/** "How this is calculated" (docs/07 §4.6): every figure on the chart, in one sentence and one list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForecastSheet(forecast: Forecast?, periodKind: PeriodKind, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.dash_explain_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (forecast == null) {
                Text(stringResource(R.string.dash_explain_none), style = MaterialTheme.typography.bodyMedium)
            } else {
                val names = forecast.recurring.map { it.label }.distinct().joinToString(", ")
                val days = pluralStringResource(R.plurals.dash_days, forecast.remainingDays, forecast.remainingDays)
                val sentence = if (forecast.recurring.isEmpty()) {
                    stringResource(
                        R.string.dash_explain_sentence_no_bills,
                        Money.format(forecast.spentSoFar), Money.format(forecast.perDay), days,
                        Money.format(forecast.expected), Money.format(forecast.low), Money.format(forecast.high),
                    )
                } else {
                    stringResource(
                        R.string.dash_explain_sentence,
                        Money.format(forecast.spentSoFar), Money.format(forecast.recurringTotal), names,
                        Money.format(forecast.perDay), days,
                        Money.format(forecast.expected), Money.format(forecast.low), Money.format(forecast.high),
                    )
                }
                Text(sentence, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(
                        if (periodKind == PeriodKind.WEEK) R.string.dash_explain_week_note else R.string.dash_explain_month_note,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (forecast.recurring.isNotEmpty()) {
                    Text(stringResource(R.string.dash_explain_bills), style = MaterialTheme.typography.titleSmall)
                    for (r in forecast.recurring) {
                        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(r.label + " · " + DAY_SHORT.format(r.date), style = MaterialTheme.typography.bodyMedium)
                            Text(Money.format(r.amount), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text(stringResource(R.string.dash_explain_confidence_title, confidenceLabel(forecast.confidence)), style = MaterialTheme.typography.titleSmall)
                Text(
                    pluralStringResource(R.plurals.dash_explain_history, forecast.historyDays, forecast.historyDays) +
                        " " + stringResource(if (forecast.usedOverallMedian) R.string.dash_explain_median else R.string.dash_explain_weekday),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).align(Alignment.End)) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}

/** A merchant's detail (docs/07 W7): average ticket, payments, and its last 12 months. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MerchantSheet(merchant: DashMerchant, onShowInTable: () -> Unit, onDismiss: () -> Unit) {
    val name = merchant.name ?: stringResource(R.string.dash_unknown_merchant)
    val peak = merchant.monthly.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MerchantAvatar(
                    name = merchant.name.orEmpty(),
                    nameColorIndex = merchant.nameColor,
                    categoryIconKey = merchant.category?.icon.takeIf { merchant.useCategoryIcon },
                    categoryColorIndex = merchant.category?.color.takeIf { merchant.useCategoryIcon },
                    image = merchant.image,
                    size = 48.dp,
                )
                Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            }
            val spoken = stringResource(
                R.string.dash_merchant_sheet_spoken,
                spokenAmount(merchant.spend), pluralStringResource(R.plurals.dash_payments, merchant.count, merchant.count),
                spokenAmount(merchant.averageTicket),
            )
            Column(modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }) {
                Text(stringResource(R.string.dash_merchant_sheet_total, Money.format(merchant.spend)), style = MaterialTheme.typography.bodyLarge)
                Text(
                    pluralStringResource(R.plurals.dash_payments, merchant.count, merchant.count) + " · " +
                        stringResource(R.string.dash_merchant_sheet_average, Money.format(merchant.averageTicket)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(stringResource(R.string.dash_merchant_sheet_months), style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // The 12 months ending with the shown period's month, oldest first; each row is a label and a share bar.
                merchant.monthly.forEachIndexed { i, spend ->
                    val monthsBack = merchant.monthly.size - 1 - i
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (monthsBack == 0) {
                                stringResource(R.string.dash_merchant_sheet_this_month)
                            } else {
                                stringResource(R.string.dash_merchant_sheet_month_ago, monthsBack)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.size(width = 64.dp, height = 20.dp),
                        )
                        Box(modifier = Modifier.weight(1f)) { ShareBar((spend.coerceAtLeast(0L) * 1000 / peak).toInt()) }
                        Text(Money.formatCompact(spend), style = MaterialTheme.typography.labelSmall, modifier = Modifier.size(width = 56.dp, height = 20.dp))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_close)) }
                Button(onClick = onShowInTable, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.dash_show_in_table)) }
            }
        }
    }
}

private val FILTER_KINDS = listOf(
    TxnKind.EXPENSE, TxnKind.INCOME, TxnKind.REFUND, TxnKind.SELF_TRANSFER_OUT, TxnKind.SELF_TRANSFER_IN, TxnKind.CARD_BILL_PAYMENT,
)

/** The table's filters (docs/07 §3): category, merchant, kind, source, recon state, direction and amount range. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun FiltersSheet(
    snapshot: DashboardSnapshot,
    current: TableFilters,
    onApply: (TableFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(current) }
    var min by remember { mutableStateOf(current.minAmount?.let { Money.format(it).removePrefix("₹") }.orEmpty()) }
    var max by remember { mutableStateOf(current.maxAmount?.let { Money.format(it).removePrefix("₹") }.orEmpty()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.dash_filters_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })

            FilterGroup(R.string.dash_filter_direction) {
                Direction.entries.forEach { d ->
                    ChoiceChip(
                        selected = draft.direction == d,
                        label = stringResource(if (d == Direction.DEBIT) R.string.dash_col_debit else R.string.dash_col_credit),
                        onClick = { draft = draft.copy(direction = if (draft.direction == d) null else d) },
                    )
                }
            }
            FilterGroup(R.string.dash_filter_kind) {
                FILTER_KINDS.forEach { k ->
                    ChoiceChip(
                        selected = k in draft.kinds,
                        label = stringResource(kindLabel(k)),
                        onClick = { draft = draft.copy(kinds = draft.kinds.toggle(k)) },
                    )
                }
            }
            FilterGroup(R.string.dash_filter_source) {
                Source.entries.forEach { s ->
                    ChoiceChip(
                        selected = s in draft.sources,
                        label = stringResource(
                            when (s) {
                                Source.SMS -> R.string.dash_source_sms
                                Source.STATEMENT -> R.string.dash_source_statement
                                Source.MANUAL -> R.string.dash_source_manual
                            },
                        ),
                        onClick = { draft = draft.copy(sources = draft.sources.toggle(s)) },
                    )
                }
            }
            FilterGroup(R.string.dash_filter_recon) {
                listOf(ReconState.MATCHED to R.string.dash_recon_matched, ReconState.MATCHED_FUZZY to R.string.dash_recon_fuzzy, ReconState.UNRECONCILED to R.string.dash_recon_none)
                    .forEach { (state, label) ->
                        ChoiceChip(
                            selected = state in draft.recon,
                            label = stringResource(label),
                            onClick = { draft = draft.copy(recon = draft.recon.toggle(state)) },
                        )
                    }
            }
            if (snapshot.categories.isNotEmpty()) {
                FilterGroup(R.string.dash_filter_category) {
                    snapshot.categories.forEach { c ->
                        val family = snapshot.categoryFamily[c.id] ?: listOf(c.id)
                        ChoiceChip(
                            selected = draft.categoryIds.isNotEmpty() && draft.categoryIds == family.toSet(),
                            label = c.name,
                            onClick = { draft = draft.copy(categoryIds = if (draft.categoryIds == family.toSet()) emptySet() else family.toSet()) },
                        )
                    }
                }
            }
            val merchants = snapshot.merchants.filter { it.counterpartyId != null }
            if (merchants.isNotEmpty()) {
                FilterGroup(R.string.dash_filter_merchant) {
                    merchants.forEach { m ->
                        val id = m.counterpartyId ?: return@forEach
                        ChoiceChip(
                            selected = draft.counterpartyId == id,
                            label = m.name.orEmpty(),
                            onClick = { draft = draft.copy(counterpartyId = if (draft.counterpartyId == id) null else id) },
                        )
                    }
                }
            }
            Text(stringResource(R.string.dash_filter_amount), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = min,
                    onValueChange = { min = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(12) },
                    label = { Text(stringResource(R.string.dash_filter_min)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = max,
                    onValueChange = { max = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(12) },
                    label = { Text(stringResource(R.string.dash_filter_max)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                OutlinedButton(
                    onClick = { onApply(TableFilters()) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.dash_filters_clear)) }
                Button(
                    onClick = {
                        onApply(draft.copy(minAmount = Money.parsePaise(min), maxAmount = Money.parsePaise(max)))
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.dash_filters_apply)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun ChoiceChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item
