package com.ledgr.app.ui.lock

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the lock screen shows. */
sealed interface LockUiState {
    data object Idle : LockUiState
    data object Prompting : LockUiState
    data class Failed(val message: String) : LockUiState

    /** Device has no screen lock: fail closed and explain. */
    data object NoCredential : LockUiState

    /** Device cannot show the prompt: stay locked and explain. */
    data object Unavailable : LockUiState
}

@HiltViewModel
class LockViewModel @Inject constructor(
    private val controller: AppLockController,
) : ViewModel() {

    val locked: StateFlow<Boolean> = controller.locked

    private val _ui = MutableStateFlow<LockUiState>(LockUiState.Idle)
    val ui: StateFlow<LockUiState> = _ui.asStateFlow()

    /** The lock epoch we have already prompted for; -1 means "prompt again on next resume". */
    private var promptedEpoch = -1

    /** True at most once per lock event, so a cancelled prompt does not reappear in a loop. */
    fun consumeAutoPrompt(): Boolean {
        val epoch = controller.lockEpoch.value
        if (!controller.locked.value || promptedEpoch == epoch) return false
        promptedEpoch = epoch
        return true
    }

    /** After returning from system settings, the state must be re-evaluated. */
    fun needsRecheck(): Boolean = _ui.value is LockUiState.NoCredential || _ui.value is LockUiState.Unavailable

    fun onPrompting() {
        _ui.value = LockUiState.Prompting
    }

    fun onSucceeded() {
        _ui.value = LockUiState.Idle
        controller.unlock()
    }

    fun onFailed(failure: UnlockFailure) {
        // The system dismissed the prompt (e.g. the app was backgrounded): ask again on the next resume.
        if (failure.systemCancelled) promptedEpoch = -1
        _ui.value = LockUiState.Failed(failure.message)
    }

    fun onAvailability(availability: LockAvailability) {
        _ui.value = when (availability) {
            LockAvailability.NoCredential -> LockUiState.NoCredential
            LockAvailability.Unavailable -> LockUiState.Unavailable
            LockAvailability.Available -> _ui.value
        }
    }
}
