package com.ledgr.app.domain.ledger

import com.ledgr.app.domain.model.AccountType
import com.ledgr.app.domain.model.CounterpartyType
import com.ledgr.app.domain.template.AccountRef
import kotlin.math.abs

/** What [TransferLinker] decided about one new transaction. */
internal class TransferOutcome(
    /** The new kind for the draft, or null to keep the one it has. */
    val kind: TxnKind?,
    /** The opposite leg the draft is now paired with. */
    val pair: LedgerTxn?,
    val patches: List<TxnPatch>,
    val proposals: List<Proposal>,
)

/**
 * Own transfers and card bill payments (docs/04 §3, ADR-007).
 *
 * A transaction is an own transfer when any of these holds:
 * 1. the other side is one of the user's own identities (own VPA, or own name on a bank-to-bank channel);
 * 2. a matching opposite leg exists in another profile account (same amount, 60 minutes for IMPS/UPI, 2 days for
 *    NEFT/RTGS and card bills, equal references when both have one);
 * 3. the SMS names a destination whose last 4 digits belong to a profile account (`dest_last4`).
 *
 * Both legs seen: they are paired through `transfer_pair_id`. A single candidate is linked; several candidates are
 * proposed to the person instead (ADR-013). One side only: it is an own transfer only through rule 1 or 3, or when
 * the template says so, otherwise it stays an expense to a person.
 */
internal class TransferLinker(accounts: List<AccountRef>, private val categorizer: Categorizer) {
    private val byId: Map<Long, AccountRef> = accounts.associateBy { it.id }
    private val all: List<AccountRef> = accounts

    fun link(d: TxnDraft, destLast4: String?, candidates: List<LedgerTxn>): TransferOutcome {
        if (d.kind !in ELIGIBLE) return TransferOutcome(null, null, emptyList(), emptyList())

        val destAccount = destLast4?.let { l4 -> all.firstOrNull { it.last4 == l4 && it.id != d.accountId } }
        val evidence = d.kind in TRANSFER_KINDS || destAccount != null ||
            d.resolution.type == CounterpartyType.OWN_ACCOUNT
        val ownAccount = byId[d.accountId]

        val matches = candidates.filter { c ->
            c.status == TxnStatus.ACTIVE &&
                c.accountId != d.accountId &&
                c.direction != d.direction &&
                c.amount == d.amount &&
                c.transferPairId == null &&
                c.kind in LINKABLE &&
                // A leg the person re-kinded by hand is not ours to reclassify.
                (c.kind in TRANSFER_KINDS || EditableField.KIND !in c.userEdited) &&
                (destAccount == null || d.direction == Direction.CREDIT || c.accountId == destAccount.id) &&
                refsCompatible(d.refNo, c.refNo) &&
                abs(c.occurredAt - d.occurredAt) <= window(d, c)
        }.sortedBy { abs(it.occurredAt - d.occurredAt) }

        val refMatch = matches.firstOrNull { d.refNo != null && it.refNo == d.refNo }
        val pair = refMatch ?: matches.singleOrNull()
        val proposals = if (pair == null && matches.size > 1) {
            matches.take(MAX_PROPOSALS).map { Proposal(ProposalType.TRANSFER, NEW, it.id) }
        } else {
            emptyList()
        }

        val kind: TxnKind? = when {
            d.kind in TRANSFER_KINDS -> null
            pair != null -> kindFor(d.direction, ownAccount, destAccount ?: byId[pair.accountId])
            evidence -> kindFor(d.direction, ownAccount, destAccount)
            else -> null
        }

        val patches = if (pair != null) {
            val pairKind = when {
                pair.kind in TRANSFER_KINDS -> null
                else -> kindFor(pair.direction, byId[pair.accountId], if (pair.direction == Direction.DEBIT) ownAccount else null)
            }
            listOf(
                TxnPatch(
                    id = pair.id,
                    kind = pairKind,
                    categoryKey = pairKind?.let { k -> categorizer.forKind(k) }
                        ?.takeIf { EditableField.CATEGORY !in pair.userEdited },
                    transferPairId = NEW,
                ),
            )
        } else {
            emptyList()
        }
        return TransferOutcome(kind, pair, patches, proposals)
    }

    /**
     * The transfer kind for one leg. [counterAccount] is the account on the other side when known: a debit into a
     * credit card is a card bill payment; a credit on a card account is the card side of one.
     */
    private fun kindFor(direction: Direction, own: AccountRef?, counterAccount: AccountRef?): TxnKind =
        when (direction) {
            Direction.DEBIT ->
                if (counterAccount?.type == AccountType.CREDIT_CARD) TxnKind.CARD_BILL_PAYMENT else TxnKind.SELF_TRANSFER_OUT
            Direction.CREDIT ->
                if (own?.type == AccountType.CREDIT_CARD) TxnKind.CARD_PAYMENT_RECEIVED else TxnKind.SELF_TRANSFER_IN
        }

    private fun refsCompatible(a: String?, b: String?): Boolean = a == null || b == null || a == b

    private fun window(d: TxnDraft, c: LedgerTxn): Long {
        val slow = d.channel in SLOW_CHANNELS || c.channel in SLOW_CHANNELS ||
            byId[d.accountId]?.type == AccountType.CREDIT_CARD || byId[c.accountId]?.type == AccountType.CREDIT_CARD ||
            d.kind in CARD_KINDS || c.kind in CARD_KINDS
        return if (slow) Windows.SLOW_TRANSFER else Windows.FAST_TRANSFER
    }

    companion object {
        val TRANSFER_KINDS = setOf(
            TxnKind.SELF_TRANSFER_OUT, TxnKind.SELF_TRANSFER_IN, TxnKind.CARD_BILL_PAYMENT, TxnKind.CARD_PAYMENT_RECEIVED,
        )
        private val CARD_KINDS = setOf(TxnKind.CARD_BILL_PAYMENT, TxnKind.CARD_PAYMENT_RECEIVED)
        private val ELIGIBLE = setOf(TxnKind.EXPENSE, TxnKind.INCOME) + TRANSFER_KINDS
        private val LINKABLE = setOf(TxnKind.EXPENSE, TxnKind.INCOME) + TRANSFER_KINDS
        private val SLOW_CHANNELS = setOf(Channel.NEFT, Channel.RTGS)
        private const val MAX_PROPOSALS = 3
    }
}
