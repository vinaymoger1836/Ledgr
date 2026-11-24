package com.ledgr.app.domain.template

import com.ledgr.app.domain.ledger.Channel
import com.ledgr.app.domain.model.AccountType
import com.ledgr.app.domain.money.Money
import com.ledgr.app.domain.rules.TemplateKind
import com.ledgr.app.domain.sms.GateResult
import com.ledgr.app.domain.sms.Outcome
import com.ledgr.app.domain.sms.Reasons
import com.ledgr.app.domain.sms.SmsClass
import com.ledgr.app.domain.sms.SmsInput
import com.ledgr.app.domain.sms.TemplateStage
import com.ledgr.app.domain.sms.Verdict
import com.ledgr.app.domain.time.Ist
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/** An account as the template stage sees it (L6): only what is needed to match an SMS to it. */
data class AccountRef(
    val id: Long,
    val bankId: String,
    val type: AccountType,
    val last4: String,
    /** Confirmed sender headers (upper-case), so a user-confirmed header can still find its bank. */
    val senderHeaders: Set<String> = emptySet(),
)

/** Field checks before anything is written (docs/03 §8). */
object FieldValidator {
    /** ₹10,00,000 in paise. Larger amounts need a person to look at them. */
    const val MAX_AMOUNT_PAISE = 100_000_000L

    /** A [Reasons] code when the fields are not acceptable, else null. */
    fun check(amountPaise: Long, balancePaise: Long?, isBalanceOnly: Boolean): String? = when {
        !isBalanceOnly && amountPaise > MAX_AMOUNT_PAISE -> Reasons.AMOUNT_OVER_CAP
        !isBalanceOnly && balancePaise != null && balancePaise == amountPaise -> Reasons.BALANCE_EQUALS_AMOUNT
        else -> null
    }
}

/**
 * Layers L5 (template match) and L6 (account match) with field validation (docs/03 §5 and §8, docs/04 §1).
 * Pure: the accounts are a snapshot handed in by the caller.
 *
 * @param permissiveAccounts golden private-coverage runs have no profile, so an unmatched account is accepted
 * with id 0 instead of `REVIEW: unknown_account`. Never set on a device.
 */
class TemplateStageImpl(
    private val engine: TemplateEngine,
    private val accounts: List<AccountRef>,
    private val permissiveAccounts: Boolean = false,
) : TemplateStage {

    override fun apply(sms: SmsInput, passed: GateResult.Pass): Verdict {
        fun review(reason: String, templateId: String? = null) =
            Verdict(Outcome.REVIEW, reason, passed.sender, passed.smsClass, templateId)

        // A balance message nobody wrote a template for (or one for an unknown account) is just a balance message.
        val balanceMessage = passed.smsClass == SmsClass.BALANCE_ONLY
        fun unmatched(reason: String, templateId: String? = null) =
            if (balanceMessage) {
                Verdict(Outcome.BALANCE, Reasons.BALANCE_ONLY, passed.sender, passed.smsClass, templateId)
            } else {
                review(reason, templateId)
            }

        val header = passed.sender.header.orEmpty()
        val bankId = passed.bankId ?: accounts.firstOrNull { header in it.senderHeaders }?.bankId
            ?: return unmatched(Reasons.NO_TEMPLATE)

        val raw = engine.match(bankId, sms.body) ?: return unmatched(Reasons.NO_TEMPLATE)
        val template = raw.template
        val rule = template.rule
        val f = raw.fields
        val balanceOnly = rule.kind == TemplateKind.BALANCE

        val amount = if (balanceOnly) 0L else Money.parsePaise(f["amount"].orEmpty())
        if (amount == null || (!balanceOnly && amount <= 0L)) return review(Reasons.INVALID_AMOUNT, rule.id)
        val balance = f["balance"]?.let { Money.parsePaise(it) }
        if (balanceOnly && balance == null) return review(Reasons.INVALID_AMOUNT, rule.id)
        FieldValidator.check(amount, balance, balanceOnly)?.let { return review(it, rule.id) }

        // L6: the account.
        val last4 = f["last4"]
        val candidates = accounts.filter { it.bankId == bankId && (rule.accountType == null || it.type == rule.accountType) }
        val account = when {
            last4 != null -> candidates.firstOrNull { it.last4 == last4 }
            candidates.size == 1 -> candidates[0]
            else -> null
        }
        val accountId = account?.id ?: if (permissiveAccounts) 0L else return unmatched(Reasons.UNKNOWN_ACCOUNT, rule.id)

        // Date and time (docs/03 §5): the SMS date, else the receipt time; far from receipt is suspect.
        val receivedDate = Ist.localDate(sms.receivedAt)
        val date = f["date"]?.let { parseDate(it, template.dateFormatter) }
        var occurredAt = sms.receivedAt
        var dateSuspect = false
        if (date != null) {
            if (abs(ChronoUnit.DAYS.between(date, receivedDate)) > SUSPECT_DAYS) {
                dateSuspect = true
            } else {
                val time = f["time"]?.let(::parseTime)
                occurredAt = when {
                    time != null -> Ist.at(date, time.hour, time.minute)
                    date == receivedDate -> sms.receivedAt
                    else -> Ist.at(date, NOON)
                }
            }
        }

        val channel = if (rule.channel != Channel.OTHER) {
            rule.channel
        } else {
            f["channelhint"]?.let { hint -> Channel.entries.firstOrNull { it.name.equals(hint.trim(), ignoreCase = true) } }
                ?: Channel.OTHER
        }

        val ref = f["ref"]
        // A balance message has no reference to miss; only transactions are marked down.
        val confidence = if (balanceOnly) {
            100
        } else {
            100 - (if (ref == null) NO_REF_PENALTY else 0) - (if (last4 == null) NO_LAST4_PENALTY else 0)
        }

        val parsed = ParsedSms(
            templateId = rule.id,
            templateKind = rule.kind,
            txnKind = rule.txnKind,
            direction = template.direction,
            channel = channel,
            accountId = accountId,
            amount = amount,
            occurredAt = occurredAt,
            dateSuspect = dateSuspect,
            last4 = last4,
            vpa = f["vpa"],
            merchant = f["merchant"],
            payee = f["payee"],
            ref = ref,
            balanceAfter = balance,
            destLast4 = f["destlast4"],
            confidence = confidence,
            mayDuplicateWith = rule.mayDuplicateWith,
        )
        return if (balanceOnly) {
            Verdict(Outcome.BALANCE, Reasons.BALANCE_SNAPSHOT, passed.sender, passed.smsClass, rule.id, parsed)
        } else {
            Verdict(Outcome.TXN, Reasons.PARSED, passed.sender, passed.smsClass, rule.id, parsed)
        }
    }

    private fun parseDate(text: String, formatter: DateTimeFormatter?): LocalDate? =
        try {
            LocalDate.parse(text.trim(), formatter ?: return null)
        } catch (e: DateTimeParseException) {
            null
        }

    private fun parseTime(text: String): LocalTime? {
        val t = text.trim()
        for (formatter in TIME_FORMATS) {
            try {
                return LocalTime.parse(t, formatter)
            } catch (e: DateTimeParseException) {
                // try the next shape
            }
        }
        return null
    }

    private companion object {
        const val SUSPECT_DAYS = 3L
        const val NOON = 12
        const val NO_REF_PENALTY = 20
        const val NO_LAST4_PENALTY = 30

        val TIME_FORMATS: List<DateTimeFormatter> = listOf("HH:mm:ss", "HH:mm", "hh:mm:ss a", "hh:mm a").map {
            DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH)
        }
    }
}
