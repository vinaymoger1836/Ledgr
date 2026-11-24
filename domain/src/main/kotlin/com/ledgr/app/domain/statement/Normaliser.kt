package com.ledgr.app.domain.statement

import java.time.LocalDate

/**
 * docs/06 §1.7: rows below the header to [StatementLine]s. Dates use the map's pattern; amounts become paise; a row with no
 * date is a continuation of the previous narration (PDF and CSV both wrap long text) unless it is page furniture or a summary.
 * Nothing is dropped silently: every row that is not a line is counted, and every problem row becomes a warning with its row number.
 */
class Normaliser(private val profiles: StatementProfiles) {
    private val amounts = AmountParser(profiles)
    private val skipMarkers = profiles.skipRowMarkers.map(Text::normalise).filter { it.isNotEmpty() }

    fun normalise(grid: Grid, headerPosition: Int, map: ColumnMap): NormalisedStatement {
        val headerCells = grid.rows.getOrNull(headerPosition)?.cells.orEmpty().map(Text::normalise)
        val warnings = ArrayList<StatementWarning>()
        val lines = ArrayList<MutableLine>()
        var skipped = 0

        fun cell(row: GridRow, field: StatementField): String = map.column(field)?.let { row.cells.getOrNull(it) }.orEmpty().trim()

        var previous: MutableLine? = null
        for (row in grid.rows.drop(headerPosition + 1)) {
            val normalisedRow = row.cells.map(Text::normalise)
            if (normalisedRow.isNotEmpty() && headerCells.isNotEmpty() && normalisedRow.take(headerCells.size) == headerCells.take(normalisedRow.size) && normalisedRow.count { it.isNotEmpty() } >= 2) {
                skipped++ // the header repeated at the top of each PDF page
                previous = null
                continue
            }
            val date = StatementDates.parse(cell(row, StatementField.DATE), map.dateFormat)
            if (date == null) {
                val hasAmounts = listOf(StatementField.DEBIT, StatementField.CREDIT, StatementField.AMOUNT, StatementField.BALANCE)
                    .any { cell(row, it).isNotEmpty() }
                val description = cell(row, StatementField.DESCRIPTION)
                val joined = Text.normalise(row.cells.joinToString(" "))
                when {
                    joined.isEmpty() -> {}
                    isFurniture(joined) -> {
                        skipped++
                        previous = null
                    }
                    !hasAmounts && description.isNotEmpty() && previous != null -> {
                        val prev: MutableLine = previous
                        prev.description = (prev.description + " " + description).trim()
                        val ref = cell(row, StatementField.REF)
                        if (ref.isNotEmpty() && prev.refNo == null) prev.refNo = ref
                    }
                    hasAmounts -> {
                        warnings += StatementWarning(WarningCodes.BAD_DATE, row.index, cell(row, StatementField.DATE))
                        skipped++
                        previous = null
                    }
                    else -> {
                        skipped++
                        previous = null
                    }
                }
                continue
            }

            val amount = readAmount(row, map, ::cell, warnings)
            if (amount == null) {
                val text = Text.normalise(cell(row, StatementField.DESCRIPTION))
                if (!isFurniture(text)) warnings += StatementWarning(WarningCodes.NO_AMOUNT, row.index)
                skipped++
                previous = null
                continue
            }
            val balance = cell(row, StatementField.BALANCE).takeIf { it.isNotEmpty() }?.let(amounts::parse)?.let {
                if (it.negative || it.side == ParsedAmount.Side.DEBIT) -it.paise else it.paise
            }
            val line = MutableLine(
                sourceRow = row.index,
                date = date,
                valueDate = StatementDates.parse(cell(row, StatementField.VALUE_DATE), map.dateFormat),
                description = cell(row, StatementField.DESCRIPTION),
                refNo = cell(row, StatementField.REF).takeIf { it.isNotEmpty() },
                debit = amount.first,
                credit = amount.second,
                balance = balance,
                rawCells = row.cells,
            )
            lines += line
            previous = line
            if (lines.size > StatementLimits.MAX_ROWS) throw StatementException(StatementException.TOO_LARGE, "The statement has too many lines")
        }

        val done = lines.mapIndexed { i, l ->
            StatementLine(i, l.sourceRow, l.date, l.valueDate, l.description, l.refNo, l.debit, l.credit, l.balance, l.rawCells)
        }
        val integrity = IntegrityChecker.check(done)
        return NormalisedStatement(
            lines = done,
            periodFrom = done.minOfOrNull { it.date },
            periodTo = done.maxOfOrNull { it.date },
            opening = integrity.opening,
            closing = integrity.closing,
            warnings = warnings + integrity.warnings,
            skippedRows = skipped,
        )
    }

    /** A marker counts at the start of the row, or anywhere when it is several words ("statement summary"): a merchant called "Total Wine" stays a narration. */
    private fun isFurniture(normalisedText: String): Boolean =
        skipMarkers.any { normalisedText.startsWith(it) || (' ' in it && normalisedText.contains(it)) }

    /** (debit, credit) in paise, or null when the row shows no amount. */
    private fun readAmount(
        row: GridRow,
        map: ColumnMap,
        cell: (GridRow, StatementField) -> String,
        warnings: MutableList<StatementWarning>,
    ): Pair<Long, Long>? {
        when (map.amountStyle) {
            AmountStyle.DEBIT_CREDIT_COLUMNS -> {
                val d = parseCell(cell(row, StatementField.DEBIT), row, warnings)
                val c = parseCell(cell(row, StatementField.CREDIT), row, warnings)
                val debit = d?.paise ?: 0L
                val credit = c?.paise ?: 0L
                if (debit == 0L && credit == 0L) return null
                if (debit != 0L && credit != 0L) {
                    warnings += StatementWarning(WarningCodes.BOTH_AMOUNTS, row.index)
                    return if (debit >= credit) (debit - credit) to 0L else 0L to (credit - debit)
                }
                return debit to credit
            }
            AmountStyle.SIGNED -> {
                val a = parseCell(cell(row, StatementField.AMOUNT), row, warnings) ?: return null
                if (a.paise == 0L) return null
                val debit = a.negative || a.side == ParsedAmount.Side.DEBIT
                return if (debit) a.paise to 0L else 0L to a.paise
            }
            AmountStyle.AMOUNT_PLUS_DRCR -> {
                val a = parseCell(cell(row, StatementField.AMOUNT), row, warnings) ?: return null
                if (a.paise == 0L) return null
                val side = a.side ?: amounts.sideOf(cell(row, StatementField.DR_CR)) ?: if (a.negative) ParsedAmount.Side.DEBIT else null
                if (side == null) {
                    warnings += StatementWarning(WarningCodes.BAD_AMOUNT, row.index, "no Dr/Cr")
                    return null
                }
                return if (side == ParsedAmount.Side.DEBIT) a.paise to 0L else 0L to a.paise
            }
        }
    }

    /** A blank cell, or a dash standing for "nothing", is null without a warning; anything else unreadable warns. */
    private fun parseCell(text: String, row: GridRow, warnings: MutableList<StatementWarning>): ParsedAmount? {
        if (text.isEmpty() || text.all { it == '-' || it == '–' || it == '—' || it == ' ' }) return null
        val parsed = amounts.parse(text)
        if (parsed == null) warnings += StatementWarning(WarningCodes.BAD_AMOUNT, row.index, text.take(30))
        return parsed
    }

    private class MutableLine(
        val sourceRow: Int,
        val date: LocalDate,
        val valueDate: LocalDate?,
        var description: String,
        var refNo: String?,
        val debit: Long,
        val credit: Long,
        val balance: Long?,
        val rawCells: List<String>,
    )
}

/** What [IntegrityChecker.check] found. */
data class IntegrityResult(
    val warnings: List<StatementWarning>,
    val opening: Long?,
    val closing: Long?,
    /** True when the statement lists its lines oldest first. */
    val oldestFirst: Boolean,
)

/** docs/06 §1.8: running-balance continuity (tolerance ₹0.01) and opening + credits − debits = closing. Failures are warnings. */
object IntegrityChecker {
    private const val TOLERANCE = 1L

    fun check(lines: List<StatementLine>): IntegrityResult {
        if (lines.none { it.balance != null }) {
            val warnings = if (lines.isEmpty()) emptyList() else listOf(StatementWarning(WarningCodes.NO_BALANCE, null))
            return IntegrityResult(warnings, null, null, true)
        }
        val ascending = breaks(lines, true)
        val descending = breaks(lines, false)
        val oldestFirst = ascending.size <= descending.size
        val broken = if (oldestFirst) ascending else descending
        val warnings = ArrayList<StatementWarning>()
        for ((index, expected) in broken) {
            val l = lines[index]
            warnings += StatementWarning(WarningCodes.BALANCE_BREAK, l.sourceRow, "$expected:${l.balance}")
        }
        val chronological = if (oldestFirst) lines else lines.asReversed()
        val first = chronological.first()
        val last = chronological.last()
        val opening = first.balance?.let { it - first.credit + first.debit }
        val closing = last.balance
        if (opening != null && closing != null && chronological.all { it.balance != null }) {
            val computed = opening + chronological.sumOf { it.credit } - chronological.sumOf { it.debit }
            if (kotlin.math.abs(computed - closing) > TOLERANCE) {
                warnings += StatementWarning(WarningCodes.SUM_MISMATCH, null, "${computed - closing}")
            }
        }
        return IntegrityResult(warnings, opening, closing, oldestFirst)
    }

    /** (index, expected balance) of each line whose balance does not follow from its neighbour, for one reading order. */
    private fun breaks(lines: List<StatementLine>, oldestFirst: Boolean): List<Pair<Int, Long>> {
        val out = ArrayList<Pair<Int, Long>>()
        for (i in 1 until lines.size) {
            val cur = lines[i]
            val prev = lines[i - 1]
            val curBalance = cur.balance ?: continue
            val prevBalance = prev.balance ?: continue
            val expected = if (oldestFirst) prevBalance + cur.credit - cur.debit else prevBalance - prev.credit + prev.debit
            if (kotlin.math.abs(expected - curBalance) > TOLERANCE) out += i to expected
        }
        return out
    }
}
