package com.ledgr.app.ui.lock

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ledgr.app.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide lock state (docs/09 §2). The app starts locked (cold start) and locks again when it has been
 * in the background for at least the configured timeout.
 *
 * It is a singleton, not a ViewModel, so rotating the screen or switching theme neither locks nor unlocks.
 * [ProcessLifecycleOwner] only reports STOP after a short delay, which also absorbs configuration changes.
 * Time is measured with [SystemClock.elapsedRealtime] so changing the wall clock cannot defeat the timeout.
 */
@Singleton
class AppLockController @Inject constructor(
    private val settings: SettingsRepository,
) {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** Increments every time the lock engages, so the UI can prompt exactly once per lock. */
    private val _lockEpoch = MutableStateFlow(0)
    val lockEpoch: StateFlow<Int> = _lockEpoch.asStateFlow()

    @Volatile private var timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
    private var backgroundedAt: Long? = null
    private var started = false

    /** Call once from Application.onCreate (main thread). */
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true

        scope.launch {
            settings.settings.collect { timeoutMillis = it.lockTimeout.minutes * 60_000L }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                backgroundedAt = SystemClock.elapsedRealtime()
            }

            override fun onStart(owner: LifecycleOwner) {
                val since = backgroundedAt ?: return
                backgroundedAt = null
                if (SystemClock.elapsedRealtime() - since >= timeoutMillis) lock()
            }
        })
    }

    fun unlock() {
        _locked.value = false
    }

    fun lock() {
        if (!_locked.value) {
            _locked.value = true
            _lockEpoch.value += 1
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5 * 60_000L
    }
}
