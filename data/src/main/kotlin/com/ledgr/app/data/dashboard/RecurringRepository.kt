package com.ledgr.app.data.dashboard

import com.ledgr.app.data.db.DatabaseManager
import com.ledgr.app.data.db.RecurringOverrideEntity
import com.ledgr.app.domain.forecast.RecurringStatus
import com.ledgr.app.domain.time.Ist
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The person's "Confirm / not recurring" answers on the Dashboard (docs/07 W8, ADR-015). Series are never stored. */
@Singleton
class RecurringRepository @Inject constructor(private val db: DatabaseManager) {

    val overrides: Flow<Map<String, RecurringStatus>> =
        db.observe { it.recurringOverrideDao().observeAll() }
            .map { rows -> rows.associate { it.key to it.status } }

    suspend fun confirm(key: String) = set(key, RecurringStatus.CONFIRMED)

    suspend fun dismiss(key: String) = set(key, RecurringStatus.DISMISSED)

    /** Forget the answer: the detector decides again. */
    suspend fun clear(key: String) {
        db.withDb { it.recurringOverrideDao().clear(key) }
    }

    private suspend fun set(key: String, status: RecurringStatus) {
        db.withDb { it.recurringOverrideDao().upsert(RecurringOverrideEntity(key = key, status = status, createdAt = Ist.now())) }
    }
}
