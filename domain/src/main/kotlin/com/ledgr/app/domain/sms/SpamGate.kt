package com.ledgr.app.domain.sms

import com.ledgr.app.domain.rules.RulesBundle
import com.ledgr.app.domain.sender.Sender
import com.ledgr.app.domain.sender.SenderNormalizer
import com.ledgr.app.domain.sender.SenderSuffix
import com.ledgr.app.domain.sender.SenderType
import com.ledgr.app.domain.time.Ist

/** The gate either lets a message through to the template stage or stops it with a [Verdict]. */
sealed interface GateResult {
    data class Pass(val sender: Sender, val smsClass: SmsClass, val bankId: String?) : GateResult

    data class Stop(val verdict: Verdict) : GateResult
}

/**
 * The mandatory spam gate (docs/04 §1, ADR-005): layers L1-L4 and L7. L5 (template) and L6 (account) run after
 * a [GateResult.Pass], in [SmsPipeline]. Nothing here creates a transaction, and every message ends with an
 * outcome and a reason code.
 *
 * Keyword lists, the suffix cut-off and per-bank link exceptions all come from `spam.yaml`; known headers come
 * from `banks.yaml` plus [confirmedHeaders] (the owner's confirmed headers from Profile).
 */
class SpamGate(rules: RulesBundle, confirmedHeaders: Set<String> = emptySet()) {
    private val classifier = Classifier(rules.spam)
    private val actionWords = KeywordMatcher(rules.spam.redFlags.actionWords)
    private val cutoffMillis: Long = Ist.startOfDay(rules.spam.cutoffDate)
    private val confirmed: Set<String> = confirmedHeaders.map { it.trim().uppercase() }.toSet()
    private val headerToBank: Map<String, String> =
        rules.banks.banks.flatMap { bank -> bank.allHeaders.map { it to bank.id } }.toMap()
    private val linksOkBanks: Set<String> = rules.spam.bankExceptions.filter { it.linksOk }.map { it.bank }.toSet()

    fun evaluate(sms: SmsInput): GateResult {
        val sender = SenderNormalizer.normalize(sms.address)

        // L1: sender type. A phone number is never a bank.
        when (sender.type) {
            SenderType.PHONE_NUMBER -> {
                // A bank-style message from a mobile number is suspicious, not just ignorable.
                val looksLikeTxn = classifier.classify(sms.body) == SmsClass.TXN_CANDIDATE
                val reason = if (looksLikeTxn) Reasons.PHONE_SENDER_TXN_LIKE else Reasons.PHONE_SENDER
                return stop(if (looksLikeTxn) Outcome.QUARANTINE else Outcome.IGNORED, reason, sender)
            }
            SenderType.INVALID -> return stop(Outcome.IGNORED, Reasons.INVALID_SENDER, sender)
            SenderType.HEADER -> Unit
        }

        // L2: TRAI suffix. `-P` is promotional, always.
        if (sender.suffix == SenderSuffix.P) return stop(Outcome.IGNORED, Reasons.PROMOTIONAL_SUFFIX, sender)

        // L3: known bank header (banks.yaml) or one the owner confirmed.
        val header = sender.header.orEmpty()
        val bankId = headerToBank[header]
        val known = bankId != null || header in confirmed
        if (!known) return stop(Outcome.IGNORED, Reasons.UNKNOWN_SENDER, sender)

        // L2 (no suffix): fine before the roll-out cut-off or for a confirmed header; otherwise quarantine.
        // ADR-012: this runs after the L3 membership check so unknown senders do not flood Suspicious.
        if (sender.suffix == SenderSuffix.NONE && sms.receivedAt >= cutoffMillis && header !in confirmed) {
            return stop(Outcome.QUARANTINE, Reasons.MISSING_SUFFIX, sender)
        }

        // L4: classifier.
        val smsClass = classifier.classify(sms.body)
        when (smsClass) {
            SmsClass.OTP -> return stop(Outcome.IGNORED, Reasons.OTP, sender, smsClass)
            SmsClass.REMINDER -> return stop(Outcome.IGNORED, Reasons.REMINDER, sender, smsClass)
            SmsClass.FAILED -> return stop(Outcome.IGNORED, Reasons.FAILED, sender, smsClass)
            SmsClass.PROMO -> return stop(Outcome.IGNORED, Reasons.PROMO, sender, smsClass)
            SmsClass.BALANCE_ONLY -> return stop(Outcome.BALANCE, Reasons.BALANCE_ONLY, sender, smsClass)
            SmsClass.NONE -> return stop(Outcome.IGNORED, Reasons.NO_TXN_SIGNAL, sender, smsClass)
            SmsClass.TXN_CANDIDATE -> Unit
        }

        // L7: red flags inside an otherwise valid-looking transaction.
        if (hasRedFlags(sms.body, bankId)) return stop(Outcome.QUARANTINE, Reasons.RED_FLAGS, sender, smsClass)

        return GateResult.Pass(sender, smsClass, bankId)
    }

    /** A link plus an action word, or a mobile number plus an action word (docs/04 §1 L7). */
    private fun hasRedFlags(body: String, bankId: String?): Boolean {
        val bounded = if (body.length > KeywordMatcher.MAX_BODY) body.substring(0, KeywordMatcher.MAX_BODY) else body
        val text = KeywordMatcher.normalize(bounded)
        if (!actionWords.matches(text)) return false
        val linkAllowed = bankId != null && bankId in linksOkBanks
        return (!linkAllowed && URL.containsMatchIn(text)) || MOBILE_NUMBER.containsMatchIn(text)
    }

    private fun stop(outcome: Outcome, reason: String, sender: Sender, smsClass: SmsClass? = null): GateResult.Stop =
        GateResult.Stop(Verdict(outcome, reason, sender, smsClass))

    private companion object {
        /** Generic link shapes. Linear-time: no nested quantifiers. */
        val URL = Regex("""https?://|www\.|[a-z0-9-]{2,}\.(?:com|in|co|net|org|link|xyz|ly|me|app|info|biz|top|site|online)\b""")

        /** An Indian mobile number as its own token (not part of a longer reference number). */
        val MOBILE_NUMBER = Regex("""(?<![0-9])(?:\+?91[\s-]?)?[6-9][0-9]{9}(?![0-9])""")
    }
}
