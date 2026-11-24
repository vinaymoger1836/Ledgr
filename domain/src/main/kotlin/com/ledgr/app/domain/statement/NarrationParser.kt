package com.ledgr.app.domain.statement

import com.ledgr.app.domain.ledger.Channel

/** What a bank wrote in a statement narration, as far as the rules in `statement_profiles.yaml` can tell. */
data class NarrationInfo(
    val channel: Channel,
    val vpa: String?,
    /** The name part, e.g. the merchant or person; null if none stood out. */
    val name: String?,
    /** 12-digit references found in the text. */
    val refs: List<String>,
)

/**
 * Reads a narration (docs/06 §3.1 and "Add to ledger", §4). Every pattern comes from [NarrationRules]; text is cut to
 * `max_length` before any regex runs (CLAUDE.md rule 12).
 */
class NarrationParser(private val rules: NarrationRules) {
    private val upiRef = Regex(rules.upiRefPattern)
    private val vpa = Regex(rules.vpaPattern)
    private val channels = rules.channels.map { Channel.valueOf(it.channel) to Regex(it.pattern) }
    private val channelWords = Regex("""(?i)\b(?:UPI|IMPS|NEFT|RTGS|ATM|ACH|NACH|ECS|POS|PCD|ECOM|CR|DR|PAYMENT|FROM|TO|BY|REF|TXN|TRANSFER)\b""")
    private val ifsc = Regex("""^[A-Za-z]{4}0[A-Za-z0-9]{6}$""")

    fun bound(text: String): String = if (text.length > rules.maxLength) text.take(rules.maxLength) else text

    fun upiRefs(description: String): List<String> = upiRef.findAll(bound(description)).map { it.value }.distinct().toList()

    fun parse(description: String): NarrationInfo {
        val text = bound(description)
        val channel = channels.firstOrNull { it.second.containsMatchIn(text) }?.first ?: Channel.OTHER
        val id = vpa.find(text)?.value
        return NarrationInfo(channel, id, name(text, id), upiRefs(text))
    }

    /** The most name-like segment: letters only, at least three, not a channel word or bank code; the one before the VPA when there is one. */
    private fun name(text: String, vpaText: String?): String? {
        val segments = text.split('/', '|', '-', '\\').map { it.trim() }
        val candidates = segments.withIndex().mapNotNull { (i, raw) ->
            if (vpaText != null && raw.contains(vpaText)) return@mapNotNull null
            if (ifsc.matches(raw.replace(" ", ""))) return@mapNotNull null
            val words = raw.split(' ').filter { w -> w.isNotEmpty() && w.none { it.isDigit() } && !w.contains('@') }
                .filter { !channelWords.matches(it) }
            val cleaned = words.joinToString(" ").trim(' ', '.', ',', ':')
            if (cleaned.count { it.isLetter() } < 3) null else i to cleaned
        }
        if (candidates.isEmpty()) return null
        if (vpaText != null) {
            val vpaIndex = segments.indexOfFirst { it.contains(vpaText) }
            candidates.lastOrNull { it.first < vpaIndex }?.let { return it.second }
        }
        return candidates.first().second
    }
}
