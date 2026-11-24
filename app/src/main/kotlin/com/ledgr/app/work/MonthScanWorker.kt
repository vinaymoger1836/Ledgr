package com.ledgr.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.ledgr.app.data.sms.ScanProgress
import java.time.YearMonth
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * A month scan as a foreground worker with a progress notification (docs/02 §3.1). Progress is also available
 * in-process through `ScanProgressRepository`. A month scan re-evaluates what it already stored, so running it
 * twice gives the same rows.
 */
class MonthScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(null)

    @OptIn(FlowPreview::class) // Flow.sample throttles the notification updates
    override suspend fun doWork(): Result {
        val month = try {
            YearMonth.parse(inputData.getString(ScanScheduler.KEY_MONTH).orEmpty())
        } catch (_: DateTimeParseException) {
            return Result.failure()
        }
        val deps = applicationContext.workerDependencies()
        if (!deps.smsSource().hasPermission()) return Result.failure()

        // Android may refuse a foreground start from the background; the scan itself still runs.
        try {
            setForeground(foreground(null))
        } catch (_: IllegalStateException) {
            // carry on without the notification
        }

        return try {
            coroutineScope {
                val notifier = launch {
                    deps.scanProgress().progress.filterNotNull().sample(NOTIFICATION_INTERVAL_MS).collect { progress ->
                        try {
                            setForeground(foreground(progress))
                        } catch (_: IllegalStateException) {
                            // not foreground: nothing to update
                        }
                    }
                }
                try {
                    deps.scanEngine().scanMonth(month)
                } finally {
                    notifier.cancel()
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The engine has already recorded the run as FAILED.
            Result.failure()
        }
    }

    private fun foreground(progress: ScanProgress?): ForegroundInfo =
        ForegroundInfo(
            ScanNotifications.NOTIFICATION_ID,
            ScanNotifications.build(applicationContext, progress),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    private companion object {
        const val NOTIFICATION_INTERVAL_MS = 400L
    }
}
