package com.ledgr.app.data.review

import androidx.room.withTransaction
import com.ledgr.app.data.db.DatabaseManager
import com.ledgr.app.data.db.LinkProposalEntity
import com.ledgr.app.data.db.ProposalState
import com.ledgr.app.data.db.RawSmsEntity
import com.ledgr.app.data.db.TxnEntity
import com.ledgr.app.data.db.UserDecision
import com.ledgr.app.data.ledger.LedgerWriter
import com.ledgr.app.data.sms.PipelineFactory
import com.ledgr.app.domain.ledger.Channel
import com.ledgr.app.domain.ledger.Direction
import com.ledgr.app.domain.ledger.EditableField
import com.ledgr.app.domain.ledger.ProposalType
import com.ledgr.app.domain.ledger.Source
import com.ledgr.app.domain.ledger.TxnKind
import com.ledgr.app.domain.ledger.UserEdits
import com.ledgr.app.domain.model.AliasSource
import com.ledgr.app.domain.profile.AliasKey
import com.ledgr.app.domain.sms.Outcome
import com.ledgr.app.domain.sms.Reasons
import com.ledgr.app.domain.sms.SmsInput
import com.ledgr.app.domain.time.Ist
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** How many open items each Needs review tab has (docs/08 §2.5). */
data class ReviewCounts(val unparsed: Int, val check: Int, val suspicious: Int, val proposals: Int) {
    val total: Int get() = unparsed + check + suspicious + proposals
}

sealed interface RecordResult {
    data object Recorded : RecordResult

    /** Still not acceptable (a phone-number sender is never recorded), or nothing matched. */
    data object NotRecorded : RecordResult

    /** The message body was purged, so there is nothing to parse. */
    data object NoBody : RecordResult
}

/** Why a manual entry was not saved. */
sealed interface ManualResult {
    data object Saved : ManualResult
    data object InvalidAmount : ManualResult
    data object NoAccount : ManualResult
}

/**
 * Needs review (docs/08 §2.5): what the pipeline could not settle, and the person's decisions about it. A decision is
 * stored (`raw_sms.user_decision`, `txn.user_edited_fields`) so a re-scan never undoes it.
 */
@Singleton
class ReviewRepository @Inject constructor(
    private val db: DatabaseManager,
    private val pipelines: PipelineFactory,
    private val ledger: LedgerWriter,
) {
    val unparsed: Flow<List<RawSmsEntity>> = db.observe { it.rawSmsDao().observeReview(UNPARSED_REASONS, LIMIT) }
    val check: Flow<List<TxnEntity>> = db.observe { it.txnDao().observeCheck(LIMIT) }
    val suspicious: Flow<List<RawSmsEntity>> = db.observe { it.rawSmsDao().observeQuarantined(LIMIT) }
    val proposals: Flow<List<LinkProposalEntity>> = db.observe { it.linkProposalDao().observeOpen(LIMIT) }

    val counts: Flow<ReviewCounts> = combine(
        db.observe { it.rawSmsDao().observeReviewCount(UNPARSED_REASONS) },
        db.observe { it.txnDao().observeCheckCount() },
        db.observe { it.rawSmsDao().observeQuarantinedCount() },
        db.observe { it.linkProposalDao().observeOpenCount() },
    ) { unparsed, check, suspicious, proposals -> ReviewCounts(unparsed, check, suspicious, proposals) }

    /** The transactions a list of proposals points at, for display. */
    suspend fun txns(ids: List<Long>): List<TxnEntity> =
        if (ids.isEmpty()) emptyList() else db.withDb { it.txnDao().getByIds(ids.distinct()) }

    // ───────────────────────────── Unparsed ─────────────────────────────

    /** "Ignore this message": it stays stored, and a re-scan leaves it alone. */
    suspend fun ignore(rawSmsId: Long) = db.withDb {
        it.rawSmsDao().decide(rawSmsId, Outcome.IGNORED.name, Reasons.USER_IGNORED, UserDecision.IGNORED)
    }

    /**
     * "Add manually": records a transaction by hand for one unparsed message (or none) and settles the message.
     * The amount is paise; [occurredAt] is epoch ms.
     */
    suspend fun addManual(
        rawSmsId: Long?,
        accountId: Long,
        direction: Direction,
        amountPaise: Long,
        occurredAt: Long,
        categoryId: Long?,
        note: String?,
    ): ManualResult = db.withDb { database ->
        if (amountPaise <= 0L) return@withDb ManualResult.InvalidAmount
        if (database.accountDao().get(accountId) == null) return@withDb ManualResult.NoAccount
        val rules = pipelines.create(database).rules
        database.withTransaction {
            val fallback = database.categoryDao().getAll().firstOrNull { it.key == rules.ledger.categories.fallback }?.id
            database.txnDao().insert(
                TxnEntity(
                    accountId = accountId,
                    kind = if (direction == Direction.DEBIT) TxnKind.EXPENSE else TxnKind.INCOME,
                    direction = direction,
                    amount = amountPaise,
                    occurredAt = occurredAt,
                    localDate = Ist.localDate(occurredAt).toString(),
                    channel = Channel.OTHER,
                    categoryId = categoryId ?: fallback,
                    source = Source.MANUAL,
                    rawSmsId = rawSmsId,
                    confidence = 100,
                    note = note?.trim()?.takeIf { it.isNotEmpty() },
                    rulesVersion = rules.version,
                ),
            )
            if (rawSmsId != null) {
                database.rawSmsDao().decide(rawSmsId, Outcome.IGNORED.name, Reasons.USER_IGNORED, UserDecision.IGNORED)
            }
        }
        ManualResult.Saved
    }

    // ───────────────────────────── Check ─────────────────────────────

    /** "This is right": the row leaves the Check list. */
    suspend fun confirmCheck(txnId: Long) = db.withDb { database ->
        database.txnDao().get(txnId)?.let { database.txnDao().update(it.copy(checkReason = null)) }
    }

    suspend fun setCategory(txnId: Long, categoryId: Long) = db.withDb { database ->
        val t = database.txnDao().get(txnId) ?: return@withDb
        val edited = UserEdits.decode(t.userEditedFields) + EditableField.CATEGORY
        database.txnDao().update(t.copy(categoryId = categoryId, userEditedFields = UserEdits.encode(edited), checkReason = null))
    }

    /**
     * "This is merchant X": sets it on the row and remembers the SMS text as an alias, so the next one resolves by
     * itself. Past rows are not rewritten in bulk.
     */
    suspend fun setCounterparty(txnId: Long, counterpartyId: Long) = db.withDb { database ->
        database.withTransaction {
            val t = database.txnDao().get(txnId) ?: return@withTransaction
            val cp = database.counterpartyDao().get(counterpartyId) ?: return@withTransaction
            val edited = UserEdits.decode(t.userEditedFields) + EditableField.COUNTERPARTY
            var u = t.copy(counterpartyId = counterpartyId, checkReason = null)
            val defaultCategory = cp.defaultCategoryId
            if (EditableField.CATEGORY !in edited && defaultCategory != null) {
                u = u.copy(categoryId = defaultCategory)
            }
            database.txnDao().update(u.copy(userEditedFields = UserEdits.encode(edited)))
            val key = t.rawCounterparty?.let(AliasKey::normalize)
            if (key != null && database.counterpartyAliasDao().get(key) == null) {
                database.counterpartyAliasDao().insert(
                    com.ledgr.app.data.db.CounterpartyAliasEntity(key, counterpartyId, AliasSource.USER),
                )
            }
        }
    }

    // ───────────────────────────── Suspicious ─────────────────────────────

    /**
     * "It's genuine, record it": runs this one message again with its message-level quarantine lifted (missing suffix,
     * red flags) and keeps that decision for re-scans. No sender header is learned. A phone-number sender stays out.
     */
    suspend fun recordQuarantined(rawSmsId: Long): RecordResult = db.withDb { database ->
        database.withTransaction {
            val raw = database.rawSmsDao().get(rawSmsId) ?: return@withTransaction RecordResult.NotRecorded
            val body = raw.body ?: return@withTransaction RecordResult.NoBody
            val built = pipelines.create(database)
            val verdict = built.pipeline.process(SmsInput(raw.address, body, raw.receivedAt), userConfirmed = true)
            if (verdict.outcome == Outcome.QUARANTINE) return@withTransaction RecordResult.NotRecorded
            val rawDao = database.rawSmsDao()
            rawDao.updateVerdict(
                rawSmsId, verdict.sender.header, verdict.sender.suffix.name, verdict.outcome.name, verdict.reason,
                verdict.templateId, built.rules.version,
            )
            rawDao.decide(rawSmsId, verdict.outcome.name, verdict.reason, UserDecision.CONFIRMED)
            val parsed = verdict.parsed
            if (parsed != null && (verdict.outcome == Outcome.TXN || verdict.outcome == Outcome.BALANCE)) {
                ledger.open(database).ingest(rawSmsId, parsed, body, built.rules.version)
            }
            RecordResult.Recorded
        }
    }

    /** "Delete": the message is kept as ignored (nothing is dropped silently) and never comes back. */
    suspend fun deleteQuarantined(rawSmsId: Long) = ignore(rawSmsId)

    // ───────────────────────────── Duplicates & transfers ─────────────────────────────

    suspend fun accept(proposalId: Long): Boolean = db.withDb { database ->
        database.withTransaction {
            val p = database.linkProposalDao().get(proposalId) ?: return@withTransaction false
            if (p.state != ProposalState.OPEN) return@withTransaction false
            val session = ledger.open(database)
            val done = when (p.type) {
                ProposalType.TRANSFER -> session.confirmTransfer(p.txnId, p.otherTxnId)
                ProposalType.DUPLICATE -> session.confirmDuplicate(p.txnId, p.otherTxnId)
            }
            // A proposal that no longer fits (a row changed meanwhile) is closed either way.
            database.linkProposalDao().setState(proposalId, (if (done) ProposalState.ACCEPTED else ProposalState.REJECTED).name)
            done
        }
    }

    suspend fun reject(proposalId: Long) = db.withDb {
        it.linkProposalDao().setState(proposalId, ProposalState.REJECTED.name)
    }

    companion object {
        /** `raw_sms` REVIEW reasons that mean "no transaction came out" (Unparsed tab). */
        val UNPARSED_REASONS: List<String> = listOf(
            Reasons.NO_TEMPLATE,
            Reasons.UNKNOWN_ACCOUNT,
            Reasons.PIPELINE_ERROR,
            Reasons.INVALID_AMOUNT,
            Reasons.AMOUNT_OVER_CAP,
            Reasons.BALANCE_EQUALS_AMOUNT,
        )
        private const val LIMIT = 200
    }
}
