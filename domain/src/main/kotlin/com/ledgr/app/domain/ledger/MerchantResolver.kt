package com.ledgr.app.domain.ledger

import com.ledgr.app.domain.model.CounterpartyType
import com.ledgr.app.domain.profile.AliasKey
import com.ledgr.app.domain.rules.LedgerFile
import com.ledgr.app.domain.rules.MerchantRule
import com.ledgr.app.domain.sms.KeywordMatcher

/** The row a user alias (`counterparty_alias`) points at. */
data class AliasTarget(
    val counterpartyId: Long,
    val merchantKey: String?,
    val displayName: String,
    val type: CounterpartyType,
)

/**
 * Everything the resolver and categoriser read, as a snapshot (so the pure engine never touches the database).
 *
 * @property aliases `counterparty_alias.raw_key` (already [AliasKey]-normalised) to its target.
 * @property ownUpiIds the user's own VPAs, lower-case.
 * @property ownNames the user's own name spellings, [AliasKey]-normalised.
 * @property categoryDefaults counterparty identity (see [Identity]) to the category key of its
 * `default_category_id`, resolved through category merges. This is the user's "apply to all from this merchant" rule.
 */
class MerchantContext(
    val aliases: Map<String, AliasTarget> = emptyMap(),
    val ownUpiIds: Set<String> = emptySet(),
    val ownNames: Set<String> = emptySet(),
    val merchants: List<MerchantRule> = emptyList(),
    val aggregators: List<String> = emptyList(),
    val ledger: LedgerFile,
    val categoryDefaults: Map<String, String> = emptyMap(),
)

/**
 * A counterparty is identified in the pure layer by a string, so the engine needs no database ids:
 * a shipped merchant is its `merchant_key`, a person is `person:<name>`, the user's own accounts are `own`, and a
 * counterparty reached through an alias without a key is `id:<row id>`.
 */
object Identity {
    const val OWN = "own"

    fun person(normalizedName: String) = "person:$normalizedName"

    fun wrapped(normalizedName: String) = "wrapped:$normalizedName"

    fun of(merchantKey: String?, counterpartyId: Long?): String? =
        merchantKey ?: counterpartyId?.let { "id:$it" }
}

/** The text an SMS gave for the other side. Any of the three may be absent. */
data class CounterpartyInput(val vpa: String?, val merchant: String?, val payee: String?) {
    val texts: List<String> get() = listOfNotNull(vpa, merchant, payee).map { it.trim() }.filter { it.isNotEmpty() }
}

/** Who the other side is (docs/03 §6). */
data class Resolution(
    val type: CounterpartyType,
    val displayName: String,
    /** Null for [CounterpartyType.UNKNOWN]: no counterparty row is created; the raw text is kept on the txn. */
    val identityKey: String?,
    /** The shipped dictionary's default category; the user's rule comes from [MerchantContext.categoryDefaults]. */
    val dictionaryCategory: String?,
    /** An aggregator or an unrecognised debit: shown in Needs review → Check until the user names it. */
    val needsCheck: Boolean,
)

/**
 * docs/03 §6: user alias, own identity, shipped dictionary, aggregator unwrap, person heuristic, unknown.
 * Pure. All matching data comes from [MerchantContext] (YAML plus the user's rows).
 */
class MerchantResolver(private val ctx: MerchantContext) {
    private val matchers: List<Pair<MerchantRule, KeywordMatcher>> =
        ctx.merchants.map { it to KeywordMatcher(it.match.keywords) }
    private val phoneVpa = Regex(ctx.ledger.person.phoneVpaPattern)
    private val ownNameChannels = ctx.ledger.ownNameChannels.toSet()

    fun resolve(input: CounterpartyInput, channel: Channel): Resolution {
        val texts = input.texts

        // 1. User alias: exact raw key.
        for (t in texts) {
            val target = AliasKey.normalize(t)?.let { ctx.aliases[it] } ?: continue
            return Resolution(
                type = target.type,
                displayName = target.displayName,
                identityKey = Identity.of(target.merchantKey, target.counterpartyId),
                dictionaryCategory = null,
                needsCheck = false,
            )
        }

        // 2. Own identity: an own VPA, or an own name on a bank-to-bank channel to a non-merchant.
        val vpa = input.vpa?.trim()?.lowercase()
        if (vpa != null && vpa in ctx.ownUpiIds) return own()
        if (channel.name in ownNameChannels) {
            for (t in listOfNotNull(input.payee, input.merchant)) {
                val key = AliasKey.normalize(t) ?: continue
                if (key in ctx.ownNames && dictionaryMatch(t) == null) return own()
            }
        }

        // 3. Shipped dictionary.
        for (t in texts) {
            val rule = dictionaryMatch(t) ?: continue
            return Resolution(CounterpartyType.MERCHANT, rule.name, rule.id, rule.category, needsCheck = false)
        }

        // 4. Aggregator unwrap.
        for (t in texts) {
            val (aggregator, rest) = unwrap(t) ?: continue
            if (rest != null) {
                val key = AliasKey.normalize(rest)
                if (key != null) {
                    return Resolution(CounterpartyType.MERCHANT, titleCase(rest), Identity.wrapped(key), null, false)
                }
            }
            return Resolution(
                type = CounterpartyType.UNKNOWN,
                displayName = "${titleCase(aggregator)} · unknown merchant",
                identityKey = null,
                dictionaryCategory = null,
                needsCheck = true,
            )
        }

        // 5. Person heuristic.
        if (vpa != null && phoneVpa.matches(vpa.substringBefore('@'))) {
            return person(input.payee?.takeIf { looksLikeName(it) } ?: vpa, vpa)
        }
        for (t in listOfNotNull(input.payee, input.merchant)) {
            if (looksLikeName(t)) return person(t, null)
        }

        // 6. Unknown.
        val raw = texts.firstOrNull()
        return Resolution(
            type = CounterpartyType.UNKNOWN,
            displayName = raw ?: "",
            identityKey = null,
            dictionaryCategory = null,
            needsCheck = raw != null,
        )
    }

    private fun own() = Resolution(CounterpartyType.OWN_ACCOUNT, "Own account", Identity.OWN, null, false)

    private fun person(display: String, fallbackKey: String?): Resolution {
        val key = AliasKey.normalize(if (looksLikeName(display)) display else fallbackKey ?: display)
            ?: return Resolution(CounterpartyType.UNKNOWN, display, null, null, true)
        return Resolution(CounterpartyType.PERSON, titleCase(display), Identity.person(key), null, false)
    }

    private fun dictionaryMatch(text: String): MerchantRule? {
        val lower = text.trim().lowercase()
        val local = if ('@' in lower) lower.substringBefore('@') else null
        val normalized = KeywordMatcher.normalize(text)
        for ((rule, matcher) in matchers) {
            if (local != null && rule.match.vpaPrefix.any { local.startsWith(it.lowercase()) }) return rule
            if (matcher.matches(normalized)) return rule
        }
        return null
    }

    /** `(aggregator, remainder?)` when [text] names a payment aggregator, else null. */
    private fun unwrap(text: String): Pair<String, String?>? {
        // In a VPA the part after "@" is the bank's handle, never a merchant name.
        val subject = text.substringBefore('@')
        val lower = subject.lowercase()
        for (a in ctx.aggregators) {
            val needle = a.lowercase()
            val at = lower.indexOf(needle)
            if (at < 0) continue
            val before = subject.substring(0, at)
            val after = subject.substring(at + needle.length)
            val rest = (after.trim(*SEPARATORS).ifBlank { before.trim(*SEPARATORS) })
            val usable = rest.takeIf { r ->
                r.count(Char::isLetter) >= 2 && ctx.aggregators.none { r.equals(it, ignoreCase = true) }
            }
            return a to usable
        }
        return null
    }

    /** 2 to 3 capitalised words, letters only (docs/03 §6 step 5). */
    private fun looksLikeName(text: String): Boolean {
        val words = text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val p = ctx.ledger.person
        if (words.size !in p.minWords..p.maxWords) return false
        return words.all { w -> w.all(Char::isLetter) && w.length >= 2 && (w[0].isUpperCase()) }
    }

    private companion object {
        val SEPARATORS = charArrayOf(' ', '*', '-', '/', '|', ':', '.', '_')
    }

    private fun titleCase(text: String): String =
        text.trim().split(Regex("""\s+""")).joinToString(" ") { w ->
            w.lowercase().replaceFirstChar { it.titlecase() }
        }
}
