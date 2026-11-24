package com.ledgr.app.domain.statement

/** What was read from a file, before any column is named. */
sealed interface RawStatement {
    /** CSV, XLSX and XLS give rows directly. */
    class Tabular(val grid: Grid) : RawStatement

    /** A PDF gives positioned lines; the column dividers decide the cells. */
    class Pdf(val lines: List<PdfLine>) : RawStatement
}

/**
 * Everything the import flow needs after reading (docs/06 §1.5-8).
 *
 * @property signature the layout identity ([HeaderDetector.signature]); null when no header row was found.
 * @property savedMap the map the person confirmed earlier for this bank and layout, if any. When it is set, mapping is skipped.
 * @property map the map actually applied: [savedMap], else a complete first guess; null when the person must map first.
 * @property statement the normalised lines, present when [map] is.
 * @property dividers for a PDF, the column dividers used (1/10 pt).
 */
class Analysis(
    val grid: Grid,
    val header: HeaderHit?,
    val signature: String?,
    val guess: ColumnGuess?,
    val savedMap: ColumnMap?,
    val map: ColumnMap?,
    val statement: NormalisedStatement?,
    val dividers: List<Int>?,
) {
    /** True when the mapping screen is needed: no saved map matched (docs/06 §1.6). */
    val needsMapping: Boolean get() = savedMap == null
}

object StatementAnalyser {
    fun read(type: FileType, bytes: ByteArray): Grid = when (type) {
        FileType.CSV -> CsvReader.read(bytes)
        FileType.XLSX -> XlsxReader.read(bytes)
        FileType.XLS -> BiffReader.read(bytes)
        FileType.PDF -> throw IllegalArgumentException("A PDF is read by a platform extractor, then analysed with RawStatement.Pdf")
    }

    /**
     * Header, signature, saved-map lookup, first guess and (if a map is usable) normalised lines. [lookup] returns the saved
     * map for a signature (the repository supplies it; golden passes a stub). [override] is a map the person has just edited on
     * the mapping screen; it wins over everything and is not "saved" until the caller stores it.
     */
    fun analyse(
        raw: RawStatement,
        profiles: StatementProfiles,
        bankId: String?,
        lookup: (String) -> ColumnMap? = { null },
        override: ColumnMap? = null,
    ): Analysis {
        var dividers: List<Int>? = null
        var signature: String? = null
        val grid: Grid
        when (raw) {
            is RawStatement.Tabular -> grid = raw.grid
            is RawStatement.Pdf -> {
                val pdfHeader = PdfLayout.findHeader(raw.lines, profiles)
                signature = pdfHeader?.cells?.let(HeaderDetector::signature)
                val saved = signature?.let(lookup)
                dividers = override?.pdfColumns ?: saved?.pdfColumns ?: pdfHeader?.dividers ?: emptyList()
                grid = PdfLayout.grid(raw.lines, dividers)
            }
        }
        val hit = HeaderDetector.find(grid, profiles)
        if (raw is RawStatement.Tabular) signature = hit?.cells?.let(HeaderDetector::signature)
        val guess = hit?.let { HeaderDetector.guess(it, grid, profiles, bankId) }
        val saved = signature?.let(lookup)
        val normaliser = Normaliser(profiles)
        val map = override ?: saved ?: guess?.takeIf { it.missing.isEmpty() && it.dateFormat != null }?.let {
            ColumnMap(
                fields = it.fields.entries.associate { (f, c) -> f.key to c },
                dateFormat = it.dateFormat!!,
                amountStyle = it.amountStyle,
                pdfColumns = dividers,
            )
        }
        val statement = if (hit != null && map != null) normaliser.normalise(grid, hit.position, map) else null
        return Analysis(grid, hit, signature, guess, saved, map, statement, dividers)
    }
}
