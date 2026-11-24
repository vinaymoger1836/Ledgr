package com.ledgr.app.domain.ledger

import com.ledgr.app.domain.template.ParsedSms

/** Stands for the transaction being ingested, whose database id does not exist yet. */
const val NEW: Long = -1L

/**
 * A stored transaction as the pure engine sees it. Counterparties are [Identity] strings and categories are keys, so
 * the engine never needs the database. Built by the data layer (and by the golden runner's in-memory store).
 */
data class LedgerTxn(
    val id: Long,
    val accountId: Long,
    val kind: TxnKind,
    val direction: Direction,
    val amount: Long,
    val occurredAt: Long,
    val channel: Channel,
    val counterpartyKey: String?,
    val categoryKey: String?,
    val refNo: String?,
    val balanceAfter: Long?,
    val templateId: String?,
    val rawCounterparty: String? = null,
    val status: TxnStatus = TxnStatus.ACTIVE,
    val duplicateOf: Long? = null,
    val transferPairId: Long? = null,
    val refundOf: Long? = null,
    val userEdited: Set<EditableField> = emptySet(),
)

/** The new transaction, fully decided: kind, category, status and links already applied. */
data class TxnDraft(
    val rawSmsId: Long,
    val accountId: Long,
    val kind: TxnKind,
    val direction: Direction,
    val amount: Long,
    val occurredAt: Long,
    val channel: Channel,
    val rawCounterparty: String?,
    val resolution: Resolution,
    val categoryKey: String,
    val refNo: String?,
    val balanceAfter: Long?,
    val templateId: String,
    val rulesVersion: String,
    val confidence: Int,
    val checkReason: String?,
    val status: TxnStatus = TxnStatus.ACTIVE,
    val duplicateOf: Long? = null,
    val transferPairId: Long? = null,
    val refundOf: Long? = null,
)

/**
 * Changes to an **existing** transaction. Null means "leave as is". [NEW] as a link target means the transaction being
 * ingested. The engine never puts a field in here that the person edited (CLAUDE.md rule 7).
 */
data class TxnPatch(
    val id: Long,
    val kind: TxnKind? = null,
    val categoryKey: String? = null,
    val status: TxnStatus? = null,
    val duplicateOf: Long? = null,
    val transferPairId: Long? = null,
    val refundOf: Long? = null,
    val refNo: String? = null,
    val balanceAfter: Long? = null,
)

enum class ProposalType { DUPLICATE, TRANSFER }

/** A link the engine is not sure enough about to make: the person confirms or rejects it in Needs review. */
data class Proposal(val type: ProposalType, val txnId: Long, val otherId: Long)

class IngestPlan(
    val draft: TxnDraft,
    val patches: List<TxnPatch>,
    val proposals: List<Proposal>,
)

/** One SMS handed to [LedgerEngine.plan]. [body] is read in memory only for keyword checks; it is never stored here. */
class IngestInput(
    val parsed: ParsedSms,
    val body: String,
    val rawSmsId: Long,
    val rulesVersion: String,
)

internal object Windows {
    const val MINUTE = 60_000L
    const val DAY = 24 * 60 * MINUTE

    /** docs/04 §2: probable duplicates are within 10 minutes. */
    const val DUPLICATE = 10 * MINUTE

    /** docs/04 §3 rule 2: IMPS / UPI own transfers. */
    const val FAST_TRANSFER = 60 * MINUTE

    /** docs/04 §3 rule 2: NEFT / RTGS and card bills (calendar days; no holiday table, ADR-013). */
    const val SLOW_TRANSFER = 2 * DAY

    /** docs/04 §4. */
    const val REFUND = 60 * DAY

    /** docs/04 §5. */
    const val REVERSAL = 3 * DAY
}
