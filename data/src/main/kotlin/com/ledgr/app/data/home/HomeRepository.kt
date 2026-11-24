package com.ledgr.app.data.home

import com.ledgr.app.data.db.AccountEntity
import com.ledgr.app.data.db.CategoryEntity
import com.ledgr.app.data.db.CounterpartyEntity
import com.ledgr.app.data.db.DailyFactRow
import com.ledgr.app.data.db.DatabaseManager
import com.ledgr.app.data.db.TxnEntity
import com.ledgr.app.data.profile.AccountRepository
import com.ledgr.app.data.profile.CategoryRepository
import com.ledgr.app.data.profile.MerchantRepository
import com.ledgr.app.data.settings.SettingsRepository
import com.ledgr.app.domain.ledger.DailyFact
import com.ledgr.app.domain.ledger.DaySpend
import com.ledgr.app.domain.ledger.Direction
import com.ledgr.app.domain.ledger.HomeStats
import com.ledgr.app.domain.ledger.LedgerRules
import com.ledgr.app.domain.ledger.LedgerView
import com.ledgr.app.domain.ledger.TxnKind
import com.ledgr.app.domain.ledger.WeekdayDelta
import com.ledgr.app.domain.model.CategoryKind
import com.ledgr.app.domain.time.Ist
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** A category as Home shows it, already followed through merges (ADR-011). */
data class HomeCategory(val id: Long, val name: String, val icon: String, val color: Int, val kind: CategoryKind)

/** One merchant's spend in the selected month (the "Where it went" strip). [name] is null for "no merchant resolved". */
data class HomeMerchant(
    val counterpartyId: Long?,
    val name: String?,
    val spend: Long,
    val nameColor: Int,
    /** Show the category icon rather than the first letter (the merchant's avatar mode). */
    val useCategoryIcon: Boolean,
    val image: File?,
    val category: HomeCategory?,
)

/** One row of the recent list. */
data class HomeTxn(
    val id: Long,
    /** The merchant or payee name; blank when nothing was resolved. */
    val title: String,
    val amount: Long,
    val isDebit: Boolean,
    val kind: TxnKind,
    val occurredAt: Long,
    val localDate: LocalDate,
    val category: HomeCategory?,
    /** "HDFC ••1234" or an empty string. */
    val accountLabel: String,
    val nameColor: Int,
    val useCategoryIcon: Boolean,
    val image: File?,
    /** Still in Needs review → Check. */
    val inCheck: Boolean,
    /** `true` for a payment-type row that is not a plain expense or income (transfers, refunds). */
    val isTransferLike: Boolean,
)

/** Everything Home shows for one month. Money is paise. */
data class HomeSnapshot(
    val month: YearMonth,
    val isCurrentMonth: Boolean,
    val today: LocalDate,
    val todaySpend: Long,
    /** Today against the usual spend on this weekday; null for a past month or without enough history. */
    val weekdayDelta: WeekdayDelta?,
    val monthSpend: Long,
    val monthIncome: Long,
    val lastSeven: List<DaySpend>,
    val merchants: List<HomeMerchant>,
    val txns: List<HomeTxn>,
    val categories: List<HomeCategory>,
) {
    val hasData: Boolean get() = txns.isNotEmpty()
}

/**
 * Home's numbers (docs/07 §5, docs/08 §2.1). Everything here is a live Room query, so a scan or a new SMS re-emits
 * the snapshot (docs/12 P4: today's spend updates within 2 s). What counts as spend is decided only by [LedgerRules];
 * this class groups and orders.
 */
@Singleton
class HomeRepository @Inject constructor(
    private val db: DatabaseManager,
    private val settings: SettingsRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val merchants: MerchantRepository,
) {
    private class Lookups(
        val counterparties: Map<Long, CounterpartyEntity>,
        val categories: Map<Long, CategoryEntity>,
        val accounts: Map<Long, AccountEntity>,
    )

    private val lookups: Flow<Lookups> = combine(
        db.observe { it.counterpartyDao().observeAll() },
        categories.categories,
        accounts.accounts,
    ) { cps, cats, accs ->
        Lookups(cps.associateBy { it.id }, cats.associateBy { it.id }, accs.associateBy { it.id })
    }

    /** IST today, re-emitted just after midnight so the hero rolls over while the app stays open. */
    private val today: Flow<LocalDate> = flow {
        while (true) {
            val now = Ist.now()
            val today = Ist.localDate(now)
            emit(today)
            delay(Ist.startOfDay(today.plusDays(1)) - now + MIDNIGHT_SLACK_MS)
        }
    }.distinctUntilChanged()

    private val countOwnTransfers: Flow<Boolean> = settings.settings.map { it.countOwnTransfers }.distinctUntilChanged()

    /** Months to mark with a dot in the picker: they hold transactions or had a finished month scan. */
    val monthsWithData: Flow<Set<YearMonth>> = combine(
        db.observe { it.txnDao().observeMonthsWithData() },
        db.observe { it.scanRunDao().observeScannedMonths() },
    ) { withTxns, scanned ->
        (withTxns + scanned).mapNotNull { runCatching { YearMonth.parse(it) }.getOrNull() }.toSet()
    }

    fun observe(month: YearMonth): Flow<HomeSnapshot> {
        val monthFrom = month.atDay(1)
        val monthTo = month.atEndOfMonth()
        // The current month needs 90 days of history for "your usual Monday"; a past month only the 6 days before
        // its first day so the last-7-days chart is complete on the 1st.
        val factsFrom = if (month == YearMonth.now(Ist.ZONE)) monthFrom.minusDays(HomeStats.BASELINE_DAYS) else monthFrom.minusDays(6)
        val facts = db.observe { it.txnDao().observeDailyFacts(factsFrom.toString(), monthTo.toString()) }
        val rows = db.observe { it.txnDao().observeRange(monthFrom.toString(), monthTo.toString()) }
        return combine(facts, rows, lookups, countOwnTransfers, today) { f, r, l, own, today ->
            build(month, f, r, l, LedgerView.Combined(own), today)
        }
    }

    private fun build(
        month: YearMonth,
        factRows: List<DailyFactRow>,
        txnRows: List<TxnEntity>,
        l: Lookups,
        view: LedgerView,
        today: LocalDate,
    ): HomeSnapshot {
        val from = month.atDay(1)
        val to = month.atEndOfMonth()
        val facts = factRows.map { it.toFact() }
        val daily = LedgerRules.daily(facts, view).mapValues { it.value.spend }
        val inMonth = facts.filter { !it.localDate.isBefore(from) && !it.localDate.isAfter(to) }
        val monthTotal = LedgerRules.total(inMonth, view)
        val isCurrent = month == YearMonth.from(today)
        val todaySpend = if (isCurrent) daily[today] ?: 0L else 0L

        val perMerchant = LedgerRules.byCounterparty(inMonth, view)
            .filter { it.value.spend > 0 }
            .entries.sortedByDescending { it.value.spend }
            .take(MAX_MERCHANTS)
        val merchantRows = perMerchant.map { (id, c) ->
            val cp = id?.let(l.counterparties::get)
            val cat = cp?.defaultCategoryId?.let { resolve(it, l.categories) }
            HomeMerchant(
                counterpartyId = id,
                name = cp?.displayName,
                spend = c.spend,
                nameColor = cp?.color ?: 0,
                useCategoryIcon = cp?.icon == CounterpartyEntity.ICON_CATEGORY,
                image = cp?.icon?.let(merchants::imageFile),
                category = cat,
            )
        }

        val rows = txnRows.map { t ->
            val cp = t.counterpartyId?.let(l.counterparties::get)
            HomeTxn(
                id = t.id,
                title = cp?.displayName ?: t.rawCounterparty.orEmpty(),
                amount = t.amount,
                isDebit = t.direction == Direction.DEBIT,
                kind = t.kind,
                occurredAt = t.occurredAt,
                localDate = LocalDate.parse(t.localDate),
                category = t.categoryId?.let { resolve(it, l.categories) },
                accountLabel = l.accounts[t.accountId]?.let { "${it.nickname} ••${it.last4}" }.orEmpty(),
                nameColor = cp?.color ?: 0,
                useCategoryIcon = cp?.icon == CounterpartyEntity.ICON_CATEGORY,
                image = cp?.icon?.let(merchants::imageFile),
                inCheck = t.checkReason != null,
                isTransferLike = t.kind != TxnKind.EXPENSE && t.kind != TxnKind.INCOME,
            )
        }

        val sevenEnd = if (isCurrent) today else to
        return HomeSnapshot(
            month = month,
            isCurrentMonth = isCurrent,
            today = today,
            todaySpend = todaySpend,
            weekdayDelta = if (isCurrent) HomeStats.delta(todaySpend, HomeStats.usualWeekday(daily, today)) else null,
            monthSpend = monthTotal.spend,
            monthIncome = monthTotal.income,
            lastSeven = HomeStats.lastSevenDays(daily, sevenEnd),
            merchants = merchantRows,
            txns = rows,
            categories = l.categories.values
                .filter { !it.hidden && it.mergedIntoId == null }
                .sortedBy { it.sortOrder }
                .map { it.toHome() },
        )
    }

    /** The category a stored id now belongs to, following merges. */
    private fun resolve(id: Long, all: Map<Long, CategoryEntity>): HomeCategory? {
        var c = all[id] ?: return null
        var hops = 0
        while (c.mergedIntoId != null && hops++ < MAX_MERGE_HOPS) c = all[c.mergedIntoId] ?: break
        return c.toHome()
    }

    private fun CategoryEntity.toHome() = HomeCategory(id, name, icon, color, kind)

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

    private companion object {
        const val MAX_MERCHANTS = 10
        const val MAX_MERGE_HOPS = 10
        const val MIDNIGHT_SLACK_MS = 1_000L
    }
}
