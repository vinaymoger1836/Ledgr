package com.ledgr.app.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ledgr.app.data.security.DbKeyProvider
import com.ledgr.app.domain.rules.CategoryRule
import com.ledgr.app.domain.rules.CategoryRulesParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Whether the encrypted database could be opened. Failures are surfaced, never swallowed. */
sealed interface DbStatus {
    data object Opening : DbStatus
    data class Ready(val categoryCount: Int) : DbStatus
    data class Failed(val reason: String) : DbStatus
}

/**
 * Builds and opens the encrypted [LedgrDatabase] exactly once per process.
 *
 * Opening happens off the main thread at app start ([initialize]); anything that needs the database
 * later calls [requireDatabase], which returns the same instance or rethrows the original failure.
 */
@Singleton
class DatabaseManager @Inject constructor(
    @ApplicationContext private val context: Context,
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
            // Force the open now so a wrong key or corrupt file fails here, in one place.
            db.openHelper.writableDatabase
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

    private fun build(): LedgrDatabase {
        val passphrase = DbKeyProvider(context, LedgrDatabase.NAME).passphrase()
        val seed = loadCategorySeed()
        System.loadLibrary("sqlcipher")
        return Room.databaseBuilder(context, LedgrDatabase::class.java, LedgrDatabase.NAME)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addCallback(CategorySeedCallback(seed))
            .build()
    }

    private fun loadCategorySeed(): List<CategoryRule> {
        val text = context.assets.open(CATEGORIES_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        return CategoryRulesParser.parse(text).categories
    }

    private fun countCategories(db: LedgrDatabase): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM category").use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** Seeds default categories inside the transaction that creates the database. */
    private class CategorySeedCallback(private val seed: List<CategoryRule>) : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            for (rule in seed) {
                val values = ContentValues().apply {
                    put("key", rule.key)
                    put("name", rule.name)
                    put("icon", rule.icon)
                    put("color", rule.color)
                    put("kind", rule.kind.name)
                    put("is_default", 1)
                    put("hidden", 0)
                }
                db.insert("category", SQLiteDatabase.CONFLICT_ABORT, values)
            }
        }
    }

    private companion object {
        const val CATEGORIES_ASSET = "rules/categories.yaml"
    }
}
