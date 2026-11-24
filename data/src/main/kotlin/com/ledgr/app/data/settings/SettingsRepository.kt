package com.ledgr.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Theme choice (docs/08 §2.6). Follows the system until the user picks one. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Background time before the app locks again (docs/08 §2.6: 1 / 5 / 15 min). */
enum class LockTimeout(val minutes: Int) {
    MIN_1(1),
    MIN_5(5),
    MIN_15(15),
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val lockTimeout: LockTimeout = LockTimeout.MIN_5,
    /** FLAG_SECURE is on unless this is true. There is no UI for it yet. */
    val allowScreenshots: Boolean = false,
)

/** Small key-value settings in DataStore (docs/05 §1 "setting lives in DataStore"). */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[THEME_MODE]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: ThemeMode.SYSTEM,
            lockTimeout = prefs[LOCK_TIMEOUT_MINUTES]?.let { minutes -> LockTimeout.entries.firstOrNull { it.minutes == minutes } }
                ?: LockTimeout.MIN_5,
            allowScreenshots = prefs[ALLOW_SCREENSHOTS] ?: false,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setLockTimeout(timeout: LockTimeout) {
        dataStore.edit { it[LOCK_TIMEOUT_MINUTES] = timeout.minutes }
    }

    suspend fun setAllowScreenshots(allow: Boolean) {
        dataStore.edit { it[ALLOW_SCREENSHOTS] = allow }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LOCK_TIMEOUT_MINUTES = intPreferencesKey("lock_timeout_minutes")
        val ALLOW_SCREENSHOTS = booleanPreferencesKey("allow_screenshots")
    }
}
