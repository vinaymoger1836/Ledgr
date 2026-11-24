package com.ledgr.app.data.statement

import android.net.Uri
import androidx.room.withTransaction
import com.ledgr.app.data.db.ColumnMapEntity
import com.ledgr.app.data.db.DatabaseManager
import com.ledgr.app.data.db.LedgrDatabase
import com.ledgr.app.data.db.ReconMatchEntity
import com.ledgr.app.data.db.ReconRow
import com.ledgr.app.data.db.StatementImportEntity
import com.ledgr.app.data.db.StatementLineEntity
import com.ledgr.app.data.ledger.LedgerWriter
import com.ledgr.app.data.rules.RulesLoader
import com.ledgr.app.domain.ledger.Direction
import com.ledgr.app.domain.ledger.EditableField
import com.ledgr.app.domain.ledger.TxnStatus
import com.ledgr.app.domain.ledger.UserEdits
import com.ledgr.app.domain.statement.Analysis
import com.ledgr.app.domain.statement.ColumnMap
import com.ledgr.app.domain.statement.FileType
import com.ledgr.app.domain.statement.MatchStatus
import com.ledgr.app.domain.statement.NarrationParser
import com.ledgr.app.domain.statement.PdfLine
import com.ledgr.app.domain.statement.RawStatement
import com.ledgr.app.domain.statement.ReconMatch
import com.ledgr.app.domain.statement.ReconReasons
import com.ledgr.app.domain.statement.ReconTxn
import com.ledgr.app.domain.statement.Reconciler
import com.ledgr.app.domain.statement.StatementAnalyser
import com.ledgr.app.domain.statement.StatementException
import com.ledgr.app.domain.statement.StatementJson
import com.ledgr.app.domain.statement.StatementLine
import com.ledgr.app.domain.statement.StatementProfiles
import com.ledgr.app.domain.statement.StatementWarning
import com.ledgr.app.domain.time.Ist
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// ───────────────────────────── what the screens see ─────────────────────────────

sealed interface OpenResult {
    data class Ready(val draftId: Long) : OpenResult

    /** An encrypted PDF. [wrong] is true when a password was tried and did not open it. */
    data class NeedsPassword(val draftId: Long, val wrong: Boolean) : OpenResult

    data class Failed(val code: String) : OpenResult
}

sealed interface CommitResult {
    data class Done(val importId: Long) : CommitResult

    data class Failed(val code: String) : CommitResult
}

/** A file that has been read and is waiting for its columns to be confirmed (or applied from a saved map). */
class DraftView(
    val draftId: Long,
    val accountId: Long,
    val bankId: String,
    val fileName: String,
    val type: FileType,
    val analysis: Analysis,
    /** PDF only: the text lines of the first page (positions in 1/10 pt) for the divider preview. */
    val pdfLines: List<PdfLine>,
)

/** One row of the results screen. Money is paise; a side that does not exist has 0 and a null id. */
data class ReconItem(
    val matchId: Long,
    val status: MatchStatus,
    val scorePm: Int,
    val resolvedByUser: Boolean,
    val reasons: List<String>,
    val lineId: Long?,
    val lineDate: LocalDate?,
    val lineDescription: String?,
    val lineRef: String?,
    val lineIsDebit: Boolean,
    val lineAmount: Long,
    val lineBalance: Long?,
    val txnId: Long?,
    val txnDate: LocalDate?,
    val txnIsDebit: Boolean,
    val txnAmount: Long,
    val txnName: String?,
    val txnCategory: String?,
    val txnRef: String?,
    val txnBalance: Long?,
) {
    /** A balance hint (docs/06 §3.4): both sides report a balance and they differ. */
    val balanceDiffers: Boolean get() = lineBalance != null && txnBalance != null && lineBalance != txnBalance
}

data class ReconSummary(
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val lineCount: Int,
    val statementDebit: Long,
    val statementCredit: Long,
    val ledgerDebit: Long,
    val ledgerCredit: Long,
    val matchRateBp: Int,
    val counts: Map<MatchStatus, Int>,
) {
    /** Statement debits minus ledger debits: what the SMS ledger is short of (negative: the ledger has more). */
    val debitDifference: Long get() = statementDebit - ledgerDebit
    val creditDifference: Long get() = statementCredit - ledgerCredit
}

class ReconResults(
    val import: StatementImportEntity,
    val summary: ReconSummary,
    val items: List<ReconItem>,
    val warnings: List<StatementWarning>,
)

// ───────────────────────────── drafts (in memory only) ─────────────────────────────

/** Files that were picked but not yet saved. Lives in memory: after a process death the person simply picks the file again. */
@Singleton
class DraftStore @Inject constructor() {
    class Draft(val id: Long, val accountId: Long, val bankId: String, val fileName: String, val file: File) {
        @Volatile var read: ReadFile? = null
    }

    private val drafts = ConcurrentHashMap<Long, Draft>()
    private val next = AtomicLong(1)

    fun add(accountId: Long, bankId: String, fileName: String, file: File): Draft =
        Draft(next.getAndIncrement(), accountId, bankId, fileName, file).also { drafts[it.id] = it }

    fun get(id: Long): Draft? = drafts[id]

    fun remove(id: Long): Draft? = drafts.remove(id)
}

// ───────────────────────────── the repository ─────────────────────────────

/**
 * Statement import and reconciliation (docs/06, ADR-016). Intake, reading, saved maps, the reconciler run, and every action on
 * the results screen. The matching itself is the pure `Reconciler`; this class loads its inputs and stores its answer.
 *
 * What it writes to a ledger transaction: `recon_state` and `statement_line_id` only, through the reconcile run. A person's
 * explicit choice (use the statement's amount, move to another account) goes through [EditableField] like any other edit, so a
 * later re-parse leaves it alone (CLAUDE.md rule 7).
 */
@Singleton
class ReconcileRepository @Inject constructor(
    private val db: DatabaseManager,
    private val intake: StatementIntake,
    private val reader: StatementReader,
    private val drafts: DraftStore,
    private val rulesLoader: RulesLoader,
    private val ledgerWriter: LedgerWriter,
) {
    // ── intake ──

    suspend fun startImport(uri: Uri, accountId: Long): OpenResult {
        val account = db.withDb { it.accountDao().get(accountId) } ?: return OpenResult.Failed("no_account")
        val stored = try {
            withContext(Dispatchers.IO) { intake.copy(uri) }
        } catch (e: StatementException) {
            return OpenResult.Failed(e.code)
        }
        val draft = drafts.add(accountId, account.bankId, stored.displayName, stored.file)
        return open(draft, null)
    }

    suspend fun unlock(draftId: Long, password: String): OpenResult {
        val draft = drafts.get(draftId) ?: return OpenResult.Failed("draft_lost")
        return open(draft, password)
    }

    /** Throws the copy away (the person backed out). */
    fun cancel(draftId: Long) {
        drafts.remove(draftId)?.let { intake.delete(it.file) }
    }

    fun purgeStale() = intake.purgeStale()

    private suspend fun open(draft: DraftStore.Draft, password: String?): OpenResult =
        try {
            draft.read = reader.read(draft.file, password)
            OpenResult.Ready(draft.id)
        } catch (e: StatementException) {
            when (e.code) {
                StatementException.NEEDS_PASSWORD -> OpenResult.NeedsPassword(draft.id, wrong = false)
                StatementException.WRONG_PASSWORD -> OpenResult.NeedsPassword(draft.id, wrong = true)
                else -> {
                    cancel(draft.id)
                    OpenResult.Failed(e.code)
                }
            }
        }

    // ── analysis and mapping ──

    /** Header, saved-map lookup, guess and (when a map applies) the normalised lines. [override] is what the person is editing. */
    suspend fun analyse(draftId: Long, override: ColumnMap? = null): DraftView? {
        val draft = drafts.get(draftId) ?: return null
        val read = draft.read ?: return null
        val profiles = rulesLoader.bundle().statementProfiles
        val saved = savedMaps(draft.bankId, read.type)
        val analysis = withContext(Dispatchers.Default) {
            StatementAnalyser.analyse(read.raw, profiles, draft.bankId, lookup = { saved[it]?.second }, override = override)
        }
        val pdfLines = (read.raw as? RawStatement.Pdf)?.lines.orEmpty().takeWhile { it.page == (read.raw as RawStatement.Pdf).lines.firstOrNull()?.page }.take(PREVIEW_LINES)
        return DraftView(draft.id, draft.accountId, draft.bankId, draft.fileName, read.type, analysis, pdfLines)
    }

    /** signature -> (row id, map) of every saved map for this bank and file type. A damaged row is skipped, not fatal. */
    private suspend fun savedMaps(bankId: String, type: FileType): Map<String, Pair<Long, ColumnMap>> =
        db.withDb { database ->
            database.columnMapDao().forBank(bankId, type).mapNotNull { row ->
                try {
                    row.headerSignature to (row.id to StatementJson.decodeMap(row.mapping, row.pdfColumns))
                } catch (e: Exception) {
                    null
                }
            }.toMap()
        }

    /**
     * Saves the import: lines, the confirmed map (when [map] is given and [remember]), the first reconciliation. With [map] null
     * the saved map for this layout is applied (the second import of a bank). The picked file's copy is deleted afterwards.
     */
    suspend fun commit(draftId: Long, map: ColumnMap?, remember: Boolean = true): CommitResult {
        val draft = drafts.get(draftId) ?: return CommitResult.Failed("draft_lost")
        val view = analyse(draftId, map) ?: return CommitResult.Failed("draft_lost")
        val analysis = view.analysis
        val statement = analysis.statement ?: return CommitResult.Failed(StatementException.NO_HEADER)
        if (statement.lines.isEmpty()) return CommitResult.Failed(StatementException.NO_ROWS)
        val from = statement.periodFrom ?: return CommitResult.Failed(StatementException.NO_ROWS)
        val to = statement.periodTo ?: return CommitResult.Failed(StatementException.NO_ROWS)
        val profiles = rulesLoader.bundle().statementProfiles
        val usedMap = analysis.map ?: return CommitResult.Failed(StatementException.NO_HEADER)

        val importId = db.withDb { database ->
            database.withTransaction {
                val mapId = mapIdFor(database, draft.bankId, view.type, analysis.signature, usedMap, map != null && remember)
                val importId = database.statementImportDao().insert(
                    StatementImportEntity(
                        accountId = draft.accountId,
                        bankId = draft.bankId,
                        fileName = draft.fileName,
                        fileType = view.type,
                        periodFrom = from.toString(),
                        periodTo = to.toString(),
                        importedAt = Ist.now(),
                        columnMapId = mapId,
                        openingBalance = statement.opening,
                        closingBalance = statement.closing,
                        lineCount = statement.lines.size,
                        skippedRows = statement.skippedRows,
                        warningsJson = StatementJson.encodeWarnings(statement.warnings),
                    ),
                )
                database.statementLineDao().insertAll(
                    statement.lines.map {
                        StatementLineEntity(
                            importId = importId,
                            ordinal = it.ordinal,
                            rowIndex = it.sourceRow,
                            txnDate = it.date.toString(),
                            valueDate = it.valueDate?.toString(),
                            description = it.description,
                            refNo = it.refNo,
                            debit = it.debit,
                            credit = it.credit,
                            balance = it.balance,
                            rawRow = StatementJson.encodeStrings(it.rawCells),
                        )
                    },
                )
                reconcile(database, importId, profiles)
                importId
            }
        }
        cancel(draftId)
        return CommitResult.Done(importId)
    }

    /** The id of the saved map for this layout: stored (or updated) when the person confirmed one, else the one that was applied. */
    private suspend fun mapIdFor(
        database: LedgrDatabase,
        bankId: String,
        type: FileType,
        signature: String?,
        map: ColumnMap,
        store: Boolean,
    ): Long? {
        if (signature == null) return null
        val dao = database.columnMapDao()
        val existing = dao.find(bankId, type, signature)
        if (!store) return existing?.id
        val row = ColumnMapEntity(
            id = existing?.id ?: 0,
            bankId = bankId,
            fileType = type,
            headerSignature = signature,
            mapping = StatementJson.encodeMapping(map),
            pdfColumns = StatementJson.encodeDividers(map.pdfColumns),
            updatedAt = Ist.now(),
        )
        return if (existing == null) dao.insert(row) else {
            dao.update(row)
            existing.id
        }
    }

    // ── reconciliation ──

    /** Re-match an import against the ledger as it is now (new SMS, rule changes). Decisions the person made are kept. */
    suspend fun rerun(importId: Long) {
        val profiles = rulesLoader.bundle().statementProfiles
        db.withDb { database -> database.withTransaction { reconcile(database, importId, profiles) } }
    }

    private suspend fun reconcile(database: LedgrDatabase, importId: Long, profiles: StatementProfiles) {
        val imp = database.statementImportDao().get(importId) ?: return
        val lineRows = database.statementLineDao().byImport(importId)
        val lines = lineRows.map {
            StatementLine(
                it.ordinal, it.rowIndex, LocalDate.parse(it.txnDate), it.valueDate?.let(LocalDate::parse),
                it.description, it.refNo, it.debit, it.credit, it.balance, emptyList(),
            )
        }
        val lineIdByOrdinal = lineRows.associate { it.ordinal to it.id }
        val ordinalByLineId = lineRows.associate { it.id to it.ordinal }
        val from = LocalDate.parse(imp.periodFrom)
        val to = LocalDate.parse(imp.periodTo)
        val txns = database.txnDao().reconCandidates(imp.accountId, from.minusDays(WINDOW_DAYS).toString(), to.plusDays(WINDOW_DAYS).toString())
        val ledger = txns.map { ReconTxn(it.id, LocalDate.parse(it.localDate), it.direction, it.amount, it.refNo, it.rawCounterparty, it.balanceAfter) }
        val ledgerIds = ledger.map { it.id }.toSet()

        val existing = database.reconMatchDao().byImport(importId)
        val markers = existing.filter { isRejection(it) }
        val decided = existing.filter { it.resolvedByUser && !isRejection(it) }
        val pinnedRows = decided.filter { it.status == MatchStatus.MATCHED || it.status == MatchStatus.MATCHED_FUZZY }
            .filter { it.statementLineId != null && it.txnId != null }
        val (livePinned, stalePinned) = pinnedRows.partition { it.txnId in ledgerIds }
        val keptLedgerOnly = decided.filter { it.status == MatchStatus.LEDGER_ONLY }
        val (liveKept, staleKept) = keptLedgerOnly.partition { it.txnId in ledgerIds }

        database.txnDao().resetReconForImport(importId)
        database.reconMatchDao().deleteUndecided(importId)
        (stalePinned + staleKept).forEach { database.reconMatchDao().delete(it.id) }

        val pinned = livePinned.mapNotNull { row ->
            val ordinal = ordinalByLineId[row.statementLineId] ?: return@mapNotNull null
            ReconMatch(ordinal, row.txnId, row.status, row.scorePm, StatementJson.decodeStrings(row.reasons))
        }
        val rejected = markers.mapNotNull { row ->
            val ordinal = ordinalByLineId[row.statementLineId] ?: return@mapNotNull null
            ordinal to (row.txnId ?: return@mapNotNull null)
        }.toSet()
        val result = Reconciler(profiles).match(lines, ledger, from, to, pinned, rejected)

        val pinnedPairs = pinned.map { it.lineOrdinal to it.txnId }.toSet()
        val keptTxnIds = liveKept.mapNotNull { it.txnId }.toSet()
        val fresh = result.matches.filter { m ->
            when {
                (m.lineOrdinal to m.txnId) in pinnedPairs -> false
                m.status == MatchStatus.LEDGER_ONLY && m.txnId in keptTxnIds -> false
                else -> true
            }
        }
        database.reconMatchDao().insertAll(
            fresh.map {
                ReconMatchEntity(
                    importId = importId,
                    statementLineId = it.lineOrdinal?.let(lineIdByOrdinal::get),
                    txnId = it.txnId,
                    status = it.status,
                    scorePm = it.scorePm,
                    reasons = StatementJson.encodeStrings(it.reasons),
                )
            },
        )

        for (m in result.matches) {
            val txn = m.txnId ?: continue
            val lineId = m.lineOrdinal?.let(lineIdByOrdinal::get)
            when (m.status) {
                MatchStatus.MATCHED -> database.txnDao().setRecon(txn, "MATCHED", lineId)
                MatchStatus.MATCHED_FUZZY -> database.txnDao().setRecon(txn, "MATCHED_FUZZY", lineId)
                MatchStatus.LEDGER_ONLY ->
                    if (ReconReasons.OUTSIDE_PERIOD !in m.reasons) database.txnDao().setRecon(txn, "LEDGER_ONLY", null)
                else -> Unit
            }
        }
        for (k in liveKept) k.txnId?.let { database.txnDao().setRecon(it, "LEDGER_ONLY", null) }
        refreshRate(database, importId)
    }

    /** A rejected pairing is remembered as a resolved STATEMENT_ONLY row that also names the transaction (ADR-016). */
    private fun isRejection(row: ReconMatchEntity) =
        row.status == MatchStatus.STATEMENT_ONLY && row.txnId != null && row.statementLineId != null

    private suspend fun refreshRate(database: LedgrDatabase, importId: Long) {
        val imp = database.statementImportDao().get(importId) ?: return
        val rows = database.reconMatchDao().byImport(importId)
        val paired = rows.count { it.statementLineId != null && it.txnId != null && it.status != MatchStatus.STATEMENT_ONLY }
        val rate = if (imp.lineCount <= 0) 0 else (paired.toLong() * 10_000 / imp.lineCount).toInt()
        if (rate != imp.matchRateBp) database.statementImportDao().update(imp.copy(matchRateBp = rate))
    }

    // ── what the screens observe ──

    fun observeHistory(): Flow<List<StatementImportEntity>> = db.observe { it.statementImportDao().observeAll() }

    fun observeResults(importId: Long): Flow<ReconResults?> =
        db.observe { database ->
            kotlinx.coroutines.flow.combine(
                database.statementImportDao().observe(importId),
                database.reconMatchDao().observeRows(importId),
            ) { imp, rows -> imp?.let { toResults(it, rows) } }
        }

    private fun toResults(imp: StatementImportEntity, rows: List<ReconRow>): ReconResults {
        val visible = rows.filterNot { it.status == MatchStatus.STATEMENT_ONLY && it.txnId != null }
        val items = visible.map { r ->
            ReconItem(
                matchId = r.matchId,
                status = r.status,
                scorePm = r.scorePm,
                resolvedByUser = r.resolvedByUser,
                reasons = StatementJson.decodeStrings(r.reasons),
                lineId = r.lineId,
                lineDate = r.lineDate?.let(LocalDate::parse),
                lineDescription = r.lineDescription,
                lineRef = r.lineRef,
                lineIsDebit = (r.lineDebit ?: 0L) != 0L,
                lineAmount = if ((r.lineDebit ?: 0L) != 0L) r.lineDebit ?: 0L else r.lineCredit ?: 0L,
                lineBalance = r.lineBalance,
                txnId = r.txnId,
                txnDate = r.txnDate?.let(LocalDate::parse),
                txnIsDebit = r.txnDirection == Direction.DEBIT,
                txnAmount = r.txnAmount ?: 0L,
                txnName = r.txnCounterparty ?: r.txnRaw,
                txnCategory = r.txnCategory,
                txnRef = r.txnRef,
                txnBalance = r.txnBalance,
            )
        }.sortedWith(compareBy({ it.lineDate ?: it.txnDate }, { it.lineId ?: Long.MAX_VALUE }, { it.txnId ?: Long.MAX_VALUE }))

        val from = LocalDate.parse(imp.periodFrom)
        val to = LocalDate.parse(imp.periodTo)
        // Plain debit/credit sums, not "spend": reconciliation compares money movements on the account (a card payment
        // received is a credit on the statement even though LedgerRules does not count it as income).
        val withLine = items.filter { it.lineId != null }
        val inPeriod = visible.filter { it.txnId != null && it.txnStatus == TxnStatus.ACTIVE && it.txnDate != null && it.txnDate >= imp.periodFrom && it.txnDate <= imp.periodTo }
        val summary = ReconSummary(
            periodFrom = from,
            periodTo = to,
            lineCount = imp.lineCount,
            statementDebit = withLine.filter { it.lineIsDebit }.sumOf { it.lineAmount },
            statementCredit = withLine.filter { !it.lineIsDebit }.sumOf { it.lineAmount },
            ledgerDebit = inPeriod.filter { it.txnDirection == Direction.DEBIT }.sumOf { it.txnAmount ?: 0L },
            ledgerCredit = inPeriod.filter { it.txnDirection == Direction.CREDIT }.sumOf { it.txnAmount ?: 0L },
            matchRateBp = imp.matchRateBp,
            counts = MatchStatus.entries.associateWith { s -> items.count { it.status == s } },
        )
        return ReconResults(imp, summary, items, StatementJson.decodeWarnings(imp.warningsJson))
    }

    // ── actions on the results screen (docs/06 §4) ──

    /** Confirm a fuzzy match: it becomes a full match that a re-run keeps. */
    suspend fun confirmFuzzy(matchId: Long) = db.withDb { database ->
        database.withTransaction {
            val m = database.reconMatchDao().get(matchId) ?: return@withTransaction
            val txn = m.txnId ?: return@withTransaction
            database.reconMatchDao().update(m.copy(status = MatchStatus.MATCHED, resolvedByUser = true))
            database.txnDao().setRecon(txn, "MATCHED", m.statementLineId)
            refreshRate(database, m.importId)
        }
    }

    /** Reject a pairing: line and transaction go back to "missing in app" / "not in statement", and are not paired again. */
    suspend fun rejectMatch(matchId: Long) = db.withDb { database ->
        database.withTransaction {
            val m = database.reconMatchDao().get(matchId) ?: return@withTransaction
            val txn = m.txnId ?: return@withTransaction
            val line = m.statementLineId ?: return@withTransaction
            val dao = database.reconMatchDao()
            dao.delete(m.id)
            dao.insert(ReconMatchEntity(importId = m.importId, statementLineId = line, txnId = null, status = MatchStatus.STATEMENT_ONLY, scorePm = 0, reasons = "[]"))
            dao.insert(ReconMatchEntity(importId = m.importId, statementLineId = null, txnId = txn, status = MatchStatus.LEDGER_ONLY, scorePm = 0, reasons = "[]"))
            dao.insert(
                ReconMatchEntity(
                    importId = m.importId, statementLineId = line, txnId = txn, status = MatchStatus.STATEMENT_ONLY, scorePm = 0,
                    reasons = StatementJson.encodeStrings(listOf(REJECTED)), resolvedByUser = true,
                ),
            )
            database.txnDao().setRecon(txn, "LEDGER_ONLY", null)
            refreshRate(database, m.importId)
        }
    }

    /**
     * An amount mismatch: [useStatementAmount] copies the statement's amount onto the transaction (recorded as the person's own
     * edit, so a re-parse keeps it); otherwise the ledger amount stands. Either way the pair is settled.
     */
    suspend fun resolveMismatch(matchId: Long, useStatementAmount: Boolean) = db.withDb { database ->
        database.withTransaction {
            val m = database.reconMatchDao().get(matchId) ?: return@withTransaction
            val txnId = m.txnId ?: return@withTransaction
            val lineId = m.statementLineId ?: return@withTransaction
            if (useStatementAmount) {
                val line = database.statementLineDao().get(lineId) ?: return@withTransaction
                val txn = database.txnDao().get(txnId) ?: return@withTransaction
                val amount = if (line.debit != 0L) line.debit else line.credit
                val edited = UserEdits.decode(txn.userEditedFields) + EditableField.AMOUNT
                database.txnDao().update(txn.copy(amount = amount, userEditedFields = UserEdits.encode(edited)))
            }
            val reasons = StatementJson.decodeStrings(m.reasons) + (if (useStatementAmount) RESOLVED_STATEMENT else RESOLVED_LEDGER)
            database.reconMatchDao().update(m.copy(status = MatchStatus.MATCHED, reasons = StatementJson.encodeStrings(reasons), resolvedByUser = true))
            database.txnDao().setRecon(txnId, "MATCHED", lineId)
            refreshRate(database, m.importId)
        }
    }

    /**
     * "Add to ledger": creates transactions (`source = STATEMENT`) for statement-only lines, running merchant resolution and
     * categorisation on the narration. Returns how many were added.
     */
    suspend fun addToLedger(matchIds: List<Long>): Int {
        val bundle = rulesLoader.bundle()
        val narration = NarrationParser(bundle.statementProfiles.narration)
        return db.withDb { database ->
            database.withTransaction {
                val session = ledgerWriter.open(database)
                var added = 0
                val touched = HashSet<Long>()
                for (id in matchIds) {
                    val m = database.reconMatchDao().get(id) ?: continue
                    if (m.status != MatchStatus.STATEMENT_ONLY || m.txnId != null) continue
                    val line = m.statementLineId?.let { database.statementLineDao().get(it) } ?: continue
                    val imp = database.statementImportDao().get(m.importId) ?: continue
                    val txnId = session.addFromStatement(imp.accountId, line, narration.parse(line.description), bundle.version)
                    database.reconMatchDao().update(
                        m.copy(txnId = txnId, status = MatchStatus.MATCHED, reasons = StatementJson.encodeStrings(listOf(ADDED)), resolvedByUser = true),
                    )
                    touched += m.importId
                    added++
                }
                touched.forEach { refreshRate(database, it) }
                added
            }
        }
    }

    /** A ledger-only transaction that is really a repeat the app missed: out of the ledger's counting, like any duplicate. */
    suspend fun markDuplicate(matchId: Long) = db.withDb { database ->
        database.withTransaction {
            val m = database.reconMatchDao().get(matchId) ?: return@withTransaction
            val txn = m.txnId?.let { database.txnDao().get(it) } ?: return@withTransaction
            database.txnDao().update(txn.copy(status = TxnStatus.DUPLICATE, checkReason = null, reconState = com.ledgr.app.domain.ledger.ReconState.UNRECONCILED, statementLineId = null))
            database.reconMatchDao().delete(m.id)
        }
    }

    /** The SMS was read as the wrong account: move the transaction (recorded as the person's edit) and take it off this list. */
    suspend fun moveToAccount(matchId: Long, accountId: Long) = db.withDb { database ->
        database.withTransaction {
            val m = database.reconMatchDao().get(matchId) ?: return@withTransaction
            val txn = m.txnId?.let { database.txnDao().get(it) } ?: return@withTransaction
            if (database.accountDao().get(accountId) == null) return@withTransaction
            val edited = UserEdits.decode(txn.userEditedFields) + EditableField.ACCOUNT
            database.txnDao().update(
                txn.copy(accountId = accountId, userEditedFields = UserEdits.encode(edited), reconState = com.ledgr.app.domain.ledger.ReconState.UNRECONCILED, statementLineId = null),
            )
            database.reconMatchDao().delete(m.id)
        }
    }

    /** "Keep": the transaction stays as it is and this list stops asking; a re-run leaves it alone. */
    suspend fun keepLedgerOnly(matchId: Long) = db.withDb { database ->
        val m = database.reconMatchDao().get(matchId) ?: return@withDb
        database.reconMatchDao().update(m.copy(resolvedByUser = true))
    }

    /**
     * Delete an import: its lines and matches go, the ledger's recon marks reset. Transactions added from it stay unless
     * [alsoDeleteAdded] (docs/09 §3), in which case they are marked deleted like any deleted transaction.
     */
    suspend fun deleteImport(importId: Long, alsoDeleteAdded: Boolean) = db.withDb { database ->
        database.withTransaction {
            if (alsoDeleteAdded) database.txnDao().deleteStatementAdded(importId)
            database.txnDao().resetReconForImport(importId)
            database.statementImportDao().delete(importId)
        }
    }

    /**
     * "Forget the saved column layout": removes the map this import used, so the next import of the same bank and layout asks
     * for the columns again. The way out of a map that was confirmed wrongly. Returns false when the import used none.
     */
    suspend fun forgetMap(importId: Long): Boolean = db.withDb { database ->
        database.withTransaction {
            val imp = database.statementImportDao().get(importId) ?: return@withTransaction false
            val mapId = imp.columnMapId ?: return@withTransaction false
            database.columnMapDao().delete(mapId)
            database.statementImportDao().update(imp.copy(columnMapId = null))
            true
        }
    }

    suspend fun accounts() = db.withDb { it.accountDao().getAll().filter { a -> a.active } }

    private companion object {
        const val WINDOW_DAYS = 3L
        const val PREVIEW_LINES = 60
        const val REJECTED = "rejected"
        const val RESOLVED_STATEMENT = "resolved:statement"
        const val RESOLVED_LEDGER = "resolved:ledger"
        const val ADDED = "added"
    }
}
