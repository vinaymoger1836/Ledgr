package com.ledgr.app.domain.statement

import java.time.LocalDate
import kotlinx.serialization.Serializable

/** What a statement file is, decided from its bytes and never from its name (docs/06 §1.2). */
enum class FileType { PDF, XLSX, XLS, CSV }

/** One row of a file as text. [index] is the 1-based row number in the source, so warnings can say "row 14". */
data class GridRow(val index: Int, val cells: List<String>)

/** Rows of text cells, as every reader produces them. Dates in real date cells arrive as `yyyy-MM-dd`. */
class Grid(val rows: List<GridRow>)

/** What a column of a statement means. Names match `statement_profiles.yaml` (`fields:`) in `snake_case`. */
enum class StatementField(val key: String) {
    DATE("date"),
    VALUE_DATE("value_date"),
    DESCRIPTION("description"),
    REF("ref"),
    DEBIT("debit"),
    CREDIT("credit"),
    AMOUNT("amount"),
    DR_CR("dr_cr"),
    BALANCE("balance"),
    ;

    companion object {
        fun ofKey(key: String): StatementField? = entries.firstOrNull { it.key == key }
    }
}

/** How a statement writes its amounts (docs/05 `column_map.mapping`). */
enum class AmountStyle { DEBIT_CREDIT_COLUMNS, SIGNED, AMOUNT_PLUS_DRCR }

/**
 * The saved answer to "which column is what" for one bank and one header layout (docs/05 `column_map`).
 *
 * @property fields [StatementField.key] to the zero-based column index.
 * @property dateFormat a `java.time` pattern from `statement_profiles.yaml`.
 * @property pdfColumns for a PDF, the x-positions (1/10 pt) that cut a line into cells, left to right: N-1 dividers for
 * N columns. Null for every other file type.
 */
@Serializable
data class ColumnMap(
    val fields: Map<String, Int>,
    val dateFormat: String,
    val amountStyle: AmountStyle,
    val pdfColumns: List<Int>? = null,
) {
    fun column(field: StatementField): Int? = fields[field.key]
}

/** One transaction line of a statement, normalised. Money is paise; exactly one of [debit] and [credit] is non-zero. */
data class StatementLine(
    /** 0-based position among the lines, the stable id used by the reconciler. */
    val ordinal: Int,
    /** 1-based row of the first source row, for warnings. */
    val sourceRow: Int,
    val date: LocalDate,
    val valueDate: LocalDate?,
    val description: String,
    val refNo: String?,
    val debit: Long,
    val credit: Long,
    /** Running balance after the line, negative when overdrawn. */
    val balance: Long?,
    /** The source cells as text, kept so a mapping mistake can be seen. */
    val rawCells: List<String>,
) {
    val isDebit: Boolean get() = debit != 0L
    val amount: Long get() = if (isDebit) debit else credit
}

/** Something the normaliser or the integrity check wants the person to look at. Codes are shown through strings in the UI. */
data class StatementWarning(val code: String, val row: Int?, val detail: String = "")

object WarningCodes {
    const val BAD_DATE = "bad_date"
    const val BAD_AMOUNT = "bad_amount"
    const val NO_AMOUNT = "no_amount"
    const val BOTH_AMOUNTS = "both_amounts"
    const val BALANCE_BREAK = "balance_break"
    const val SUM_MISMATCH = "sum_mismatch"
    const val NO_BALANCE = "no_balance"
    const val OUTSIDE_LIMIT = "outside_limit"
}

/** Everything normalising produced. [opening] and [closing] are derived from the balance column when there is one. */
data class NormalisedStatement(
    val lines: List<StatementLine>,
    val periodFrom: LocalDate?,
    val periodTo: LocalDate?,
    val opening: Long?,
    val closing: Long?,
    val warnings: List<StatementWarning>,
    /** Rows that were page furniture or summaries and left out. */
    val skippedRows: Int,
)

/** A header row found by [HeaderDetector]. [position] is the index into [Grid.rows]. */
data class HeaderHit(val position: Int, val cells: List<String>, val fieldsByColumn: Map<Int, StatementField>)

/** Why a file cannot be read; the message key is shown to the person. */
class StatementException(val code: String, message: String) : Exception(message) {
    companion object {
        const val TOO_LARGE = "too_large"
        const val UNSUPPORTED = "unsupported"
        const val BAD_ZIP = "bad_zip"
        const val ZIP_BOMB = "zip_bomb"
        const val NEEDS_PASSWORD = "needs_password"
        const val WRONG_PASSWORD = "wrong_password"
        const val NO_TEXT = "no_text"
        const val CORRUPT = "corrupt"
        const val NO_HEADER = "no_header"
        const val TIMEOUT = "timeout"
        const val NO_ROWS = "no_rows"
    }
}
