package com.ledgr.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgr.app.data.db.DatabaseManager
import com.ledgr.app.data.db.DbStatus
import com.ledgr.app.data.settings.AppSettings
import com.ledgr.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Activity-level state: app settings (null until DataStore has answered) and database health. */
@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    databaseManager: DatabaseManager,
) : ViewModel() {

    /** `null` only until the first DataStore read finishes; the splash screen is held until then. */
    val settings: StateFlow<AppSettings?> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val dbStatus: StateFlow<DbStatus> = databaseManager.status
}
