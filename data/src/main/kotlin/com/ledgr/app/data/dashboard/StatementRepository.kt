package com.ledgr.app.data.dashboard

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.ledgr.app.data.db.CategoryEntity
import com.ledgr.app.data.db.DailyFactRow
import com.ledgr.app.data.db.DatabaseManager
import com.ledgr.app.data.db.StatementQuery
import com.ledgr.app.data.db.StatementRow
import com.ledgr.app.data.db.observeStatementFacts
import com.ledgr.app.data.db.statementAll
import com.ledgr.app.data.db.statementPage
import com.ledgr.app.data.settings.SettingsRepository
import com.ledgr.app.domain.export.StatementCsv
import com.ledgr.app.domain.export.StatementCsvRow
import com.ledgr.app.domain.ledger.Contribution
import com.ledgr.app.domain.ledger.DailyFact
import com.ledgr.app.domain.ledger.Direction
import com.ledgr.app.domain.ledger.LedgerRules
import com.ledgr.app.domain.ledger.LedgerView
import com.ledgr.app.domain.ledger.Period
import com.ledgr.app.domain.ledger.ReconState
import com.ledgr.app.domain.ledger.Source
import com.ledgr.app.domain.ledger.TxnKind
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

enum class StatementSort { DATE_DESC, DATE_ASC, AMOUNT_DESC, AMOUNT_ASC }

/**
 * Everything that narrows the statement-style table (docs/07 §3). The period and account come from the Dashboard
 * controls, so the table always shows what the KPI row counts; the rest are the table's own filters.
 */
data class StatementFilter(
    val period: Period,
    val accountId: Long? = null,
    /** Narrow to one day (from a bar's day sheet). */
    val day: LocalDate? = null,
    /** A visible category together with the categories merged into it; empty means no category filter. */
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
    /** How many filters beyond period, account and sort are on (the badge on the filter button). */
    val activeCount: Int
        get() = listOf(
            day != null, categoryIds.isNotEmpty(), counterpartyId != null, kinds.isNotEmpty(), sources.isNotEmpty(),
            recon.isNotEmpty(), direction != null, minAmount != null || maxAmount != null, search.isNotBlank(),
        ).count { it }
}

/** The table footer and the day subtotals for one filter, all from `LedgerRules`. */
data class StatementSummary(
    val view: LedgerView,
    val total: Contribution,
    val daily: Map<LocalDate, Contribution>,
    val rowCount: Int,
)

/**
 * Paging, totals and CSV for the Dashboard table. The page query and the totals query share their SQL text, and the
 * totals go through `LedgerRules`, so the footer equals the KPI row when no table filter is on.
 */
@Singleton
class StatementRepository @Inject constructor(
    private val db: DatabaseManager,
    private val settings: SettingsRepository,
) {
    private val countOwnTransfers: Flow<Boolean> = settings.settings.map { it.countOwnTransfers }.distinctUntilChanged()

    fun pager(filter: StatementFilter): Flow<PagingData<StatementRow>> {
        val query = filter.toQuery()
        return Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) {
            db.requireDatabase().txnDao().statementPage(query)
        }.flow
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun summary(filter: StatementFilter): Flow<StatementSummary> {
        val query = filter.toQuery()
        return countOwnTransfers.flatMapLatest { own ->
            val view: LedgerView = if (filter.accountId == null) LedgerView.Combined(own) else LedgerView.PerAccount
            db.observe { it.txnDao().observeStatementFacts(query) }.map { rows ->
                val facts = rows.map { it.toFact() }
                StatementSummary(
                    view = view,
                    total = LedgerRules.total(facts, view),
                    daily = LedgerRules.daily(facts, view),
                    rowCount = rows.sumOf { it.count },
                )
            }
        }
    }

    /** The CSV text of every row the filter matches, in table order (the export ignores paging). */
    suspend fun exportCsv(filter: StatementFilter): String = db.withDb { database ->
        val rows = database.txnDao().statementAll(filter.toQuery())
        val accounts = database.accountDao().getAll().associateBy { it.id }
        val categories = database.categoryDao().getAll().associateBy { it.id }
        StatementCsv.render(
            rows.map { r ->
                StatementCsvRow(
                    date = r.localDate,
                    description = r.counterpartyName ?: r.rawCounterparty.orEmpty(),
                    refNo = r.refNo,
                    debit = r.amount.takeIf { r.direction == Direction.DEBIT },
                    credit = r.amount.takeIf { r.direction == Direction.CREDIT },
                    balance = r.balance,
                    account = accounts[r.accountId]?.let { "${it.nickname} ••${it.last4}" }.orEmpty(),
                    category = r.categoryId?.let { categoryName(it, categories) }.orEmpty(),
                    source = r.source.name,
                    recon = when (r.reconState) {
                        ReconState.MATCHED -> "matched"
                        ReconState.MATCHED_FUZZY -> "fuzzy"
                        else -> ""
                    },
                )
            },
        )
    }

    private fun categoryName(id: Long, all: Map<Long, CategoryEntity>): String {
        var c = all[id] ?: return ""
        var hops = 0
        while (c.mergedIntoId != null && hops++ < MAX_MERGE_HOPS) c = all[c.mergedIntoId] ?: break
        return c.name
    }

    private fun DailyFactRow.toFact() = DailyFact(
        localDate = LocalDate.parse(localDate),
        accountId = accountId,
        categoryId = categoryId,
        counterpartyId = counterpartyId,
        kind = kind,
        status = status,
        linked = linked != 0,
        amount = amount,
        count = count,
    )

    private fun StatementFilter.toQuery() = StatementQuery(
        fromDate = (day ?: period.start).toString(),
        toDate = (day ?: period.end).toString(),
        accountId = accountId,
        useCategories = categoryIds.isNotEmpty(),
        categoryIds = categoryIds.toList(),
        counterpartyId = counterpartyId,
        useKinds = kinds.isNotEmpty(),
        kinds = kinds.map { it.name },
        useSources = sources.isNotEmpty(),
        sources = sources.map { it.name },
        useRecon = recon.isNotEmpty(),
        recon = recon.map { it.name },
        direction = direction?.name,
        minAmount = minAmount,
        maxAmount = maxAmount,
        search = likePattern(search),
        sort = sort.ordinal,
    )

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_SEARCH_CHARS = 100
        const val MAX_MERGE_HOPS = 10

        /** `%text%` with LIKE's own characters escaped (the query says `ESCAPE '\'`); blank means no search. */
        fun likePattern(text: String): String? {
            val t = text.trim().take(MAX_SEARCH_CHARS)
            if (t.isEmpty()) return null
            val escaped = t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            return "%$escaped%"
        }
    }
}
