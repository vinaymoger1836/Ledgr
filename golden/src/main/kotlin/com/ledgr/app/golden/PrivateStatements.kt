package com.ledgr.app.golden

import com.ledgr.app.domain.rules.RulesBundle
import com.ledgr.app.domain.statement.FileSniffer
import com.ledgr.app.domain.statement.FileType
import com.ledgr.app.domain.statement.PdfLayout
import com.ledgr.app.domain.statement.RawStatement
import com.ledgr.app.domain.statement.Reconciler
import com.ledgr.app.domain.statement.ReconTxn
import com.ledgr.app.domain.statement.StatementAnalyser
import com.ledgr.app.domain.statement.StatementException
import java.io.File
import java.time.LocalDate

/**
 * docs/12 §4(c) for statements: reads the owner's real statements (the files in `golden/private/statements`, git-ignored) through the same
 * `:domain` code the app uses and reports how far automatic reading gets: file type, whether a header was found, which map
 * fields are still missing, lines parsed, integrity warnings and skipped rows. It never asserts, never prints a narration, an
 * amount or a digit string, and writes the same report to `golden/private/statements-report.txt`.
 *
 * A bank is taken from the file name's first word when it is a known bank id (`hdfc-oct.pdf`). An encrypted PDF is read
 * only if the environment variable `LEDGR_PDF_PASSWORD` holds its password; the password is never written anywhere.
 *
 * The match rate is reported only when `golden/private/ledger/<same name>.json` exists (the owner's ledger for that period,
 * as a `ledger` array like `fixtures/recon/match/scenarios.json`); otherwise "n/a" (the real number is shown in the app's
 * results screen after the first import on the phone).
 */
internal object PrivateStatements {
    fun report(privateDir: File, rules: RulesBundle) {
        val dir = File(privateDir, "statements")
        val files = dir.listFiles { f -> f.isFile && !f.name.startsWith(".") && !f.name.endsWith(".txt") && !f.name.endsWith(".json") }
            .orEmpty().sortedBy { it.name }
        if (files.isEmpty()) {
            println("[info] golden/private/statements has no files: private statement import not measured")
            return
        }
        val profiles = rules.statementProfiles
        val knownBanks = rules.banks.banks.map { it.id }.toSet()
        val out = ArrayList<String>()
        out += "PRIVATE STATEMENTS: ${files.size} file(s)"
        var perfect = 0
        for (f in files) {
            val bank = f.name.substringBefore('-').substringBefore('_').substringBefore('.').lowercase().takeIf { it in knownBanks }
            val line = try {
                val bytes = f.readBytes()
                val type = FileSniffer.detect(bytes)
                val raw = when (type) {
                    FileType.PDF -> {
                        val (_, glyphs) = GReconPdfCase.extract(bytes, System.getenv("LEDGR_PDF_PASSWORD"))
                        RawStatement.Pdf(PdfLayout.lines(glyphs, profiles.pdf))
                    }
                    else -> RawStatement.Tabular(StatementAnalyser.read(type, bytes))
                }
                val a = StatementAnalyser.analyse(raw, profiles, bank)
                val s = a.statement
                if (a.header != null && a.map != null && s != null && s.warnings.isEmpty() && s.lines.isNotEmpty()) perfect++
                val warnings = s?.warnings.orEmpty().groupingBy { it.code }.eachCount().toSortedMap()
                val rate = matchRate(privateDir, f, s?.lines, a, profiles)
                "  ${f.name}: $type, header=${if (a.header != null) "found" else "NOT FOUND"}, " +
                    "signature=${a.signature?.take(8) ?: "-"}, auto-map=${if (a.map != null) "complete" else "needs mapping (missing ${a.guess?.missing?.joinToString { it.key } ?: "everything"})"}, " +
                    "lines=${s?.lines?.size ?: 0}, skipped=${s?.skippedRows ?: 0}, warnings=${warnings.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "none" }}, " +
                    "period=${s?.periodFrom ?: "-"}..${s?.periodTo ?: "-"}, match rate=$rate"
            } catch (e: StatementException) {
                "  ${f.name}: not read (${e.code})"
            } catch (e: Exception) {
                "  ${f.name}: not read (${e::class.simpleName})"
            }
            out += line
        }
        out += "  read without warnings: $perfect of ${files.size}"
        out.forEach(::println)
        File(privateDir, "statements-report.txt").writeText(out.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    private fun matchRate(
        privateDir: File,
        statement: File,
        lines: List<com.ledgr.app.domain.statement.StatementLine>?,
        a: com.ledgr.app.domain.statement.Analysis,
        profiles: com.ledgr.app.domain.statement.StatementProfiles,
    ): String {
        if (lines.isNullOrEmpty()) return "n/a"
        val sidecar = File(File(privateDir, "ledger"), statement.nameWithoutExtension + ".json")
        if (!sidecar.isFile) return "n/a (no ledger sidecar)"
        return try {
            val fx = GoldenJson.json.decodeFromString(MatchFx.serializer(), sidecar.readText(Charsets.UTF_8))
            val ledger = fx.ledger.map { ReconTxn(it.id, LocalDate.parse(it.date), it.direction, it.amount, it.ref, it.description, it.balanceAfter) }
            val result = Reconciler(profiles).match(lines, ledger, a.statement?.periodFrom, a.statement?.periodTo)
            "%d.%02d%%".format(result.matchRateBp(lines.size) / 100, result.matchRateBp(lines.size) % 100)
        } catch (e: Exception) {
            "n/a (ledger sidecar unreadable)"
        }
    }
}

