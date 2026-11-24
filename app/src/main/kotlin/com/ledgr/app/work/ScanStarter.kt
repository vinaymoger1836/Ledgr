package com.ledgr.app.work

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** Lets a ViewModel queue scans without holding a Context of its own (Home, onboarding and the developer screen). */
@Singleton
class ScanStarter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Reads and judges only [month]'s messages (docs/12 P4: an older month scans just that month). */
    fun scanMonth(month: YearMonth) = ScanScheduler.scanMonth(context, month)

    /** Everything since the last watermark: what pull to refresh runs. */
    fun catchUpNow() = ScanScheduler.catchUpNow(context)
}
