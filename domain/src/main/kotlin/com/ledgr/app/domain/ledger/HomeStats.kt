package com.ledgr.app.domain.ledger

import java.time.LocalDate

/** One bar of the Home "last 7 days" chart. [spend] is paise and can be negative on a refund-only day. */
data class DaySpend(val date: LocalDate, val spend: Long)

/** Today against the usual spend on the same weekday: [percent] is signed (negative is below usual). */
data class WeekdayDelta(val percent: Int, val usualPaise: Long)

/**
 * Small pure helpers behind the Home hero and mini chart (docs/08 §2.1). They take daily spend that `LedgerRules`
 * already produced, so nothing here decides what counts as spend (CLAUDE.md rule 5). Money is `Long` paise.
 */
object HomeStats {
    /** How far back "your usual Monday" looks (docs/07 §4 uses the same 90 days for the forecast). */
    const val BASELINE_DAYS = 90L

    /** Fewer same-weekday samples than this and there is no honest baseline, so no delta is shown. */
    const val MIN_SAMPLES = 3

    /**
     * The mean spend on [today]'s weekday over the [BASELINE_DAYS] before today, today excluded. A weekday with no
     * recorded spend counts as 0, but only from the first day that has any data, so a new install is not diluted by
     * days before it existed. Null when there are fewer than [MIN_SAMPLES] such days.
     */
    fun usualWeekday(dailySpend: Map<LocalDate, Long>, today: LocalDate): Long? {
        val firstData = dailySpend.keys.minOrNull() ?: return null
        val from = maxOf(today.minusDays(BASELINE_DAYS), firstData)
        var samples = 0
        var sum = 0L
        var d = today.minusDays(7)
        while (!d.isBefore(from)) {
            samples++
            sum += dailySpend[d] ?: 0L
            d = d.minusDays(7)
        }
        return if (samples < MIN_SAMPLES) null else sum / samples
    }

    /** `(today - usual) / usual` in whole percent, truncated toward zero; null when there is no baseline to compare. */
    fun delta(todayPaise: Long, usualPaise: Long?): WeekdayDelta? {
        if (usualPaise == null || usualPaise <= 0L) return null
        return WeekdayDelta(((todayPaise - usualPaise) * 100 / usualPaise).toInt(), usualPaise)
    }

    /** Seven consecutive days ending on [end], oldest first; a day with no spend is 0. */
    fun lastSevenDays(dailySpend: Map<LocalDate, Long>, end: LocalDate): List<DaySpend> =
        (6L downTo 0L).map { back -> end.minusDays(back).let { DaySpend(it, dailySpend[it] ?: 0L) } }
}
