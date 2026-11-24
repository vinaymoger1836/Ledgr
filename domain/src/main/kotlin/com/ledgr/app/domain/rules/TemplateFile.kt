package com.ledgr.app.domain.rules

import com.ledgr.app.domain.ledger.Channel
import com.ledgr.app.domain.ledger.TxnKind
import com.ledgr.app.domain.model.AccountType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a template's message does to the account (docs/03 §5). */
enum class TemplateKind { DEBIT, CREDIT, BALANCE }

/**
 * One SMS template (docs/03 §5, ADR-013). [pattern] is a Java regex with named groups; see `TemplateEngine` for
 * the groups it understands. Templates are tried in file order and the first match wins.
 */
@Serializable
data class TemplateRule(
    /** `<bank>.<channel>.<what>.v<n>`, for example `hdfc.upi.debit.v1`. Must start with the file's bank id. */
    val id: String,
    val kind: TemplateKind,
    /** Overrides the txn kind the linkers would otherwise derive (refund, reversal, card bill ...). */
    @SerialName("txn_kind") val txnKind: TxnKind? = null,
    val channel: Channel = Channel.OTHER,
    /** Only accounts of this type match (a card template must not match a savings account). */
    @SerialName("account_type") val accountType: AccountType? = null,
    val pattern: String,
    /** `java.time` pattern for the `date` group, for example `dd-MM-yy`. Optional when there is no `date` group. */
    @SerialName("date_format") val dateFormat: String? = null,
    /** Template ids whose alert for the same payment is a known duplicate of this one (docs/04 §2). */
    @SerialName("may_duplicate_with") val mayDuplicateWith: List<String> = emptyList(),
    /** True while not yet checked against the owner's real messages. */
    val verify: Boolean = false,
)

/** `assets/rules/templates/<bank>.yaml`. */
@Serializable
data class TemplateFile(
    val version: String,
    /** A `banks.yaml` id. */
    val bank: String,
    val templates: List<TemplateRule>,
)

/** `assets/rules/ledger.yaml` (ADR-013). */
@Serializable
data class LedgerFile(
    val version: String,
    @SerialName("salary_keywords") val salaryKeywords: List<String> = emptyList(),
    @SerialName("refund_keywords") val refundKeywords: List<String> = emptyList(),
    val person: PersonRules = PersonRules(),
    /** [Channel] names. */
    @SerialName("own_name_channels") val ownNameChannels: List<String> = emptyList(),
    val categories: LedgerCategories = LedgerCategories(),
)

@Serializable
data class PersonRules(
    @SerialName("phone_vpa_pattern") val phoneVpaPattern: String = "^(?:91)?[6-9][0-9]{9}$",
    @SerialName("min_words") val minWords: Int = 2,
    @SerialName("max_words") val maxWords: Int = 3,
)

@Serializable
data class LedgerCategories(
    val fallback: String = "uncategorised",
    val person: String = "transfers_to_people",
    val salary: String = "salary",
    @SerialName("credit_default") val creditDefault: String = "transfers_in",
    /** Keys are [Channel] names; checked by `RulesBundle`. */
    @SerialName("by_channel") val byChannel: Map<String, String> = emptyMap(),
    /** Keys are [TxnKind] names; checked by `RulesBundle`. */
    @SerialName("by_kind") val byKind: Map<String, String> = emptyMap(),
)
