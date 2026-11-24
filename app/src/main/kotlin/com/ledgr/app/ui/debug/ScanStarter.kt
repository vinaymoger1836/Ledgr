package com.ledgr.app.ui.debug

import android.content.Context
import com.ledgr.app.work.ScanScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** Lets a ViewModel queue scans without holding a Context of its own. */
@Singleton
class ScanStarter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun scanMonth(month: YearMonth) = ScanScheduler.scanMonth(context, month)

    fun catchUpNow() = ScanScheduler.catchUpNow(context)
}
