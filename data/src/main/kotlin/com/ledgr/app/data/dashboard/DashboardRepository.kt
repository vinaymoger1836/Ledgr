package com.ledgr.app.data.dashboard

import com.ledgr.app.data.db.AccountEntity
import com.ledgr.app.data.db.CategoryEntity
import com.ledgr.app.data.db.CounterpartyEntity
import com.ledgr.app.data.db.DailyFactRow
import com.ledgr.app.data.db.DatabaseManager
import com.ledgr.app.data.db.SpendEventRow
import com.ledgr.app.data.home.HomeCategory
import com.ledgr.app.data.profile.AccountRepository
import com.ledgr.app.data.profile.CategoryRepository
import com.ledgr.app.data.profile.MerchantRepository
import com.ledgr.app.data.settings.SettingsRepository
import com.ledgr.app.domain.forecast.Cadence
import com.ledgr.app.domain.forecast.Forecast
import com.ledgr.app.domain.forecast.Forecaster
import com.ledgr.app.domain.forecast.RecurringDetector
import com.ledgr.app.domain.forecast.RecurringSeries
import com.ledgr.app.domain.forecast.RecurringStatus
import com.ledgr.app.domain.forecast.SpendEvents
import com.ledgr.app.domain.ledger.Aggregations
import com.ledgr.app.domain.ledger.Bucket
import com.ledgr.app.domain.ledger.DailyFact
import com.ledgr.app.domain.ledger.Kpis
import com.ledgr.app.domain.ledger.LedgerRules
import com.ledgr.app.domain.ledger.LedgerView
import com.ledgr.app.domain.ledger.Period
import com.ledgr.app.domain.ledger.PeriodKind
import com.ledgr.app.domain.time.Ist
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** What the Dashboard is filtered by: one account or all, and a week or a month around [anchor]. */
data class DashFilter(val accountId: Long?, val kind: PeriodKind, val anchor: LocalDate) {
    val period: Period get() = Period.of(kind, anchor)
}

data class DashAccount(val id: Long, val nickname: String, val last4: String, val color: Int)

/** One day of the W2 bars. [cumulative] is null for a day after today. */
data class DayBar(val date: LocalDate, val spend: Long, val cumulative: Long?, val topMerchant: String?)

/** One donut slice. [name] is null for "Uncategorised"; [isOther] is the rolled-up tail. */
data class DashSlice(
    val categoryId: Long?,
    val name: String?,
    val colorIndex: Int,
    val spend: Long,
    val sharePerMille: Int,
    val isOther: Boolean,
)

/** One row of "Where money went" (W7), with what the avatar needs. */
data class DashMerchant(
    val counterpartyId: Long?,
    val name: String?,
    val spend: Long,
    val count: Int,
    val averageTicket: Long,
    val sharePerMille: Int,
    val nameColor: Int,
    val useCategoryIcon: Boolean,
    val image: File?,
    val category: HomeCategory?,
    /** The merchant's spend in each of the last 12 months, oldest first, for its detail sheet. */
    val monthly: List<Long>,
)

/** One recurring item in W8. */
data class DashRecurring(
    val key: String,
    val name: String,
    val cadence: Cadence,
    val amount: Long,
    val next: LocalDate,
    val last: LocalDate,
    val occurrences: Int,
    val confirmed: Boolean,
)

/** Everything the Dashboard widgets show for one filter. Money is paise. */
data class DashboardSnapshot(
    val filter: DashFilter,
    val period: Period,
    val today: LocalDate,
    /** `today` is inside [period]; only then is there a forecast. */
    val isCurrent: Boolean,
    val accounts: List<DashAccount>,
    val countOwnTransfers: Boolean,
    val kpis: Kpis,
    val forecast: Forecast?,
    val days: List<DayBar>,
    /** The previous period, day by day, for the faint ghost series (aligned by day index). */
    val ghostSpend: List<Long>,
    val ghostCumulative: List<Long>,
    val weekly: List<Bucket>,
    val monthly: List<Bucket>,
    val slices: List<DashSlice>,
    val merchants: List<DashMerchant>,
    val recurring: List<DashRecurring>,
    val categories: List<HomeCategory>,
    /** Category ids that were merged into each visible category, so a table filter also finds their old rows. */
    val categoryFamily: Map<Long, List<Long>>,
    val hasData: Boolean,
)

/**
 * The Dashboard's numbers (docs/07). One live snapshot per [DashFilter]. All counting goes through `LedgerRules` /
 * `Aggregations`, with the same `LedgerView` Home uses: combined with the persisted "count own transfers" setting for
 * All accounts, per-account for one account. So the KPI row, charts, donut, and the table footer agree with Home.
 */
@Singleton
class DashboardRepository @Inject constructor(
    private val db: DatabaseManager,
    private val settings: SettingsRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val merchants: MerchantRepository,
    private val recurringRepo: RecurringRepository,
) {
    private class Lookups(
        val counterparties: Map<Long, CounterpartyEntity>,
        val categories: Map<Long, CategoryEntity>,
        val accounts: List<AccountEntity>,
    )

    private val lookups: Flow<Lookups> = combine(
        db.observe { it.counterpartyDao().observeAll() },
        categories.categories,
        accounts.accounts,
    ) { cps, cats, accs -> Lookups(cps.associateBy { it.id }, cats.associateBy { it.id }, accs) }

    private val today: Flow<LocalDate> = flow {
        while (true) {
            val now = Ist.now()
            val day = Ist.localDate(now)
            emit(day)
            delay(Ist.startOfDay(day.plusDays(1)) - now + MIDNIGHT_SLACK_MS)
        }
    }.distinctUntilChanged()

    private val countOwnTransfers: Flow<Boolean> = settings.settings.map { it.countOwnTransfers }.distinctUntilChanged()

    fun observe(filter: DashFilter): Flow<DashboardSnapshot> {
        val period = filter.period
        // 12 months back from the end of the shown period covers the monthly trend, the 12 weeks, the 90-day forecast
        // history and the ghost period in one live query.
        val from = period.end.withDayOfMonth(1).minusMonths(TREND_MONTHS.toLong())
        val facts = db.observe { it.txnDao().observeDailyFacts(from.toString(), period.end.toString()) }
        val events = db.observe { it.txnDao().observeSpendEvents(from.toString(), maxOf(period.end, Ist.today()).toString()) }
        val base = combine(facts, events, recurringRepo.overrides) { f, e, o -> Triple(f, e, o) }
        return combine(base, lookups, countOwnTransfers, today) { (f, e, o), l, own, today ->
            build(filter, f, e, o, l, own, today)
        }
    }

    private fun build(
        filter: DashFilter,
        factRows: List<DailyFactRow>,
        eventRows: List<SpendEventRow>,
        overrides: Map<String, RecurringStatus>,
        l: Lookups,
        own: Boolean,
        today: LocalDate,
    ): DashboardSnapshot {
        val period = filter.period
        val view: LedgerView = if (filter.accountId == null) LedgerView.Combined(own) else LedgerView.PerAccount
        val merged = mergeTargets(l.categories)

        // Account filter in memory; a merged-away category counts under the category it became (ADR-011).
        val facts = factRows.asSequence()
            .filter { filter.accountId == null || it.accountId == filter.accountId }
            .map { it.toFact(merged) }
            .toList()

        val kpis = Aggregations.kpis(facts, view, period)
        val daily = Aggregations.dailySpend(facts, view, period)
        val cumulative = Aggregations.cumulative(daily)
        val topByDay = Aggregations.topMerchantByDay(facts, view, period)
        val days = period.dates().mapIndexed { i, date ->
            DayBar(
                date = date,
                spend = daily[i],
                cumulative = if (date.isAfter(today)) null else cumulative[i],
                topMerchant = topByDay[date]?.let { id -> l.counterparties[id]?.displayName },
            )
        }
        val previous = period.previous()
        val ghost = Aggregations.dailySpend(facts, view, previous)

        val trendEnd = if (today.isBefore(period.end)) maxOf(today, period.start) else period.end
        val weekly = Aggregations.weeklyBuckets(facts, view, trendEnd)
        val monthly = Aggregations.monthlyBuckets(facts, view, trendEnd)

        // Recurring: detected as of today from single payments, then the person's answers applied.
        val events = eventRows.asSequence()
            .filter { filter.accountId == null || it.accountId == filter.accountId }
            .mapNotNull { r ->
                val name = r.counterpartyId?.let(l.counterparties::get)?.displayName ?: r.rawCounterparty.orEmpty()
                SpendEvents.of(r.toEventFact(merged), r.rawCounterparty, name, view)
            }.toList()
        val series = RecurringDetector.detect(events, today, overrides)
        val isCurrent = today in period
        val dailyMap = LedgerRules.daily(facts, view).mapValues { it.value.spend }
        val forecast = if (isCurrent) Forecaster.forecast(dailyMap, series, today, period.start, period.end) else null

        val slices = Aggregations.categorySlices(facts, view, period)
        val total = kpis.spent.coerceAtLeast(1L)
        val sliceRows = slices.map { s ->
            val cat = s.categoryId?.let { l.categories[it] }
            DashSlice(
                categoryId = s.categoryId,
                name = cat?.name,
                colorIndex = cat?.color ?: 0,
                spend = s.spend,
                sharePerMille = (s.spend * 1000 / total).toInt(),
                isOther = s.isOther,
            )
        }

        val monthStarts = (TREND_MONTHS - 1 downTo 0).map { Period.of(PeriodKind.MONTH, trendEnd).step(-it) }
        val merchantRows = Aggregations.merchants(facts, view, period).map { m ->
            val cp = m.counterpartyId?.let(l.counterparties::get)
            val mine = facts.filter { it.counterpartyId == m.counterpartyId }
            DashMerchant(
                counterpartyId = m.counterpartyId,
                name = cp?.displayName,
                spend = m.spend,
                count = m.count,
                averageTicket = m.averageTicket,
                sharePerMille = m.sharePerMille,
                nameColor = cp?.color ?: 0,
                useCategoryIcon = cp?.icon == CounterpartyEntity.ICON_CATEGORY,
                image = cp?.icon?.let(merchants::imageFile),
                category = cp?.defaultCategoryId?.let { resolve(it, l.categories) },
                monthly = monthStarts.map { p -> LedgerRules.total(Aggregations.inPeriod(mine, p), view).spend },
            )
        }

        val visible = l.categories.values.filter { !it.hidden && it.mergedIntoId == null }.sortedBy { it.sortOrder }
        return DashboardSnapshot(
            filter = filter,
            period = period,
            today = today,
            isCurrent = isCurrent,
            accounts = l.accounts.filter { it.active }.map { DashAccount(it.id, it.nickname, it.last4, it.color) },
            countOwnTransfers = own,
            kpis = kpis,
            forecast = forecast,
            days = days,
            ghostSpend = ghost,
            ghostCumulative = Aggregations.cumulative(ghost),
            weekly = weekly,
            monthly = monthly,
            slices = sliceRows,
            merchants = merchantRows,
            recurring = series.map { it.toDash() },
            categories = visible.map { HomeCategory(it.id, it.name, it.icon, it.color, it.kind) },
            categoryFamily = visible.associate { c -> c.id to (listOf(c.id) + merged.filterValues { it == c.id }.keys) },
            hasData = factRows.isNotEmpty(),
        )
    }

    private fun RecurringSeries.toDash() = DashRecurring(
        key = key, name = label, cadence = cadence, amount = amount, next = next, last = last,
        occurrences = occurrences, confirmed = confirmed,
    )

    /** Merged-away category id → the category it ended up in, following chains. */
    private fun mergeTargets(all: Map<Long, CategoryEntity>): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        for (c in all.values) {
            if (c.mergedIntoId == null) continue
            var cur = c
            var hops = 0
            while (cur.mergedIntoId != null && hops++ < MAX_MERGE_HOPS) cur = all[cur.mergedIntoId] ?: break
            if (cur.id != c.id) out[c.id] = cur.id
        }
        return out
    }

    private fun resolve(id: Long, all: Map<Long, CategoryEntity>): HomeCategory? {
        var c = all[id] ?: return null
        var hops = 0
        while (c.mergedIntoId != null && hops++ < MAX_MERGE_HOPS) c = all[c.mergedIntoId] ?: break
        return HomeCategory(c.id, c.name, c.icon, c.color, c.kind)
    }

    private fun DailyFactRow.toFact(merged: Map<Long, Long>) = DailyFact(
        localDate = LocalDate.parse(localDate),
        accountId = accountId,
        categoryId = categoryId?.let { merged[it] ?: it },
        counterpartyId = counterpartyId,
        kind = kind,
        status = status,
        linked = linked != 0,
        amount = amount,
        count = count,
    )

    private fun SpendEventRow.toEventFact(merged: Map<Long, Long>) = DailyFact(
        localDate = LocalDate.parse(localDate),
        accountId = accountId,
        categoryId = categoryId?.let { merged[it] ?: it },
        counterpartyId = counterpartyId,
        kind = kind,
        status = status,
        linked = linked != 0,
        amount = amount,
    )

    private companion object {
        const val TREND_MONTHS = 12
        const val MAX_MERGE_HOPS = 10
        const val MIDNIGHT_SLACK_MS = 1_000L
    }
}
