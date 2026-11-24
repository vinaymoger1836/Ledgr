package com.ledgr.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgr.app.data.db.DatabaseManager
import com.ledgr.app.data.db.DbStatus
import com.ledgr.app.data.rules.RulesLoader
import com.ledgr.app.data.settings.LockTimeout
import com.ledgr.app.data.settings.SettingsRepository
import com.ledgr.app.data.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Immutable state for the Settings screen. */
data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val lockTimeout: LockTimeout = LockTimeout.MIN_5,
    val database: DbStatus = DbStatus.Opening,
    /** Combined version of the shipped rule files (docs/05 §2); null until read. */
    val rulesVersion: String? = null,
    /** Count transfers between my own accounts (docs/04 §6). Off by default. */
    val countOwnTransfers: Boolean = false,
    /** Developer options are on (tap the version 7 times); reveals the scan debug screen in Profile. */
    val developerMode: Boolean = false,
)

/** Things the user can do on the Settings screen. */
sealed interface SettingsIntent {
    data class SetTheme(val mode: ThemeMode) : SettingsIntent
    data class SetLockTimeout(val timeout: LockTimeout) : SettingsIntent
    data class SetCountOwnTransfers(val count: Boolean) : SettingsIntent
    data class SetDeveloperMode(val on: Boolean) : SettingsIntent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    databaseManager: DatabaseManager,
    rulesLoader: RulesLoader,
) : ViewModel() {

    /** Read once, off the main thread; null if a shipped rules file is broken (the database banner says so). */
    private val rulesVersion: Flow<String?> = flow<String?> { emit(rulesLoader.bundle().version) }
        .catch { emit(null) }
        .onStart { emit(null) }

    val state: StateFlow<SettingsUiState> =
        combine(settings.settings, databaseManager.status, rulesVersion) { s, db, rules ->
            SettingsUiState(
                themeMode = s.themeMode,
                lockTimeout = s.lockTimeout,
                database = db,
                rulesVersion = rules,
                countOwnTransfers = s.countOwnTransfers,
                developerMode = s.developerMode,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onIntent(intent: SettingsIntent) {
        viewModelScope.launch {
            when (intent) {
                is SettingsIntent.SetTheme -> settings.setThemeMode(intent.mode)
                is SettingsIntent.SetLockTimeout -> settings.setLockTimeout(intent.timeout)
                is SettingsIntent.SetCountOwnTransfers -> settings.setCountOwnTransfers(intent.count)
                is SettingsIntent.SetDeveloperMode -> settings.setDeveloperMode(intent.on)
            }
        }
    }
}
