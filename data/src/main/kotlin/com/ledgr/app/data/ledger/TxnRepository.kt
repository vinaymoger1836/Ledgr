package com.ledgr.app.data.ledger

import androidx.room.withTransaction
import com.ledgr.app.data.db.CounterpartyAliasEntity
import com.ledgr.app.data.db.DatabaseManager
import com.ledgr.app.data.db.TxnEntity
import com.ledgr.app.data.db.UserDecision
import com.ledgr.app.data.rules.RulesLoader
import com.ledgr.app.domain.ledger.Channel
import com.ledgr.app.domain.ledger.Direction
import com.ledgr.app.domain.ledger.EditableField
import com.ledgr.app.domain.ledger.Source
import com.ledgr.app.domain.ledger.TxnKind
import com.ledgr.app.domain.ledger.TxnKinds
import com.ledgr.app.domain.ledger.TxnStatus
import com.ledgr.app.domain.ledger.UserEdits
import com.ledgr.app.domain.model.AliasSource
import com.ledgr.app.domain.profile.AliasKey
import com.ledgr.app.domain.sms.Outcome
import com.ledgr.app.domain.sms.Reasons
import com.ledgr.app.domain.time.Ist
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** What the transaction screen saves: every field a person can change, as it should be after the edit. */
data class TxnDraft(
    val accountId: Long,
    val kind: TxnKind,
    /** Paise, positive. */
    val amount: Long,
    /** Epoch ms; the IST `local_date` is derived from it. */
    val occurredAt: Long,
    val counterpartyId: Long?,
    val categoryId: Long?,
    val note: String?,
)

/** A new transaction typed in by hand (Home "+"). */
data class ManualTxn(
    val direction: Direction,
    val draft: TxnDraft,
)

sealed interface EditResult {
    data object Saved : EditResult
    data object NotFound : EditResult
    data object InvalidAmount : EditResult
    data object NoAccount : EditResult
}

/**
 * Editing, deleting and adding transactions by hand (docs/08 §2.1 detail, FAB). Every field a person changes is added
 * to `user_edited_fields`, so a re-scan, dedup or reconciliation never changes it (CLAUDE.md rule 7).
 */
@Singleton
class TxnRepository @Inject constructor(
    private val db: DatabaseManager,
    private val rulesLoader: RulesLoader,
) {
    fun observe(id: Long): Flow<TxnEntity?> = db.observe { it.txnDao().observe(id) }

    suspend fun get(id: Long): TxnEntity? = db.withDb { it.txnDao().get(id) }

    /**
     * Saves [draft] on [txnId]. [applyCategoryToMerchant] also re-categorises every other row of the same merchant
     * that the person has not categorised themselves, and makes the category the merchant's default for new ones.
     */
    suspend fun edit(txnId: Long, draft: TxnDraft, applyCategoryToMerchant: Boolean): EditResult = db.withDb { database ->
        if (draft.amount <= 0L) return@withDb EditResult.InvalidAmount
        database.withTransaction {
            val t = database.txnDao().get(txnId) ?: return@withTransaction EditResult.NotFound
            if (database.accountDao().get(draft.accountId) == null) return@withTransaction EditResult.NoAccount

            val note = draft.note?.trim()?.takeIf { it.isNotEmpty() }
            val edited = UserEdits.decode(t.userEditedFields).toMutableSet()
            if (draft.amount != t.amount) edited += EditableField.AMOUNT
            if (draft.occurredAt != t.occurredAt) edited += EditableField.OCCURRED_AT
            if (draft.kind != t.kind) edited += EditableField.KIND
            if (draft.categoryId != t.categoryId) edited += EditableField.CATEGORY
            if (draft.counterpartyId != t.counterpartyId) edited += EditableField.COUNTERPARTY
            if (note != t.note) edited += EditableField.NOTE
            if (draft.accountId != t.accountId) edited += EditableField.ACCOUNT

            var u = t.copy(
                accountId = draft.accountId,
                kind = draft.kind,
                amount = draft.amount,
                occurredAt = draft.occurredAt,
                localDate = Ist.localDate(draft.occurredAt).toString(),
                counterpartyId = draft.counterpartyId,
                categoryId = draft.categoryId,
                note = note,
                userEditedFields = UserEdits.encode(edited),
                checkReason = null,
            )
            if (draft.kind != t.kind) {
                // A transfer pair, a refund link or a reversal link no longer describes this row: detach both sides.
                u = u.copy(transferPairId = null, refundOf = null)
                database.txnDao().clearPairsTo(t.id)
                database.txnDao().clearRefundsTo(t.id)
            }
            database.txnDao().update(u)

            // Remember "this SMS text is that merchant", as Needs review does, so the next one resolves by itself.
            val cpId = draft.counterpartyId
            if (cpId != null && cpId != t.counterpartyId) {
                val key = t.rawCounterparty?.let(AliasKey::normalize)
                if (key != null && database.counterpartyAliasDao().get(key) == null) {
                    database.counterpartyAliasDao().insert(CounterpartyAliasEntity(key, cpId, AliasSource.USER))
                }
            }
            val categoryId = draft.categoryId
            if (applyCategoryToMerchant && cpId != null && categoryId != null) {
                database.txnDao().setCategoryForCounterparty(cpId, categoryId, t.id)
                database.counterpartyDao().get(cpId)?.let {
                    database.counterpartyDao().update(it.copy(defaultCategoryId = categoryId))
                }
            }
            EditResult.Saved
        }
    }

    /**
     * "Delete": the row is marked DELETED (the ledger never drops anything silently) and detached from any link. An SMS
     * the row came from is marked ignored, so a month re-scan does not bring it back.
     */
    suspend fun delete(txnId: Long) = db.withDb { database ->
        database.withTransaction {
            val t = database.txnDao().get(txnId) ?: return@withTransaction
            database.txnDao().update(
                t.copy(status = TxnStatus.DELETED, checkReason = null, duplicateOf = null, transferPairId = null, refundOf = null),
            )
            database.txnDao().clearPairsTo(t.id)
            database.txnDao().clearRefundsTo(t.id)
            database.txnDao().reviveDuplicatesOf(t.id)
            t.rawSmsId?.let {
                database.rawSmsDao().decide(it, Outcome.IGNORED.name, Reasons.USER_IGNORED, UserDecision.IGNORED)
            }
        }
    }

    /** Adds a transaction by hand. Provenance: `source = MANUAL`, confidence 100, no template, the current rules version. */
    suspend fun addManual(entry: ManualTxn): EditResult = db.withDb { database ->
        val d = entry.draft
        if (d.amount <= 0L) return@withDb EditResult.InvalidAmount
        if (database.accountDao().get(d.accountId) == null) return@withDb EditResult.NoAccount
        val rules = rulesLoader.bundle()
        database.withTransaction {
            val fallback = database.categoryDao().getAll().firstOrNull { it.key == rules.ledger.categories.fallback }?.id
            val kind = d.kind.takeIf { it in TxnKinds.forDirection(entry.direction) } ?: TxnKinds.default(entry.direction)
            // Everything on the row was typed by the person, so none of it may be re-derived later.
            val edited = buildSet {
                add(EditableField.AMOUNT)
                add(EditableField.OCCURRED_AT)
                add(EditableField.KIND)
                add(EditableField.ACCOUNT)
                add(EditableField.CATEGORY)
                if (d.counterpartyId != null) add(EditableField.COUNTERPARTY)
                if (!d.note.isNullOrBlank()) add(EditableField.NOTE)
            }
            database.txnDao().insert(
                TxnEntity(
                    accountId = d.accountId,
                    kind = kind,
                    direction = entry.direction,
                    amount = d.amount,
                    occurredAt = d.occurredAt,
                    localDate = Ist.localDate(d.occurredAt).toString(),
                    channel = Channel.OTHER,
                    counterpartyId = d.counterpartyId,
                    categoryId = d.categoryId ?: fallback,
                    source = Source.MANUAL,
                    confidence = 100,
                    userEditedFields = UserEdits.encode(edited),
                    note = d.note?.trim()?.takeIf { it.isNotEmpty() },
                    rulesVersion = rules.version,
                ),
            )
            EditResult.Saved
        }
    }
}
