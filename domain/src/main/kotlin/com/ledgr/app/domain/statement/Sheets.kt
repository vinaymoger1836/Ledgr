package com.ledgr.app.domain.statement

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/** Number and date helpers shared by the XLSX and XLS readers. No `Double` anywhere (CLAUDE.md rule 6). */
object SheetValues {
    private val DIGITS_15 = MathContext(15, RoundingMode.HALF_EVEN)

    /** Excel prints 15 significant digits; a stored `1234.5599999999999` is `1234.56`. Returns the plain decimal text. */
    fun plain(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return try {
            val d = BigDecimal(t).round(DIGITS_15)
            if (d.signum() == 0) "0" else d.stripTrailingZeros().toPlainString()
        } catch (e: NumberFormatException) {
            t
        }
    }

    /** The decimal text of an IEEE-754 double given as its 64 bits, computed exactly with big integers. */
    fun decimalOfBits(bits: Long): String {
        val negative = bits < 0
        val exp = ((bits ushr 52) and 0x7FF).toInt()
        val frac = bits and 0xFFFFFFFFFFFFFL
        if (exp == 0x7FF) return ""
        val mantissa = if (exp == 0) frac else frac or (1L shl 52)
        val e = if (exp == 0) -1074 else exp - 1075
        val exact = if (e >= 0) {
            BigDecimal(BigInteger.valueOf(mantissa).shiftLeft(e))
        } else {
            val k = -e
            BigDecimal(BigInteger.valueOf(mantissa).multiply(BigInteger.valueOf(5).pow(k)), k)
        }
        val signed = if (negative) exact.negate() else exact
        return plain(signed.toPlainString())
    }

    /** An RK number (BIFF8) as decimal text. */
    fun decimalOfRk(rk: Int): String {
        val base = if (rk and 2 != 0) {
            (rk shr 2).toString()
        } else {
            decimalOfBits((rk.toLong() and 0xFFFFFFFCL) shl 32)
        }
        if (rk and 1 == 0) return base
        return try {
            plain(BigDecimal(base).movePointLeft(2).toPlainString())
        } catch (e: NumberFormatException) {
            base
        }
    }

    /** `yyyy-MM-dd` for an Excel serial day number (the whole days of [serialText]); null if it is not a plausible date. */
    fun isoDateOfSerial(serialText: String, date1904: Boolean): String? {
        val whole = serialText.trim().substringBefore('.').substringBefore('E').substringBefore('e')
        val days = whole.toLongOrNull() ?: return null
        if (days < 1 || days > 2_958_465) return null
        val base = if (date1904) LocalDate.of(1904, 1, 1) else LocalDate.of(1899, 12, 30)
        return base.plusDays(days).toString()
    }

    /** True if number format [id] (or its custom [code]) shows a calendar date. Time-only formats are not dates. */
    fun isDateFormat(id: Int, code: String?): Boolean {
        if (code == null) {
            return id in 14..22 || id in 27..36 || id in 45..47 || id in 50..58
        }
        val cleaned = StringBuilder()
        var inQuote = false
        var inBracket = false
        var skip = false
        for (ch in code) {
            when {
                skip -> skip = false
                ch == '\\' || ch == '_' || ch == '*' -> skip = true
                ch == '"' -> inQuote = !inQuote
                inQuote -> {}
                ch == '[' -> inBracket = true
                ch == ']' -> inBracket = false
                inBracket -> {}
                else -> cleaned.append(ch.lowercaseChar())
            }
        }
        val s = cleaned.toString()
        return 'd' in s || 'y' in s
    }
}
