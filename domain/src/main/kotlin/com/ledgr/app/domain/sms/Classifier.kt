package com.ledgr.app.domain.sms

import com.ledgr.app.domain.rules.SpamFile

/** Gate layer L4 classes (docs/03 §4). */
enum class SmsClass {
    OTP,
    REMINDER,
    FAILED,
    PROMO,
    BALANCE_ONLY,

    /** Amount plus a debit/credit verb. The only class that goes on to templates. */
    TXN_CANDIDATE,

    /** No signal at all (greetings, service notices). */
    NONE,
}

/**
 * Whole-word / whole-phrase, case-insensitive keyword matching with no regex, so there is nothing to
 * backtrack on. Keywords come from `spam.yaml`.
 */
internal class KeywordMatcher(keywords: List<String>) {
    private val needles: List<String> = keywords.map { normalize(it) }.filter { it.isNotEmpty() }.distinct()

    fun matches(normalizedText: String): Boolean = needles.any { containsWord(normalizedText, it) }

    private fun containsWord(text: String, needle: String): Boolean {
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return false
            val before = if (at == 0) ' ' else text[at - 1]
            val afterIndex = at + needle.length
            val after = if (afterIndex >= text.length) ' ' else text[afterIndex]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            from = at + 1
        }
    }

    companion object {
        /** Lower-case, collapse whitespace, and bound the length (docs/09: regex/text input <= 2,000 chars). */
        fun normalize(text: String): String {
            val bounded = if (text.length > MAX_BODY) text.substring(0, MAX_BODY) else text
            val out = StringBuilder(bounded.length)
            var lastSpace = true
            for (c in bounded) {
                if (c.isWhitespace()) {
                    if (!lastSpace) out.append(' ')
                    lastSpace = true
                } else {
                    out.append(c.lowercaseChar())
                    lastSpace = false
                }
            }
            return out.toString().trimEnd()
        }

        const val MAX_BODY = 2000
    }
}

/**
 * Layer L4. Decides what kind of message a body is, from the keyword lists in `spam.yaml`
 * (docs/03 §4). Priority, highest first:
 * OTP, FAILED, REMINDER, then TXN_CANDIDATE (amount marker + debit/credit verb) so a real
 * transaction is never ignored as a promo, then PROMO, BALANCE_ONLY, and finally NONE.
 */
class Classifier(spam: SpamFile) {
    private val otp = KeywordMatcher(spam.classes["otp"].orEmpty())
    private val failed = KeywordMatcher(spam.classes["failed"].orEmpty())
    private val reminder = KeywordMatcher(spam.classes["reminder"].orEmpty())
    private val promo = KeywordMatcher(spam.classes["promo"].orEmpty())
    private val balanceOnly = KeywordMatcher(spam.classes["balance_only"].orEmpty())
    private val txnVerbs = KeywordMatcher(spam.classes["txn_verbs"].orEmpty())

    fun classify(body: String): SmsClass {
        val text = KeywordMatcher.normalize(body)
        return when {
            otp.matches(text) -> SmsClass.OTP
            failed.matches(text) -> SmsClass.FAILED
            reminder.matches(text) -> SmsClass.REMINDER
            hasAmountMarker(text) && txnVerbs.matches(text) -> SmsClass.TXN_CANDIDATE
            promo.matches(text) -> SmsClass.PROMO
            balanceOnly.matches(text) -> SmsClass.BALANCE_ONLY
            else -> SmsClass.NONE
        }
    }

    internal companion object {
        /**
         * "Rs 500", "Rs.500", "INR 1,200", "₹99". This only detects that an amount is present; the amount itself
         * is parsed by templates in P3 (money is `Long` paise, never read here).
         */
        private val AMOUNT_MARKER = Regex("""(?<![a-z])(?:rs\.?|inr|₹)\s?[0-9]""")

        fun hasAmountMarker(normalizedText: String): Boolean = AMOUNT_MARKER.containsMatchIn(normalizedText)
    }
}
