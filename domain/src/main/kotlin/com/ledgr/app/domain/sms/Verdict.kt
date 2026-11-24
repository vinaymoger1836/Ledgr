package com.ledgr.app.domain.sms

import com.ledgr.app.domain.sender.Sender

/** What happened to one SMS (docs/05 `raw_sms.outcome`). */
enum class Outcome { TXN, IGNORED, REVIEW, QUARANTINE, BALANCE }

/**
 * Reason codes (docs/04 §1, ADR-012). Identifiers only: the keyword lists behind them live in
 * `spam.yaml`. The full code shown to people is `"OUTCOME: reason"` (see [Verdict.code]).
 */
object Reasons {
    // L1 sender type
    const val PHONE_SENDER = "phone_sender"
    const val PHONE_SENDER_TXN_LIKE = "phone_sender_txn_like"
    const val INVALID_SENDER = "invalid_sender"

    // L2 TRAI suffix
    const val PROMOTIONAL_SUFFIX = "promotional_suffix"
    const val MISSING_SUFFIX = "missing_suffix"

    // L3 known bank header
    const val UNKNOWN_SENDER = "unknown_sender"

    // L4 classifier
    const val OTP = "otp"
    const val REMINDER = "reminder"
    const val FAILED = "failed"
    const val PROMO = "promo"
    const val BALANCE_ONLY = "balance_only"
    const val NO_TXN_SIGNAL = "no_txn_signal"

    // L5 / L6 (templates arrive in P3)
    const val NO_TEMPLATE = "no_template"
    const val UNKNOWN_ACCOUNT = "unknown_account"

    // L7 red flags
    const val RED_FLAGS = "red_flags"

    /** A message that made the pipeline throw. Counted as an error; the scan goes on. */
    const val PIPELINE_ERROR = "pipeline_error"
}

/** The result of running one SMS through the pipeline. Every SMS gets exactly one. */
data class Verdict(
    val outcome: Outcome,
    val reason: String,
    val sender: Sender,
    /** The L4 class, when the message got that far. */
    val smsClass: SmsClass? = null,
    /** Set by the template stage (P3). */
    val templateId: String? = null,
) {
    /** `"IGNORED: promotional_suffix"` */
    val code: String get() = "${outcome.name}: $reason"
}

/** One received SMS as the pipeline sees it. */
data class SmsInput(
    val address: String,
    val body: String,
    /** Epoch milliseconds (UTC) the phone received it. */
    val receivedAt: Long,
)
