package com.ledgr.app.domain.statement

import com.ledgr.app.domain.ledger.Direction
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One ledger transaction as the reconciler sees it (docs/06 §3 inputs): ACTIVE rows of one account, period ± 3 days. */
data class ReconTxn(
    val id: Long,
    val date: LocalDate,
    val direction: Direction,
    /** Paise, positive. */
    val amount: Long,
    val refNo: String?,
    /** `raw_counterparty`: the VPA / merchant text the SMS gave. */
    val description: String?,
    val balanceAfter: Long?,
)

enum class MatchStatus { MATCHED, MATCHED_FUZZY, AMOUNT_MISMATCH, STATEMENT_ONLY, LEDGER_ONLY }

/**
 * One result row. [lineOrdinal] is null for [MatchStatus.LEDGER_ONLY], [txnId] is null for [MatchStatus.STATEMENT_ONLY].
 * [scorePm] is the score in per-mille (0..1000; an exact reference is 1000).
 */
data class ReconMatch(
    val lineOrdinal: Int?,
    val txnId: Long?,
    val status: MatchStatus,
    val scorePm: Int,
    val reasons: List<String>,
)

/** A matched pair whose statement balance differs from the balance the SMS reported (docs/06 §3.4). */
data class BalanceHint(val lineOrdinal: Int, val txnId: Long, val statementBalance: Long, val ledgerBalance: Long)

data class ReconResult(val matches: List<ReconMatch>, val hints: List<BalanceHint>) {
    fun count(status: MatchStatus): Int = matches.count { it.status == status }

    /**
     * How much of the statement the ledger already covers, in basis points of the statement lines: a line paired with a
     * ledger transaction counts (matched, fuzzy or with a different amount). 0 for an empty statement.
     */
    fun matchRateBp(lineCount: Int): Int {
        if (lineCount <= 0) return 0
        val paired = matches.count { it.lineOrdinal != null && it.txnId != null }
        return (paired.toLong() * 10_000 / lineCount).toInt()
    }
}

object ReconReasons {
    const val REF = "ref"
    const val AMOUNT_DIFFERS = "amount_differs"
    const val SCORED = "scored"
    const val OUTSIDE_PERIOD = "outside_period"
}

/**
 * docs/06 §3, deterministic: the same inputs give the same result. Scores are integers in per-mille so the JVM and the phone
 * agree exactly: `500 + 300·(4−d)/4 + 200·similarity` (similarity = token Jaccard, 0..1000 per-mille). Ties break on
 * score, date, row index, transaction id.
 */
class Reconciler(private val profiles: StatementProfiles) {
    private val narration = NarrationParser(profiles.narration)
    private val noise = profiles.noiseWords.map(Text::normalise).toSet()

    /**
     * @param pinned pairs the person already decided (confirmed fuzzy matches, resolved mismatches): kept exactly as they are
     * and taken out of the search, so a re-run never undoes a decision (CLAUDE.md rule 7).
     * @param rejected (line ordinal, transaction id) pairs the person said are not the same payment: never proposed again.
     */
    fun match(
        statementLines: List<StatementLine>,
        ledger: List<ReconTxn>,
        from: LocalDate? = null,
        to: LocalDate? = null,
        pinned: List<ReconMatch> = emptyList(),
        rejected: Set<Pair<Int, Long>> = emptySet(),
    ): ReconResult {
        // The order the caller listed things in must not matter.
        val lines = statementLines.sortedBy { it.ordinal }
        val usedLines = HashSet<Int>()
        val usedTxns = HashSet<Long>()
        val paired = ArrayList<ReconMatch>()
        for (p in pinned.sortedWith(compareBy({ it.lineOrdinal }, { it.txnId }))) {
            val line = p.lineOrdinal ?: continue
            val txn = p.txnId ?: continue
            if (lines.none { it.ordinal == line } || ledger.none { it.id == txn }) continue
            usedLines += line
            usedTxns += txn
            paired += p
        }
        val txnById = ledger.associateBy { it.id }
        val lineByOrdinal = lines.associateBy { it.ordinal }

        // Pass 1: exact reference (the ref column, or a 12-digit UPI ref anywhere in the narration).
        val byRef = HashMap<String, MutableList<ReconTxn>>()
        for (t in ledger.sortedBy { it.id }) {
            val key = refKey(t.refNo) ?: continue
            byRef.getOrPut(key) { ArrayList() } += t
        }
        for (line in lines) {
            val keys = (listOf(refKey(line.refNo)) + narration.upiRefs(line.description).map(::refKey)).filterNotNull().distinct()
            val candidates = keys.flatMap { byRef[it].orEmpty() }
                .filter { it.id !in usedTxns && it.direction == directionOf(line) && (line.ordinal to it.id) !in rejected }
                .distinctBy { it.id }
                .sortedWith(compareBy({ Math.abs(ChronoUnit.DAYS.between(line.date, it.date)) }, { it.id }))
            val t = candidates.firstOrNull() ?: continue
            usedLines += line.ordinal
            usedTxns += t.id
            paired += if (t.amount == line.amount) {
                ReconMatch(line.ordinal, t.id, MatchStatus.MATCHED, 1000, listOf(ReconReasons.REF))
            } else {
                ReconMatch(line.ordinal, t.id, MatchStatus.AMOUNT_MISMATCH, 1000, listOf(ReconReasons.REF, ReconReasons.AMOUNT_DIFFERS))
            }
        }

        // Pass 2: same direction, same amount, within 3 days, best score first, one-to-one.
        class Candidate(val line: StatementLine, val txn: ReconTxn, val score: Int)

        val candidates = ArrayList<Candidate>()
        val freeTxns = ledger.filter { it.id !in usedTxns }
        for (line in lines) {
            if (line.ordinal in usedLines) continue
            val lineTokens = tokens(line.description)
            for (t in freeTxns) {
                if (t.direction != directionOf(line) || t.amount != line.amount || (line.ordinal to t.id) in rejected) continue
                val d = Math.abs(ChronoUnit.DAYS.between(line.date, t.date)).toInt()
                if (d > MAX_DAYS) continue
                val similarity = jaccardPm(lineTokens, tokens(t.description))
                val score = 500 + 300 * (MAX_DAYS + 1 - d) / (MAX_DAYS + 1) + 200 * similarity / 1000
                if (score >= FUZZY_MIN) candidates += Candidate(line, t, score)
            }
        }
        candidates.sortWith(compareBy<Candidate>({ -it.score }, { it.line.date }, { it.line.ordinal }, { it.txn.id }))
        for (c in candidates) {
            if (c.line.ordinal in usedLines || c.txn.id in usedTxns) continue
            usedLines += c.line.ordinal
            usedTxns += c.txn.id
            val status = if (c.score >= MATCHED_MIN) MatchStatus.MATCHED else MatchStatus.MATCHED_FUZZY
            paired += ReconMatch(c.line.ordinal, c.txn.id, status, c.score, listOf(ReconReasons.SCORED))
        }

        // Leftovers.
        val statementOnly = lines.filter { it.ordinal !in usedLines }
            .map { ReconMatch(it.ordinal, null, MatchStatus.STATEMENT_ONLY, 0, emptyList()) }
        val ledgerOnly = ledger.filter { it.id !in usedTxns }.sortedWith(compareBy({ it.date }, { it.id })).map {
            val outside = (from != null && it.date < from) || (to != null && it.date > to)
            ReconMatch(null, it.id, MatchStatus.LEDGER_ONLY, 0, if (outside) listOf(ReconReasons.OUTSIDE_PERIOD) else emptyList())
        }

        val hints = paired.mapNotNull { m ->
            val line = lineByOrdinal[m.lineOrdinal] ?: return@mapNotNull null
            val txn = txnById[m.txnId] ?: return@mapNotNull null
            val sb = line.balance ?: return@mapNotNull null
            val lb = txn.balanceAfter ?: return@mapNotNull null
            if (sb == lb) null else BalanceHint(line.ordinal, txn.id, sb, lb)
        }.sortedBy { it.lineOrdinal }

        return ReconResult(paired.sortedBy { it.lineOrdinal } + statementOnly + ledgerOnly, hints)
    }

    private fun directionOf(line: StatementLine): Direction = if (line.isDebit) Direction.DEBIT else Direction.CREDIT

    /** Upper-case letters and digits, leading zeros dropped; refs shorter than 6 characters are too weak to match on. */
    private fun refKey(ref: String?): String? {
        val k = ref?.uppercase()?.filter { it.isLetterOrDigit() }?.trimStart('0') ?: return null
        return k.takeIf { it.length >= MIN_REF }
    }

    private fun tokens(text: String?): Set<String> {
        if (text.isNullOrBlank()) return emptySet()
        return Text.normalise(narration.bound(text)).split(' ')
            .filter { it.length >= 2 && !it.all(Char::isDigit) && it !in noise }
            .toSet()
    }

    /** Jaccard similarity of two token sets in per-mille; 0 when both are empty. */
    private fun jaccardPm(a: Set<String>, b: Set<String>): Int {
        val union = a.size + b.size - a.intersect(b).size
        if (union == 0) return 0
        return a.intersect(b).size * 1000 / union
    }

    private companion object {
        const val MAX_DAYS = 3
        const val MATCHED_MIN = 850
        const val FUZZY_MIN = 600
        const val MIN_REF = 6
    }
}
