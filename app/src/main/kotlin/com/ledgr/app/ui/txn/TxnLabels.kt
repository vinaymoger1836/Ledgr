package com.ledgr.app.ui.txn

import androidx.annotation.StringRes
import com.ledgr.app.R
import com.ledgr.app.domain.ledger.TxnKind
import com.ledgr.app.domain.model.CategoryKind

/** The person-facing name of a transaction kind (docs/04 §6). Shown as text, so colour is never the only cue. */
@StringRes
fun kindLabel(kind: TxnKind): Int = when (kind) {
    TxnKind.EXPENSE -> R.string.txn_kind_expense
    TxnKind.INCOME -> R.string.txn_kind_income
    TxnKind.REFUND -> R.string.txn_kind_refund
    TxnKind.SELF_TRANSFER_OUT -> R.string.txn_kind_transfer_out
    TxnKind.SELF_TRANSFER_IN -> R.string.txn_kind_transfer_in
    TxnKind.CARD_BILL_PAYMENT -> R.string.txn_kind_card_bill
    TxnKind.CARD_PAYMENT_RECEIVED -> R.string.txn_kind_card_payment_received
    TxnKind.REVERSAL -> R.string.txn_kind_reversal
}

/** Which categories fit a kind of transaction: expenses and refunds use expense categories, and so on. */
fun categoryKindFor(kind: TxnKind): CategoryKind = when (kind) {
    TxnKind.INCOME -> CategoryKind.INCOME
    TxnKind.SELF_TRANSFER_OUT, TxnKind.SELF_TRANSFER_IN, TxnKind.CARD_BILL_PAYMENT, TxnKind.CARD_PAYMENT_RECEIVED ->
        CategoryKind.TRANSFER
    TxnKind.EXPENSE, TxnKind.REFUND, TxnKind.REVERSAL -> CategoryKind.EXPENSE
}
