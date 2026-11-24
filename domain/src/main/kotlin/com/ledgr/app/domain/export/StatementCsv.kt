package com.ledgr.app.domain.export

/** One line of the statement-style table as it is exported (docs/07 §3). Amounts are paise; null means blank. */
data class StatementCsvRow(
    val date: String,
    val description: String,
    val refNo: String?,
    val debit: Long?,
    val credit: Long?,
    val balance: Long?,
    val account: String,
    val category: String,
    val source: String,
    val recon: String,
)

/**
 * CSV for the table export (RFC 4180). Money is written as plain decimals from `Long` paise (no currency symbol, no
 * grouping, so a spreadsheet reads it as a number). Text cells that start with `=`, `+`, `-`, `@`, tab or CR get a
 * leading apostrophe so a merchant name cannot run as a spreadsheet formula.
 */
object StatementCsv {
    val HEADER = listOf("Date", "Description", "Ref no", "Debit", "Credit", "Balance", "Account", "Category", "Source", "Recon")

    /** Starts with a UTF-8 byte-order mark so Excel reads non-ASCII merchant names correctly. */
    fun render(rows: List<StatementCsvRow>): String {
        val sb = StringBuilder("﻿")
        appendLine(sb, HEADER)
        for (r in rows) {
            appendLine(
                sb,
                listOf(
                    r.date, safe(r.description), safe(r.refNo.orEmpty()), decimal(r.debit), decimal(r.credit),
                    decimal(r.balance), safe(r.account), safe(r.category), r.source, r.recon,
                ),
            )
        }
        return sb.toString()
    }

    /** `123456` → `1234.56`, `-5` → `-0.05`, null → empty. */
    fun decimal(paise: Long?): String {
        if (paise == null) return ""
        val abs = if (paise == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(paise)
        return (if (paise < 0) "-" else "") + (abs / 100) + "." + (abs % 100).toString().padStart(2, '0')
    }

    fun safe(text: String): String =
        if (text.isNotEmpty() && text[0] in "=+-@\t\r") "'$text" else text

    private fun appendLine(sb: StringBuilder, cells: List<String>) {
        cells.forEachIndexed { i, cell ->
            if (i > 0) sb.append(',')
            sb.append(quote(cell))
        }
        sb.append("\r\n")
    }

    private fun quote(cell: String): String =
        if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
}
