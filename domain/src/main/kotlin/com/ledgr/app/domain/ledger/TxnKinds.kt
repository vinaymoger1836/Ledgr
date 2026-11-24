package com.ledgr.app.domain.ledger

/**
 * Which [TxnKind]s a person may pick for a row (Transaction detail, manual entry). The bank's direction is a fact of the
 * message, so it limits the choice: a debit can be an expense or money moved out, a credit income, a refund or money
 * moved in. REVERSAL is only ever set by the linker.
 */
object TxnKinds {
    private val debit = listOf(TxnKind.EXPENSE, TxnKind.SELF_TRANSFER_OUT, TxnKind.CARD_BILL_PAYMENT)
    private val credit = listOf(TxnKind.INCOME, TxnKind.REFUND, TxnKind.SELF_TRANSFER_IN, TxnKind.CARD_PAYMENT_RECEIVED)

    fun forDirection(direction: Direction): List<TxnKind> = if (direction == Direction.DEBIT) debit else credit

    /** The plain kind for a direction: what manual entry starts with. */
    fun default(direction: Direction): TxnKind = if (direction == Direction.DEBIT) TxnKind.EXPENSE else TxnKind.INCOME
}
