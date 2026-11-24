package com.ledgr.app.golden

import java.io.File
import kotlinx.serialization.encodeToString

/**
 * G-RECON-CSV (docs/06 §5): debit/credit columns, Indian number format, preamble rows, a wrapped narration, quoted
 * commas, summary and footer rows. Pins type detection, header row, guessed map, every line to the paisa and the integrity result.
 */
internal object GReconCsvCase : GoldenCase {
    override val id = "G-RECON-CSV"
    override val expectedFile = "g-recon-csv.json"

    override fun run(context: GoldenContext): String {
        val profiles = GoldenRules.load(context).statementProfiles
        val bytes = File(context.fixturesDir, "recon/csv/statement.csv").readBytes()
        val (type, analysis) = analyseTabular(bytes, profiles, "hdfc")
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes
        val sameWithBom = analyseTabular(withBom, profiles, "hdfc").second.dump(type) == analysis.dump(type)
        check(sameWithBom) { "a byte-order mark changed the result" }
        // The same rows with tabs instead of commas must read to the same lines (delimiter sniffing); row numbers differ
        // because blank rows are not written back.
        val tabbed = analysis.grid.rows.joinToString("\n") { it.cells.joinToString("\t") }.toByteArray(Charsets.UTF_8)
        val tabLines = analyseTabular(tabbed, profiles, "hdfc").second.dump(type).lines.map { it.copy(row = 0) }
        check(tabLines == analysis.dump(type).lines.map { it.copy(row = 0) }) { "a tab-separated copy read differently" }
        return GoldenJson.finish(GoldenJson.json.encodeToString(analysis.dump(type)))
    }
}
