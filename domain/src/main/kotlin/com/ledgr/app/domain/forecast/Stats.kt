package com.ledgr.app.domain.forecast

/** Integer statistics for the forecaster. Money is `Long` paise, so nothing here uses `Double` (CLAUDE.md rule 6). */
object Stats {
    /** The middle value; for an even count, the integer mean of the two middle values. Empty gives 0. */
    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /**
     * Nearest-rank percentile of an already **sorted** list: the value at rank `ceil(pct / 100 * n)`, so the result is
     * always one of the observed values and the same on every machine. Empty gives 0.
     */
    fun percentile(sorted: List<Long>, pct: Int): Long {
        if (sorted.isEmpty()) return 0L
        val rank = ((pct.toLong() * sorted.size + 99) / 100).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** Integer mean, rounded toward zero. Empty gives 0. */
    fun mean(values: List<Long>): Long = if (values.isEmpty()) 0L else values.sum() / values.size
}
