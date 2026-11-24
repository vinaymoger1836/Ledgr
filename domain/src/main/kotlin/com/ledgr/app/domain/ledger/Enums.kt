package com.ledgr.app.domain.ledger

/** What a transaction is (docs/04 §3). Stored as TEXT. */
enum class TxnKind {
    EXPENSE,
    INCOME,
    REFUND,
    SELF_TRANSFER_OUT,
    SELF_TRANSFER_IN,

    /** Bank side of paying an own credit card. */
    CARD_BILL_PAYMENT,

    /** Card side of the same payment. */
    CARD_PAYMENT_RECEIVED,
    REVERSAL,
}

enum class Direction { DEBIT, CREDIT }

/** docs/05 `txn.channel`. */
enum class Channel { UPI, CARD, IMPS, NEFT, RTGS, ATM, AUTO_DEBIT, CASH, OTHER }

enum class TxnStatus { ACTIVE, DUPLICATE, REVERSED, DELETED }

enum class Source { SMS, STATEMENT, MANUAL }

enum class ReconState { UNRECONCILED, MATCHED, MATCHED_FUZZY, LEDGER_ONLY }

/** Why a transaction sits in Needs review → Check (docs/08 §2.5). `null` means nothing to check. */
object CheckReasons {
    const val LOW_CONFIDENCE = "low_confidence"
    const val UNKNOWN_MERCHANT = "unknown_merchant"
    const val DATE_SUSPECT = "date_suspect"
}

/**
 * Fields a person can change on a transaction. A field named in `txn.user_edited_fields` is never touched by
 * re-parse, dedup, linking or reconciliation (CLAUDE.md rule 7).
 */
enum class EditableField {
    AMOUNT,
    OCCURRED_AT,
    KIND,
    CATEGORY,
    COUNTERPARTY,
    NOTE,
    ACCOUNT,
}
