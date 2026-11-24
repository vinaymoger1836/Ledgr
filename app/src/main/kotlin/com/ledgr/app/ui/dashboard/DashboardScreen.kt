package com.ledgr.app.ui.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.ledgr.app.R
import com.ledgr.app.domain.forecast.RecurringStatus
import com.ledgr.app.ui.motion.Appear
import com.ledgr.app.ui.motion.rememberReducedMotion

private const val PERIOD_FADE_MS = 280
private const val PERIOD_FADE_FROM = 0.35f

/** Items above the table in the list (header, KPIs, 7 widgets); a KPI or slice tap scrolls to the one after them. */
private const val TABLE_ITEM_INDEX = 9

/**
 * Dashboard (docs/08 §2.2): controls, KPI row, spend bars, forecast, categories, merchants, trends, recurring and the
 * statement-style table, all for one filter. Every number comes from `DashboardRepository` / `StatementRepository`,
 * which count with `LedgerRules`, so the KPI row, charts, table footer and Home agree.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    onOpenTxn: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rows = viewModel.rows.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val tableScroll = rememberScrollState()
    val reduced = rememberReducedMotion()
    val onIntent = viewModel::onIntent

    val snackbar = remember { SnackbarHostState() }
    val message = state.message
    val messageText = when (message) {
        is DashMessage.Exported -> pluralStringResource(R.plurals.dash_export_done, message.rows, message.rows)
        DashMessage.ExportFailed -> stringResource(R.string.dash_export_failed)
        null -> null
    }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            onIntent(DashIntent.MessageShown)
        }
    }

    val snapshot = state.snapshot
    val exportName = snapshot?.let { "ledgr-statement-${it.period.start}.csv" } ?: "ledgr-statement.csv"
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) onIntent(DashIntent.Export(uri))
    }

    LaunchedEffect(state.scrollToTable) {
        if (state.scrollToTable > 0) {
            if (reduced) listState.scrollToItem(TABLE_ITEM_INDEX) else listState.animateScrollToItem(TABLE_ITEM_INDEX)
        }
    }

    // Month / week change (docs/08 §4): the content fades in; it never blocks input.
    val fade = remember { Animatable(1f) }
    val period = snapshot?.period
    LaunchedEffect(period, reduced) {
        if (reduced) {
            fade.snapTo(1f)
        } else {
            fade.snapTo(PERIOD_FADE_FROM)
            fade.animateTo(1f, tween(PERIOD_FADE_MS, easing = FastOutSlowInEasing))
        }
    }
    val content = Modifier.graphicsLayer { alpha = fade.value }

    Box(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        if (snapshot == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                stickyHeader(key = "controls") {
                    DashboardControls(
                        accounts = snapshot.accounts,
                        selectedAccount = state.filter.accountId,
                        kind = state.filter.kind,
                        period = state.filter.period,
                        canGoNext = state.canGoNext,
                        countOwnTransfers = snapshot.countOwnTransfers,
                        onAccount = { onIntent(DashIntent.SelectAccount(it)) },
                        onKind = { onIntent(DashIntent.SelectKind(it)) },
                        onStep = { onIntent(DashIntent.Step(it)) },
                        onToggleOwnTransfers = { onIntent(DashIntent.ToggleOwnTransfers) },
                    )
                }
                if (!snapshot.hasData) {
                    item(key = "empty") { EmptyDashboard(content.padding(horizontal = 16.dp)) }
                } else {
                    val side = content.padding(horizontal = 16.dp)
                    item(key = "kpis") {
                        Appear(0) {
                            KpiRow(
                                snapshot = snapshot,
                                onSpent = { onIntent(DashIntent.FilterDirection(SPENT_DIRECTION)) },
                                onIncome = { onIntent(DashIntent.FilterDirection(INCOME_DIRECTION)) },
                                onNet = { onIntent(DashIntent.FilterDirection(null)) },
                                onForecast = { onIntent(DashIntent.Open(DashSheet.ForecastExplanation)) },
                                modifier = side,
                            )
                        }
                    }
                    item(key = "spend") {
                        SpendBarsWidget(
                            snapshot = snapshot,
                            onViewAsTable = { onIntent(DashIntent.Open(DashSheet.ChartTable(ChartId.SPEND))) },
                            onShowDay = { onIntent(DashIntent.ShowDay(it)) },
                            modifier = side,
                        )
                    }
                    item(key = "forecast") {
                        ForecastWidget(
                            snapshot = snapshot,
                            onViewAsTable = { onIntent(DashIntent.Open(DashSheet.ChartTable(ChartId.FORECAST))) },
                            onExplain = { onIntent(DashIntent.Open(DashSheet.ForecastExplanation)) },
                            modifier = side,
                        )
                    }
                    item(key = "categories") {
                        CategoriesWidget(
                            snapshot = snapshot,
                            onViewAsTable = { onIntent(DashIntent.Open(DashSheet.ChartTable(ChartId.CATEGORIES))) },
                            onCategory = { slice -> slice.categoryId?.takeIf { !slice.isOther }?.let { onIntent(DashIntent.FilterCategory(it)) } },
                            modifier = side,
                        )
                    }
                    item(key = "merchants") {
                        MerchantsWidget(
                            snapshot = snapshot,
                            onViewAsTable = { onIntent(DashIntent.Open(DashSheet.ChartTable(ChartId.MERCHANTS))) },
                            onMerchant = { onIntent(DashIntent.Open(DashSheet.Merchant(it.counterpartyId))) },
                            modifier = side,
                        )
                    }
                    item(key = "weekly") {
                        WeeklyTrendWidget(
                            snapshot = snapshot,
                            onViewAsTable = { onIntent(DashIntent.Open(DashSheet.ChartTable(ChartId.WEEKLY))) },
                            modifier = side,
                        )
                    }
                    item(key = "monthly") {
                        MonthlyTrendWidget(
                            snapshot = snapshot,
                            onViewAsTable = { onIntent(DashIntent.Open(DashSheet.ChartTable(ChartId.MONTHLY))) },
                            onOpenMonth = { onIntent(DashIntent.JumpToMonth(it)) },
                            modifier = side,
                        )
                    }
                    item(key = "recurring") {
                        RecurringWidget(
                            snapshot = snapshot,
                            onConfirm = { onIntent(DashIntent.SetRecurring(it.key, RecurringStatus.CONFIRMED)) },
                            onDismiss = { onIntent(DashIntent.SetRecurring(it.key, RecurringStatus.DISMISSED)) },
                            modifier = side,
                        )
                    }
                    statementTable(
                        snapshot = snapshot,
                        summary = state.summary,
                        rows = rows,
                        search = state.table.search,
                        sort = state.table.sort,
                        activeFilters = state.table.toFilter(snapshot.period, state.filter.accountId).activeCount,
                        scroll = tableScroll,
                        callbacks = TableCallbacks(
                            onSearch = { onIntent(DashIntent.Search(it)) },
                            onSort = { onIntent(DashIntent.Sort(it)) },
                            onOpenFilters = { onIntent(DashIntent.Open(DashSheet.Filters)) },
                            onClearFilters = { onIntent(DashIntent.ClearTableFilters) },
                            onExport = { exportLauncher.launch(exportName) },
                            onOpenTxn = onOpenTxn,
                        ),
                    )
                }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (snapshot != null) {
        when (val sheet = state.sheet) {
            is DashSheet.ChartTable -> ChartTableSheet(sheet.chart, snapshot) { onIntent(DashIntent.CloseSheet) }
            DashSheet.ForecastExplanation ->
                ForecastSheet(snapshot.forecast, snapshot.period.kind) { onIntent(DashIntent.CloseSheet) }
            is DashSheet.Merchant -> snapshot.merchants.firstOrNull { it.counterpartyId == sheet.counterpartyId }?.let { m ->
                MerchantSheet(
                    merchant = m,
                    onShowInTable = { m.counterpartyId?.let { onIntent(DashIntent.FilterMerchant(it)) } },
                    onDismiss = { onIntent(DashIntent.CloseSheet) },
                )
            }
            DashSheet.Filters -> FiltersSheet(
                snapshot = snapshot,
                current = state.table,
                onApply = { onIntent(DashIntent.ApplyFilters(it)) },
                onDismiss = { onIntent(DashIntent.CloseSheet) },
            )
            null -> Unit
        }
    }
}

@Composable
private fun EmptyDashboard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dash_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.dash_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
