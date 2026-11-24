package com.ledgr.app.data.sms

import androidx.room.withTransaction
import com.ledgr.app.data.db.DatabaseManager
import com.ledgr.app.data.db.LedgrDatabase
import com.ledgr.app.data.db.RawSmsEntity
import com.ledgr.app.data.db.ScanKind
import com.ledgr.app.data.db.ScanRunEntity
import com.ledgr.app.data.db.ScanStatus
import com.ledgr.app.data.rules.RulesLoader
import com.ledgr.app.data.settings.WatermarkStore
import com.ledgr.app.domain.profile.SenderHeaders
import com.ledgr.app.domain.sender.Sender
import com.ledgr.app.domain.sender.SenderSuffix
import com.ledgr.app.domain.sender.SenderType
import com.ledgr.app.domain.sms.BodyHash
import com.ledgr.app.domain.sms.Outcome
import com.ledgr.app.domain.sms.Reasons
import com.ledgr.app.domain.sms.SmsInput
import com.ledgr.app.domain.sms.SmsPipeline
import com.ledgr.app.domain.sms.Verdict
import com.ledgr.app.domain.time.Ist
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What [ScanEngine.processLive] found. */
enum class LiveResult {
    /** The message was in the inbox and has been processed (or was already stored). */
    PROCESSED,

    /** Not in the inbox (yet). Retry later; catch-up is the safety net. */
    NOT_FOUND,
}

/**
 * The ingestion path (docs/02 §3): inbox -> pure-Kotlin [SmsPipeline] -> `raw_sms` rows + a `scan_run` record.
 *
 * - One run at a time (a lock), so anything still marked RUNNING when a run starts is left over from a dead process.
 * - A month scan **re-evaluates** messages it has already stored, with today's rules and confirmed headers. That is
 *   idempotent: the same inbox gives the same rows. Catch-up and live runs leave stored messages alone.
 * - A message that makes the pipeline throw is stored as `REVIEW: pipeline_error` and counted; the run goes on.
 * - Nothing here logs message text, amounts or digits.
 */
@Singleton
class ScanEngine @Inject constructor(
    private val databaseManager: DatabaseManager,
    private val source: SmsSource,
    private val rulesLoader: RulesLoader,
    private val watermark: WatermarkStore,
    private val progress: ScanProgressRepository,
) {
    private val lock = Mutex()

    /** Scans every inbox message of [month] (Asia/Kolkata). Advances the watermark only for the current month. */
    suspend fun scanMonth(month: YearMonth, reevaluate: Boolean = true): ScanRunEntity {
        val range = Ist.monthRange(month)
        val from = range.first
        val to = range.last + 1
        val total = source.count(from, to)
        return execute(
            kind = ScanKind.MONTH,
            label = month.toString(),
            total = total,
            pages = source.pages(from, to),
            reevaluate = reevaluate,
            advanceWatermark = month == Ist.monthOf(Ist.now()),
        )
    }

    /** Processes everything received since the watermark (or the current month if there is none yet). */
    suspend fun catchUp(): ScanRunEntity {
        val from = watermark.get() ?: Ist.monthRange(Ist.monthOf(Ist.now())).first
        val to = Long.MAX_VALUE
        return execute(
            kind = ScanKind.CATCHUP,
            label = Ist.monthOf(from).toString(),
            total = source.count(from, to),
            pages = source.pages(from, to),
            reevaluate = false,
            advanceWatermark = true,
        )
    }

    /**
     * Processes one just-received message, found in the inbox by [receivedAt] and the SHA-256 of its sender and of its body.
     * Never moves the watermark: only a scan that read everything up to a point may do that.
     */
    suspend fun processLive(addressHash: String, receivedAt: Long, bodyHash: String): LiveResult {
        val matches = source.findNear(receivedAt, LIVE_WINDOW_MS)
            .filter { BodyHash.sha256Hex(it.body) == bodyHash && BodyHash.sha256Hex(it.address) == addressHash }
        if (matches.isEmpty()) return LiveResult.NOT_FOUND
        execute(
            kind = ScanKind.LIVE,
            label = Ist.monthOf(receivedAt).toString(),
            total = matches.size,
            pages = flowOf(matches),
            reevaluate = false,
            advanceWatermark = false,
        )
        return LiveResult.PROCESSED
    }

    private suspend fun execute(
        kind: ScanKind,
        label: String,
        total: Int,
        pages: Flow<List<InboxSms>>,
        reevaluate: Boolean,
        advanceWatermark: Boolean,
    ): ScanRunEntity = lock.withLock {
        val rules = rulesLoader.bundle()
        val confirmed = databaseManager.withDb { db ->
            db.accountDao().getAll().flatMap { SenderHeaders.decode(it.senderHeaders) }.toSet()
        }
        val pipeline = SmsPipeline(rules, confirmed)
        val rulesVersion = rules.version

        val startedAt = Ist.now()
        var record = databaseManager.withDb { db ->
            db.scanRunDao().failStaleRunning(startedAt)
            val draft = ScanRunEntity(
                month = label,
                kind = kind,
                status = ScanStatus.RUNNING,
                startedAt = startedAt,
                finishedAt = null,
                rulesVersion = rulesVersion,
            )
            draft.copy(id = db.scanRunDao().insert(draft))
        }

        val counts = HashMap<String, Int>()
        var read = 0
        var skipped = 0
        var errors = 0
        var newest = 0L
        val showProgress = kind != ScanKind.LIVE

        fun publish(status: ScanStatus) {
            if (showProgress) progress.publish(ScanProgress(kind, label, status, read, total, counts.toMap(), errors))
        }
        publish(ScanStatus.RUNNING)

        try {
            pages.collect { page ->
                val evaluated = withContext(Dispatchers.Default) { page.map { evaluate(pipeline, it) } }
                val result = databaseManager.withDb { db ->
                    db.withTransaction { store(db, evaluated, rulesVersion, reevaluate, counts) }
                }
                read += page.size
                skipped += result.skipped
                errors += evaluated.count { it.failed }
                newest = maxOf(newest, page.maxOf { it.date })
                publish(ScanStatus.RUNNING)
            }
            if (advanceWatermark && newest > 0) watermark.advanceTo(newest)
            record = finish(record, ScanStatus.DONE, read, skipped, errors, counts)
            publish(ScanStatus.DONE)
            record
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                finish(record, ScanStatus.CANCELLED, read, skipped, errors, counts)
                publish(ScanStatus.CANCELLED)
            }
            throw e
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                finish(record, ScanStatus.FAILED, read, skipped, errors, counts)
                publish(ScanStatus.FAILED)
            }
            throw t
        }
    }

    private class Evaluated(val sms: InboxSms, val hash: String, val verdict: Verdict, val failed: Boolean)

    private class StoreResult(val skipped: Int)

    /** Runs the pure pipeline on one message. Any exception becomes `REVIEW: pipeline_error`, never a dropped message. */
    private fun evaluate(pipeline: SmsPipeline, sms: InboxSms): Evaluated {
        val hash = BodyHash.sha256Hex(sms.body)
        return try {
            Evaluated(sms, hash, pipeline.process(SmsInput(sms.address, sms.body, sms.date)), failed = false)
        } catch (_: Exception) {
            val sender = Sender(sms.address, SenderType.INVALID, null, null, SenderSuffix.NONE)
            Evaluated(sms, hash, Verdict(Outcome.REVIEW, Reasons.PIPELINE_ERROR, sender), failed = true)
        }
    }

    private suspend fun store(
        db: LedgrDatabase,
        evaluated: List<Evaluated>,
        rulesVersion: String,
        reevaluate: Boolean,
        counts: MutableMap<String, Int>,
    ): StoreResult {
        val dao = db.rawSmsDao()
        val stored = dao.storedKeys(evaluated.map { it.sms.providerId }).groupBy { it.providerId }
        val inserts = ArrayList<RawSmsEntity>()
        var skipped = 0
        for (e in evaluated) {
            val existing = stored[e.sms.providerId]?.firstOrNull { it.bodyHash == e.hash }
            val v = e.verdict
            when {
                existing == null -> {
                    inserts += RawSmsEntity(
                        providerId = e.sms.providerId,
                        address = e.sms.address,
                        senderHeader = v.sender.header,
                        senderSuffix = v.sender.suffix,
                        body = e.sms.body,
                        bodyHash = e.hash,
                        receivedAt = e.sms.date,
                        subId = e.sms.subId,
                        outcome = v.outcome,
                        reason = v.reason,
                        templateId = v.templateId,
                        rulesVersion = rulesVersion,
                    )
                    counts[v.code] = (counts[v.code] ?: 0) + 1
                }
                reevaluate && existing.hasBody -> {
                    dao.updateVerdict(existing.id, v.sender.header, v.sender.suffix.name, v.outcome.name, v.reason, v.templateId, rulesVersion)
                    counts[v.code] = (counts[v.code] ?: 0) + 1
                }
                else -> skipped++
            }
        }
        if (inserts.isNotEmpty()) dao.insertAll(inserts)
        return StoreResult(skipped)
    }

    private suspend fun finish(
        run: ScanRunEntity,
        status: ScanStatus,
        read: Int,
        skipped: Int,
        errors: Int,
        counts: Map<String, Int>,
    ): ScanRunEntity {
        val done = run.copy(
            status = status,
            finishedAt = Ist.now(),
            readCount = read,
            skippedCount = skipped,
            errorCount = errors,
            countsJson = ScanCounts.encode(counts),
        )
        databaseManager.withDb { it.scanRunDao().update(done) }
        return done
    }

    private companion object {
        /** How far from the receipt time a stored message may sit and still count as "the one that just arrived". */
        const val LIVE_WINDOW_MS = 3 * 60 * 1000L
    }
}
