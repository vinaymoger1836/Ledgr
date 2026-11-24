package com.ledgr.app.domain.ledger

import com.ledgr.app.domain.model.CounterpartyType
import com.ledgr.app.domain.sms.KeywordMatcher

/**
 * docs/03 §7. Returns a `categories.yaml` key; the data layer maps it to a row (through `merged_into_id`).
 *
 * Order: a kind that has its own category (own transfer, card bill, refund) wins; then the user's rule for the
 * counterparty; the shipped merchant default; channel rules (ATM); salary keywords on a credit; people; a plain
 * credit; and finally the fallback. Every key comes from `ledger.yaml`.
 */
class Categorizer(private val ctx: MerchantContext) {
    private val salary = KeywordMatcher(ctx.ledger.salaryKeywords)

    fun categorize(
        resolution: Resolution,
        kind: TxnKind,
        channel: Channel,
        direction: Direction,
        body: String,
    ): String {
        val rules = ctx.ledger.categories
        rules.byKind[kind.name]?.let { return it }
        resolution.identityKey?.let { key -> ctx.categoryDefaults[key]?.let { return it } }
        resolution.dictionaryCategory?.let { return it }
        rules.byChannel[channel.name]?.let { return it }
        if (direction == Direction.CREDIT && salary.matches(KeywordMatcher.normalize(body))) return rules.salary
        if (resolution.type == CounterpartyType.PERSON && direction == Direction.DEBIT) return rules.person
        if (direction == Direction.CREDIT) return rules.creditDefault
        return rules.fallback
    }

    /** The category a kind forces, if any (used when a link changes an existing transaction's kind). */
    fun forKind(kind: TxnKind): String? = ctx.ledger.categories.byKind[kind.name]
}
