package com.ledgr.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * Processes everything newer than the watermark (docs/02 §3.2): on app open, and every 6 h for phones that kill the
 * live receiver. Quiet: no notification, and it does nothing without the SMS permission.
 */
class CatchUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deps = applicationContext.workerDependencies()
        if (!deps.smsSource().hasPermission()) return Result.success()
        return try {
            deps.scanEngine().catchUp()
            Result.success()
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
