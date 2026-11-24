package com.ledgr.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgr.app.data.db.DatabaseManager
import com.ledgr.app.data.db.DbStatus
import com.ledgr.app.data.settings.LockTimeout
import com.ledgr.app.data.settings.SettingsRepository
import com.ledgr.app.data.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Immutable state for the Settings screen. */
data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val lockTimeout: LockTimeout = LockTimeout.MIN_5,
    val database: DbStatus = DbStatus.Opening,
)

/** Things the user can do on the Settings screen. */
sealed interface SettingsIntent {
    data class SetTheme(val mode: ThemeMode) : SettingsIntent
    data class SetLockTimeout(val timeout: LockTimeout) : SettingsIntent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    databaseManager: DatabaseManager,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> =
        combine(settings.settings, databaseManager.status) { s, db ->
            SettingsUiState(themeMode = s.themeMode, lockTimeout = s.lockTimeout, database = db)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onIntent(intent: SettingsIntent) {
        viewModelScope.launch {
            when (intent) {
                is SettingsIntent.SetTheme -> settings.setThemeMode(intent.mode)
                is SettingsIntent.SetLockTimeout -> settings.setLockTimeout(intent.timeout)
            }
        }
    }
}
