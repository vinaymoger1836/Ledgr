package com.ledgr.app.domain.ledger

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

enum class PeriodKind { WEEK, MONTH }

/** The Dashboard period: an ISO Monday–Sunday week or an IST calendar month, both ends inclusive (docs/05 §3). */
data class Period(val kind: PeriodKind, val start: LocalDate, val end: LocalDate) {
    val dayCount: Int get() = (end.toEpochDay() - start.toEpochDay() + 1).toInt()

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    fun step(by: Int): Period =
        of(kind, if (kind == PeriodKind.MONTH) start.plusMonths(by.toLong()) else start.plusWeeks(by.toLong()))

    fun previous(): Period = step(-1)

    fun next(): Period = step(1)

    fun dates(): List<LocalDate> = (0 until dayCount).map { start.plusDays(it.toLong()) }

    companion object {
        fun of(kind: PeriodKind, anchor: LocalDate): Period = when (kind) {
            PeriodKind.MONTH -> Period(kind, anchor.withDayOfMonth(1), YearMonth.from(anchor).atEndOfMonth())
            PeriodKind.WEEK -> {
                val monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                Period(kind, monday, monday.plusDays(6))
            }
        }
    }
}

/** Spent, income and net for a period; the KPI row, the donut centre and the table footer all show these. */
data class Kpis(val spent: Long, val income: Long) {
    val net: Long get() = income - spent
}

/** One bar of a trend chart. [movingAverage] is the mean of this and up to three earlier buckets (W4 only). */
data class Bucket(val start: LocalDate, val spend: Long, val income: Long, val movingAverage: Long = 0L)

data class CategorySlice(val categoryId: Long?, val spend: Long, val isOther: Boolean)

data class MerchantRow(
    val counterpartyId: Long?,
    val spend: Long,
    val count: Int,
    /** `spend / count`, paise. */
    val averageTicket: Long,
    /** Share of the period's spend in whole per-mille (a 31.2 % share is 312), for the share bar. */
    val sharePerMille: Int,
)

/**
 * Everything the Dashboard shows, computed from the facts of one filter. **No counting happens here**: each function
 * hands the facts to `LedgerRules`, so the KPI row, the charts, the donut and the table footer agree (docs/07, CLAUDE.md
 * rule 5). Callers filter facts by account first and pass the matching [LedgerView].
 */
object Aggregations {
    const val TOP_CATEGORIES = 7
    const val TOP_MERCHANTS = 10
    const val TREND_BUCKETS = 12
    private const val MOVING_AVERAGE_WEEKS = 4

    fun inPeriod(facts: List<DailyFact>, period: Period): List<DailyFact> = facts.filter { it.localDate in period }

    fun kpis(facts: List<DailyFact>, view: LedgerView, period: Period): Kpis {
        val c = LedgerRules.total(inPeriod(facts, period), view)
        return Kpis(spent = c.spend, income = c.income)
    }

    /** Spend for every day of [period] (0 for a quiet day), oldest first. */
    fun dailySpend(facts: List<DailyFact>, view: LedgerView, period: Period): List<Long> {
        val daily = LedgerRules.daily(inPeriod(facts, period), view)
        return period.dates().map { daily[it]?.spend ?: 0L }
    }

    /** Running total of [values]; the last element equals the sum. */
    fun cumulative(values: List<Long>): List<Long> {
        var sum = 0L
        return values.map { sum += it; sum }
    }

    /** The top merchant (by spend) for each day, for the bar tooltip. Days without positive spend are absent. */
    fun topMerchantByDay(facts: List<DailyFact>, view: LedgerView, period: Period): Map<LocalDate, Long?> {
        val out = LinkedHashMap<LocalDate, Long?>()
        for ((date, dayFacts) in inPeriod(facts, period).groupBy { it.localDate }.toSortedMap()) {
            val best = LedgerRules.byCounterparty(dayFacts, view).filterValues { it.spend > 0L }
                .entries.sortedWith(compareByDescending<Map.Entry<Long?, Contribution>> { it.value.spend }.thenBy { it.key ?: -1L })
                .firstOrNull()
            if (best != null) out[date] = best.key
        }
        return out
    }

    /**
     * Weekly buckets, 12 weeks ending with the week of [endDate], oldest first. The moving average is over the 4 weeks
     * up to and including each bucket (fewer at the start of the history, never invented).
     */
    fun weeklyBuckets(facts: List<DailyFact>, view: LedgerView, endDate: LocalDate): List<Bucket> {
        val lastWeek = Period.of(PeriodKind.WEEK, endDate)
        val periods = (TREND_BUCKETS - 1 downTo 0).map { lastWeek.step(-it) }
        val raw = periods.map { p ->
            val c = LedgerRules.total(inPeriod(facts, p), view)
            Bucket(p.start, c.spend, c.income)
        }
        return raw.mapIndexed { i, b ->
            val window = raw.subList(maxOf(0, i - MOVING_AVERAGE_WEEKS + 1), i + 1)
            b.copy(movingAverage = window.sumOf { it.spend } / window.size)
        }
    }

    /** Monthly buckets, 12 months ending with the month of [endDate], oldest first. */
    fun monthlyBuckets(facts: List<DailyFact>, view: LedgerView, endDate: LocalDate): List<Bucket> {
        val lastMonth = Period.of(PeriodKind.MONTH, endDate)
        return (TREND_BUCKETS - 1 downTo 0).map { lastMonth.step(-it) }.map { p ->
            val c = LedgerRules.total(inPeriod(facts, p), view)
            Bucket(p.start, c.spend, c.income)
        }
    }

    /**
     * The donut: the [TOP_CATEGORIES] categories with the most spend, then everything else as "Other". Categories that
     * net to zero or less (a refund bigger than the spend) go into Other, so **the slices add up to the KPI spend**.
     */
    fun categorySlices(facts: List<DailyFact>, view: LedgerView, period: Period): List<CategorySlice> {
        val byCategory = LedgerRules.byCategory(inPeriod(facts, period), view)
            .mapValues { it.value.spend }.filterValues { it != 0L }
        val ranked = byCategory.entries.filter { it.value > 0L }
            .sortedWith(compareByDescending<Map.Entry<Long?, Long>> { it.value }.thenBy { it.key ?: -1L })
        val top = ranked.take(TOP_CATEGORIES)
        val topIds = top.map { it.key }.toSet()
        val other = byCategory.filterKeys { it !in topIds }.values.sum()
        return top.map { CategorySlice(it.key, it.value, isOther = false) } +
            if (other != 0L) listOf(CategorySlice(null, other, isOther = true)) else emptyList()
    }

    /** Merchant ranking by spend. [limit] null returns every merchant (used by "View as table" and the golden check). */
    fun merchants(
        facts: List<DailyFact>,
        view: LedgerView,
        period: Period,
        limit: Int? = TOP_MERCHANTS,
    ): List<MerchantRow> {
        val inRange = inPeriod(facts, period)
        val spend = LedgerRules.byCounterparty(inRange, view)
        val counts = LedgerRules.counts(inRange, view) { it.counterpartyId }
        val total = spend.values.sumOf { it.spend }.coerceAtLeast(1L)
        val rows = spend.entries.filter { it.value.spend > 0L }
            .sortedWith(compareByDescending<Map.Entry<Long?, Contribution>> { it.value.spend }.thenBy { it.key ?: -1L })
            .map { (id, c) ->
                val n = (counts[id] ?: 1).coerceAtLeast(1)
                MerchantRow(id, c.spend, n, c.spend / n, (c.spend * 1000 / total).toInt())
            }
        return if (limit == null) rows else rows.take(limit)
    }
}
