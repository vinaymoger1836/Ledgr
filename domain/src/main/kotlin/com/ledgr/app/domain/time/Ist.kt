package com.ledgr.app.domain.time

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** The app's one calendar: Asia/Kolkata (CLAUDE.md: dates use IST `local_date`). */
object Ist {
    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    /** `[start, endExclusive)` of [month] as epoch milliseconds. */
    fun monthRange(month: YearMonth): LongRange {
        val start = month.atDay(1).atStartOfDay(ZONE).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant().toEpochMilli()
        return start until end
    }

    fun monthOf(epochMillis: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(ZONE))

    fun startOfDay(date: LocalDate): Long = date.atStartOfDay(ZONE).toInstant().toEpochMilli()

    /** The IST calendar day of an instant: the stored `local_date` (ADR-009). */
    fun localDate(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(ZONE).toLocalDate()

    /** [date] at [hour]:[minute] IST as epoch milliseconds. */
    fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(ZONE).toInstant().toEpochMilli()

    fun now(): Long = System.currentTimeMillis()
}
