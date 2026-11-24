package com.ledgr.app.golden

import com.ledgr.app.domain.ledger.Direction
import com.ledgr.app.domain.statement.ReconResult
import com.ledgr.app.domain.statement.ReconTxn
import com.ledgr.app.domain.statement.Reconciler
import com.ledgr.app.domain.statement.StatementLine
import java.io.File
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal data class MatchLineFx(
    val ordinal: Int,
    val date: String,
    val description: String,
    val ref: String?,
    val debit: Long,
    val credit: Long,
    val balance: Long?,
)

@Serializable
internal data class MatchTxnFx(
    val id: Long,
    val date: String,
    val direction: Direction,
    val amount: Long,
    val ref: String?,
    val description: String?,
    val balanceAfter: Long?,
)

@Serializable
internal data class MatchFx(val from: String, val to: String, val lines: List<MatchLineFx>, val ledger: List<MatchTxnFx>)

@Serializable
internal data class MatchDump(val line: Int?, val txn: Long?, val status: String, val scorePm: Int, val reasons: List<String>)

@Serializable
internal data class HintDump(val line: Int, val txn: Long, val statement: Long, val ledger: Long)

@Serializable
internal data class MatchCaseOut(
    val counts: Map<String, Int>,
    val matchRateBp: Int,
    val matches: List<MatchDump>,
    val balanceHints: List<HintDump>,
    val sameWhenInputsReordered: Boolean,
)

/**
 * G-RECON-MATCH (docs/06 §3, §5): an exact reference with leading zeros, a 12-digit reference only inside the narration, a
 * date shift that lands in the fuzzy band, two equal same-day amounts told apart by the narration, an amount mismatch, a
 * statement-only line, ledger-only transactions (one outside the period), a same-reference but opposite-direction pair that
 * must not match, and a same-amount pair four days apart that must not match. Run twice with the inputs reordered: identical.
 */
internal object GReconMatchCase : GoldenCase {
    override val id = "G-RECON-MATCH"
    override val expectedFile = "g-recon-match.json"

    override fun run(context: GoldenContext): String {
        val profiles = GoldenRules.load(context).statementProfiles
        val fx = GoldenJson.json.decodeFromString(MatchFx.serializer(), File(context.fixturesDir, "recon/match/scenarios.json").readText())
        val lines = fx.lines.map {
            StatementLine(it.ordinal, it.ordinal + 7, LocalDate.parse(it.date), null, it.description, it.ref, it.debit, it.credit, it.balance, emptyList())
        }
        val ledger = fx.ledger.map { ReconTxn(it.id, LocalDate.parse(it.date), it.direction, it.amount, it.ref, it.description, it.balanceAfter) }
        val from = LocalDate.parse(fx.from)
        val to = LocalDate.parse(fx.to)

        val reconciler = Reconciler(profiles)
        val result = reconciler.match(lines, ledger, from, to)
        val again = reconciler.match(lines.asReversed(), ledger.asReversed(), from, to)

        return GoldenJson.finish(GoldenJson.json.encodeToString(dump(result, lines.size, result == again)))
    }

    private fun dump(r: ReconResult, lineCount: Int, same: Boolean) = MatchCaseOut(
        counts = com.ledgr.app.domain.statement.MatchStatus.entries.associate { it.name to r.count(it) },
        matchRateBp = r.matchRateBp(lineCount),
        matches = r.matches.map { MatchDump(it.lineOrdinal, it.txnId, it.status.name, it.scorePm, it.reasons) },
        balanceHints = r.hints.map { HintDump(it.lineOrdinal, it.txnId, it.statementBalance, it.ledgerBalance) },
        sameWhenInputsReordered = same,
    )
}
