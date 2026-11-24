package com.ledgr.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * The catch-up watermark (docs/01 glossary): the receipt time (epoch ms) of the newest SMS already processed.
 * Catch-up starts from it. Lives in DataStore with the other small settings (docs/05: `setting` is not in Room).
 * It only moves forward.
 */
@Singleton
class WatermarkStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun get(): Long? = dataStore.data.first()[KEY]

    suspend fun advanceTo(epochMillis: Long) {
        dataStore.edit { prefs ->
            val current = prefs[KEY]
            if (current == null || epochMillis > current) prefs[KEY] = epochMillis
        }
    }

    private companion object {
        val KEY = longPreferencesKey("catchup_watermark_ms")
    }
}
