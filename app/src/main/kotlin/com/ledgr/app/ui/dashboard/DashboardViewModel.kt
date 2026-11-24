package com.ledgr.app.ui.dashboard

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.ledgr.app.data.dashboard.DashFilter
import com.ledgr.app.data.dashboard.DashboardRepository
import com.ledgr.app.data.dashboard.DashboardSnapshot
import com.ledgr.app.data.dashboard.RecurringRepository
import com.ledgr.app.data.dashboard.StatementFilter
import com.ledgr.app.data.dashboard.StatementRepository
import com.ledgr.app.data.dashboard.StatementSort
import com.ledgr.app.data.dashboard.StatementSummary
import com.ledgr.app.data.db.StatementRow
import com.ledgr.app.data.settings.SettingsRepository
import com.ledgr.app.domain.forecast.RecurringStatus
import com.ledgr.app.domain.ledger.Direction
import com.ledgr.app.domain.ledger.Period
import com.ledgr.app.domain.ledger.PeriodKind
import com.ledgr.app.domain.ledger.ReconState
import com.ledgr.app.domain.ledger.Source
import com.ledgr.app.domain.ledger.TxnKind
import com.ledgr.app.domain.time.Ist
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The charts that have a "View as table" (docs/08 §5). */
enum class ChartId { SPEND, FORECAST, WEEKLY, MONTHLY, CATEGORIES, MERCHANTS }

sealed interface DashSheet {
    data class ChartTable(val chart: ChartId) : DashSheet
    data object ForecastExplanation : DashSheet
    data class Merchant(val counterpartyId: Long?) : DashSheet
    data object Filters : DashSheet
}

sealed interface DashMessage {
    data class Exported(val rows: Int) : DashMessage
    data object ExportFailed : DashMessage
}

/** The table's own filters; period and account come from the Dashboard controls. */
data class TableFilters(
    val day: LocalDate? = null,
    val categoryIds: Set<Long> = emptySet(),
    val counterpartyId: Long? = null,
    val kinds: Set<TxnKind> = emptySet(),
    val sources: Set<Source> = emptySet(),
    val recon: Set<ReconState> = emptySet(),
    val direction: Direction? = null,
    val minAmount: Long? = null,
    val maxAmount: Long? = null,
    val search: String = "",
    val sort: StatementSort = StatementSort.DATE_DESC,
) {
    fun toFilter(period: Period, accountId: Long?) = StatementFilter(
        period = period, accountId = accountId, day = day, categoryIds = categoryIds, counterpartyId = counterpartyId,
        kinds = kinds, sources = sources, recon = recon, direction = direction, minAmount = minAmount,
        maxAmount = maxAmount, search = search, sort = sort,
    )
}

data class DashboardUiState(
    val ready: Boolean = false,
    /** What the controls show right now; [snapshot] may still be the previous filter's for a moment. */
    val filter: DashFilter,
    val snapshot: DashboardSnapshot? = null,
    val table: TableFilters = TableFilters(),
    val summary: StatementSummary? = null,
    val canGoNext: Boolean = false,
    /** -1 earlier, +1 later, 0 none: the direction the content slides when the period changes. */
    val direction: Int = 0,
    val sheet: DashSheet? = null,
    val message: DashMessage? = null,
    /** Bumped to ask the screen to scroll to the table (a KPI or a slice was tapped). */
    val scrollToTable: Int = 0,
)

sealed interface DashIntent {
    data class SelectAccount(val accountId: Long?) : DashIntent
    data class SelectKind(val kind: PeriodKind) : DashIntent
    data class Step(val by: Int) : DashIntent
    data class JumpToMonth(val month: LocalDate) : DashIntent
    data object ToggleOwnTransfers : DashIntent

    /** KPI tapped: show only debits or only credits (the same one again clears it). */
    data class FilterDirection(val direction: Direction?) : DashIntent
    data class FilterCategory(val categoryId: Long) : DashIntent
    data class FilterMerchant(val counterpartyId: Long) : DashIntent
    data class ShowDay(val date: LocalDate) : DashIntent
    data class ApplyFilters(val filters: TableFilters) : DashIntent
    data object ClearTableFilters : DashIntent
    data class Search(val text: String) : DashIntent
    data class Sort(val sort: StatementSort) : DashIntent

    data class Open(val sheet: DashSheet) : DashIntent
    data object CloseSheet : DashIntent
    data class SetRecurring(val key: String, val status: RecurringStatus?) : DashIntent
    data class Export(val uri: Uri) : DashIntent
    data object MessageShown : DashIntent
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val saved: SavedStateHandle,
    private val settings: SettingsRepository,
    dashboard: DashboardRepository,
    private val statements: StatementRepository,
    private val recurring: RecurringRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val filter = MutableStateFlow(restoreFilter())
    private val table = MutableStateFlow(TableFilters())
    private val local = MutableStateFlow(Local())

    private data class Local(
        val direction: Int = 0,
        val sheet: DashSheet? = null,
        val message: DashMessage? = null,
        val scrollTick: Int = 0,
    )

    init {
        // First visit: open on the month Home is showing (docs/07 §5 "both read the same aggregates").
        if (saved.get<String>(KEY_ANCHOR) == null) {
            viewModelScope.launch {
                val month = settings.settings.first().selectedMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
                if (month != null && !month.isAfter(YearMonth.from(Ist.today())) && saved.get<String>(KEY_ANCHOR) == null) {
                    filter.update { it.copy(anchor = anchorFor(month)) }
                }
            }
        }
    }

    private val snapshot: Flow<DashboardSnapshot> = filter.flatMapLatest { dashboard.observe(it) }

    private val statementFilter: Flow<StatementFilter> =
        combine(filter, table) { f, t -> t.toFilter(f.period, f.accountId) }.distinctUntilChanged()

    /** The table's rows. Cached in the ViewModel so a rotation does not reload the page. */
    val rows: Flow<PagingData<StatementRow>> =
        statementFilter.debounce(SEARCH_DEBOUNCE_MS).flatMapLatest { statements.pager(it) }.cachedIn(viewModelScope)

    private val summary: Flow<StatementSummary> =
        statementFilter.debounce(SEARCH_DEBOUNCE_MS).flatMapLatest { statements.summary(it) }

    val state: StateFlow<DashboardUiState> = combine(filter, snapshot, summary, table, local) { f, snap, sum, t, l ->
        DashboardUiState(
            ready = true,
            filter = f,
            snapshot = snap,
            table = t,
            summary = sum,
            canGoNext = !f.period.next().start.isAfter(Ist.today()),
            direction = l.direction,
            sheet = l.sheet,
            message = l.message,
            scrollToTable = l.scrollTick,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState(filter = filter.value))

    fun onIntent(intent: DashIntent) {
        when (intent) {
            is DashIntent.SelectAccount -> change(direction = 0) { it.copy(accountId = intent.accountId) }
            is DashIntent.SelectKind -> change(direction = 0) { f ->
                // Switching between week and month keeps "today" in view when the current period holds it.
                val anchor = if (Ist.today() in f.period) Ist.today() else f.anchor
                f.copy(kind = intent.kind, anchor = anchor)
            }
            is DashIntent.Step -> {
                val next = filter.value.period.step(intent.by)
                if (!next.start.isAfter(Ist.today())) change(direction = intent.by.coerceIn(-1, 1)) { it.copy(anchor = next.start) }
            }
            is DashIntent.JumpToMonth -> change(direction = 0) {
                it.copy(kind = PeriodKind.MONTH, anchor = intent.month.withDayOfMonth(1))
            }
            DashIntent.ToggleOwnTransfers -> viewModelScope.launch {
                settings.setCountOwnTransfers(!settings.settings.first().countOwnTransfers)
            }
            is DashIntent.FilterDirection -> {
                table.update { it.copy(direction = if (it.direction == intent.direction) null else intent.direction) }
                scrollToTable()
            }
            is DashIntent.FilterCategory -> {
                val family = state.value.snapshot?.categoryFamily?.get(intent.categoryId) ?: listOf(intent.categoryId)
                table.update { it.copy(categoryIds = family.toSet()) }
                scrollToTable()
            }
            is DashIntent.FilterMerchant -> {
                table.update { it.copy(counterpartyId = intent.counterpartyId) }
                local.update { it.copy(sheet = null) }
                scrollToTable()
            }
            is DashIntent.ShowDay -> {
                table.update { it.copy(day = intent.date) }
                local.update { it.copy(sheet = null) }
                scrollToTable()
            }
            is DashIntent.ApplyFilters -> {
                table.update { intent.filters.copy(search = it.search, sort = it.sort) }
                local.update { it.copy(sheet = null) }
            }
            DashIntent.ClearTableFilters -> table.update { TableFilters(sort = it.sort) }
            is DashIntent.Search -> table.update { it.copy(search = intent.text.take(MAX_SEARCH)) }
            is DashIntent.Sort -> table.update { it.copy(sort = intent.sort) }
            is DashIntent.Open -> local.update { it.copy(sheet = intent.sheet) }
            DashIntent.CloseSheet -> local.update { it.copy(sheet = null) }
            is DashIntent.SetRecurring -> viewModelScope.launch {
                when (intent.status) {
                    RecurringStatus.CONFIRMED -> recurring.confirm(intent.key)
                    RecurringStatus.DISMISSED -> recurring.dismiss(intent.key)
                    null -> recurring.clear(intent.key)
                }
            }
            is DashIntent.Export -> export(intent.uri)
            DashIntent.MessageShown -> local.update { it.copy(message = null) }
        }
    }

    private fun change(direction: Int, transform: (DashFilter) -> DashFilter) {
        val next = transform(filter.value)
        filter.value = next
        saved[KEY_KIND] = next.kind.name
        saved[KEY_ANCHOR] = next.anchor.toString()
        saved[KEY_ACCOUNT] = next.accountId ?: NO_ACCOUNT
        // A "show this day" or any other table filter scoped to the old period would now show nothing.
        table.update { it.copy(day = null) }
        local.update { it.copy(direction = direction) }
    }

    private fun scrollToTable() = local.update { it.copy(scrollTick = it.scrollTick + 1) }

    /** Writes the filtered rows (not just the loaded page) to the file the person chose (SAF, no permission needed). */
    private fun export(uri: Uri) {
        viewModelScope.launch {
            val filter = statementFilterNow()
            val ok = runCatching {
                val csv = statements.exportCsv(filter)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                        ?: error("no output stream")
                }
            }.isSuccess
            local.update {
                it.copy(message = if (ok) DashMessage.Exported(state.value.summary?.rowCount ?: 0) else DashMessage.ExportFailed)
            }
        }
    }

    private fun statementFilterNow(): StatementFilter = table.value.toFilter(filter.value.period, filter.value.accountId)

    private fun restoreFilter(): DashFilter {
        val kind = saved.get<String>(KEY_KIND)?.let { n -> PeriodKind.entries.firstOrNull { it.name == n } } ?: PeriodKind.MONTH
        val anchor = saved.get<String>(KEY_ANCHOR)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: Ist.today()
        val account = saved.get<Long>(KEY_ACCOUNT)?.takeIf { it != NO_ACCOUNT }
        return DashFilter(account, kind, anchor)
    }

    /** The anchor for a past month is its first day; the current month keeps today so a week view opens on this week. */
    private fun anchorFor(month: YearMonth): LocalDate =
        if (month == YearMonth.from(Ist.today())) Ist.today() else month.atDay(1)

    private companion object {
        const val KEY_KIND = "kind"
        const val KEY_ANCHOR = "anchor"
        const val KEY_ACCOUNT = "account"
        const val NO_ACCOUNT = -1L
        const val SEARCH_DEBOUNCE_MS = 250L
        const val MAX_SEARCH = 100
    }
}
