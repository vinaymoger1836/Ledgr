package com.ledgr.app.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ledgr.app.MainActivity
import com.ledgr.app.R
import com.ledgr.app.data.sms.ScanProgress
import java.text.NumberFormat
import java.util.Locale

/**
 * The scan progress notification (docs/08: "Read 1,240 / 3,050"). It only ever shows counts, never message text.
 * Low importance: no sound, no heads-up.
 */
object ScanNotifications {
    const val CHANNEL_ID = "scan_progress"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.scan_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.scan_channel_description) }
        manager.createNotificationChannel(channel)
    }

    /** [progress] null means "starting": an indeterminate bar. */
    fun build(context: Context, progress: ScanProgress?): Notification {
        ensureChannel(context)
        val number = NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN"))
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle(context.getString(R.string.scan_notification_title))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (progress == null || progress.total <= 0) {
            builder.setProgress(0, 0, true)
                .setContentText(context.getString(R.string.scan_notification_starting))
        } else {
            builder.setProgress(progress.total, progress.read.coerceAtMost(progress.total), false)
                .setContentText(
                    context.getString(
                        R.string.scan_notification_progress,
                        number.format(progress.read),
                        number.format(progress.total),
                    ),
                )
        }
        return builder.build()
    }
}
