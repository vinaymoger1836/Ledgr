package com.ledgr.app.domain.template

import com.ledgr.app.domain.ledger.Direction
import com.ledgr.app.domain.ledger.TxnKind
import com.ledgr.app.domain.rules.RulesFormatException
import com.ledgr.app.domain.rules.TemplateFile
import com.ledgr.app.domain.rules.TemplateKind
import com.ledgr.app.domain.rules.TemplateRule
import com.ledgr.app.domain.sms.KeywordMatcher
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** A template that ran past its time budget (CLAUDE.md rule 12). The pipeline files the SMS as `pipeline_error`. */
class TemplateTimeoutException : RuntimeException("template matching ran out of time")

/** A [TemplateRule] with its regex compiled and checked. Built by [TemplateCompiler]. */
class CompiledTemplate internal constructor(
    val rule: TemplateRule,
    internal val pattern: Pattern,
    /** Group names (underscores removed, as Java requires) the pattern declares. */
    val groups: Set<String>,
    internal val dateFormatter: DateTimeFormatter?,
) {
    val direction: Direction?
        get() = when (rule.kind) {
            TemplateKind.DEBIT -> Direction.DEBIT
            TemplateKind.CREDIT -> Direction.CREDIT
            TemplateKind.BALANCE -> null
        }
}

/**
 * Compiles and validates templates (docs/03 §5, ADR-013). Run for every shipped template when the rules load, so a
 * broken template fails `goldenCheck` instead of silently never matching.
 *
 * Named groups the engine understands: `amount, last4, date, time, vpa, merchant, payee, ref, balance,
 * channel_hint, dest_last4`. Java group names cannot contain `_`, so the underscores are removed before compiling
 * (`channel_hint` becomes `channelhint`); write them with the underscore in YAML.
 */
object TemplateCompiler {
    /** Known group names after underscore removal. */
    val GROUPS: Set<String> = setOf(
        "amount", "last4", "date", "time", "vpa", "merchant", "payee", "ref", "balance", "channelhint", "destlast4",
    )

    private const val MAX_PATTERN = 2000
    private val GROUP_DECL = Regex("""\(\?<([A-Za-z][A-Za-z0-9_]*)>""")
    private val DEBIT_ONLY = setOf(TxnKind.EXPENSE, TxnKind.SELF_TRANSFER_OUT, TxnKind.CARD_BILL_PAYMENT)
    private val CREDIT_ONLY = setOf(
        TxnKind.INCOME, TxnKind.REFUND, TxnKind.SELF_TRANSFER_IN, TxnKind.CARD_PAYMENT_RECEIVED, TxnKind.REVERSAL,
    )

    fun compile(rule: TemplateRule): CompiledTemplate {
        fun fail(message: String): Nothing = throw RulesFormatException("template '${rule.id}': $message")

        if (rule.pattern.isBlank()) fail("pattern is empty")
        if (rule.pattern.length > MAX_PATTERN) fail("pattern is longer than $MAX_PATTERN characters")

        val sanitized = GROUP_DECL.replace(rule.pattern.trim()) { "(?<" + it.groupValues[1].replace("_", "") + ">" }
        val declared = GROUP_DECL.findAll(sanitized).map { it.groupValues[1] }.toList()
        val unknown = declared.filter { it !in GROUPS }
        if (unknown.isNotEmpty()) fail("unknown group(s) ${unknown.joinToString()}; allowed: ${GROUPS.joinToString()}")
        if (declared.size != declared.toSet().size) fail("a group name is used twice")

        when (rule.kind) {
            TemplateKind.DEBIT, TemplateKind.CREDIT -> if ("amount" !in declared) fail("needs an (?<amount>...) group")
            TemplateKind.BALANCE -> if ("balance" !in declared) fail("a BALANCE template needs a (?<balance>...) group")
        }
        rule.txnKind?.let { kind ->
            val ok = when (rule.kind) {
                TemplateKind.DEBIT -> kind in DEBIT_ONLY
                TemplateKind.CREDIT -> kind in CREDIT_ONLY
                TemplateKind.BALANCE -> false
            }
            if (!ok) fail("txn_kind $kind does not fit kind ${rule.kind}")
        }

        val pattern = try {
            Pattern.compile(sanitized, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        } catch (e: PatternSyntaxException) {
            fail("pattern is not a valid regex: ${e.description}")
        }

        val formatter = if ("date" in declared) {
            val format = rule.dateFormat ?: "yyyy-MM-dd"
            try {
                DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(format).toFormatter(Locale.ENGLISH)
            } catch (e: IllegalArgumentException) {
                fail("date_format '$format' is not valid: ${e.message}")
            }
        } else {
            null
        }
        return CompiledTemplate(rule, pattern, declared.toSet(), formatter)
    }
}

/** The groups a template captured, plus the template. */
class RawMatch(val template: CompiledTemplate, val fields: Map<String, String>)

/**
 * Matches an SMS body against a bank's templates, in file order; the first match wins (docs/03 §5). The body is
 * bounded to the shared 2,000-character limit, and matching has a time budget.
 */
class TemplateEngine(files: List<TemplateFile>) {
    private val byBank: Map<String, List<CompiledTemplate>> =
        files.groupBy { it.bank }.mapValues { (_, list) -> list.flatMap { f -> f.templates.map(TemplateCompiler::compile) } }

    fun hasTemplates(bankId: String): Boolean = byBank[bankId].orEmpty().isNotEmpty()

    fun match(bankId: String, body: String): RawMatch? {
        val templates = byBank[bankId] ?: return null
        val bounded = if (body.length > KeywordMatcher.MAX_BODY) body.substring(0, KeywordMatcher.MAX_BODY) else body
        val chars = DeadlineChars(bounded, System.nanoTime() + BUDGET_NANOS)
        for (t in templates) {
            val m = t.pattern.matcher(chars)
            if (!m.find()) continue
            val fields = LinkedHashMap<String, String>()
            for (name in t.groups) {
                val value = m.group(name)?.trim()
                if (!value.isNullOrEmpty()) fields[name] = value
            }
            return RawMatch(t, fields)
        }
        return null
    }

    private companion object {
        /** One message may spend at most this long across all templates. */
        const val BUDGET_NANOS = 200_000_000L
    }
}

/** A [CharSequence] that throws once its deadline passes, so a runaway regex cannot hang a scan. */
private class DeadlineChars(private val text: String, private val deadlineNanos: Long) : CharSequence {
    private var reads = 0

    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        if (++reads and 0xFFF == 0 && System.nanoTime() > deadlineNanos) throw TemplateTimeoutException()
        return text[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)

    override fun toString(): String = text
}
