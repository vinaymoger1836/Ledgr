package com.ledgr.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgr.app.domain.ledger.Channel
import com.ledgr.app.domain.ledger.Direction
import com.ledgr.app.domain.ledger.ProposalType
import com.ledgr.app.domain.ledger.ReconState
import com.ledgr.app.domain.ledger.Source
import com.ledgr.app.domain.ledger.TxnKind
import com.ledgr.app.domain.ledger.TxnStatus
import com.ledgr.app.domain.model.AccountType
import com.ledgr.app.domain.model.AliasSource
import com.ledgr.app.domain.model.CategoryKind
import com.ledgr.app.domain.model.CounterpartyType
import com.ledgr.app.domain.model.OwnIdentityKind
import com.ledgr.app.domain.sender.SenderSuffix
import com.ledgr.app.domain.sms.Outcome

/** docs/05 §1 `account`. Only the last 4 digits are ever stored (ADR-006). */
@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bank_id") val bankId: String,
    val type: AccountType,
    val nickname: String,
    val last4: String,
    /** Chip / accent colour (ARGB). */
    val color: Int,
    /** JSON list of confirmed sender headers for this bank. */
    @ColumnInfo(name = "sender_headers", defaultValue = "[]") val senderHeaders: String = "[]",
    /** Paise; optional, for balance charts. */
    @ColumnInfo(name = "opening_balance") val openingBalance: Long? = null,
    @ColumnInfo(defaultValue = "1") val active: Boolean = true,
)

/**
 * docs/05 §1 `category`. Colour is an index into the fixed category palette.
 * [sortOrder] and [mergedIntoId] are P1 additions (ADR-011, proposed).
 */
@Entity(
    tableName = "category",
    indices = [Index(value = ["key"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val name: String,
    val icon: String,
    val color: Int,
    val kind: CategoryKind,
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,
    @ColumnInfo(name = "is_default", defaultValue = "1") val isDefault: Boolean = true,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    /** Position in the user's list (ascending); unique per row so a move is a swap. */
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
    /** Set when this category was merged away: the category its transactions now belong to. */
    @ColumnInfo(name = "merged_into_id") val mergedIntoId: Long? = null,
)

/** docs/05 §1 `own_identity`: own VPAs and name spellings. The same value cannot be stored twice (any case). */
@Entity(
    tableName = "own_identity",
    indices = [Index(value = ["kind", "value"], unique = true)],
)
data class OwnIdentityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: OwnIdentityKind,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val value: String,
)

/**
 * docs/05 §1 `counterparty`. [icon] is `LETTER`, `CATEGORY` or `IMG:<file name>` (a picture in app-private
 * storage). [color] is a palette index 0..9, like `category.color`.
 */
@Entity(
    tableName = "counterparty",
    indices = [Index(value = ["merchant_key"], unique = true)],
)
data class CounterpartyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "display_name") val displayName: String,
    val type: CounterpartyType,
    /** Id of the shipped `merchants.yaml` entry this row came from; null for user-made rows. */
    @ColumnInfo(name = "merchant_key") val merchantKey: String? = null,
    @ColumnInfo(name = "default_category_id") val defaultCategoryId: Long? = null,
    @ColumnInfo(defaultValue = "LETTER") val icon: String = ICON_LETTER,
    val color: Int,
) {
    companion object {
        const val ICON_LETTER = "LETTER"
        const val ICON_CATEGORY = "CATEGORY"
        const val ICON_IMAGE_PREFIX = "IMG:"
    }
}

/** docs/05 §1 `counterparty_alias`: a normalised raw text/VPA that always maps to one counterparty. */
@Entity(
    tableName = "counterparty_alias",
    foreignKeys = [
        ForeignKey(
            entity = CounterpartyEntity::class,
            parentColumns = ["id"],
            childColumns = ["counterparty_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["counterparty_id"])],
)
data class CounterpartyAliasEntity(
    @PrimaryKey @ColumnInfo(name = "raw_key") val rawKey: String,
    @ColumnInfo(name = "counterparty_id") val counterpartyId: Long,
    @ColumnInfo(name = "created_by") val createdBy: AliasSource,
)

/**
 * docs/05 §1 `raw_sms` (schema v3, P2): every SMS the scanner or receiver has seen, with the outcome and
 * reason it got. [body] is encrypted with the database and can be purged to null later (docs/09); [bodyHash]
 * stays, so a re-scan still recognises the message. `sub_id` is the telephony subscription id (ADR-012).
 */
@Entity(
    tableName = "raw_sms",
    indices = [
        Index(value = ["provider_id", "body_hash"], unique = true),
        Index(value = ["received_at"]),
        Index(value = ["outcome", "reason"]),
    ],
)
data class RawSmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** `Telephony.Sms._id`. */
    @ColumnInfo(name = "provider_id") val providerId: Long,
    val address: String,
    @ColumnInfo(name = "sender_header") val senderHeader: String?,
    @ColumnInfo(name = "sender_suffix") val senderSuffix: SenderSuffix,
    val body: String?,
    /** SHA-256 hex of the UTF-8 body ([com.ledgr.app.domain.sms.BodyHash]). */
    @ColumnInfo(name = "body_hash") val bodyHash: String,
    /** Epoch ms UTC. */
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "sub_id") val subId: Int?,
    val outcome: Outcome,
    /** The reason part of the code, e.g. `promotional_suffix` (see [com.ledgr.app.domain.sms.Reasons]). */
    val reason: String,
    @ColumnInfo(name = "template_id") val templateId: String?,
    @ColumnInfo(name = "rules_version") val rulesVersion: String,
    /** A person's decision in Needs review (schema v4); a month re-scan honours it instead of re-judging the message. */
    @ColumnInfo(name = "user_decision") val userDecision: UserDecision? = null,
)

/**
 * What the person decided about one SMS in Needs review. [CONFIRMED]: "it's genuine, record it" (re-scans lift its
 * quarantine again). [IGNORED]: ignored, deleted or recorded by hand (re-scans leave it alone).
 */
enum class UserDecision { CONFIRMED, IGNORED }

/**
 * docs/05 §1 `txn` (schema v4, P3, ADR-013). Money is paise; [occurredAt] is epoch ms UTC and [localDate] the IST
 * `yyyy-MM-dd` computed when the row is written (ADR-009). Every row records where it came from ([source], [rawSmsId],
 * [templateId]) and which rules produced it ([rulesVersion]).
 *
 * [userEditedFields] is a JSON list of `EditableField` names; a field named there is never changed by re-parse, dedup,
 * linking or reconciliation (CLAUDE.md rule 7). [checkReason] is why the row is in Needs review → Check; null once
 * confirmed. `refund_of` also holds a reversal's link to its debit (kind REVERSAL).
 */
@Entity(
    tableName = "txn",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CounterpartyEntity::class,
            parentColumns = ["id"],
            childColumns = ["counterparty_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["account_id", "local_date"]),
        Index(value = ["local_date"]),
        Index(value = ["ref_no"]),
        Index(value = ["counterparty_id", "local_date"]),
        Index(value = ["category_id", "local_date"]),
        Index(value = ["raw_sms_id"], unique = true),
        Index(value = ["occurred_at"]),
    ],
)
data class TxnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    val kind: TxnKind,
    val direction: Direction,
    /** Paise, always positive; the sign comes from [direction] and `LedgerRules`. */
    val amount: Long,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "local_date") val localDate: String,
    val channel: Channel,
    @ColumnInfo(name = "counterparty_id") val counterpartyId: Long? = null,
    /** The VPA / merchant / payee text as the SMS had it. */
    @ColumnInfo(name = "raw_counterparty") val rawCounterparty: String? = null,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "ref_no") val refNo: String? = null,
    @ColumnInfo(name = "balance_after") val balanceAfter: Long? = null,
    val source: Source,
    @ColumnInfo(name = "raw_sms_id") val rawSmsId: Long? = null,
    /** Reserved for statement reconciliation (P5). */
    @ColumnInfo(name = "statement_line_id") val statementLineId: Long? = null,
    /** 0..100 (ADR-013). */
    val confidence: Int,
    val status: TxnStatus = TxnStatus.ACTIVE,
    @ColumnInfo(name = "duplicate_of") val duplicateOf: Long? = null,
    @ColumnInfo(name = "transfer_pair_id") val transferPairId: Long? = null,
    @ColumnInfo(name = "refund_of") val refundOf: Long? = null,
    @ColumnInfo(name = "user_edited_fields", defaultValue = "[]") val userEditedFields: String = "[]",
    val note: String? = null,
    @ColumnInfo(name = "recon_state", defaultValue = "UNRECONCILED") val reconState: ReconState = ReconState.UNRECONCILED,
    @ColumnInfo(name = "template_id") val templateId: String? = null,
    @ColumnInfo(name = "rules_version") val rulesVersion: String,
    @ColumnInfo(name = "check_reason") val checkReason: String? = null,
)

/** docs/05 §1 `balance_snapshot` (schema v4): the balance an SMS reported for an account at a time. */
@Entity(
    tableName = "balance_snapshot",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["account_id", "at"], unique = true)],
)
data class BalanceSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    /** Epoch ms UTC. */
    val at: Long,
    /** Paise. */
    val balance: Long,
    val source: Source,
)

enum class ProposalState { OPEN, ACCEPTED, REJECTED }

/**
 * A duplicate or transfer link the engine was not sure about (ADR-013); the person confirms or rejects it in
 * Needs review → Duplicates & transfers.
 */
@Entity(
    tableName = "link_proposal",
    indices = [
        Index(value = ["type", "txn_id", "other_txn_id"], unique = true),
        Index(value = ["state"]),
    ],
)
data class LinkProposalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: ProposalType,
    @ColumnInfo(name = "txn_id") val txnId: Long,
    @ColumnInfo(name = "other_txn_id") val otherTxnId: Long,
    val state: ProposalState = ProposalState.OPEN,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

enum class ScanKind { MONTH, CATCHUP, LIVE }

enum class ScanStatus { RUNNING, DONE, FAILED, CANCELLED }

/**
 * docs/05 §1 `scan_run` (schema v3, P2). [countsJson] maps a full reason code (`"IGNORED: otp"`) to how many
 * messages of the run got it; outcome totals are derived from the prefix.
 */
@Entity(
    tableName = "scan_run",
    indices = [Index(value = ["started_at"])],
)
data class ScanRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** `yyyy-MM` (IST) the run was about; for catch-up and live runs, the month it started in. */
    val month: String,
    val kind: ScanKind,
    val status: ScanStatus,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    /** Messages read from the inbox. */
    @ColumnInfo(name = "read_count", defaultValue = "0") val readCount: Int = 0,
    /** Messages already stored that were left as they were. */
    @ColumnInfo(name = "skipped_count", defaultValue = "0") val skippedCount: Int = 0,
    @ColumnInfo(name = "error_count", defaultValue = "0") val errorCount: Int = 0,
    @ColumnInfo(name = "counts_json", defaultValue = "{}") val countsJson: String = "{}",
    @ColumnInfo(name = "rules_version") val rulesVersion: String,
)
