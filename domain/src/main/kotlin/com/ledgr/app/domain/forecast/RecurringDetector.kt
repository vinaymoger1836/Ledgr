package com.ledgr.app.domain.forecast

import com.ledgr.app.domain.ledger.DailyFact
import com.ledgr.app.domain.ledger.LedgerRules
import com.ledgr.app.domain.ledger.LedgerView
import java.time.LocalDate

/** One payment that counted as spend, with the key of the merchant or payee it went to. Amount is positive paise. */
data class SpendEvent(val date: LocalDate, val key: String, val label: String, val amount: Long)

/** What a person said about a detected series (docs/07 W8 "Confirm / not recurring"). */
enum class RecurringStatus { CONFIRMED, DISMISSED }

enum class Cadence { WEEKLY, MONTHLY }

/** A detected recurring payment. [events] is the run of payments the detection used. */
data class RecurringSeries(
    val key: String,
    val label: String,
    val cadence: Cadence,
    /** Median amount, paise. */
    val amount: Long,
    val intervalDays: Int,
    val last: LocalDate,
    val next: LocalDate,
    val occurrences: Int,
    val confirmed: Boolean,
    val events: List<SpendEvent>,
)

/** Turns stored transactions into [SpendEvent]s, using `LedgerRules` to decide what is spend (rule 5). */
object SpendEvents {
    /** `cp:<id>` for a resolved merchant, else `raw:<normalised text>`; null when there is nothing to group by. */
    fun key(counterpartyId: Long?, rawCounterparty: String?): String? {
        if (counterpartyId != null) return "cp:$counterpartyId"
        val norm = rawCounterparty.orEmpty().lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            .trim().split(' ').filter { it.isNotEmpty() }.joinToString(" ").take(40)
        return if (norm.isEmpty()) null else "raw:$norm"
    }

    /** One stored transaction as a single-row [DailyFact] gives one event, or null when it is not positive spend. */
    fun of(fact: DailyFact, rawCounterparty: String?, label: String, view: LedgerView): SpendEvent? {
        val spend = LedgerRules.contribution(fact, view).spend
        if (spend <= 0L) return null
        val key = key(fact.counterpartyId, rawCounterparty) ?: return null
        return SpendEvent(fact.localDate, key, label, spend)
    }
}

/**
 * Recurring detection, docs/07 §4.1. A series is a **run of the latest payments** to one counterparty:
 * at least 3 of them, each gap 30 ± 4 days (monthly) or 7 ± 1 days (weekly), every amount within ±10 % of the run's
 * median. Older history that does not fit (a price change, a gap) is left out of the run instead of voiding the series.
 * A series whose last payment is more than two intervals ago has lapsed and is dropped.
 *
 * A person's answer wins: [RecurringStatus.DISMISSED] removes a key; [RecurringStatus.CONFIRMED] accepts 2 payments
 * and any amounts (the gaps must still fit a cadence).
 */
object RecurringDetector {
    const val MIN_OCCURRENCES = 3
    const val CONFIRMED_MIN_OCCURRENCES = 2
    const val AMOUNT_TOLERANCE_PCT = 10L

    private val WEEKLY_GAP = 6..8
    private val MONTHLY_GAP = 26..34

    fun detect(
        events: List<SpendEvent>,
        today: LocalDate,
        overrides: Map<String, RecurringStatus> = emptyMap(),
    ): List<RecurringSeries> {
        val out = ArrayList<RecurringSeries>()
        for ((key, group) in events.groupBy { it.key }.toSortedMap()) {
            val status = overrides[key]
            if (status == RecurringStatus.DISMISSED) continue
            val series = detectOne(group.sortedBy { it.date.toEpochDay() }, status == RecurringStatus.CONFIRMED) ?: continue
            if (today.toEpochDay() - series.last.toEpochDay() > 2L * series.intervalDays) continue
            out += series
        }
        return out.sortedWith(compareBy({ it.next.toEpochDay() }, { it.key }))
    }

    private fun detectOne(events: List<SpendEvent>, confirmed: Boolean): RecurringSeries? {
        val minOccurrences = if (confirmed) CONFIRMED_MIN_OCCURRENCES else MIN_OCCURRENCES
        if (events.size < minOccurrences) return null
        val lastGap = gap(events[events.size - 2], events.last())
        val cadence = when (lastGap) {
            in WEEKLY_GAP -> Cadence.WEEKLY
            in MONTHLY_GAP -> Cadence.MONTHLY
            else -> return null
        }
        val band = if (cadence == Cadence.WEEKLY) WEEKLY_GAP else MONTHLY_GAP

        val run = ArrayDeque<SpendEvent>()
        run.addLast(events.last())
        for (i in events.size - 2 downTo 0) {
            val candidate = events[i]
            if (gap(candidate, run.first()) !in band) break
            if (!confirmed && !amountsFit(run.map { it.amount } + candidate.amount)) break
            run.addFirst(candidate)
        }
        if (run.size < minOccurrences) return null

        val list = run.toList()
        val intervals = list.zipWithNext { a, b -> gap(a, b).toLong() }
        val interval = Stats.median(intervals).toInt()
        val last = list.last()
        return RecurringSeries(
            key = last.key,
            label = last.label,
            cadence = cadence,
            amount = Stats.median(list.map { it.amount }),
            intervalDays = interval,
            last = last.date,
            next = last.date.plusDays(interval.toLong()),
            occurrences = list.size,
            confirmed = confirmed,
            events = list,
        )
    }

    private fun gap(a: SpendEvent, b: SpendEvent): Int = (b.date.toEpochDay() - a.date.toEpochDay()).toInt()

    private fun amountsFit(amounts: List<Long>): Boolean {
        val median = Stats.median(amounts)
        if (median <= 0L) return false
        return amounts.all { kotlin.math.abs(it - median) * 100 <= median * AMOUNT_TOLERANCE_PCT }
    }
}
