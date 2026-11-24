package com.ledgr.app.domain.forecast

import java.time.LocalDate

enum class Confidence { LOW, MEDIUM, GOOD }

/** A recurring payment expected after today and inside the period. */
data class RecurringExpected(val key: String, val label: String, val date: LocalDate, val amount: Long)

/** Cumulative spend at the end of [date] under the expected, low (P25) and high (P75) assumptions. */
data class PathPoint(val date: LocalDate, val expected: Long, val low: Long, val high: Long)

/**
 * The forecast for one period and its explanation (docs/07 §4). Every number the explanation sheet shows is a field
 * here, so the sheet cannot disagree with the chart.
 *
 * `expected = spentSoFar + recurringTotal + discretionary`, and low/high replace `discretionary` with the sum of the
 * per-weekday P25/P75.
 */
data class Forecast(
    val start: LocalDate,
    val end: LocalDate,
    val today: LocalDate,
    val spentSoFar: Long,
    val recurring: List<RecurringExpected>,
    val recurringTotal: Long,
    val remainingDays: Int,
    /** Sum of the weekday rates over the remaining days, paise. */
    val discretionary: Long,
    /** `discretionary / remainingDays`, for the sentence "about ₹420/day for 8 days". 0 when no days remain. */
    val perDay: Long,
    val expected: Long,
    val low: Long,
    val high: Long,
    val confidence: Confidence,
    /** Days from the first recorded day to today. */
    val historyDays: Int,
    /** True when under 8 weeks of history made the rate the overall daily median instead of per weekday. */
    val usedOverallMedian: Boolean,
    /** From today (cumulative so far) to [end]; what the dashed projection and the band draw. */
    val path: List<PathPoint>,
)

/**
 * Explainable forecaster, no ML (docs/07 §4, ADR-015). Pure and deterministic: `today` is passed in, money is `Long`
 * paise, percentiles are nearest-rank, and means are integer divisions.
 *
 * Input [dailySpend] is what `LedgerRules.daily` produced for the current filter, so the forecast counts spend the same
 * way the KPI row and Home do.
 */
object Forecaster {
    const val HISTORY_DAYS = 90L
    const val MIN_WEEKDAY_DAYS = 56
    private const val OUTLIER_FACTOR = 3L

    /** Null when there is no history, or [today] is outside the period (past and future periods have no forecast). */
    fun forecast(
        dailySpend: Map<LocalDate, Long>,
        series: List<RecurringSeries>,
        today: LocalDate,
        start: LocalDate,
        end: LocalDate,
    ): Forecast? {
        if (today.isBefore(start) || today.isAfter(end)) return null
        val first = dailySpend.keys.minOrNull() ?: return null

        val recurringByDate = HashMap<LocalDate, Long>()
        for (s in series) for (e in s.events) recurringByDate.merge(e.date, e.amount, Long::plus)

        // 1. Discretionary history: the 90 days before today (today is a part day), recurring payments taken out.
        val from = maxOf(today.minusDays(HISTORY_DAYS), first)
        val values = ArrayList<Long>()
        val byWeekday = List(7) { ArrayList<Long>() }
        var d = from
        val lastDay = today.minusDays(1)
        val raw = ArrayList<Pair<LocalDate, Long>>()
        while (!d.isAfter(lastDay)) {
            raw += d to maxOf(0L, (dailySpend[d] ?: 0L) - (recurringByDate[d] ?: 0L))
            d = d.plusDays(1)
        }
        // 2. Outliers: a day above 3 x P95 is capped at P95 (only when P95 is above zero).
        val p95 = Stats.percentile(raw.map { it.second }.sorted(), 95)
        for ((date, v) in raw) {
            val capped = if (p95 > 0L && v > OUTLIER_FACTOR * p95) p95 else v
            values += capped
            byWeekday[date.dayOfWeek.ordinal] += capped
        }

        val usedOverall = raw.size < MIN_WEEKDAY_DAYS
        val overall = values.sorted()
        val overallRate = Stats.median(values)
        val overallP25 = Stats.percentile(overall, 25)
        val overallP75 = Stats.percentile(overall, 75)
        val rate = LongArray(7)
        val p25 = LongArray(7)
        val p75 = LongArray(7)
        for (w in 0 until 7) {
            if (usedOverall) {
                rate[w] = overallRate; p25[w] = overallP25; p75[w] = overallP75
            } else {
                val sorted = byWeekday[w].sorted()
                rate[w] = Stats.mean(byWeekday[w])
                p25[w] = Stats.percentile(sorted, 25)
                p75[w] = Stats.percentile(sorted, 75)
            }
        }

        // 3. Recurring payments still to come in (today, end].
        val expectedRecurring = ArrayList<RecurringExpected>()
        for (s in series) {
            var date = s.next
            while (!date.isAfter(end)) {
                if (date.isAfter(today)) expectedRecurring += RecurringExpected(s.key, s.label, date, s.amount)
                date = date.plusDays(s.intervalDays.toLong())
            }
        }
        expectedRecurring.sortWith(compareBy({ it.date.toEpochDay() }, { it.key }))
        val recurringOn = expectedRecurring.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.amount } }

        // 4. Spend so far and the cumulative path to the end of the period.
        var spentSoFar = 0L
        var cursor = start
        while (!cursor.isAfter(today)) {
            spentSoFar += dailySpend[cursor] ?: 0L
            cursor = cursor.plusDays(1)
        }

        var cumExpected = spentSoFar
        var cumLow = spentSoFar
        var cumHigh = spentSoFar
        var discretionary = 0L
        var lowDisc = 0L
        var highDisc = 0L
        var remaining = 0
        val path = arrayListOf(PathPoint(today, spentSoFar, spentSoFar, spentSoFar))
        var day = today.plusDays(1)
        while (!day.isAfter(end)) {
            val w = day.dayOfWeek.ordinal
            val rec = recurringOn[day] ?: 0L
            discretionary += rate[w]; lowDisc += p25[w]; highDisc += p75[w]
            cumExpected += rate[w] + rec
            cumLow += p25[w] + rec
            cumHigh += p75[w] + rec
            remaining++
            path += PathPoint(day, cumExpected, minOf(cumExpected, cumLow), maxOf(cumExpected, cumHigh))
            day = day.plusDays(1)
        }

        val recurringTotal = expectedRecurring.sumOf { it.amount }
        val expected = spentSoFar + recurringTotal + discretionary
        // A weekday mean can sit above its own P75 on a spiky weekday; keep the band around the number it explains.
        val low = minOf(expected, spentSoFar + recurringTotal + lowDisc)
        val high = maxOf(expected, spentSoFar + recurringTotal + highDisc)
        val historyDays = (today.toEpochDay() - first.toEpochDay()).toInt()

        return Forecast(
            start = start,
            end = end,
            today = today,
            spentSoFar = spentSoFar,
            recurring = expectedRecurring,
            recurringTotal = recurringTotal,
            remainingDays = remaining,
            discretionary = discretionary,
            perDay = if (remaining == 0) 0L else discretionary / remaining,
            expected = expected,
            low = low,
            high = high,
            confidence = when {
                historyDays < 30 -> Confidence.LOW
                historyDays < 90 -> Confidence.MEDIUM
                else -> Confidence.GOOD
            },
            historyDays = historyDays,
            usedOverallMedian = usedOverall,
            path = path,
        )
    }
}
