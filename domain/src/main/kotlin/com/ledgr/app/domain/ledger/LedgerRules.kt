package com.ledgr.app.domain.ledger

import java.time.LocalDate

/** Which numbers a screen is asking for (docs/04 §6). */
sealed interface LedgerView {
    /** One bank or card account on its own: a transfer out is spend in the source bank. */
    data object PerAccount : LedgerView

    /**
     * Several accounts together. [includeOwnTransfers] is the "Count transfers between my own accounts" setting
     * (off by default; turning it on double-counts a transfer by design).
     */
    data class Combined(val includeOwnTransfers: Boolean) : LedgerView
}

/** One group of stored transactions, summed by the database. The grouping keys are what the screens slice by. */
data class DailyFact(
    val localDate: LocalDate,
    val accountId: Long,
    val categoryId: Long?,
    val counterpartyId: Long?,
    val kind: TxnKind,
    val status: TxnStatus,
    /** `refund_of` is set: a refund is linked to its expense, a reversal to its debit. */
    val linked: Boolean,
    /** Sum of the group's amounts, paise (always positive; the sign comes from [LedgerRules]). */
    val amount: Long,
)

/** Spend and income in paise. Spend can be negative on a day that only has a linked refund. */
data class Contribution(val spend: Long = 0, val income: Long = 0) {
    operator fun plus(other: Contribution) = Contribution(spend + other.spend, income + other.income)

    companion object {
        val ZERO = Contribution()
    }
}

/**
 * **The one definition of "what counts as spend or income"** (CLAUDE.md rule 5, docs/04 §6, ADR-007). The UI and the
 * DAO-backed queries call this; nobody re-implements it. The database does the grouping and summing; this decides what
 * each group means.
 *
 * | Kind | Per account | Combined, own transfers off | Combined, own transfers on |
 * |---|---|---|---|
 * | EXPENSE | spend | spend | spend |
 * | INCOME | income | income | income |
 * | SELF_TRANSFER_OUT | spend | excluded | spend |
 * | SELF_TRANSFER_IN | income | excluded | income |
 * | CARD_BILL_PAYMENT | spend | excluded | spend |
 * | CARD_PAYMENT_RECEIVED | excluded | excluded | excluded |
 * | REFUND linked | negative spend | same | same |
 * | REFUND not linked | income | income | income |
 * | REVERSAL | excluded | excluded | excluded |
 *
 * Only ACTIVE rows count: duplicates, reversed pairs and deleted rows are left out.
 */
object LedgerRules {

    fun contribution(kind: TxnKind, status: TxnStatus, linked: Boolean, amount: Long, view: LedgerView): Contribution {
        if (status != TxnStatus.ACTIVE) return Contribution.ZERO
        val ownTransfers = when (view) {
            LedgerView.PerAccount -> true
            is LedgerView.Combined -> view.includeOwnTransfers
        }
        return when (kind) {
            TxnKind.EXPENSE -> Contribution(spend = amount)
            TxnKind.INCOME -> Contribution(income = amount)
            TxnKind.SELF_TRANSFER_OUT, TxnKind.CARD_BILL_PAYMENT ->
                if (ownTransfers) Contribution(spend = amount) else Contribution.ZERO
            TxnKind.SELF_TRANSFER_IN -> if (ownTransfers) Contribution(income = amount) else Contribution.ZERO
            TxnKind.CARD_PAYMENT_RECEIVED, TxnKind.REVERSAL -> Contribution.ZERO
            TxnKind.REFUND -> if (linked) Contribution(spend = -amount) else Contribution(income = amount)
        }
    }

    fun contribution(fact: DailyFact, view: LedgerView): Contribution =
        contribution(fact.kind, fact.status, fact.linked, fact.amount, view)

    fun total(facts: Iterable<DailyFact>, view: LedgerView): Contribution =
        facts.fold(Contribution.ZERO) { acc, f -> acc + contribution(f, view) }

    fun daily(facts: Iterable<DailyFact>, view: LedgerView): Map<LocalDate, Contribution> =
        group(facts, view) { it.localDate }

    fun byCategory(facts: Iterable<DailyFact>, view: LedgerView): Map<Long?, Contribution> =
        group(facts, view) { it.categoryId }

    fun byAccount(facts: Iterable<DailyFact>, view: LedgerView): Map<Long, Contribution> =
        group(facts, view) { it.accountId }

    private fun <K> group(facts: Iterable<DailyFact>, view: LedgerView, key: (DailyFact) -> K): Map<K, Contribution> {
        val out = LinkedHashMap<K, Contribution>()
        for (f in facts) {
            val c = contribution(f, view)
            if (c != Contribution.ZERO) out[key(f)] = (out[key(f)] ?: Contribution.ZERO) + c
        }
        return out
    }
}
