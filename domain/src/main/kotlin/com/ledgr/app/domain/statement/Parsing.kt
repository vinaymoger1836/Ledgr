package com.ledgr.app.domain.statement

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** An amount as read from one cell: [paise] is never negative; [negative] and [side] say what the cell itself showed. */
data class ParsedAmount(val paise: Long, val negative: Boolean, val side: Side?) {
    enum class Side { DEBIT, CREDIT }
}

/**
 * Amount text to paise (docs/06 §1.7): `1,23,456.00`, `(500.00)`, `-500`, `500.00 Dr`, `Cr 500`, `₹ 1,200.5`. Exact decimal
 * arithmetic only; there is no `Double` on this path (CLAUDE.md rule 6).
 */
class AmountParser(profiles: StatementProfiles) {
    private val debit = profiles.debitTokens.map { it.lowercase() }.toSet()
    private val credit = profiles.creditTokens.map { it.lowercase() }.toSet()

    /** Null for blank text or anything that is not an amount. */
    fun parse(text: String): ParsedAmount? {
        var t = text.trim().replace(' ', ' ').replace(" ", "")
        if (t.isEmpty() || t.length > 40) return null
        t = t.replace("₹", " ").replace("INR", " ", ignoreCase = true).replace("Rs.", " ", ignoreCase = true).replace("Rs", " ", ignoreCase = true).trim()
        var side: ParsedAmount.Side? = null
        // A Dr/Cr token on either end.
        val suffix = Regex("""^(.*?)[\s.]*([A-Za-z]{1,6})\.?$""").matchEntire(t)
        if (suffix != null && suffix.groupValues[2].lowercase() in debit + credit) {
            side = sideOf(suffix.groupValues[2])
            t = suffix.groupValues[1].trim()
        } else {
            val prefix = Regex("""^([A-Za-z]{1,6})\.?[\s.]*(.*)$""").matchEntire(t)
            if (prefix != null && prefix.groupValues[1].lowercase() in debit + credit) {
                side = sideOf(prefix.groupValues[1])
                t = prefix.groupValues[2].trim()
            }
        }
        var negative = false
        if (t.startsWith("(") && t.endsWith(")")) {
            negative = true
            t = t.substring(1, t.length - 1).trim()
        }
        if (t.startsWith("-")) {
            negative = !negative
            t = t.substring(1).trim()
        } else if (t.endsWith("-")) {
            negative = !negative
            t = t.dropLast(1).trim()
        } else if (t.startsWith("+")) {
            t = t.substring(1).trim()
        }
        t = t.replace(",", "").replace(" ", "")
        if (t.isEmpty() || !NUMBER.matches(t)) return null
        val paise = try {
            BigDecimal(t).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        } catch (e: ArithmeticException) {
            return null
        } catch (e: NumberFormatException) {
            return null
        }
        return ParsedAmount(paise, negative, side)
    }

    /** The direction a Dr/Cr cell names, or null. */
    fun sideOf(token: String): ParsedAmount.Side? {
        val t = token.trim().trimEnd('.').lowercase()
        return when (t) {
            in debit -> ParsedAmount.Side.DEBIT
            in credit -> ParsedAmount.Side.CREDIT
            else -> null
        }
    }

    private companion object {
        val NUMBER = Regex("""^[0-9]*\.?[0-9]*(?:[eE][+-]?[0-9]+)?$""")
    }
}

/** Date text with a `java.time` pattern from `statement_profiles.yaml` (strict, English month names, `yy` = 20yy). */
object StatementDates {
    private val formatters = ConcurrentHashMap<String, DateTimeFormatter>()
    private val TIME_TAIL = Regex("""[\sT]+\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AaPp][Mm])?\s*$""")

    private fun formatter(pattern: String): DateTimeFormatter = formatters.getOrPut(pattern) {
        // `y` is year-of-era, which a strict parse cannot resolve without an era; `u` is the plain year.
        val strict = pattern.replace('y', 'u')
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(strict).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
    }

    fun parse(text: String, pattern: String): LocalDate? {
        val t = text.trim().replace(Regex("""\s+"""), " ").replace(TIME_TAIL, "")
        if (t.isEmpty() || t.length > 30) return null
        return try {
            LocalDate.parse(t, formatter(pattern))
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** The first pattern in [candidates] that reads the most of [samples]; null if none reads any. */
    fun detect(samples: List<String>, candidates: List<String>): String? {
        val nonEmpty = samples.map { it.trim() }.filter { it.isNotEmpty() }
        var best: String? = null
        var bestCount = 0
        for (pattern in candidates) {
            val count = nonEmpty.count { parse(it, pattern) != null }
            if (count > bestCount) {
                best = pattern
                bestCount = count
            }
        }
        return best
    }
}
