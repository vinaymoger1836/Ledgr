package com.ledgr.app.domain.sms

import com.ledgr.app.domain.rules.RulesBundle
import java.security.MessageDigest

/**
 * Layers L5 (template match) and L6 (account match). [NoTemplates] sends every message that passed the gate to
 * review; `TemplateStageImpl` is the real one (P3).
 */
fun interface TemplateStage {
    fun apply(sms: SmsInput, passed: GateResult.Pass): Verdict
}

/**
 * No templates: a gate-passing transaction is `REVIEW: no_template` (never a transaction) and a balance message is
 * `BALANCE: balance_only`. Used by gate-only goldens.
 */
object NoTemplates : TemplateStage {
    override fun apply(sms: SmsInput, passed: GateResult.Pass): Verdict =
        if (passed.smsClass == SmsClass.BALANCE_ONLY) {
            Verdict(Outcome.BALANCE, Reasons.BALANCE_ONLY, passed.sender, passed.smsClass)
        } else {
            Verdict(Outcome.REVIEW, Reasons.NO_TEMPLATE, passed.sender, passed.smsClass)
        }
}

/** Raw SMS -> verdict (docs/02 §3.3): sender normaliser, spam gate, classifier, template stage. */
class SmsPipeline(
    private val gate: SpamGate,
    private val templates: TemplateStage = NoTemplates,
) {
    constructor(
        rules: RulesBundle,
        confirmedHeaders: Set<String> = emptySet(),
        templates: TemplateStage = NoTemplates,
    ) : this(SpamGate(rules, confirmedHeaders), templates)

    fun process(sms: SmsInput, userConfirmed: Boolean = false): Verdict =
        when (val result = gate.evaluate(sms, userConfirmed)) {
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
