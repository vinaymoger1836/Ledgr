package com.ledgr.app.data.db

import android.content.Context
import androidx.room.Room
import com.ledgr.app.data.rules.RuleDataSync
import com.ledgr.app.data.rules.RulesLoader
import com.ledgr.app.data.security.DbKeyProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Whether the encrypted database could be opened and its rule data prepared. Failures are surfaced, never swallowed. */
sealed interface DbStatus {
    data object Opening : DbStatus
    data class Ready(val categoryCount: Int) : DbStatus
    data class Failed(val reason: String) : DbStatus
}

/**
 * Builds and opens the encrypted [LedgrDatabase] exactly once per process.
 *
 * Opening happens off the main thread at app start ([initialize]); anything that needs the database
 * later goes through [observe] / [withDb] (which hop to a background thread) or [requireDatabase], which
 * returns the same instance or rethrows the original failure. Nothing injects the database directly, so a
 * failed open can never crash a ViewModel's construction.
 */
@Singleton
class DatabaseManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rulesLoader: RulesLoader,
) {
    private val _status = MutableStateFlow<DbStatus>(DbStatus.Opening)
    val status: StateFlow<DbStatus> = _status.asStateFlow()

    private val lock = Any()
    private var database: LedgrDatabase? = null
    private var failure: Throwable? = null

    fun requireDatabase(): LedgrDatabase = synchronized(lock) {
        database?.let { return it }
        failure?.let { throw IllegalStateException("The encrypted database is unavailable.", it) }
        try {
            val db = build()
            // Force the open now so a wrong key, corrupt file or failed migration fails here, in one place.
            db.openHelper.writableDatabase
            // Shipped rule data (categories, starter merchants): insert what is missing, never overwrite.
            RuleDataSync.apply(db, rulesLoader.load())
            database = db
            _status.value = DbStatus.Ready(categoryCount = countCategories(db))
            db
        } catch (t: Throwable) {
            failure = t
            _status.value = DbStatus.Failed("${t.javaClass.simpleName}: ${t.message ?: "no detail"}")
            throw t
        }
    }

    /** Call from a background dispatcher. Never throws: the outcome is in [status]. */
    fun initialize() {
        try {
            requireDatabase()
        } catch (_: Throwable) {
            // already recorded in `status`
        }
    }

    /**
     * A live query. Runs on the IO dispatcher; if the database is unavailable the flow just ends without
     * emitting (the global banner already says so) instead of throwing into the UI.
     */
    fun <T> observe(query: (LedgrDatabase) -> Flow<T>): Flow<T> =
        flow {
            val db = try {
                requireDatabase()
            } catch (_: Throwable) {
                return@flow
            }
            emitAll(query(db))
        }.flowOn(Dispatchers.IO)

    /** One-shot work on the IO dispatcher. Throws if the database is unavailable. */
    suspend fun <T> withDb(block: suspend (LedgrDatabase) -> T): T =
        withContext(Dispatchers.IO) { block(requireDatabase()) }

    private fun build(): LedgrDatabase {
        val passphrase = DbKeyProvider(context, LedgrDatabase.NAME).passphrase()
        System.loadLibrary("sqlcipher")
        return Room.databaseBuilder(context, LedgrDatabase::class.java, LedgrDatabase.NAME)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(*Migrations.ALL)
            .build()
    }

    private fun countCategories(db: LedgrDatabase): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM category").use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
}
