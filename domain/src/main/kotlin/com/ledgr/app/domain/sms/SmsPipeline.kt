package com.ledgr.app.domain.sms

import com.ledgr.app.domain.rules.RulesBundle
import java.security.MessageDigest

/**
 * Layers L5 (template match) and L6 (account match). Real templates arrive in P3; until then
 * [NoTemplates] sends every message that passed the gate to review.
 */
fun interface TemplateStage {
    fun apply(sms: SmsInput, passed: GateResult.Pass): Verdict
}

/** P2 stub: no templates yet, so a gate-passing message is `REVIEW: no_template` (never a transaction). */
object NoTemplates : TemplateStage {
    override fun apply(sms: SmsInput, passed: GateResult.Pass): Verdict =
        Verdict(Outcome.REVIEW, Reasons.NO_TEMPLATE, passed.sender, passed.smsClass)
}

/** Raw SMS -> verdict (docs/02 §3.3, P2 part): sender normaliser, spam gate, classifier, template stub. */
class SmsPipeline(
    private val gate: SpamGate,
    private val templates: TemplateStage = NoTemplates,
) {
    constructor(rules: RulesBundle, confirmedHeaders: Set<String> = emptySet()) : this(SpamGate(rules, confirmedHeaders))

    fun process(sms: SmsInput): Verdict =
        when (val result = gate.evaluate(sms)) {
            is GateResult.Stop -> result.verdict
            is GateResult.Pass -> templates.apply(sms, result)
        }
}

/** `body_hash`: SHA-256 (lower-case hex) of the UTF-8 body only. Survives body purge (docs/05, ADR-012). */
object BodyHash {
    fun sha256Hex(body: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}
