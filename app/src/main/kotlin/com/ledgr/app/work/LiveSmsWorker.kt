package com.ledgr.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ledgr.app.data.sms.LiveResult
import kotlinx.coroutines.CancellationException

/**
 * Runs the pipeline for one just-received message (docs/02 §3.2). It finds the message in the inbox by sender hash,
 * receipt time and body hash. If the system has not stored it yet, it retries a few times; after that a catch-up
 * takes over, so a message is never lost. It never moves the watermark.
 */
class LiveSmsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val addressHash = inputData.getString(ScanScheduler.KEY_ADDRESS_HASH) ?: return Result.failure()
        val receivedAt = inputData.getLong(ScanScheduler.KEY_RECEIVED_AT, -1L)
        val bodyHash = inputData.getString(ScanScheduler.KEY_BODY_HASH) ?: return Result.failure()
        if (receivedAt < 0) return Result.failure()

        val deps = applicationContext.workerDependencies()
        if (!deps.smsSource().hasPermission()) return Result.success()

        return try {
            when (deps.scanEngine().processLive(addressHash, receivedAt, bodyHash)) {
                LiveResult.PROCESSED -> Result.success()
                LiveResult.NOT_FOUND ->
                    if (runAttemptCount < MAX_RETRIES) {
                        Result.retry()
                    } else {
                        ScanScheduler.catchUpNow(applicationContext)
                        Result.success()
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
