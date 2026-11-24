package com.ledgr.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.YearMonth
import java.util.concurrent.TimeUnit

/** Every way work is queued (docs/02 §3). All of it is unique work, so repeated taps and broadcasts collapse. */
object ScanScheduler {
    const val KEY_MONTH = "month"
    const val KEY_ADDRESS_HASH = "address_hash"
    const val KEY_RECEIVED_AT = "received_at"
    const val KEY_BODY_HASH = "body_hash"

    private const val UNIQUE_CATCH_UP_NOW = "catchup-now"
    private const val UNIQUE_CATCH_UP_PERIODIC = "catchup-periodic"
    private const val CATCH_UP_HOURS = 6L
    private const val LIVE_BACKOFF_SECONDS = 15L

    /** A foreground scan of one month, started by the person (the debug screen in P2). */
    fun scanMonth(context: Context, month: YearMonth) {
        val request = OneTimeWorkRequestBuilder<MonthScanWorker>()
            .setInputData(workDataOf(KEY_MONTH to month.toString()))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("scan-month-$month", ExistingWorkPolicy.KEEP, request)
    }

    /** Catch up from the watermark now (app open, or after a live message was not found). */
    fun catchUpNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CatchUpWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_CATCH_UP_NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** Every 6 h, for phones whose manufacturer kills the receiver. KEEP: re-registering at each start changes nothing. */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<CatchUpWorker>(CATCH_UP_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.NONE)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_CATCH_UP_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * One just-received message. The input carries SHA-256 hashes of the sender and the body and the receipt time, never the
     * sender or body themselves: WorkManager keeps its inputs in an unencrypted database.
     */
    fun enqueueLive(context: Context, addressHash: String, receivedAt: Long, bodyHash: String) {
        val request = OneTimeWorkRequestBuilder<LiveSmsWorker>()
            .setInputData(workDataOf(KEY_ADDRESS_HASH to addressHash, KEY_RECEIVED_AT to receivedAt, KEY_BODY_HASH to bodyHash))
            .setBackoffCriteria(BackoffPolicy.LINEAR, LIVE_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        val unique = "live-${bodyHash.take(16)}-${addressHash.take(8)}"
        WorkManager.getInstance(context).enqueueUniqueWork(unique, ExistingWorkPolicy.KEEP, request)
    }
}
