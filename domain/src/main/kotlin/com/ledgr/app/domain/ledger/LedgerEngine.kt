package com.ledgr.app.domain.ledger

import com.ledgr.app.domain.model.CounterpartyType
import com.ledgr.app.domain.rules.RulesBundle
import com.ledgr.app.domain.sms.KeywordMatcher
import com.ledgr.app.domain.template.AccountRef
import com.ledgr.app.domain.template.ParsedSms

/**
 * Everything [LedgerEngine] needs besides the SMS: the merchant snapshot, the profile's accounts, and which template
 * pairs may duplicate each other. Built once per scan page by the data layer, or from a fixture by the golden runner.
 */
class LedgerContext(
    val merchant: MerchantContext,
    val accounts: List<AccountRef>,
    /** `(templateA, templateB)` pairs from `may_duplicate_with`. */
    val mayDuplicate: Set<Pair<String, String>> = emptySet(),
) {
    companion object {
        /** The `may_duplicate_with` pairs of all shipped templates. */
        fun duplicatePairs(rules: RulesBundle): Set<Pair<String, String>> =
            rules.templates.flatMap { f -> f.templates.flatMap { t -> t.mayDuplicateWith.map { t.id to it } } }.toSet()
    }
}

/**
 * Turns one parsed SMS into a ledger decision (docs/02 §3.3): merchant, kind, category, then dedup, transfer, refund
 * and reversal linking. Pure: the data layer passes in the nearby existing transactions ([candidates]) and applies the
 * returned [IngestPlan]; the golden runner applies the same plan to an in-memory list.
 *
 * The engine never writes a field the person edited ([LedgerTxn.userEdited]); it just does not put it in a patch.
 */
class LedgerEngine(private val ctx: LedgerContext) {
    private val resolver = MerchantResolver(ctx.merchant)
    private val categorizer = Categorizer(ctx.merchant)
    private val dedup = Deduplicator { a, b -> (a to b) in ctx.mayDuplicate }
    private val transfers = TransferLinker(ctx.accounts, categorizer)
    private val refunds = RefundLinker()
    private val refundWords = KeywordMatcher(ctx.merchant.ledger.refundKeywords)

    /** Who the other side is. Public so the data layer can fetch that counterparty's history before planning. */
    fun resolve(p: ParsedSms): Resolution = resolver.resolve(CounterpartyInput(p.vpa, p.merchant, p.payee), p.channel)

    /**
     * "Add to ledger" for a statement line (docs/06 §4): who the other side is, the base kind and the category, from the
     * narration alone. There is no SMS, so no template, no dedup and no linking; those run if an SMS for the same payment
     * arrives later (the shared reference makes the deduplicator merge them).
     */
    fun classifyStatementLine(input: CounterpartyInput, channel: Channel, direction: Direction, narration: String): StatementClassification {
        val resolution = resolver.resolve(input, channel)
        val kind = when {
            resolution.type == CounterpartyType.OWN_ACCOUNT ->
                if (direction == Direction.DEBIT) TxnKind.SELF_TRANSFER_OUT else TxnKind.SELF_TRANSFER_IN
            else -> baseKind(direction, KeywordMatcher.normalize(narration))
        }
        return StatementClassification(resolution, kind, categorizer.categorize(resolution, kind, channel, direction, narration))
    }

    fun plan(input: IngestInput, candidates: List<LedgerTxn>): IngestPlan {
        val p = input.parsed
        val direction = p.direction ?: error("a balance-only SMS has no transaction")
        val resolution = resolve(p)
        val normalizedBody = KeywordMatcher.normalize(input.body)

        var draft = TxnDraft(
            rawSmsId = input.rawSmsId,
            accountId = p.accountId,
            kind = p.txnKind ?: baseKind(direction, normalizedBody),
            direction = direction,
            amount = p.amount,
            occurredAt = p.occurredAt,
            channel = p.channel,
            rawCounterparty = p.rawCounterparty,
            resolution = resolution,
            categoryKey = "",
            refNo = p.ref,
            balanceAfter = p.balanceAfter,
            templateId = p.templateId,
            rulesVersion = input.rulesVersion,
            confidence = p.confidence,
            checkReason = null,
        )
        val patches = ArrayList<TxnPatch>()
        val proposals = ArrayList<Proposal>()
        var inheritedCategory: String? = null

        // 1. Duplicates.
        var duplicate = false
        when (val r = dedup.check(draft, candidates)) {
            Deduplicator.Result.None -> Unit
            is Deduplicator.Result.Possible -> proposals += Proposal(ProposalType.DUPLICATE, NEW, r.existing.id)
            is Deduplicator.Result.Merge -> {
                if (r.draftWins) {
                    patches += TxnPatch(id = r.existing.id, status = TxnStatus.DUPLICATE, duplicateOf = NEW)
                } else {
                    duplicate = true
                    draft = draft.copy(status = TxnStatus.DUPLICATE, duplicateOf = r.existing.id)
                    // The survivor learns what only the duplicate knew.
                    val ref = if (r.existing.refNo == null) draft.refNo else null
                    val balance = if (r.existing.balanceAfter == null) draft.balanceAfter else null
                    if (ref != null || balance != null) {
                        patches += TxnPatch(id = r.existing.id, refNo = ref, balanceAfter = balance)
                    }
                }
            }
        }

        if (!duplicate) {
            val others = candidates.filter { it.status != TxnStatus.DUPLICATE }
            when (draft.kind) {
                TxnKind.REVERSAL -> {
                    val r = refunds.linkReversal(draft, others)
                    patches += r.patches
                    draft = draft.copy(refundOf = r.refundOf, status = r.status ?: draft.status)
                }
                TxnKind.REFUND -> {
                    val r = refunds.linkRefund(draft, others)
                    draft = draft.copy(refundOf = r.refundOf)
                    inheritedCategory = r.category
                }
                else -> {
                    val t = transfers.link(draft, p.destLast4, others)
                    patches += t.patches
                    proposals += t.proposals
                    draft = draft.copy(kind = t.kind ?: draft.kind, transferPairId = t.pair?.id)
                    // The category is final once the kind is, and refunds waiting for this expense adopt it.
                    draft = draft.copy(categoryKey = categorizer.categorize(resolution, draft.kind, draft.channel, direction, input.body))
                    if (draft.kind == TxnKind.EXPENSE || draft.kind == TxnKind.INCOME) {
                        val (waiting, reversed) = refunds.linkWaiting(draft, others)
                        patches += waiting
                        if (reversed) draft = draft.copy(status = TxnStatus.REVERSED)
                    }
                }
            }
        }

        if (draft.categoryKey.isEmpty()) {
            val category = inheritedCategory
                ?: categorizer.categorize(resolution, draft.kind, draft.channel, direction, input.body)
            draft = draft.copy(categoryKey = category)
        }
        return IngestPlan(draft.copy(checkReason = checkReason(p, resolution, draft)), patches, proposals)
    }

    private fun baseKind(direction: Direction, normalizedBody: String): TxnKind =
        when {
            direction == Direction.DEBIT -> TxnKind.EXPENSE
            refundWords.matches(normalizedBody) -> TxnKind.REFUND
            else -> TxnKind.INCOME
        }

    /** One reason is kept, most important first (docs/08 §2.5 Check tab). */
    private fun checkReason(p: ParsedSms, resolution: Resolution, d: TxnDraft): String? =
        when {
            d.status != TxnStatus.ACTIVE -> null
            p.confidence < ParsedSms.LOW_CONFIDENCE -> CheckReasons.LOW_CONFIDENCE
            p.dateSuspect -> CheckReasons.DATE_SUSPECT
            resolution.needsCheck && d.direction == Direction.DEBIT && d.kind == TxnKind.EXPENSE ->
                CheckReasons.UNKNOWN_MERCHANT
            else -> null
        }
}

/** What [LedgerEngine.classifyStatementLine] decided for a statement line. */
class StatementClassification(val resolution: Resolution, val kind: TxnKind, val categoryKey: String)
