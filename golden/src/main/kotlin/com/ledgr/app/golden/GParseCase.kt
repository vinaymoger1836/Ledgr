package com.ledgr.app.golden

import com.ledgr.app.domain.sms.SmsPipeline
import com.ledgr.app.domain.template.TemplateEngine
import com.ledgr.app.domain.template.TemplateStageImpl
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * G-PARSE (docs/03 §5, docs/12 §4): 40 synthetic SMS across three banks (UPI debit and credit, card spend, ATM, NEFT,
 * IMPS, auto-debit, card bill, refund, reversal, balance-only, and the failure paths) through the real gate and
 * template stage with the synthetic templates in `fixtures/parse/rules/templates`. Every extracted field is asserted
 * exactly: amount in paise, last 4, date, channel, reference, VPA / merchant / payee, balance.
 */
object GParseCase : GoldenCase {
    override val id = "G-PARSE"
    override val expectedFile = "g-parse.json"

    @Serializable
    private data class Fixture(val profile: FxProfile, val messages: List<FxMessage>)

    @Serializable
    private data class Row(
        val id: String,
        val outcome: String,
        val reason: String,
        val templateId: String?,
        val accountId: Long?,
        val direction: String?,
        val txnKind: String?,
        val channel: String?,
        val amountPaise: Long?,
        val last4: String?,
        val occurredAt: String?,
        val dateSuspect: Boolean?,
        val vpa: String?,
        val merchant: String?,
        val payee: String?,
        val ref: String?,
        val balancePaise: Long?,
        val destLast4: String?,
        val confidence: Int?,
    )

    override fun run(context: GoldenContext): String {
        val fixture = GoldenJson.json.decodeFromString(
            Fixture.serializer(),
            File(context.fixturesDir, "parse/messages.json").readText(Charsets.UTF_8),
        )
        val rules = GoldenRules.load(context)
        val stage = TemplateStageImpl(TemplateEngine(rules.templates), fixture.profile.accountRefs())
        val pipeline = SmsPipeline(rules, emptySet(), stage)

        val rows = fixture.messages.map { m ->
            val v = pipeline.process(m.toSms())
            val p = v.parsed
            Row(
                id = m.id,
                outcome = v.outcome.name,
                reason = v.reason,
                templateId = v.templateId,
                accountId = p?.accountId,
                direction = p?.direction?.name,
                txnKind = p?.txnKind?.name,
                channel = p?.channel?.name,
                amountPaise = p?.amount,
                last4 = p?.last4,
                occurredAt = p?.occurredAt?.let(::isoIst),
                dateSuspect = p?.dateSuspect,
                vpa = p?.vpa,
                merchant = p?.merchant,
                payee = p?.payee,
                ref = p?.ref,
                balancePaise = p?.balanceAfter,
                destLast4 = p?.destLast4,
                confidence = p?.confidence,
            )
        }
        return GoldenJson.finish(GoldenJson.json.encodeToString(ListSerializer(Row.serializer()), rows))
    }
}
