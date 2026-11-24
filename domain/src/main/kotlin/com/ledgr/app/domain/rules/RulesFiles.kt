package com.ledgr.app.domain.rules

import com.charleskorn.kaml.Yaml
import com.ledgr.app.domain.ledger.Channel
import com.ledgr.app.domain.ledger.TxnKind
import com.ledgr.app.domain.statement.StatementProfiles
import com.ledgr.app.domain.statement.StatementProfilesValidator
import com.ledgr.app.domain.template.TemplateCompiler
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ───────────────────────────── banks.yaml (docs/03 §3) ─────────────────────────────

/** One bank in `banks.yaml`. Headers are upper-case, without the operator prefix or TRAI suffix. */
@Serializable
data class BankRule(
    val id: String,
    val name: String,
    /** `#RRGGBB` card accent. */
    val color: String,
    val headers: List<String> = emptyList(),
    @SerialName("card_headers") val cardHeaders: List<String> = emptyList(),
    /** True while the headers have not been checked against the owner's own inbox. */
    val verify: Boolean = false,
) {
    val allHeaders: List<String> get() = headers + cardHeaders

    /** [color] as 0xAARRGGBB. Validated by [RulesBundle]. */
    val argb: Int get() = (0xFF000000L or color.removePrefix("#").toLong(16)).toInt()
}

@Serializable
data class BanksFile(val version: String, val banks: List<BankRule>)

// ───────────────────────────── spam.yaml (docs/03 §4, docs/04 §1) ─────────────────────────────

@Serializable
data class RedFlagRules(@SerialName("action_words") val actionWords: List<String> = emptyList())

@Serializable
data class SpamBankException(
    val bank: String,
    @SerialName("links_ok") val linksOk: Boolean = false,
)

@Serializable
data class SpamFile(
    val version: String,
    /** yyyy-MM-dd; the TRAI suffix roll-out cut-off used by gate layer L2. */
    @SerialName("suffix_cutoff") val suffixCutoff: String,
    /** Class name -> keywords (docs/03 §4). */
    val classes: Map<String, List<String>>,
    @SerialName("red_flags") val redFlags: RedFlagRules = RedFlagRules(),
    @SerialName("bank_exceptions") val bankExceptions: List<SpamBankException> = emptyList(),
) {
    val cutoffDate: LocalDate get() = LocalDate.parse(suffixCutoff)
}

// ───────────────────────────── merchants.yaml (docs/03 §6) ─────────────────────────────

@Serializable
data class MerchantMatch(
    @SerialName("vpa_prefix") val vpaPrefix: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
)

@Serializable
data class MerchantRule(
    val id: String,
    val name: String,
    /** A key from `categories.yaml`. */
    val category: String,
    val match: MerchantMatch = MerchantMatch(),
    val verify: Boolean = false,
)

@Serializable
data class MerchantsFile(
    val version: String,
    val aggregators: List<String> = emptyList(),
    val merchants: List<MerchantRule>,
)

// ───────────────────────────── the whole rule set ─────────────────────────────

/**
 * The rule files, parsed and cross-checked: the four P1 files, `ledger.yaml` and the per-bank template files
 * (P3). Built by [RulesBundle.parse].
 */
data class RulesBundle(
    val banks: BanksFile,
    val spam: SpamFile,
    val merchants: MerchantsFile,
    val categories: CategoryRulesFile,
    val ledger: LedgerFile = LedgerFile(version = banks.version),
    /** `templates/<bank>.yaml` files, in the order given. Empty until real templates are written. */
    val templates: List<TemplateFile> = emptyList(),
    /** `statement_profiles.yaml` (P6, ADR-016): how statements are recognised and reconciled. */
    val statementProfiles: StatementProfiles = StatementProfiles.empty(banks.version),
) {
    /**
     * The version stamped on every row the rules produce (docs/05 §2). One number when all files agree
     * (the normal case), otherwise every file is listed so a mixed release is visible.
     */
    val version: String
        get() {
            val all = listOf(banks.version, spam.version, merchants.version, categories.version, ledger.version, statementProfiles.version) +
                templates.map { it.version }
            return if (all.distinct().size == 1) {
                all.first()
            } else {
                "banks=${banks.version},spam=${spam.version},merchants=${merchants.version}," +
                    "categories=${categories.version},ledger=${ledger.version},statements=${statementProfiles.version}" +
                    templates.joinToString("") { ",${it.bank}=${it.version}" }
            }
        }

    /** Templates still waiting to be checked against the owner's own messages. */
    val unverifiedTemplates: Int get() = templates.sumOf { f -> f.templates.count { it.verify } }

    /** Entries still waiting to be checked against the owner's own data. */
    val unverifiedBanks: Int get() = banks.banks.count { it.verify }
    val unverifiedMerchants: Int get() = merchants.merchants.count { it.verify }

    fun bank(id: String): BankRule? = banks.banks.firstOrNull { it.id == id }

    companion object {
        /**
         * Parses and validates all files (and the references between them). [ledgerYaml] and [templateYamls]
         * are optional: a missing `ledger.yaml` falls back to defaults and no templates means nothing parses.
         * [templateYamls] maps a file name (for error messages) to its text.
         */
        fun parse(
            banksYaml: String,
            spamYaml: String,
            merchantsYaml: String,
            categoriesYaml: String,
            ledgerYaml: String? = null,
            templateYamls: Map<String, String> = emptyMap(),
            statementProfilesYaml: String? = null,
        ): RulesBundle {
            val categories = CategoryRulesParser.parse(categoriesYaml)
            val banks = decode(BanksFile.serializer(), banksYaml, "banks.yaml")
            val spam = decode(SpamFile.serializer(), spamYaml, "spam.yaml")
            val merchants = decode(MerchantsFile.serializer(), merchantsYaml, "merchants.yaml")
            validateBanks(banks)
            validateSpam(spam, banks)
            validateMerchants(merchants, categories)
            val ledger = if (ledgerYaml == null) {
                LedgerFile(version = banks.version)
            } else {
                decode(LedgerFile.serializer(), ledgerYaml, "ledger.yaml")
            }
            validateLedger(ledger, categories)
            val templates = templateYamls.map { (name, text) -> decode(TemplateFile.serializer(), text, name) }
            validateTemplates(templates, banks)
            val statementProfiles = if (statementProfilesYaml == null) {
                StatementProfiles.empty(banks.version)
            } else {
                decode(StatementProfiles.serializer(), statementProfilesYaml, "statement_profiles.yaml")
            }
            StatementProfilesValidator.validate(statementProfiles, banks.banks.map { it.id }.toSet())
            return RulesBundle(banks, spam, merchants, categories, ledger, templates, statementProfiles)
        }

        private fun <T> decode(serializer: KSerializer<T>, text: String, name: String): T =
            try {
                Yaml.default.decodeFromString(serializer, text)
            } catch (e: Exception) {
                throw RulesFormatException("$name is not valid: ${e.message}", e)
            }

        private val ID = Regex("""^[a-z][a-z0-9_]*$""")
        private val HEADER = Regex("""^(?=.*[A-Z])[A-Z0-9]{3,11}$""")
        private val COLOR = Regex("""^#[0-9A-Fa-f]{6}$""")

        private fun validateBanks(file: BanksFile) {
            requireVersion("banks.yaml", file.version)
            if (file.banks.isEmpty()) throw RulesFormatException("banks.yaml: no banks")
            val ids = HashSet<String>()
            val headerOwner = HashMap<String, String>()
            for (b in file.banks) {
                if (!ID.matches(b.id)) throw RulesFormatException("banks.yaml: bad id '${b.id}' (use lower_snake_case)")
                if (!ids.add(b.id)) throw RulesFormatException("banks.yaml: duplicate id '${b.id}'")
                if (b.name.isBlank()) throw RulesFormatException("banks.yaml: '${b.id}' has no name")
                if (!COLOR.matches(b.color)) throw RulesFormatException("banks.yaml: '${b.id}' colour '${b.color}' is not #RRGGBB")
                for (h in b.allHeaders) {
                    if (!HEADER.matches(h)) {
                        throw RulesFormatException("banks.yaml: '${b.id}' header '$h' must be 3-11 upper-case letters/digits with a letter")
                    }
                    val previous = headerOwner.put(h, b.id)
                    if (previous != null) {
                        throw RulesFormatException("banks.yaml: header '$h' is listed for both '$previous' and '${b.id}'")
                    }
                }
            }
        }

        private fun validateSpam(file: SpamFile, banks: BanksFile) {
            requireVersion("spam.yaml", file.version)
            try {
                file.cutoffDate
            } catch (e: DateTimeParseException) {
                throw RulesFormatException("spam.yaml: suffix_cutoff '${file.suffixCutoff}' is not yyyy-MM-dd", e)
            }
            if (file.classes.isEmpty()) throw RulesFormatException("spam.yaml: no classes")
            for ((name, words) in file.classes) {
                if (words.isEmpty() || words.any { it.isBlank() }) {
                    throw RulesFormatException("spam.yaml: class '$name' has an empty keyword list or a blank keyword")
                }
            }
            val bankIds = banks.banks.map { it.id }.toSet()
            for (e in file.bankExceptions) {
                if (e.bank !in bankIds) throw RulesFormatException("spam.yaml: bank_exceptions names unknown bank '${e.bank}'")
            }
        }

        private fun validateMerchants(file: MerchantsFile, categories: CategoryRulesFile) {
            requireVersion("merchants.yaml", file.version)
            val keys = categories.categories.map { it.key }.toSet()
            val ids = HashSet<String>()
            for (m in file.merchants) {
                if (!ID.matches(m.id)) throw RulesFormatException("merchants.yaml: bad id '${m.id}' (use lower_snake_case)")
                if (!ids.add(m.id)) throw RulesFormatException("merchants.yaml: duplicate id '${m.id}'")
                if (m.name.isBlank()) throw RulesFormatException("merchants.yaml: '${m.id}' has no name")
                if (m.category !in keys) {
                    throw RulesFormatException("merchants.yaml: '${m.id}' uses unknown category '${m.category}'")
                }
                if (m.match.vpaPrefix.isEmpty() && m.match.keywords.isEmpty()) {
                    throw RulesFormatException("merchants.yaml: '${m.id}' has nothing to match on")
                }
            }
        }

        private fun validateLedger(file: LedgerFile, categories: CategoryRulesFile) {
            requireVersion("ledger.yaml", file.version)
            val keys = categories.categories.map { it.key }.toSet()
            val c = file.categories
            val used = listOf(c.fallback, c.person, c.salary, c.creditDefault) + c.byChannel.values + c.byKind.values
            for (key in used) {
                if (key !in keys) throw RulesFormatException("ledger.yaml: unknown category '$key'")
            }
            val channels = Channel.entries.map { it.name }.toSet()
            for (name in c.byChannel.keys + file.ownNameChannels) {
                if (name !in channels) throw RulesFormatException("ledger.yaml: '$name' is not a channel")
            }
            val kinds = TxnKind.entries.map { it.name }.toSet()
            for (name in c.byKind.keys) {
                if (name !in kinds) throw RulesFormatException("ledger.yaml: '$name' is not a transaction kind")
            }
            try {
                Regex(file.person.phoneVpaPattern)
            } catch (e: IllegalArgumentException) {
                throw RulesFormatException("ledger.yaml: person.phone_vpa_pattern is not a valid regex", e)
            }
            if (file.person.minWords < 1 || file.person.maxWords < file.person.minWords) {
                throw RulesFormatException("ledger.yaml: person.min_words/max_words are not a valid range")
            }
        }

        private fun validateTemplates(files: List<TemplateFile>, banks: BanksFile) {
            val bankIds = banks.banks.map { it.id }.toSet()
            val seen = HashSet<String>()
            for (file in files) {
                requireVersion("templates/${file.bank}.yaml", file.version)
                if (file.bank !in bankIds) throw RulesFormatException("templates: unknown bank '${file.bank}'")
                for (t in file.templates) {
                    if (!t.id.startsWith("${file.bank}.")) {
                        throw RulesFormatException("templates: '${t.id}' must start with '${file.bank}.'")
                    }
                    if (!seen.add(t.id)) throw RulesFormatException("templates: duplicate id '${t.id}'")
                    TemplateCompiler.compile(t)
                }
            }
        }

        private fun requireVersion(file: String, version: String) {
            if (version.isBlank()) throw RulesFormatException("$file: version is blank")
        }
    }
}
