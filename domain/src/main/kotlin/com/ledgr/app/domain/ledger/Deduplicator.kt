package com.ledgr.app.domain.ledger

import kotlin.math.abs

/**
 * docs/04 §2. Candidates are in the same account, the same direction and the same amount.
 * 1. The same reference number is a certain duplicate.
 * 2. Within 10 minutes with no conflicting references is a probable duplicate: merged when the two templates list each
 *    other in `may_duplicate_with`, otherwise proposed for the person to decide.
 * 3. Different references are never merged ("two coffees of ₹120").
 */
internal class Deduplicator(private val mayDuplicate: (String, String) -> Boolean) {

    sealed interface Result {
        data object None : Result

        /** [draftWins]: keep the new one and mark [existing] as the duplicate. Otherwise the reverse. */
        data class Merge(val existing: LedgerTxn, val draftWins: Boolean) : Result

        data class Possible(val existing: LedgerTxn) : Result
    }

    fun check(d: TxnDraft, candidates: List<LedgerTxn>): Result {
        val same = candidates.filter {
            it.status == TxnStatus.ACTIVE &&
                it.accountId == d.accountId &&
                it.direction == d.direction &&
                it.amount == d.amount
        }.sortedBy { abs(it.occurredAt - d.occurredAt) }

        same.firstOrNull { d.refNo != null && it.refNo == d.refNo }?.let { return merge(d, it) }

        val probable = same.firstOrNull { c ->
            abs(c.occurredAt - d.occurredAt) <= Windows.DUPLICATE &&
                !(d.refNo != null && c.refNo != null && d.refNo != c.refNo)
        } ?: return Result.None
        return if (listed(d.templateId, probable.templateId)) merge(d, probable) else Result.Possible(probable)
    }

    /** True when either template names the other in `may_duplicate_with`. */
    private fun listed(a: String, b: String?): Boolean = b != null && (mayDuplicate(a, b) || mayDuplicate(b, a))

    private fun merge(d: TxnDraft, existing: LedgerTxn): Result {
        // The richer record wins; a transaction the person edited always wins.
        val draftWins = existing.userEdited.isEmpty() && richness(d) > richness(existing)
        return Result.Merge(existing, draftWins)
    }

    private fun richness(d: TxnDraft): Int =
        (if (d.rawCounterparty != null) 1 else 0) + (if (d.balanceAfter != null) 1 else 0) +
            (if (d.refNo != null) 1 else 0) + (if (d.resolution.identityKey != null) 1 else 0)

    private fun richness(t: LedgerTxn): Int =
        (if (t.rawCounterparty != null) 1 else 0) + (if (t.balanceAfter != null) 1 else 0) +
            (if (t.refNo != null) 1 else 0) + (if (t.counterpartyKey != null) 1 else 0)
}
