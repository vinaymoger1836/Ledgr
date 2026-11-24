package com.ledgr.app.domain.ledger

/** What [RefundLinker] decided about the new transaction. */
internal class RefundOutcome(
    val refundOf: Long?,
    /** A linked refund takes its original's category (docs/04 §4); null when unlinked. */
    val category: String?,
    val status: TxnStatus?,
    val patches: List<TxnPatch>,
)

/**
 * Refunds and reversals (docs/04 §4–§5). Works in both directions so the result does not depend on the order the
 * messages are scanned in: a refund looks for its expense, and a new expense looks for refunds waiting for it.
 *
 * - REFUND: links to the most recent EXPENSE of the same counterparty, no later than the refund and at most 60 days
 *   before it, that can still absorb the amount (cumulative refunds never exceed the original, ADR-013).
 * - REVERSAL: links to the debit in the same account with the same reference, or the same amount within 3 days.
 *   Both rows become REVERSED and are left out of spend. The link is stored in `refund_of`.
 */
internal class RefundLinker {

    fun linkRefund(d: TxnDraft, candidates: List<LedgerTxn>): RefundOutcome {
        val key = d.resolution.identityKey ?: return NONE
        val original = candidates
            .filter {
                it.status == TxnStatus.ACTIVE && it.kind == TxnKind.EXPENSE && it.direction == Direction.DEBIT &&
                    it.counterpartyKey == key && it.occurredAt <= d.occurredAt &&
                    d.occurredAt - it.occurredAt <= Windows.REFUND &&
                    remaining(it, candidates) >= d.amount
            }
            .maxByOrNull { it.occurredAt } ?: return NONE
        return RefundOutcome(original.id, original.categoryKey, null, emptyList())
    }

    fun linkReversal(d: TxnDraft, candidates: List<LedgerTxn>): RefundOutcome {
        val original = candidates
            .filter {
                it.status == TxnStatus.ACTIVE && it.direction == Direction.DEBIT && it.accountId == d.accountId &&
                    it.kind != TxnKind.REVERSAL && it.amount == d.amount && it.occurredAt <= d.occurredAt + Windows.MINUTE &&
                    (sameRef(d.refNo, it.refNo) || d.occurredAt - it.occurredAt <= Windows.REVERSAL)
            }
            .sortedWith(compareByDescending<LedgerTxn> { sameRef(d.refNo, it.refNo) }.thenByDescending { it.occurredAt })
            .firstOrNull() ?: return NONE
        return RefundOutcome(
            refundOf = original.id,
            category = null,
            status = TxnStatus.REVERSED,
            patches = listOf(TxnPatch(id = original.id, status = TxnStatus.REVERSED)),
        )
    }

    /**
     * A new debit may be the original of refunds or reversals that arrived first. Returns patches for those rows and
     * whether the new debit itself is now reversed.
     */
    fun linkWaiting(d: TxnDraft, candidates: List<LedgerTxn>): Pair<List<TxnPatch>, Boolean> {
        if (d.direction != Direction.DEBIT) return emptyList<TxnPatch>() to false
        val patches = ArrayList<TxnPatch>()

        val reversal = candidates.firstOrNull {
            it.status == TxnStatus.ACTIVE && it.kind == TxnKind.REVERSAL && it.refundOf == null &&
                it.accountId == d.accountId && it.amount == d.amount && it.occurredAt >= d.occurredAt - Windows.MINUTE &&
                (sameRef(it.refNo, d.refNo) || it.occurredAt - d.occurredAt <= Windows.REVERSAL)
        }
        if (reversal != null) {
            patches += TxnPatch(id = reversal.id, status = TxnStatus.REVERSED, refundOf = NEW)
            return patches to true
        }

        val key = d.resolution.identityKey
        if (d.kind == TxnKind.EXPENSE && key != null) {
            var room = d.amount
            val waiting = candidates
                .filter {
                    it.status == TxnStatus.ACTIVE && it.kind == TxnKind.REFUND && it.refundOf == null &&
                        it.counterpartyKey == key && it.occurredAt >= d.occurredAt &&
                        it.occurredAt - d.occurredAt <= Windows.REFUND
                }
                .sortedBy { it.occurredAt }
            for (r in waiting) {
                if (r.amount > room) continue
                room -= r.amount
                patches += TxnPatch(
                    id = r.id,
                    refundOf = NEW,
                    categoryKey = d.categoryKey.takeIf { EditableField.CATEGORY !in r.userEdited },
                )
            }
        }
        return patches to false
    }

    /** How much of [original] later refunds have not yet absorbed. */
    private fun remaining(original: LedgerTxn, all: List<LedgerTxn>): Long =
        original.amount - all.filter { it.refundOf == original.id && it.kind == TxnKind.REFUND && it.status == TxnStatus.ACTIVE }
            .sumOf { it.amount }

    private fun sameRef(a: String?, b: String?): Boolean = a != null && a == b

    private companion object {
        val NONE = RefundOutcome(null, null, null, emptyList())
    }
}
