package com.ledgr.app.ui.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import com.ledgr.app.R
import com.ledgr.app.data.dashboard.DashboardSnapshot
import com.ledgr.app.data.dashboard.StatementSort
import com.ledgr.app.data.dashboard.StatementSummary
import com.ledgr.app.data.db.StatementRow
import com.ledgr.app.data.home.HomeCategory
import com.ledgr.app.domain.ledger.Contribution
import com.ledgr.app.domain.ledger.Direction
import com.ledgr.app.domain.ledger.LedgerRules
import com.ledgr.app.domain.ledger.LedgerView
import com.ledgr.app.domain.ledger.ReconState
import com.ledgr.app.domain.ledger.Source
import com.ledgr.app.domain.money.Money
import com.ledgr.app.ui.motion.Appear
import com.ledgr.app.ui.review.spokenAmount
import com.ledgr.app.ui.theme.LedgrTheme
import com.ledgr.app.ui.txn.kindLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_HEADING: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.ENGLISH)
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private const val RAW_TEXT_LIMIT = 400

// Column widths in dp. The table scrolls sideways as one piece (docs/08 §5: tables scroll horizontally at 200 % text).
private val W_DATE = 64.dp
private val W_DESC = 200.dp
private val W_REF = 104.dp
private val W_DEBIT = 104.dp
private val W_CREDIT = 104.dp
private val W_BALANCE = 112.dp
private val W_ACCOUNT = 128.dp
private val W_CATEGORY = 136.dp
private val W_SOURCE = 88.dp
private val W_RECON = 72.dp

/** What the table section needs from the screen. */
internal class TableCallbacks(
    val onSearch: (String) -> Unit,
    val onSort: (StatementSort) -> Unit,
    val onOpenFilters: () -> Unit,
    val onClearFilters: () -> Unit,
    val onExport: () -> Unit,
    val onOpenTxn: (Long) -> Unit,
)

/**
 * W9 (docs/07 §3): the bank-statement layout. Controls, the column header, rows grouped by day with a subtotal each, and
 * a footer. Rows are paged from Room. Subtotals and the footer come from `LedgerRules` over the same filter, so they
 * match the KPI row; own transfers are listed but marked "not counted" when the setting leaves them out.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.statementTable(
    snapshot: DashboardSnapshot,
    summary: StatementSummary?,
    rows: LazyPagingItems<StatementRow>,
    search: String,
    sort: StatementSort,
    activeFilters: Int,
    scroll: ScrollState,
    callbacks: TableCallbacks,
) {
    val categories = categoryLookup(snapshot)
    val accounts = snapshot.accounts.associate { it.id to "${it.nickname} ••${it.last4}" }

    item(key = "table-controls") {
        TableControls(search, sort, activeFilters, rows.itemCount, callbacks)
    }
    item(key = "table-columns") { ColumnHeader(scroll) }

    val refresh = rows.loadState.refresh
    if (rows.itemCount == 0) {
        item(key = "table-empty") {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                if (refresh is LoadState.Loading) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        stringResource(if (activeFilters > 0 || search.isNotBlank()) R.string.dash_table_empty_filtered else R.string.dash_table_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    items(count = rows.itemCount, key = rows.itemKey { it.id }) { index ->
        val row = rows[index] ?: return@items
        val date = LocalDate.parse(row.localDate)
        val previous = if (index > 0) rows.peek(index - 1) else null
        val groupByDay = sort == StatementSort.DATE_DESC || sort == StatementSort.DATE_ASC
        Column {
            if (groupByDay && (previous == null || previous.localDate != row.localDate)) {
                DayGroupHeader(date, snapshot.today, summary?.daily?.get(date))
            }
            val counted = summary?.view?.let {
                LedgerRules.contribution(row.kind, row.status, row.linked != 0, row.amount, it) != Contribution.ZERO
            } ?: true
            val line = @Composable {
                TableRow(
                    row = row,
                    category = row.categoryId?.let { categories[it] },
                    account = accounts[row.accountId].orEmpty(),
                    counted = counted,
                    scroll = scroll,
                    onClick = { callbacks.onOpenTxn(row.id) },
                )
            }
            if (index < 8) Appear(index) { line() } else line()
        }
    }

    item(key = "table-footer") { TableFooter(summary) }
}

@Composable
private fun TableControls(search: String, sort: StatementSort, activeFilters: Int, loaded: Int, cb: TableCallbacks) {
    var text by remember(search) { mutableStateOf(search) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.dash_table_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    cb.onSearch(it)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.dash_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = ""; cb.onSearch("") }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dash_search_clear))
                        }
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SortMenu(sort, cb.onSort)
                OutlinedButton(onClick = cb.onOpenFilters, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(
                        if (activeFilters > 0) stringResource(R.string.dash_filters_count, activeFilters) else stringResource(R.string.dash_filters),
                    )
                }
                if (activeFilters > 0) {
                    TextButton(onClick = cb.onClearFilters, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.dash_filters_clear))
                    }
                }
            }
            OutlinedButton(onClick = cb.onExport, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.dash_export_csv))
            }
        }
    }
}

@Composable
private fun SortMenu(sort: StatementSort, onSort: (StatementSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(sortLabel(sort))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in StatementSort.entries) {
                DropdownMenuItem(
                    text = { Text(sortLabel(option)) },
                    onClick = {
                        open = false
                        onSort(option)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
internal fun sortLabel(sort: StatementSort): String = stringResource(
    when (sort) {
        StatementSort.DATE_DESC -> R.string.dash_sort_newest
        StatementSort.DATE_ASC -> R.string.dash_sort_oldest
        StatementSort.AMOUNT_DESC -> R.string.dash_sort_amount_high
        StatementSort.AMOUNT_ASC -> R.string.dash_sort_amount_low
    },
)

@Composable
private fun ColumnHeader(scroll: ScrollState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .horizontalScroll(scroll)
            .padding(vertical = 8.dp)
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell(R.string.dash_col_date, W_DATE)
        HeaderCell(R.string.dash_col_description, W_DESC)
        HeaderCell(R.string.dash_col_ref, W_REF)
        HeaderCell(R.string.dash_col_debit, W_DEBIT, end = true)
        HeaderCell(R.string.dash_col_credit, W_CREDIT, end = true)
        HeaderCell(R.string.dash_col_balance, W_BALANCE, end = true)
        HeaderCell(R.string.dash_col_account, W_ACCOUNT)
        HeaderCell(R.string.dash_col_category, W_CATEGORY)
        HeaderCell(R.string.dash_col_source, W_SOURCE)
        HeaderCell(R.string.dash_col_recon, W_RECON)
    }
}

@Composable
private fun HeaderCell(label: Int, width: Dp, end: Boolean = false) {
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
        modifier = Modifier.width(width).padding(horizontal = 8.dp),
    )
}

@Composable
private fun DayGroupHeader(date: LocalDate, today: LocalDate, subtotal: Contribution?) {
    val label = when (date) {
        today -> stringResource(R.string.dash_today)
        today.minusDays(1) -> stringResource(R.string.dash_yesterday)
        else -> DAY_HEADING.format(date)
    }
    val spoken = if (subtotal == null || subtotal == Contribution.ZERO) {
        label
    } else {
        stringResource(R.string.dash_day_subtotal_spoken, label, spokenSigned(subtotal.spend), spokenSigned(subtotal.income))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = spoken
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        if (subtotal != null && subtotal != Contribution.ZERO) {
            Text(
                buildString {
                    if (subtotal.spend != 0L) append("−").append(Money.format(subtotal.spend))
                    if (subtotal.spend != 0L && subtotal.income != 0L) append("  ")
                    if (subtotal.income != 0L) append("+").append(Money.format(subtotal.income))
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TableRow(
    row: StatementRow,
    category: HomeCategory?,
    account: String,
    counted: Boolean,
    scroll: ScrollState,
    onClick: () -> Unit,
) {
    val colors = LedgrTheme.colors
    val isDebit = row.direction == Direction.DEBIT
    val title = row.counterpartyName ?: row.rawCounterparty.orEmpty().ifBlank { stringResource(kindLabel(row.kind)) }
    val date = LocalDate.parse(row.localDate)
    var expanded by remember(row.id) { mutableStateOf(false) }
    val spoken = stringResource(
        R.string.dash_row_spoken,
        stringResource(if (isDebit) R.string.dash_spoken_spent else R.string.dash_spoken_received),
        spokenAmount(row.amount),
        title,
        category?.name ?: stringResource(R.string.dash_uncategorised),
        SHORT_DATE.format(date),
        account,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.dash_row_open), onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Row(
            modifier = Modifier.horizontalScroll(scroll).heightIn(min = 48.dp).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BodyCell(SHORT_DATE.format(date), W_DATE)
            Column(modifier = Modifier.width(W_DESC).padding(horizontal = 8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!counted) {
                    Text(
                        stringResource(R.string.dash_not_counted),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            BodyCell(row.refNo.orEmpty(), W_REF)
            // Debit and credit carry a − or + sign as well as a colour (docs/07 §3).
            AmountCell(if (isDebit) "−" + Money.format(row.amount) else "", W_DEBIT, colors.spend)
            AmountCell(if (!isDebit) "+" + Money.format(row.amount) else "", W_CREDIT, colors.income)
            AmountCell(
                row.balance?.let { (if (row.balanceStored == 0) "≈ " else "") + Money.format(it) }.orEmpty(),
                W_BALANCE,
                MaterialTheme.colorScheme.onSurface,
            )
            BodyCell(account, W_ACCOUNT)
            BodyCell(category?.name.orEmpty(), W_CATEGORY)
            Box(modifier = Modifier.width(W_SOURCE).padding(horizontal = 8.dp)) {
                AssistChip(onClick = {}, enabled = false, label = { Text(sourceLabel(row.source), style = MaterialTheme.typography.labelSmall) })
            }
            // The mark is text, not colour, and says its meaning aloud (docs/06 §4: reconciled rows show a check).
            val reconWords = when (row.reconState) {
                ReconState.MATCHED -> stringResource(R.string.dash_recon_matched)
                ReconState.MATCHED_FUZZY -> stringResource(R.string.dash_recon_fuzzy)
                ReconState.LEDGER_ONLY -> stringResource(R.string.dash_recon_ledger_only)
                ReconState.UNRECONCILED -> ""
            }
            Text(
                text = when (row.reconState) {
                    ReconState.MATCHED -> "✓"
                    ReconState.MATCHED_FUZZY -> "~"
                    ReconState.LEDGER_ONLY -> "!"
                    ReconState.UNRECONCILED -> ""
                },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .width(W_RECON)
                    .padding(horizontal = 8.dp)
                    .semantics { if (reconWords.isNotEmpty()) contentDescription = reconWords },
            )
        }
        Row(modifier = Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (expanded) R.string.dash_row_hide_text else R.string.dash_row_show_text), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (expanded) {
            Text(
                text = row.rawBody?.take(RAW_TEXT_LIMIT) ?: stringResource(R.string.dash_row_no_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            row.note?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun BodyCell(text: String, width: Dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width).padding(horizontal = 8.dp),
    )
}

@Composable
private fun AmountCell(text: String, width: Dp, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        maxLines = 1,
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
        modifier = Modifier.width(width).padding(horizontal = 8.dp),
    )
}

@Composable
private fun sourceLabel(source: Source): String = stringResource(
    when (source) {
        Source.SMS -> R.string.dash_source_sms
        Source.STATEMENT -> R.string.dash_source_statement
        Source.MANUAL -> R.string.dash_source_manual
    },
)

@Composable
private fun TableFooter(summary: StatementSummary?) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (summary == null) return@Column
            val t = summary.total
            val net = t.income - t.spend
            val spoken = stringResource(
                R.string.dash_footer_spoken,
                spokenSigned(t.spend), spokenSigned(t.income), spokenSigned(net),
            )
            Column(modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }) {
                Text(stringResource(R.string.dash_footer_title), style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    FooterFigure(stringResource(R.string.dash_col_debit), "−" + Money.format(t.spend), LedgrTheme.colors.spend)
                    FooterFigure(stringResource(R.string.dash_col_credit), "+" + Money.format(t.income), LedgrTheme.colors.income)
                    FooterFigure(stringResource(R.string.dash_kpi_net), (if (net > 0) "+" else "") + Money.format(net), MaterialTheme.colorScheme.onSurface)
                }
            }
            Text(
                pluralStringResource(R.plurals.dash_rows, summary.rowCount, summary.rowCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val own = (summary.view as? LedgerView.Combined)?.includeOwnTransfers == false
            if (own) {
                Text(
                    stringResource(R.string.dash_footer_transfers_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FooterFigure(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 1)
    }
}

/** Visible category for any stored id, including the ids of categories that were merged into it. */
private fun categoryLookup(snapshot: DashboardSnapshot): Map<Long, HomeCategory> {
    val byId = snapshot.categories.associateBy { it.id }
    val out = HashMap<Long, HomeCategory>()
    for ((visible, family) in snapshot.categoryFamily) {
        val cat = byId[visible] ?: continue
        for (id in family) out[id] = cat
    }
    return out
}
