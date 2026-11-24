package com.ledgr.app.domain.money

/**
 * Money is `Long` paise everywhere (CLAUDE.md rule 6, ADR-009). No `Double`. This is the one place that formats and
 * parses amounts.
 */
object Money {
    /** `123456` paise → `₹1,234.56`; `100000` → `₹1,000` (paise hidden when zero); Indian digit grouping. */
    fun format(paise: Long, symbol: String = "₹"): String {
        val negative = paise < 0
        // Long.MIN_VALUE has no positive counterpart; amounts that large are not real, so clamp.
        val abs = if (paise == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(paise)
        val rupees = abs / 100
        val fraction = (abs % 100).toInt()
        val body = group(rupees) + if (fraction == 0) "" else "." + fraction.toString().padStart(2, '0')
        return (if (negative) "-" else "") + symbol + body
    }

    /** Indian grouping: the last three digits, then pairs. */
    private fun group(value: Long): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits
        val head = digits.dropLast(3)
        val tail = digits.takeLast(3)
        val pairs = StringBuilder()
        var i = head.length
        while (i > 0) {
            val start = maxOf(0, i - 2)
            if (pairs.isNotEmpty()) pairs.insert(0, ',')
            pairs.insert(0, head.substring(start, i))
            i = start
        }
        return "$pairs,$tail"
    }

    /**
     * `"1,234.5"` → 123450, `"500"` → 50000, `".5"` → 50. Returns null for anything that is not a plain
     * amount (letters, more than two decimals, empty, overflow).
     */
    fun parsePaise(text: String): Long? {
        val cleaned = text.trim().replace(",", "")
        if (cleaned.isEmpty()) return null
        val dot = cleaned.indexOf('.')
        val whole = if (dot < 0) cleaned else cleaned.substring(0, dot)
        val frac = if (dot < 0) "" else cleaned.substring(dot + 1)
        if (whole.isEmpty() && frac.isEmpty()) return null
        if (!whole.all { it in '0'..'9' } || !frac.all { it in '0'..'9' } || frac.length > 2) return null
        if (whole.length > 15) return null
        val rupees = if (whole.isEmpty()) 0L else whole.toLong()
        val paise = when (frac.length) {
            0 -> 0
            1 -> frac.toInt() * 10
            else -> frac.toInt()
        }
        return rupees * 100 + paise
    }
}
