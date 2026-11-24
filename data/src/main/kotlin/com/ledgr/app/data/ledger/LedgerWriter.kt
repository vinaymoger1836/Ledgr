package com.ledgr.app.data.ledger

import com.ledgr.app.data.db.BalanceSnapshotEntity
import com.ledgr.app.data.db.CategoryEntity
import com.ledgr.app.data.db.CounterpartyAliasEntity
import com.ledgr.app.data.db.CounterpartyEntity
import com.ledgr.app.data.db.LedgrDatabase
import com.ledgr.app.data.db.LinkProposalEntity
import com.ledgr.app.data.db.TxnEntity
import com.ledgr.app.data.rules.RulesLoader
import com.ledgr.app.domain.ledger.AliasTarget
import com.ledgr.app.domain.ledger.EditableField
import com.ledgr.app.domain.ledger.IngestInput
import com.ledgr.app.domain.ledger.IngestPlan
import com.ledgr.app.domain.ledger.Identity
import com.ledgr.app.domain.ledger.LedgerContext
import com.ledgr.app.domain.ledger.LedgerEngine
import com.ledgr.app.domain.ledger.LedgerTxn
import com.ledgr.app.domain.ledger.MerchantContext
import com.ledgr.app.domain.ledger.NEW
import com.ledgr.app.domain.ledger.Resolution
import com.ledgr.app.domain.ledger.Source
import com.ledgr.app.domain.ledger.TxnKind
import com.ledgr.app.domain.ledger.TxnPatch
import com.ledgr.app.domain.ledger.TxnStatus
import com.ledgr.app.domain.ledger.UserEdits
import com.ledgr.app.domain.model.OwnIdentityKind
import com.ledgr.app.domain.profile.AliasKey
import com.ledgr.app.domain.profile.NameColor
import com.ledgr.app.domain.profile.SenderHeaders
import com.ledgr.app.domain.rules.RulesBundle
import com.ledgr.app.domain.rules.TemplateKind
import com.ledgr.app.domain.template.AccountRef
import com.ledgr.app.domain.template.ParsedSms
import com.ledgr.app.domain.time.Ist
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the ledger (docs/02 §3.3, ADR-013): runs the pure `LedgerEngine` and applies what it decides, with
 * provenance (`source`, `raw_sms_id`, `template_id`, `rules_version`). Open one [LedgerSession] per scan page, **inside**
 * the page's database transaction, so a page is all-or-nothing.
 */
@Singleton
class LedgerWriter @Inject constructor(private val rulesLoader: RulesLoader) {

    suspend fun open(db: LedgrDatabase): LedgerSession {
        val rules = rulesLoader.bundle()
        val accounts = db.accountDao().getAll().filter { it.active }.map {
            AccountRef(it.id, it.bankId, it.type, it.last4, SenderHeaders.decode(it.senderHeaders).toSet())
        }
        return LedgerSession(
            db = db,
            rules = rules,
            categories = db.categoryDao().getAll(),
            counterparties = db.counterpartyDao().getAll(),
            aliases = db.counterpartyAliasDao().getAll(),
            identities = db.ownIdentityDao().getAll(),
            accounts = accounts,
        )
    }
}

/**
 * A snapshot of the profile and rule data plus the writes for one page of messages. Not thread-safe; one coroutine
 * uses it at a time.
 */
class LedgerSession internal constructor(
    private val db: LedgrDatabase,
    private val rules: RulesBundle,
    categories: List<CategoryEntity>,
    counterparties: List<CounterpartyEntity>,
    aliases: List<CounterpartyAliasEntity>,
    identities: List<com.ledgr.app.data.db.OwnIdentityEntity>,
    accounts: List<AccountRef>,
) {
    private val accountType: Map<Long, com.ledgr.app.domain.model.AccountType> = accounts.associate { it.id to it.type }
    private val categoryById: Map<Long, CategoryEntity> = categories.associateBy { it.id }
    private val categoryByKey: Map<String, CategoryEntity> = categories.associateBy { it.key }
    private val fallbackKey = rules.ledger.categories.fallback

    /** identity string -> counterparty row id, and back. Grows when a person or wrapped merchant is first seen. */
    private val idByIdentity = HashMap<String, Long>()
    private val identityById = HashMap<Long, String>()

    private val engine: LedgerEngine
    private val dao = db.txnDao()

    init {
        for (cp in counterparties) register(cp.id, cp.merchantKey)
        val targets = HashMap<String, AliasTarget>()
        val cpById = counterparties.associateBy { it.id }
        for (a in aliases) {
            val cp = cpById[a.counterpartyId] ?: continue
            targets[a.rawKey] = AliasTarget(cp.id, cp.merchantKey, cp.displayName, cp.type)
        }
        val defaults = HashMap<String, String>()
        for (cp in counterparties) {
            val key = cp.defaultCategoryId?.let(::keyOf) ?: continue
            defaults[Identity.of(cp.merchantKey, cp.id) ?: continue] = key
        }
        val merchantContext = MerchantContext(
            aliases = targets,
            ownUpiIds = identities.filter { it.kind == OwnIdentityKind.UPI_ID }.map { it.value.lowercase() }.toSet(),
            ownNames = identities.filter { it.kind == OwnIdentityKind.NAME_VARIANT }.mapNotNull { AliasKey.normalize(it.value) }.toSet(),
            merchants = rules.merchants.merchants,
            aggregators = rules.merchants.aggregators,
            ledger = rules.ledger,
            categoryDefaults = defaults,
        )
        engine = LedgerEngine(LedgerContext(merchantContext, accounts, LedgerContext.duplicatePairs(rules)))
    }

    private fun register(id: Long, merchantKey: String?) {
        val identity = Identity.of(merchantKey, id) ?: return
        idByIdentity[identity] = id
        identityById[id] = identity
    }

    // ───────────────────────────── categories ─────────────────────────────

    /** The category row a key points at, following merges (ADR-011). Falls back to `uncategorised`. */
    private fun categoryId(key: String): Long? = finalCategory(categoryByKey[key] ?: categoryByKey[fallbackKey])?.id

    /** The key of the category a stored id now belongs to (after merges), for the pure engine. */
    private fun keyOf(id: Long): String? = finalCategory(categoryById[id])?.key

    private fun finalCategory(start: CategoryEntity?): CategoryEntity? {
        var c = start ?: return null
        var hops = 0
        while (c.mergedIntoId != null && hops++ < MAX_MERGE_HOPS) c = categoryById[c.mergedIntoId] ?: return c
        return c
    }

    // ───────────────────────────── counterparties ─────────────────────────────

    /** The row for a resolved counterparty, created on first sight (persons, wrapped merchants, "own account"). */
    private suspend fun counterpartyId(res: Resolution): Long? {
        val identity = res.identityKey ?: return null
        idByIdentity[identity]?.let { return it }
        if (identity.startsWith("id:")) return null // an alias target that no longer exists
        val row = CounterpartyEntity(
            displayName = res.displayName,
            type = res.type,
            merchantKey = identity,
            defaultCategoryId = res.dictionaryCategory?.let(::categoryId),
            icon = CounterpartyEntity.ICON_LETTER,
            color = NameColor.indexFor(res.displayName),
        )
        val id = db.counterpartyDao().insert(row)
        register(id, identity)
        return id
    }

    // ───────────────────────────── ingest ─────────────────────────────

    /**
     * Records one parsed SMS. A balance-only message writes a snapshot. A transaction is inserted, or, when the same
     * SMS was ingested before (a month re-scan), refreshed without touching anything the person edited.
     */
    suspend fun ingest(rawSmsId: Long, parsed: ParsedSms, body: String, rulesVersion: String) {
        if (parsed.templateKind == TemplateKind.BALANCE) {
            snapshot(parsed)
            return
        }
        val existing = dao.byRawSmsId(rawSmsId)
        val resolution = engine.resolve(parsed)
        val candidates = candidates(parsed, resolution).filter { it.id != existing?.id }
        val plan = engine.plan(IngestInput(parsed, body, rawSmsId, rulesVersion), candidates)
        if (existing == null) insertNew(plan) else refresh(existing, plan)
        snapshot(parsed)
    }

    /** The message no longer parses as a transaction: detach and delete the row unless the person edited it. */
    suspend fun retract(rawSmsId: Long) {
        val t = dao.byRawSmsId(rawSmsId) ?: return
        if (t.status == TxnStatus.DELETED || t.source != Source.SMS) return
        if (UserEdits.decode(t.userEditedFields).isNotEmpty()) return
        dao.update(t.copy(status = TxnStatus.DELETED, checkReason = null, duplicateOf = null, transferPairId = null, refundOf = null))
        dao.clearPairsTo(t.id)
        dao.clearRefundsTo(t.id)
        dao.reviveDuplicatesOf(t.id)
    }

    // ───────────────────────────── a person confirms a proposal ─────────────────────────────

    /**
     * "Yes, these two are one transfer" (Needs review → Duplicates & transfers). Pairs the legs and gives each its
     * transfer kind and category, except where the person already set that field. False if they no longer fit.
     */
    suspend fun confirmTransfer(txnId: Long, otherId: Long): Boolean {
        val a = dao.get(txnId) ?: return false
        val b = dao.get(otherId) ?: return false
        if (a.direction == b.direction || a.accountId == b.accountId) return false
        if (a.status != TxnStatus.ACTIVE || b.status != TxnStatus.ACTIVE) return false
        val (debit, credit) = if (a.direction == com.ledgr.app.domain.ledger.Direction.DEBIT) a to b else b to a
        val toCard = accountType[credit.accountId] == com.ledgr.app.domain.model.AccountType.CREDIT_CARD
        val debitKind = if (toCard) TxnKind.CARD_BILL_PAYMENT else TxnKind.SELF_TRANSFER_OUT
        val creditKind = if (toCard) TxnKind.CARD_PAYMENT_RECEIVED else TxnKind.SELF_TRANSFER_IN
        dao.update(retyped(debit, debitKind).copy(transferPairId = credit.id))
        dao.update(retyped(credit, creditKind).copy(transferPairId = debit.id))
        return true
    }

    /** "Yes, that is the same payment twice": the newer one becomes the duplicate unless the person edited it less. */
    suspend fun confirmDuplicate(txnId: Long, otherId: Long): Boolean {
        val a = dao.get(txnId) ?: return false
        val b = dao.get(otherId) ?: return false
        if (a.status != TxnStatus.ACTIVE || b.status != TxnStatus.ACTIVE) return false
        val aEdited = UserEdits.decode(a.userEditedFields).isNotEmpty()
        val bEdited = UserEdits.decode(b.userEditedFields).isNotEmpty()
        val (loser, winner) = if (aEdited && !bEdited) b to a else a to b
        dao.update(loser.copy(status = TxnStatus.DUPLICATE, duplicateOf = winner.id, checkReason = null))
        return true
    }

    private fun retyped(t: TxnEntity, kind: TxnKind): TxnEntity {
        val edited = UserEdits.decode(t.userEditedFields)
        var u = t.copy(checkReason = null)
        if (EditableField.KIND !in edited) u = u.copy(kind = kind)
        if (EditableField.CATEGORY !in edited) {
            rules.ledger.categories.byKind[kind.name]?.let { key -> u = u.copy(categoryId = categoryId(key)) }
        }
        return u
    }

    private suspend fun snapshot(p: ParsedSms) {
        val balance = p.balanceAfter ?: return
        if (p.accountId <= 0) return
        db.balanceSnapshotDao().insert(
            BalanceSnapshotEntity(accountId = p.accountId, at = p.occurredAt, balance = balance, source = Source.SMS),
        )
    }

    private suspend fun insertNew(plan: IngestPlan) {
        val d = plan.draft
        val id = dao.insert(
            TxnEntity(
                accountId = d.accountId,
                kind = d.kind,
                direction = d.direction,
                amount = d.amount,
                occurredAt = d.occurredAt,
                localDate = Ist.localDate(d.occurredAt).toString(),
                channel = d.channel,
                counterpartyId = counterpartyId(d.resolution),
                rawCounterparty = d.rawCounterparty,
                categoryId = categoryId(d.categoryKey),
                refNo = d.refNo,
                balanceAfter = d.balanceAfter,
                source = Source.SMS,
                rawSmsId = d.rawSmsId,
                confidence = d.confidence,
                status = d.status,
                duplicateOf = d.duplicateOf,
                transferPairId = d.transferPairId,
                refundOf = d.refundOf,
                templateId = d.templateId,
                rulesVersion = d.rulesVersion,
                checkReason = d.checkReason,
            ),
        )
        applyPatches(id, plan)
    }

    /** A re-scan of a stored message: refresh what the parse gave, never what the person edited. */
    private suspend fun refresh(existing: TxnEntity, plan: IngestPlan) {
        val d = plan.draft
        val edited = UserEdits.decode(existing.userEditedFields)
        var u = existing.copy(
            channel = d.channel,
            rawCounterparty = d.rawCounterparty,
            refNo = existing.refNo ?: d.refNo,
            balanceAfter = existing.balanceAfter ?: d.balanceAfter,
            templateId = d.templateId,
            rulesVersion = d.rulesVersion,
            confidence = d.confidence,
        )
        if (EditableField.AMOUNT !in edited) u = u.copy(amount = d.amount)
        if (EditableField.OCCURRED_AT !in edited) {
            u = u.copy(occurredAt = d.occurredAt, localDate = Ist.localDate(d.occurredAt).toString())
        }
        if (EditableField.ACCOUNT !in edited) u = u.copy(accountId = d.accountId)

        val unlinked = (existing.status == TxnStatus.ACTIVE || existing.status == TxnStatus.DELETED) &&
            existing.duplicateOf == null && existing.transferPairId == null && existing.refundOf == null
        if (unlinked) {
            if (EditableField.KIND !in edited) u = u.copy(kind = d.kind)
            if (EditableField.COUNTERPARTY !in edited) u = u.copy(counterpartyId = counterpartyId(d.resolution))
            if (EditableField.CATEGORY !in edited) u = u.copy(categoryId = categoryId(d.categoryKey))
            u = u.copy(
                status = d.status,
                duplicateOf = d.duplicateOf,
                transferPairId = d.transferPairId,
                refundOf = d.refundOf,
                // A confirmed row stays confirmed; a flagged one is re-judged.
                checkReason = if (existing.checkReason != null || existing.status == TxnStatus.DELETED) d.checkReason else null,
            )
        } else if (existing.checkReason != null && existing.status == TxnStatus.ACTIVE) {
            u = u.copy(checkReason = d.checkReason)
        }
        if (u != existing) dao.update(u)
        if (unlinked) applyPatches(existing.id, plan)
    }

    private suspend fun applyPatches(newId: Long, plan: IngestPlan) {
        fun sub(x: Long?): Long? = if (x == NEW) newId else x
        for (patch in plan.patches) {
            val t = dao.get(patch.id) ?: continue
            val u = patched(t, patch, ::sub)
            if (u != t) dao.update(u)
        }
        val now = Ist.now()
        for (p in plan.proposals) {
            val a = sub(p.txnId) ?: continue
            val b = sub(p.otherId) ?: continue
            db.linkProposalDao().insert(LinkProposalEntity(type = p.type, txnId = a, otherTxnId = b, createdAt = now))
        }
    }

    private fun patched(t: TxnEntity, patch: TxnPatch, sub: (Long?) -> Long?): TxnEntity {
        val edited = UserEdits.decode(t.userEditedFields)
        var u = t
        patch.kind?.let { if (EditableField.KIND !in edited) u = u.copy(kind = it, checkReason = null) }
        patch.categoryKey?.let { if (EditableField.CATEGORY !in edited) u = u.copy(categoryId = categoryId(it)) }
        patch.status?.let { u = u.copy(status = it, checkReason = null) }
        patch.duplicateOf?.let { u = u.copy(duplicateOf = sub(it)) }
        patch.transferPairId?.let { u = u.copy(transferPairId = sub(it)) }
        patch.refundOf?.let { u = u.copy(refundOf = sub(it)) }
        patch.refNo?.let { u = u.copy(refNo = it) }
        patch.balanceAfter?.let { u = u.copy(balanceAfter = it) }
        return u
    }

    // ───────────────────────────── candidates ─────────────────────────────

    /** Existing transactions near the new one, as the pure engine's [LedgerTxn]. */
    private suspend fun candidates(p: ParsedSms, resolution: Resolution): List<LedgerTxn> {
        val near = dao.window(p.occurredAt - NEAR_MS, p.occurredAt + NEAR_MS)
        val byCounterparty = resolution.identityKey?.let { idByIdentity[it] }?.let { cp ->
            dao.byCounterparty(cp, p.occurredAt - REFUND_MS, p.occurredAt + REFUND_MS)
        }.orEmpty()
        val merged = (near + byCounterparty).distinctBy { it.id }
        val originals = merged.filter { it.kind == TxnKind.EXPENSE }.map { it.id }
        val linked = originals.chunked(CHUNK).flatMap { dao.linkedTo(it) }
        return (merged + linked).distinctBy { it.id }.map(::toLedger)
    }

    private fun toLedger(e: TxnEntity) = LedgerTxn(
        id = e.id,
        accountId = e.accountId,
        kind = e.kind,
        direction = e.direction,
        amount = e.amount,
        occurredAt = e.occurredAt,
        channel = e.channel,
        counterpartyKey = e.counterpartyId?.let { identityById[it] },
        categoryKey = e.categoryId?.let(::keyOf),
        refNo = e.refNo,
        balanceAfter = e.balanceAfter,
        templateId = e.templateId,
        rawCounterparty = e.rawCounterparty,
        status = e.status,
        duplicateOf = e.duplicateOf,
        transferPairId = e.transferPairId,
        refundOf = e.refundOf,
        userEdited = UserEdits.decode(e.userEditedFields),
    )

    private companion object {
        const val MAX_MERGE_HOPS = 10
        const val DAY_MS = 24 * 60 * 60 * 1000L

        /** Dedup, transfer and reversal windows are at most 3 days. */
        const val NEAR_MS = 3 * DAY_MS
        const val REFUND_MS = 60 * DAY_MS

        /** SQLite allows 999 bound variables. */
        const val CHUNK = 500
    }
}
