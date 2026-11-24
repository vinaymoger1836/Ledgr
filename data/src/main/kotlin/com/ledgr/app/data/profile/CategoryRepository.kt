package com.ledgr.app.data.profile

import androidx.room.withTransaction
import com.ledgr.app.data.db.CategoryEntity
import com.ledgr.app.data.db.DatabaseManager
import com.ledgr.app.domain.rules.CategoryRulesParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

sealed interface CategoryChangeResult {
    data object Done : CategoryChangeResult
    data object BlankName : CategoryChangeResult

    /** Another visible category already has this name. */
    data object DuplicateName : CategoryChangeResult

    /** The category cannot be hidden or merged away (the fallback bucket). */
    data object Protected : CategoryChangeResult

    /** Merge needs two different categories of the same kind, the target not hidden. */
    data object InvalidMerge : CategoryChangeResult
}

/**
 * Category edits (docs/05 `category`, docs/08 §2.4): rename, reorder, colour/icon, hide, merge.
 * Everything here is a user edit, so [RuleDataSync][com.ledgr.app.data.rules.RuleDataSync] never undoes it.
 */
@Singleton
class CategoryRepository @Inject constructor(
    private val db: DatabaseManager,
) {
    val categories: Flow<List<CategoryEntity>> = db.observe { it.categoryDao().observeAll() }

    suspend fun rename(id: Long, name: String): CategoryChangeResult = db.withDb { database ->
        val clean = name.trim().replace(Regex("""\s+"""), " ")
        val dao = database.categoryDao()
        val category = dao.get(id)
        when {
            category == null -> CategoryChangeResult.Done
            clean.isEmpty() -> CategoryChangeResult.BlankName
            dao.getAll().any { it.id != id && !it.hidden && it.name.equals(clean, ignoreCase = true) } ->
                CategoryChangeResult.DuplicateName
            else -> {
                dao.update(category.copy(name = clean.take(MAX_NAME)))
                CategoryChangeResult.Done
            }
        }
    }

    suspend fun setColor(id: Long, colorIndex: Int) = db.withDb { database ->
        val dao = database.categoryDao()
        dao.get(id)?.let { dao.update(it.copy(color = colorIndex.coerceIn(0, CategoryRulesParser.PALETTE_SIZE - 1))) }
    }

    suspend fun setIcon(id: Long, iconKey: String) = db.withDb { database ->
        val dao = database.categoryDao()
        dao.get(id)?.let { dao.update(it.copy(icon = iconKey)) }
    }

    suspend fun setHidden(id: Long, hidden: Boolean): CategoryChangeResult = db.withDb { database ->
        val dao = database.categoryDao()
        val category = dao.get(id) ?: return@withDb CategoryChangeResult.Done
        if (hidden && category.key == FALLBACK_KEY) return@withDb CategoryChangeResult.Protected
        dao.update(category.copy(hidden = hidden))
        CategoryChangeResult.Done
    }

    /** Moves a category one place up ([delta] = -1) or down (+1) among the visible ones of its kind. */
    suspend fun move(id: Long, delta: Int) = db.withDb { database ->
        database.withTransaction {
            val dao = database.categoryDao()
            val all = dao.getAll()
            val me = all.firstOrNull { it.id == id } ?: return@withTransaction
            val group = all.filter { it.kind == me.kind && !it.hidden }
            val from = group.indexOfFirst { it.id == id }
            val neighbour = group.getOrNull(from + delta) ?: return@withTransaction
            // sort_order is unique per row, so a move is a swap of the two values.
            dao.setSortOrder(me.id, neighbour.sortOrder)
            dao.setSortOrder(neighbour.id, me.sortOrder)
        }
    }

    /**
     * Folds [sourceId] into [targetId]: children and merchant defaults move to the target, the source is hidden
     * and remembers where it went (`merged_into_id`). There is no `txn` table until P3; the ledger re-points
     * transactions through that column.
     */
    suspend fun merge(sourceId: Long, targetId: Long): CategoryChangeResult = db.withDb { database ->
        database.withTransaction {
            val dao = database.categoryDao()
            val source = dao.get(sourceId)
            val target = dao.get(targetId)
            when {
                source == null || target == null || sourceId == targetId -> CategoryChangeResult.InvalidMerge
                source.key == FALLBACK_KEY -> CategoryChangeResult.Protected
                source.kind != target.kind || target.hidden -> CategoryChangeResult.InvalidMerge
                else -> {
                    dao.repointChildren(sourceId, targetId)
                    dao.repointMerged(sourceId, targetId)
                    database.counterpartyDao().repointDefaultCategory(sourceId, targetId)
                    dao.markMerged(sourceId, targetId)
                    CategoryChangeResult.Done
                }
            }
        }
    }

    companion object {
        const val MAX_NAME = 40

        /** The "Uncategorised" bucket; the categoriser falls back to it, so it must stay visible. */
        const val FALLBACK_KEY = "uncategorised"
    }
}
