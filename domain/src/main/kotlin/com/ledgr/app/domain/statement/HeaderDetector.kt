package com.ledgr.app.domain.statement

import java.security.MessageDigest

/** What the mapping screen starts from: the best reading of the header, plus what is still missing. */
data class ColumnGuess(
    val header: HeaderHit,
    val fields: Map<StatementField, Int>,
    val amountStyle: AmountStyle,
    /** Null when no date format reads the date column (the person picks one). */
    val dateFormat: String?,
) {
    /** Fields a usable map still lacks, in the order the mapping screen shows them. */
    val missing: List<StatementField>
        get() = ColumnMaps.missing(fields.keys, amountStyle)
}

object ColumnMaps {
    /** A usable map needs a date, and either debit + credit columns, or one amount column. A description is wanted but optional. */
    fun missing(present: Set<StatementField>, style: AmountStyle): List<StatementField> {
        val out = ArrayList<StatementField>()
        if (StatementField.DATE !in present) out += StatementField.DATE
        when (style) {
            AmountStyle.DEBIT_CREDIT_COLUMNS -> {
                if (StatementField.DEBIT !in present) out += StatementField.DEBIT
                if (StatementField.CREDIT !in present) out += StatementField.CREDIT
            }
            AmountStyle.SIGNED, AmountStyle.AMOUNT_PLUS_DRCR -> if (StatementField.AMOUNT !in present) out += StatementField.AMOUNT
        }
        return out
    }
}

/** docs/06 §1.5-6: find the header row, name its columns, and make a signature that identifies the layout. */
object HeaderDetector {
    /** The first row among the first `header_scan_rows` with at least `min_header_hits` recognised cells. */
    fun find(grid: Grid, profiles: StatementProfiles): HeaderHit? {
        val index = SynonymIndex(profiles)
        for ((position, row) in grid.rows.withIndex()) {
            if (position >= profiles.headerScanRows) break
            val fields = classifyRow(row.cells, index)
            if (fields.size >= profiles.minHeaderHits) return HeaderHit(position, row.cells, fields)
        }
        return null
    }

    /** Header cells to a field per column. A field named twice keeps its leftmost column. */
    fun classifyRow(cells: List<String>, index: SynonymIndex): Map<Int, StatementField> {
        val out = LinkedHashMap<Int, StatementField>()
        val taken = HashSet<StatementField>()
        for ((col, cell) in cells.withIndex()) {
            val field = index.classify(cell) ?: continue
            if (taken.add(field)) out[col] = field
        }
        return out
    }

    /** SHA-256 (lower-case hex) of the normalised, non-blank header cells in order: the identity of a layout (docs/05 `header_signature`). */
    fun signature(headerCells: List<String>): String {
        val text = headerCells.map(Text::normalise).filter { it.isNotEmpty() }.joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** A first guess for the column map from a found header. [dateSamples] are the cells under the date column. */
    fun guess(hit: HeaderHit, grid: Grid, profiles: StatementProfiles, bankId: String?): ColumnGuess {
        val fields = hit.fieldsByColumn.entries.associate { (col, field) -> field to col }
        val style = when {
            StatementField.DEBIT in fields && StatementField.CREDIT in fields -> AmountStyle.DEBIT_CREDIT_COLUMNS
            StatementField.AMOUNT in fields && StatementField.DR_CR in fields -> AmountStyle.AMOUNT_PLUS_DRCR
            StatementField.AMOUNT in fields -> amountStyleFromSamples(grid, hit, fields[StatementField.AMOUNT]!!, profiles)
            else -> AmountStyle.DEBIT_CREDIT_COLUMNS
        }
        val dateCol = fields[StatementField.DATE]
        val samples = if (dateCol == null) emptyList() else sampleColumn(grid, hit.position, dateCol)
        val format = StatementDates.detect(samples, profiles.dateFormatsFor(bankId))
        return ColumnGuess(hit, fields, style, format)
    }

    /** Up to 60 cells below the header in column [col], for choosing a date format or amount style. */
    fun sampleColumn(grid: Grid, headerPosition: Int, col: Int, max: Int = 60): List<String> =
        grid.rows.drop(headerPosition + 1).take(400).mapNotNull { it.cells.getOrNull(col) }.filter { it.isNotBlank() }.take(max)

    private fun amountStyleFromSamples(grid: Grid, hit: HeaderHit, col: Int, profiles: StatementProfiles): AmountStyle {
        val parser = AmountParser(profiles)
        val parsed = sampleColumn(grid, hit.position, col).mapNotNull(parser::parse)
        return if (parsed.any { it.side != null }) AmountStyle.AMOUNT_PLUS_DRCR else AmountStyle.SIGNED
    }
}

/** Header-cell lookup built from `statement_profiles.yaml`: exact synonym match, else the longest synonym the cell starts with. */
class SynonymIndex(profiles: StatementProfiles) {
    private val exact = HashMap<String, StatementField>()
    private val bySize: List<Pair<String, StatementField>>

    init {
        val all = ArrayList<Pair<String, StatementField>>()
        // Walk the fields in a fixed order so a synonym listed under two fields always resolves the same way.
        for (field in StatementField.entries) {
            for (syn in profiles.synonyms(field)) {
                exact.putIfAbsent(syn, field)
                all += syn to field
            }
        }
        bySize = all.sortedWith(compareByDescending<Pair<String, StatementField>> { it.first.length }.thenBy { it.first })
    }

    fun classify(cell: String): StatementField? {
        val n = Text.normalise(cell)
        if (n.isEmpty()) return null
        exact[n]?.let { return it }
        // "withdrawal amt inr" starts with the synonym "withdrawal amt".
        return bySize.firstOrNull { n.startsWith(it.first + " ") }?.second
    }
}
