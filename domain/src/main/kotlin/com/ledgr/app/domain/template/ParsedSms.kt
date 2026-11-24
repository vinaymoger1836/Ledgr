package com.ledgr.app.domain.template

import com.ledgr.app.domain.ledger.Channel
import com.ledgr.app.domain.ledger.Direction
import com.ledgr.app.domain.ledger.TxnKind
import com.ledgr.app.domain.rules.TemplateKind

/**
 * What a template extracted from one SMS, validated (docs/03 §5 and §8). Money is `Long` paise. Holds only what the
 * ledger stores: last 4 digits, never a full account or card number, and never the SMS text.
 */
data class ParsedSms(
    val templateId: String,
    val templateKind: TemplateKind,
    /** The template's own kind override (refund, reversal, card bill ...), if any. */
    val txnKind: TxnKind?,
    /** Null for a balance-only template. */
    val direction: Direction?,
    val channel: Channel,
    val accountId: Long,
    /** Paise; 0 for a balance-only template. */
    val amount: Long,
    /** Epoch milliseconds (UTC). */
    val occurredAt: Long,
    /** The SMS date was more than 3 days from receipt, so the receipt time was used (docs/03 §5). */
    val dateSuspect: Boolean,
    val last4: String?,
    val vpa: String?,
    val merchant: String?,
    val payee: String?,
    val ref: String?,
    val balanceAfter: Long?,
    /** The card or account the money went to, for card bill payments (docs/04 §3 rule 3). */
    val destLast4: String?,
    /** 0..100. 100 = full match; below [LOW_CONFIDENCE] the transaction goes to Needs review → Check. */
    val confidence: Int,
    val mayDuplicateWith: List<String>,
) {
    /** The text the merchant resolver works from: the VPA, else the merchant, else the payee name. */
    val rawCounterparty: String? get() = vpa ?: merchant ?: payee

    companion object {
        const val LOW_CONFIDENCE = 70
    }
}
