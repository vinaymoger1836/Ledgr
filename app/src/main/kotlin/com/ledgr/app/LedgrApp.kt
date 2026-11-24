package com.ledgr.app

import android.app.Application
import com.ledgr.app.data.db.DatabaseManager
import com.ledgr.app.ui.lock.AppLockController
import com.ledgr.app.work.ScanNotifications
import com.ledgr.app.work.ScanScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class LedgrApp : Application() {

    @Inject lateinit var databaseManager: DatabaseManager
    @Inject lateinit var appLock: AppLockController

    /** Lives as long as the process. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate() // Hilt injects here
        appLock.start(appScope)
        // Open (and on first run create + seed) the encrypted DB off the main thread. The outcome is in `status`.
        appScope.launch(Dispatchers.IO) { databaseManager.initialize() }
        // P2: the progress notification channel, and the 6-hourly catch-up (a no-op until SMS access is granted).
        ScanNotifications.ensureChannel(this)
        appScope.launch(Dispatchers.IO) { ScanScheduler.ensurePeriodic(this@LedgrApp) }
    }
}
