package com.ledgr.app.domain.profile

import com.ledgr.app.domain.rules.CategoryRulesParser
import com.ledgr.app.domain.sender.SenderNormalizer
import com.ledgr.app.domain.sender.SenderType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The only account number ever stored: its last four digits (ADR-006). */
object Last4 {
    const val LENGTH = 4

    fun isValid(value: String): Boolean = value.length == LENGTH && value.all { it in '0'..'9' }

    /**
     * Input filter for the text field. Non-digits are dropped; anything longer than four digits is **refused**
     * (the previous text is kept) instead of truncated, so pasting a full card number can never store the wrong
     * four digits.
     */
    fun accept(previous: String, typed: String): String {
        val digits = typed.filter { it in '0'..'9' }
        return if (digits.length <= LENGTH) digits else previous
    }
}

/** Own UPI IDs (VPAs). Stored lower-case. */
object UpiId {
    private val pattern = Regex("""^[a-z0-9._\-]{2,64}@[a-z][a-z0-9.\-]{1,32}$""")

    /** Lower-cased, trimmed VPA, or null when it is not `name@handle`. */
    fun normalize(input: String): String? = input.trim().lowercase().takeIf { pattern.matches(it) }
}

/** Own name spellings, e.g. "Vinay Moger", "V MOGER". Kept as typed (whitespace collapsed). */
object NameVariant {
    const val MAX_LENGTH = 60

    fun normalize(input: String): String? {
        val cleaned = input.trim().replace(Regex("""\s+"""), " ")
        return cleaned.takeIf { it.length in 2..MAX_LENGTH && it.any(Char::isLetter) }
    }
}

/** The normalised key of `counterparty_alias.raw_key`; shared with the merchant resolver (docs/03 §6). */
object AliasKey {
    const val MAX_LENGTH = 120

    fun normalize(raw: String): String? {
        val key = raw.trim().lowercase().replace(Regex("""\s+"""), " ")
        return key.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }
    }
}

/** `account.sender_headers`: a JSON list of confirmed headers such as `["HDFCBK","HDFCBN"]`. */
object SenderHeaders {
    private val json = Json
    private val serializer = ListSerializer(String.serializer())

    fun encode(headers: Collection<String>): String = json.encodeToString(serializer, headers.distinct())

    /** A damaged value reads as "no headers" rather than crashing the profile screen. */
    fun decode(stored: String): List<String> =
        try {
            json.decodeFromString(serializer, stored)
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * Turns what the user typed (`vm-hdfcbk-s`, `HDFCBK`) into the bare header (`HDFCBK`), or null when it is
     * not an alphanumeric header (phone numbers are never banks, docs/04 L1).
     */
    fun fromInput(input: String): String? {
        val sender = SenderNormalizer.normalize(input)
        return if (sender.type == SenderType.HEADER) sender.header else null
    }
}

/** Deterministic colour index for avatars without a category colour (docs/08 §3: "colour derived from the name hash"). */
object NameColor {
    fun indexFor(name: String): Int = Math.floorMod(name.trim().lowercase().hashCode(), CategoryRulesParser.PALETTE_SIZE)
}
