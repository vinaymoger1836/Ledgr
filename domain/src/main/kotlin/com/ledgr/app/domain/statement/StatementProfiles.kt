package com.ledgr.app.domain.statement

import com.ledgr.app.domain.ledger.Channel
import com.ledgr.app.domain.rules.RulesFormatException
import java.time.format.DateTimeFormatter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `assets/rules/statement_profiles.yaml` (docs/06, ADR-016). */
@Serializable
data class StatementProfiles(
    val version: String,
    @SerialName("header_scan_rows") val headerScanRows: Int = 40,
    @SerialName("min_header_hits") val minHeaderHits: Int = 3,
    /** [StatementField.key] to synonyms (normalised by [Text.normalise] when compared). */
    val fields: Map<String, List<String>> = emptyMap(),
    @SerialName("date_formats") val dateFormats: List<String> = listOf("yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy"),
    val banks: Map<String, BankStatementProfile> = emptyMap(),
    @SerialName("debit_tokens") val debitTokens: List<String> = listOf("dr", "debit"),
    @SerialName("credit_tokens") val creditTokens: List<String> = listOf("cr", "credit"),
    @SerialName("skip_row_markers") val skipRowMarkers: List<String> = emptyList(),
    @SerialName("noise_words") val noiseWords: List<String> = emptyList(),
    val pdf: PdfLayoutRules = PdfLayoutRules(),
    val narration: NarrationRules = NarrationRules(),
) {
    /** Date patterns to try for [bankId]: its own first, then the shared list, without repeats. */
    fun dateFormatsFor(bankId: String?): List<String> =
        (banks[bankId]?.dateFormats.orEmpty() + dateFormats).distinct()

    /** Synonyms of [field], already normalised, longest first so "closing balance" wins over "balance". */
    fun synonyms(field: StatementField): List<String> =
        fields[field.key].orEmpty().map(Text::normalise).filter { it.isNotEmpty() }.distinct()
            .sortedWith(compareByDescending<String> { it.length }.thenBy { it })

    companion object {
        /** Used only when the shipped file is missing (tests, a stripped build); the shipped YAML always replaces it. */
        fun empty(version: String) = StatementProfiles(version = version)
    }
}

@Serializable
data class BankStatementProfile(@SerialName("date_formats") val dateFormats: List<String> = emptyList())

@Serializable
data class PdfLayoutRules(
    @SerialName("space_gap_pct") val spaceGapPct: Int = 15,
    @SerialName("phrase_gap_pct") val phraseGapPct: Int = 90,
    @SerialName("line_tolerance_pct") val lineTolerancePct: Int = 55,
    @SerialName("max_pages") val maxPages: Int = 300,
)

@Serializable
data class NarrationChannelRule(val channel: String, val pattern: String)

@Serializable
data class NarrationRules(
    @SerialName("max_length") val maxLength: Int = 500,
    @SerialName("upi_ref_pattern") val upiRefPattern: String = "(?<![0-9])[0-9]{12}(?![0-9])",
    @SerialName("vpa_pattern") val vpaPattern: String = "[A-Za-z0-9._\\-]{2,}@[A-Za-z][A-Za-z0-9]{1,}",
    val channels: List<NarrationChannelRule> = emptyList(),
)

object StatementProfilesValidator {
    private val DATE_LETTERS = Regex("^[A-Za-z0-9/\\-. ,]+$")

    fun validate(p: StatementProfiles, bankIds: Set<String>) {
        fun bad(message: String): Nothing = throw RulesFormatException("statement_profiles.yaml: $message")
        if (p.version.isBlank()) bad("version is blank")
        if (p.headerScanRows < 1 || p.minHeaderHits < 1) bad("header_scan_rows and min_header_hits must be at least 1")
        val known = StatementField.entries.map { it.key }.toSet()
        for ((key, words) in p.fields) {
            if (key !in known) bad("unknown field '$key'")
            if (words.isEmpty() || words.any { Text.normalise(it).isEmpty() }) bad("field '$key' has an empty synonym list or a blank synonym")
        }
        for (required in listOf(StatementField.DATE, StatementField.DESCRIPTION, StatementField.BALANCE)) {
            if (p.fields[required.key].isNullOrEmpty()) bad("field '${required.key}' needs synonyms")
        }
        if (p.dateFormats.isEmpty()) bad("date_formats is empty")
        for (pattern in p.dateFormats + p.banks.values.flatMap { it.dateFormats }) {
            if (!DATE_LETTERS.matches(pattern)) bad("date format '$pattern' has unexpected characters")
            try {
                DateTimeFormatter.ofPattern(pattern)
            } catch (e: IllegalArgumentException) {
                throw RulesFormatException("statement_profiles.yaml: date format '$pattern' is not valid", e)
            }
        }
        for (id in p.banks.keys) if (id !in bankIds) bad("banks: unknown bank '$id'")
        if (p.debitTokens.isEmpty() || p.creditTokens.isEmpty()) bad("debit_tokens and credit_tokens must not be empty")
        if (p.debitTokens.map { it.lowercase() }.toSet().intersect(p.creditTokens.map { it.lowercase() }.toSet()).isNotEmpty()) {
            bad("a token cannot be both a debit and a credit token")
        }
        val pdf = p.pdf
        if (pdf.spaceGapPct !in 1..200 || pdf.phraseGapPct !in 1..400 || pdf.lineTolerancePct !in 5..200 || pdf.maxPages !in 1..2000) {
            bad("pdf geometry is out of range")
        }
        if (pdf.spaceGapPct >= pdf.phraseGapPct) bad("pdf.space_gap_pct must be smaller than pdf.phrase_gap_pct")
        val n = p.narration
        if (n.maxLength !in 50..5000) bad("narration.max_length must be 50..5000")
        compile("narration.upi_ref_pattern", n.upiRefPattern)
        compile("narration.vpa_pattern", n.vpaPattern)
        val channels = Channel.entries.map { it.name }.toSet()
        for (c in n.channels) {
            if (c.channel !in channels) bad("narration channel '${c.channel}' is not a channel")
            compile("narration channel ${c.channel}", c.pattern)
        }
    }

    private fun compile(what: String, pattern: String) {
        try {
            Regex(pattern)
        } catch (e: IllegalArgumentException) {
            throw RulesFormatException("statement_profiles.yaml: $what is not a valid regex", e)
        }
    }
}

/** Text helpers shared by the header detector and the reconciler. */
object Text {
    /** Lower-case; every run of anything but letters and digits becomes one space; trimmed. */
    fun normalise(text: String): String {
        val sb = StringBuilder(text.length)
        var pendingSpace = false
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                pendingSpace = false
                sb.append(ch.lowercaseChar())
            } else {
                pendingSpace = true
            }
        }
        return sb.toString()
    }
}
